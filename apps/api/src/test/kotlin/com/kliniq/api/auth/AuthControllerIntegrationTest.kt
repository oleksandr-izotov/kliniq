package com.kliniq.api.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.kliniq.db.tables.references.AUDIT_EVENTS
import com.kliniq.db.tables.references.EMAIL_VERIFICATION_TOKENS
import com.kliniq.db.tables.references.PASSWORD_RESET_TOKENS
import com.kliniq.db.tables.references.USERS
import com.kliniq.infra.mail.EmailSender
import com.kliniq.infra.security.SessionCookieService
import com.kliniq.infra.security.breach.BreachedPasswordChecker
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
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post

@SpringBootTest
@Import(TestcontainersConfig::class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Suppress("LargeClass") // single class keeps cross-feature setup (mocks, redis cleanup) in one place
class AuthControllerIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val objectMapper: ObjectMapper,
        private val dsl: DSLContext,
        private val redis: StringRedisTemplate,
    ) {
        @MockitoBean
        private lateinit var emailSender: EmailSender

        @MockitoBean
        private lateinit var breachChecker: BreachedPasswordChecker

        @BeforeEach
        fun reset() {
            // Mockito.reset clears invocation history so per-test verify(times(...))
            // assertions are not contaminated by previous tests in this class.
            Mockito.reset(emailSender, breachChecker)
            // Default: passwords are not breached. Tests that need the
            // PASSWORD_BREACHED branch override this with whenever().
            org.mockito.kotlin
                .whenever(breachChecker.isBreached(org.mockito.kotlin.any()))
                .thenReturn(false)
            // Tokens cascade-delete via FK ON DELETE CASCADE when users are dropped.
            // Bookings + operating_rooms FK back to users with ON DELETE RESTRICT,
            // so they have to go first if a prior test class created any.
            dsl.deleteFrom(com.kliniq.db.tables.references.BOOKINGS).execute()
            dsl.deleteFrom(com.kliniq.db.tables.references.OPERATING_ROOMS).execute()
            dsl.deleteFrom(EMAIL_VERIFICATION_TOKENS).execute()
            dsl.deleteFrom(PASSWORD_RESET_TOKENS).execute()
            dsl.deleteFrom(USERS).execute()
            // audit_events is append-only at the DB level (triggers in V2 reject
            // UPDATE/DELETE), so old rows accumulate across tests. Tests filter
            // by entity_id of the user under test to isolate themselves.
            // Wipe rate-limit counters so a flurry of register/login calls in
            // one test doesn't trip the limit on the next one.
            redis.keys("rate-limit:*")?.takeIf { it.isNotEmpty() }?.let(redis::delete)
            // Same for the login-backoff state.
            redis.keys("kliniq:login-backoff:*")?.takeIf { it.isNotEmpty() }?.let(redis::delete)
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

        @Test
        fun `register with breached password returns 400 PASSWORD_BREACHED and creates no user`() {
            // Override the default stub: pretend HIBP says this password is breached.
            org.mockito.kotlin
                .whenever(breachChecker.isBreached(org.mockito.kotlin.any()))
                .thenReturn(true)

            mockMvc
                .post("/api/v1/auth/register") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = registerBody(email = "breached@kliniq.local")
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("PASSWORD_BREACHED") }
                }

            // Rejection happens before any user-existence check, so no DB write.
            assertThat(
                dsl.fetchExists(
                    dsl
                        .selectOne()
                        .from(USERS)
                        .where(USERS.EMAIL_NORMALIZED.eq("breached@kliniq.local")),
                ),
            ).isFalse()
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
        fun `repeated failed logins from one IP are throttled with LOGIN_BACKOFF and Retry-After`() {
            registerAndVerify(email = "backoff@kliniq.local")
            // First failure: 401 INVALID_CREDENTIALS (no prior state to gate on)
            // After it, the tracker has 1 failure recorded with a 2s wait window.
            mockMvc
                .post("/api/v1/auth/login") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = loginBody("backoff@kliniq.local", "still wrong yes really 12345")
                }.andExpect {
                    status { isUnauthorized() }
                    jsonPath("$.code") { value("INVALID_CREDENTIALS") }
                    header { exists("Retry-After") }
                }
            // Immediate retry — now we're inside the 2s window, so the
            // backoff layer rejects before LoginUseCase even runs.
            mockMvc
                .post("/api/v1/auth/login") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = loginBody("backoff@kliniq.local", DEFAULT_PASSWORD)
                }.andExpect {
                    status { isTooManyRequests() }
                    jsonPath("$.code") { value("LOGIN_BACKOFF") }
                    header { exists("Retry-After") }
                }
        }

        @Test
        fun `successful login wipes the backoff counter`() {
            registerAndVerify(email = "wipe@kliniq.local")
            // Burn one failure
            mockMvc
                .post("/api/v1/auth/login") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = loginBody("wipe@kliniq.local", "wrong wrong wrong wrong")
                }.andExpect { status { isUnauthorized() } }
            // Backoff state was written under kliniq:login-backoff:<ip>.
            assertThat(redis.keys("kliniq:login-backoff:*")).isNotEmpty()

            // A user-driven correct login is still gated by the wait window
            // — but the test isn't measuring time-based eviction; it asserts
            // that AFTER a success the counter is gone. So we wipe state to
            // simulate the wait elapsing, then login normally.
            redis.keys("kliniq:login-backoff:*")?.takeIf { it.isNotEmpty() }?.let(redis::delete)
            mockMvc
                .post("/api/v1/auth/login") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = loginBody("wipe@kliniq.local", DEFAULT_PASSWORD)
                }.andExpect { status { isOk() } }

            assertThat(redis.keys("kliniq:login-backoff:*")).isNullOrEmpty()
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

            // The wrong-password attempt above tripped the V13.2.6 backoff
            // counter; in production the user would wait it out, but this
            // test isn't about backoff so we wipe the state to keep the
            // assertion focused.
            redis.keys("kliniq:login-backoff:*")?.takeIf { it.isNotEmpty() }?.let(redis::delete)

            // New password works.
            mockMvc
                .post("/api/v1/auth/login") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = loginBody("kate@kliniq.local", "new horse battery staple xyz")
                }.andExpect { status { isOk() } }
        }

        // ---- /password/change ------------------------------------------

        private fun changeBody(
            currentPassword: String,
            newPassword: String,
        ): String =
            objectMapper.writeValueAsString(
                mapOf("currentPassword" to currentPassword, "newPassword" to newPassword),
            )

        @Test
        fun `change-password updates the hash and terminates other sessions but keeps the current one`() {
            registerAndVerify(email = "claire@kliniq.local")

            // Two concurrent sessions for the same user — laptop and phone.
            val laptopCookie =
                mockMvc
                    .post("/api/v1/auth/login") {
                        with(csrf())
                        contentType = MediaType.APPLICATION_JSON
                        content = loginBody("claire@kliniq.local", DEFAULT_PASSWORD)
                    }.andReturn()
                    .sessionCookieValue()!!
            val phoneCookie =
                mockMvc
                    .post("/api/v1/auth/login") {
                        with(csrf())
                        contentType = MediaType.APPLICATION_JSON
                        content = loginBody("claire@kliniq.local", DEFAULT_PASSWORD)
                    }.andReturn()
                    .sessionCookieValue()!!

            mockMvc
                .post("/api/v1/auth/password/change") {
                    with(csrf())
                    cookie(jakarta.servlet.http.Cookie(SessionCookieService.COOKIE_NAME, laptopCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = changeBody(DEFAULT_PASSWORD, "freshly-minted-passphrase 99")
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.message") { exists() }
                }

            // Current session (laptop) still works.
            mockMvc
                .get("/api/v1/auth/me") {
                    cookie(jakarta.servlet.http.Cookie(SessionCookieService.COOKIE_NAME, laptopCookie))
                }.andExpect { status { isOk() } }

            // Other session (phone) was killed.
            mockMvc
                .get("/api/v1/auth/me") {
                    cookie(jakarta.servlet.http.Cookie(SessionCookieService.COOKIE_NAME, phoneCookie))
                }.andExpect { status { isUnauthorized() } }

            // New password works for fresh logins; old one no longer does.
            redis.keys("kliniq:login-backoff:*")?.takeIf { it.isNotEmpty() }?.let(redis::delete)
            mockMvc
                .post("/api/v1/auth/login") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = loginBody("claire@kliniq.local", "freshly-minted-passphrase 99")
                }.andExpect { status { isOk() } }
        }

        @Test
        fun `change-password with wrong currentPassword returns 401 INVALID_CREDENTIALS`() {
            registerAndVerify(email = "diana@kliniq.local")
            val cookie =
                mockMvc
                    .post("/api/v1/auth/login") {
                        with(csrf())
                        contentType = MediaType.APPLICATION_JSON
                        content = loginBody("diana@kliniq.local", DEFAULT_PASSWORD)
                    }.andReturn()
                    .sessionCookieValue()!!

            mockMvc
                .post("/api/v1/auth/password/change") {
                    with(csrf())
                    cookie(jakarta.servlet.http.Cookie(SessionCookieService.COOKIE_NAME, cookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = changeBody("wrong wrong wrong wrong", "fresh good passphrase here 99")
                }.andExpect {
                    status { isUnauthorized() }
                    jsonPath("$.code") { value("INVALID_CREDENTIALS") }
                }
        }

        @Test
        fun `change-password with breached new password returns 400 PASSWORD_BREACHED`() {
            registerAndVerify(email = "ed@kliniq.local")
            val cookie =
                mockMvc
                    .post("/api/v1/auth/login") {
                        with(csrf())
                        contentType = MediaType.APPLICATION_JSON
                        content = loginBody("ed@kliniq.local", DEFAULT_PASSWORD)
                    }.andReturn()
                    .sessionCookieValue()!!

            org.mockito.kotlin
                .whenever(breachChecker.isBreached(org.mockito.kotlin.any()))
                .thenReturn(true)

            mockMvc
                .post("/api/v1/auth/password/change") {
                    with(csrf())
                    cookie(jakarta.servlet.http.Cookie(SessionCookieService.COOKIE_NAME, cookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = changeBody(DEFAULT_PASSWORD, "totally compromised pwd")
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("PASSWORD_BREACHED") }
                }
        }

        @Test
        fun `change-password rejects identical currentPassword and newPassword as SAME_PASSWORD`() {
            registerAndVerify(email = "frida@kliniq.local")
            val cookie =
                mockMvc
                    .post("/api/v1/auth/login") {
                        with(csrf())
                        contentType = MediaType.APPLICATION_JSON
                        content = loginBody("frida@kliniq.local", DEFAULT_PASSWORD)
                    }.andReturn()
                    .sessionCookieValue()!!

            mockMvc
                .post("/api/v1/auth/password/change") {
                    with(csrf())
                    cookie(jakarta.servlet.http.Cookie(SessionCookieService.COOKIE_NAME, cookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = changeBody(DEFAULT_PASSWORD, DEFAULT_PASSWORD)
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("SAME_PASSWORD") }
                }
        }

        @Test
        fun `change-password without a session is rejected as 401`() {
            mockMvc
                .post("/api/v1/auth/password/change") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = changeBody(DEFAULT_PASSWORD, "fresh good passphrase here 99")
                }.andExpect { status { isUnauthorized() } }
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

        @Test
        fun `reset-password with breached new password returns 400 PASSWORD_BREACHED`() {
            // Set up a real reset token first.
            val email = "reset-breached@kliniq.local"
            registerAndVerify(email)
            mockMvc
                .post("/api/v1/auth/password/forgot") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = forgotBody(email)
                }.andExpect { status { isOk() } }
            val token = captureResetToken()

            // From this point, pretend the candidate password is breached.
            org.mockito.kotlin
                .whenever(breachChecker.isBreached(org.mockito.kotlin.any()))
                .thenReturn(true)

            mockMvc
                .post("/api/v1/auth/password/reset") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = resetBody(token, "another long valid password 123")
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("PASSWORD_BREACHED") }
                }

            // Token must NOT have been consumed — user can retry with a new password.
            // (We can prove this by issuing a non-breached reset on the same token.)
            org.mockito.kotlin
                .whenever(breachChecker.isBreached(org.mockito.kotlin.any()))
                .thenReturn(false)
            mockMvc
                .post("/api/v1/auth/password/reset") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = resetBody(token, "fresh good passphrase here 99")
                }.andExpect { status { isOk() } }
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

        // ---- rate limiting ----------------------------------------------

        @Test
        fun `password-forgot returns 429 RATE_LIMITED after the IP burns through its window`() {
            // Limit is 3 per minute; the 4th call from the same IP must be rejected.
            repeat(3) {
                mockMvc
                    .post("/api/v1/auth/password/forgot") {
                        with(csrf())
                        contentType = MediaType.APPLICATION_JSON
                        content = forgotBody("anyone-$it@kliniq.local")
                    }.andExpect { status { isOk() } }
            }

            mockMvc
                .post("/api/v1/auth/password/forgot") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = forgotBody("nope@kliniq.local")
                }.andExpect {
                    status { isTooManyRequests() }
                    jsonPath("$.code") { value("RATE_LIMITED") }
                    header { exists("Retry-After") }
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

        // ---- PATCH /me (self-service displayName) ------------------------

        private fun profileBody(name: String) = objectMapper.writeValueAsString(mapOf("displayName" to name))

        @Test
        fun `PATCH me updates displayName, returns the new user, and writes an audit row`() {
            registerAndVerify(email = "morgan@kliniq.local", displayName = "Old Name")
            val cookie =
                mockMvc
                    .post("/api/v1/auth/login") {
                        with(csrf())
                        contentType = MediaType.APPLICATION_JSON
                        content = loginBody("morgan@kliniq.local", DEFAULT_PASSWORD)
                    }.andReturn()
                    .sessionCookieValue()!!

            mockMvc
                .patch("/api/v1/auth/me") {
                    with(csrf())
                    cookie(jakarta.servlet.http.Cookie(SessionCookieService.COOKIE_NAME, cookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = profileBody("New Name")
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.displayName") { value("New Name") }
                    jsonPath("$.email") { value("morgan@kliniq.local") }
                }

            // GET /me on the same session reflects the new name.
            mockMvc
                .get("/api/v1/auth/me") {
                    cookie(jakarta.servlet.http.Cookie(SessionCookieService.COOKIE_NAME, cookie))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.displayName") { value("New Name") }
                }

            val userId =
                dsl
                    .select(USERS.ID)
                    .from(USERS)
                    .where(USERS.EMAIL_NORMALIZED.eq("morgan@kliniq.local"))
                    .fetchOne(0, java.util.UUID::class.java)!!
            val actions =
                dsl
                    .select(AUDIT_EVENTS.ACTION)
                    .from(AUDIT_EVENTS)
                    .where(AUDIT_EVENTS.ENTITY_ID.eq(userId))
                    .fetch(AUDIT_EVENTS.ACTION)
            assertThat(actions).contains("user.display_name_changed")
        }

        @Test
        fun `PATCH me trims whitespace and treats an unchanged value as a no-op (no audit row)`() {
            registerAndVerify(email = "nia@kliniq.local", displayName = "Same Name")
            val cookie =
                mockMvc
                    .post("/api/v1/auth/login") {
                        with(csrf())
                        contentType = MediaType.APPLICATION_JSON
                        content = loginBody("nia@kliniq.local", DEFAULT_PASSWORD)
                    }.andReturn()
                    .sessionCookieValue()!!

            mockMvc
                .patch("/api/v1/auth/me") {
                    with(csrf())
                    cookie(jakarta.servlet.http.Cookie(SessionCookieService.COOKIE_NAME, cookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = profileBody("  Same Name  ")
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.displayName") { value("Same Name") }
                }

            val userId =
                dsl
                    .select(USERS.ID)
                    .from(USERS)
                    .where(USERS.EMAIL_NORMALIZED.eq("nia@kliniq.local"))
                    .fetchOne(0, java.util.UUID::class.java)!!
            val displayNameAudits =
                dsl
                    .selectCount()
                    .from(AUDIT_EVENTS)
                    .where(AUDIT_EVENTS.ENTITY_ID.eq(userId))
                    .and(AUDIT_EVENTS.ACTION.eq("user.display_name_changed"))
                    .fetchOne(0, Int::class.java) ?: 0
            assertThat(displayNameAudits)
                .`as`("idempotent PATCH writes no display_name_changed audit row")
                .isEqualTo(0)
        }

        @Test
        fun `PATCH me without a session returns 401`() {
            mockMvc
                .patch("/api/v1/auth/me") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = profileBody("Anything")
                }.andExpect { status { isUnauthorized() } }
        }

        @Test
        fun `PATCH me with blank displayName returns 400 VALIDATION_ERROR`() {
            registerAndVerify(email = "olga@kliniq.local")
            val cookie =
                mockMvc
                    .post("/api/v1/auth/login") {
                        with(csrf())
                        contentType = MediaType.APPLICATION_JSON
                        content = loginBody("olga@kliniq.local", DEFAULT_PASSWORD)
                    }.andReturn()
                    .sessionCookieValue()!!

            mockMvc
                .patch("/api/v1/auth/me") {
                    with(csrf())
                    cookie(jakarta.servlet.http.Cookie(SessionCookieService.COOKIE_NAME, cookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = profileBody("   ")
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("VALIDATION_ERROR") }
                    jsonPath("$.fieldErrors[*].field") { value("displayName") }
                }
        }

        @Test
        fun `PATCH me with overlong displayName returns 400 VALIDATION_ERROR`() {
            registerAndVerify(email = "petra@kliniq.local")
            val cookie =
                mockMvc
                    .post("/api/v1/auth/login") {
                        with(csrf())
                        contentType = MediaType.APPLICATION_JSON
                        content = loginBody("petra@kliniq.local", DEFAULT_PASSWORD)
                    }.andReturn()
                    .sessionCookieValue()!!

            // 101 characters — one past the 100-char domain bound.
            val tooLong = "x".repeat(101)
            mockMvc
                .patch("/api/v1/auth/me") {
                    with(csrf())
                    cookie(jakarta.servlet.http.Cookie(SessionCookieService.COOKIE_NAME, cookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = profileBody(tooLong)
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("VALIDATION_ERROR") }
                    jsonPath("$.fieldErrors[*].field") { value("displayName") }
                }
        }

        companion object {
            private const val DEFAULT_PASSWORD = "correct horse battery staple"
        }
    }
