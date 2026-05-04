package com.kliniq.api.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.kliniq.db.tables.references.AUDIT_EVENTS
import com.kliniq.db.tables.references.EMAIL_VERIFICATION_TOKENS
import com.kliniq.db.tables.references.PASSWORD_RESET_TOKENS
import com.kliniq.db.tables.references.USERS
import com.kliniq.infra.mail.EmailSender
import com.kliniq.infra.security.SessionCookieService
import com.kliniq.support.TestcontainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest
@Import(TestcontainersConfig::class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val objectMapper: ObjectMapper,
        private val dsl: DSLContext,
    ) {
        @MockitoBean
        private lateinit var emailSender: EmailSender

        @BeforeEach
        fun reset() {
            // Mockito.reset clears invocation history so per-test verify(times(...))
            // assertions are not contaminated by previous tests in this class.
            Mockito.reset(emailSender)
            // Tokens cascade-delete via FK ON DELETE CASCADE when users are dropped.
            dsl.deleteFrom(EMAIL_VERIFICATION_TOKENS).execute()
            dsl.deleteFrom(PASSWORD_RESET_TOKENS).execute()
            dsl.deleteFrom(USERS).execute()
            // audit_events is append-only at the DB level (triggers in V2 reject
            // UPDATE/DELETE), so old rows accumulate across tests. Tests filter
            // by entity_id of the user under test to isolate themselves.
        }

        // ---- helpers ------------------------------------------------------

        private fun registerBody(
            email: String,
            password: String = DEFAULT_PASSWORD,
            displayName: String = "Test User",
        ): String =
            objectMapper.writeValueAsString(
                mapOf(
                    "email" to email,
                    "password" to password,
                    "displayName" to displayName,
                ),
            )

        private fun loginBody(
            email: String,
            password: String,
        ): String = objectMapper.writeValueAsString(mapOf("email" to email, "password" to password))

        private fun verifyBody(token: String) = objectMapper.writeValueAsString(mapOf("token" to token))

        /** Captures the verification URL token that the use case sends to EmailSender. */
        private fun captureIssuedToken(): String {
            val captor = argumentCaptor<String>()
            verify(emailSender).sendEmailVerification(any(), any(), captor.capture())
            return captor.lastValue.substringAfter("token=")
        }

        /** Captures the password-reset URL token. */
        private fun captureResetToken(): String {
            val captor = argumentCaptor<String>()
            verify(emailSender).sendPasswordReset(any(), any(), captor.capture())
            return captor.lastValue.substringAfter("token=")
        }

        private fun forgotBody(email: String) = objectMapper.writeValueAsString(mapOf("email" to email))

        private fun resetBody(
            token: String,
            newPassword: String,
        ) = objectMapper.writeValueAsString(mapOf("token" to token, "newPassword" to newPassword))

        /** Full `Set-Cookie` header value, including all attributes after the value=secret pair. */
        private fun MvcResult.setCookieHeader(): String? = response.getHeader("Set-Cookie")

        /** Just the cookie value (between `=` and the first `;`). */
        private fun MvcResult.sessionCookieValue(): String? =
            setCookieHeader()
                ?.takeIf { it.startsWith("${SessionCookieService.COOKIE_NAME}=") }
                ?.substringAfter("=")
                ?.substringBefore(";")

        private fun registerAndVerify(
            email: String,
            password: String = DEFAULT_PASSWORD,
            displayName: String = "Test User",
        ) {
            mockMvc
                .post("/api/v1/auth/register") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = registerBody(email, password, displayName)
                }.andExpect { status { isOk() } }
            val token = captureIssuedToken()
            mockMvc
                .post("/api/v1/auth/verify") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = verifyBody(token)
                }.andExpect { status { isOk() } }
        }

        // ---- /register ---------------------------------------------------

        @Test
        fun `register creates a user and dispatches a verification email`() {
            mockMvc
                .post("/api/v1/auth/register") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = registerBody(email = "alice@kliniq.local", displayName = "Alice")
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.message") { exists() }
                }

            assertThat(
                dsl.fetchExists(
                    dsl
                        .selectOne()
                        .from(USERS)
                        .where(USERS.EMAIL_NORMALIZED.eq("alice@kliniq.local")),
                ),
            ).isTrue()

            verify(emailSender, times(1)).sendEmailVerification(
                eq("alice@kliniq.local"),
                eq("Alice"),
                argThat { url -> url.contains("token=") && url.startsWith("https://") },
            )
        }

        @Test
        fun `register with an existing email returns success without recreating the user`() {
            val body = registerBody(email = "bob@kliniq.local", displayName = "Bob")

            mockMvc
                .post("/api/v1/auth/register") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }.andExpect { status { isOk() } }

            mockMvc
                .post("/api/v1/auth/register") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }.andExpect { status { isOk() } }

            val count =
                dsl
                    .selectCount()
                    .from(USERS)
                    .where(USERS.EMAIL_NORMALIZED.eq("bob@kliniq.local"))
                    .fetchOne(0, Int::class.java) ?: 0
            assertThat(count).isEqualTo(1)
            verify(emailSender, times(1)).sendEmailVerification(any(), any(), any())
        }

        @Test
        fun `register with too-short password returns 400 with VALIDATION_ERROR`() {
            mockMvc
                .post("/api/v1/auth/register") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = registerBody(email = "carol@kliniq.local", password = "short")
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("VALIDATION_ERROR") }
                    jsonPath("$.fieldErrors[*].field") { value("password") }
                }
            verify(emailSender, never()).sendEmailVerification(any(), any(), any())
        }

        // ---- /verify -----------------------------------------------------

        @Test
        fun `verify happy path stamps email_verified_at`() {
            mockMvc
                .post("/api/v1/auth/register") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = registerBody(email = "dave@kliniq.local", displayName = "Dave")
                }.andExpect { status { isOk() } }
            val token = captureIssuedToken()

            mockMvc
                .post("/api/v1/auth/verify") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = verifyBody(token)
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.message") { exists() }
                }

            val verifiedAt =
                dsl
                    .select(USERS.EMAIL_VERIFIED_AT)
                    .from(USERS)
                    .where(USERS.EMAIL_NORMALIZED.eq("dave@kliniq.local"))
                    .fetchOne(0, java.time.OffsetDateTime::class.java)
            assertThat(verifiedAt).isNotNull()
        }

        @Test
        fun `verify with bogus token returns 400 INVALID_TOKEN`() {
            mockMvc
                .post("/api/v1/auth/verify") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = verifyBody("not-a-real-token")
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("INVALID_TOKEN") }
                }
        }

        // ---- /login ------------------------------------------------------

        @Test
        fun `login returns user and sets session cookie when credentials are correct`() {
            registerAndVerify(email = "eve@kliniq.local")

            val result =
                mockMvc
                    .post("/api/v1/auth/login") {
                        with(csrf())
                        contentType = MediaType.APPLICATION_JSON
                        content = loginBody("eve@kliniq.local", DEFAULT_PASSWORD)
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.email") { value("eve@kliniq.local") }
                        jsonPath("$.role") { value("STAFF") }
                    }.andReturn()

            val setCookie = result.setCookieHeader()
            assertThat(setCookie).isNotNull
            assertThat(setCookie).startsWith("${SessionCookieService.COOKIE_NAME}=")
            assertThat(setCookie).contains("Secure", "HttpOnly", "SameSite=Lax", "Path=/")
        }

        @Test
        fun `login with wrong password returns 401 INVALID_CREDENTIALS`() {
            registerAndVerify(email = "frank@kliniq.local")

            mockMvc
                .post("/api/v1/auth/login") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = loginBody("frank@kliniq.local", "wrong horse battery staple")
                }.andExpect {
                    status { isUnauthorized() }
                    jsonPath("$.code") { value("INVALID_CREDENTIALS") }
                }
        }

        @Test
        fun `login with unknown email also returns 401 INVALID_CREDENTIALS`() {
            mockMvc
                .post("/api/v1/auth/login") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = loginBody("ghost@nowhere.local", DEFAULT_PASSWORD)
                }.andExpect {
                    status { isUnauthorized() }
                    jsonPath("$.code") { value("INVALID_CREDENTIALS") }
                }
        }

        @Test
        fun `login before email verification returns 403 EMAIL_NOT_VERIFIED`() {
            // Register but skip verify.
            mockMvc
                .post("/api/v1/auth/register") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = registerBody(email = "grace@kliniq.local")
                }.andExpect { status { isOk() } }

            mockMvc
                .post("/api/v1/auth/login") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = loginBody("grace@kliniq.local", DEFAULT_PASSWORD)
                }.andExpect {
                    status { isForbidden() }
                    jsonPath("$.code") { value("EMAIL_NOT_VERIFIED") }
                }
        }

        // ---- /me + /logout -----------------------------------------------

        @Test
        fun `me without session returns 401 UNAUTHENTICATED`() {
            mockMvc.get("/api/v1/auth/me").andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("UNAUTHENTICATED") }
            }
        }

        @Test
        fun `full happy flow - register, verify, login, me, logout, me`() {
            registerAndVerify(email = "henry@kliniq.local", displayName = "Henry")

            // Login → cookie
            val loginResult =
                mockMvc
                    .post("/api/v1/auth/login") {
                        with(csrf())
                        contentType = MediaType.APPLICATION_JSON
                        content = loginBody("henry@kliniq.local", DEFAULT_PASSWORD)
                    }.andExpect { status { isOk() } }
                    .andReturn()
            val cookieValue = loginResult.sessionCookieValue()!!

            // /me with the cookie returns the user
            mockMvc
                .get("/api/v1/auth/me") {
                    cookie(jakarta.servlet.http.Cookie(SessionCookieService.COOKIE_NAME, cookieValue))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.email") { value("henry@kliniq.local") }
                    jsonPath("$.displayName") { value("Henry") }
                }

            // Logout invalidates session
            mockMvc
                .post("/api/v1/auth/logout") {
                    with(csrf())
                    cookie(jakarta.servlet.http.Cookie(SessionCookieService.COOKIE_NAME, cookieValue))
                }.andExpect {
                    status { isOk() }
                    header { exists("Set-Cookie") }
                }

            // Same cookie no longer authenticates
            mockMvc
                .get("/api/v1/auth/me") {
                    cookie(jakarta.servlet.http.Cookie(SessionCookieService.COOKIE_NAME, cookieValue))
                }.andExpect { status { isUnauthorized() } }
        }

        // ---- /password/forgot + /password/reset --------------------------

        @Test
        fun `forgot-password sends reset email when user exists and is verified`() {
            registerAndVerify(email = "ivan@kliniq.local", displayName = "Ivan")
            // Reset the mock so we don't see the verification email from setup.
            Mockito.reset(emailSender)

            mockMvc
                .post("/api/v1/auth/password/forgot") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = forgotBody("ivan@kliniq.local")
                }.andExpect { status { isOk() } }

            verify(emailSender, times(1)).sendPasswordReset(
                eq("ivan@kliniq.local"),
                eq("Ivan"),
                argThat { url -> url.contains("token=") && url.contains("/reset?") },
            )
        }

        @Test
        fun `forgot-password returns neutral when user does not exist`() {
            mockMvc
                .post("/api/v1/auth/password/forgot") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = forgotBody("ghost@nowhere.local")
                }.andExpect { status { isOk() } }

            verify(emailSender, never()).sendPasswordReset(any(), any(), any())
        }

        @Test
        fun `forgot-password returns neutral when user is unverified`() {
            // Register without verify.
            mockMvc
                .post("/api/v1/auth/register") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = registerBody(email = "jenny@kliniq.local")
                }.andExpect { status { isOk() } }
            Mockito.reset(emailSender)

            mockMvc
                .post("/api/v1/auth/password/forgot") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = forgotBody("jenny@kliniq.local")
                }.andExpect { status { isOk() } }

            verify(emailSender, never()).sendPasswordReset(any(), any(), any())
        }

        @Test
        fun `reset-password updates the hash, invalidates sessions, and old password no longer works`() {
            registerAndVerify(email = "kate@kliniq.local", displayName = "Kate")

            // Active session before reset.
            val loginResult =
                mockMvc
                    .post("/api/v1/auth/login") {
                        with(csrf())
                        contentType = MediaType.APPLICATION_JSON
                        content = loginBody("kate@kliniq.local", DEFAULT_PASSWORD)
                    }.andExpect { status { isOk() } }
                    .andReturn()
            val sessionCookie = loginResult.sessionCookieValue()!!

            // Trigger forgot.
            Mockito.reset(emailSender)
            mockMvc
                .post("/api/v1/auth/password/forgot") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = forgotBody("kate@kliniq.local")
                }.andExpect { status { isOk() } }
            val resetToken = captureResetToken()

            // Reset.
            mockMvc
                .post("/api/v1/auth/password/reset") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = resetBody(resetToken, "new horse battery staple xyz")
                }.andExpect { status { isOk() } }

            // The session from before reset must be invalidated.
            mockMvc
                .get("/api/v1/auth/me") {
                    cookie(jakarta.servlet.http.Cookie(SessionCookieService.COOKIE_NAME, sessionCookie))
                }.andExpect { status { isUnauthorized() } }

            // Old password no longer works.
            mockMvc
                .post("/api/v1/auth/login") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = loginBody("kate@kliniq.local", DEFAULT_PASSWORD)
                }.andExpect { status { isUnauthorized() } }

            // New password works.
            mockMvc
                .post("/api/v1/auth/login") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = loginBody("kate@kliniq.local", "new horse battery staple xyz")
                }.andExpect { status { isOk() } }
        }

        @Test
        fun `reset-password with bogus token returns 400 INVALID_TOKEN`() {
            mockMvc
                .post("/api/v1/auth/password/reset") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = resetBody("not-a-real-token", "another long valid password 123")
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("INVALID_TOKEN") }
                }
        }

        // ---- CSRF protection --------------------------------------------

        @Test
        fun `POST without CSRF token is rejected with 403`() {
            mockMvc
                .post("/api/v1/auth/register") {
                    // intentionally no with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = registerBody(email = "noscrf@kliniq.local")
                }.andExpect {
                    status { isForbidden() }
                    jsonPath("$.code") { value("FORBIDDEN") }
                }
        }

        // ---- audit -------------------------------------------------------

        @Test
        fun `audit log records register, verify, and login events for the user`() {
            registerAndVerify(email = "leo@kliniq.local", displayName = "Leo")

            mockMvc
                .post("/api/v1/auth/login") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = loginBody("leo@kliniq.local", DEFAULT_PASSWORD)
                }.andExpect { status { isOk() } }

            // Find the userId from the DB to scope the audit query.
            val userId =
                dsl
                    .select(USERS.ID)
                    .from(USERS)
                    .where(USERS.EMAIL_NORMALIZED.eq("leo@kliniq.local"))
                    .fetchOne(0, java.util.UUID::class.java)!!

            val actions =
                dsl
                    .select(AUDIT_EVENTS.ACTION)
                    .from(AUDIT_EVENTS)
                    .where(AUDIT_EVENTS.ENTITY_ID.eq(userId))
                    .orderBy(AUDIT_EVENTS.CREATED_AT)
                    .fetch(AUDIT_EVENTS.ACTION)

            assertThat(actions).containsExactly(
                "user.registered",
                "user.email_verified",
                "user.login",
            )
        }

        companion object {
            private const val DEFAULT_PASSWORD = "correct horse battery staple"
        }
    }
