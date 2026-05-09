package com.kliniq.api.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.kliniq.db.tables.references.BOOKINGS
import com.kliniq.db.tables.references.OPERATING_ROOMS
import com.kliniq.db.tables.references.USERS
import com.kliniq.db.tables.references.USER_INVITATIONS
import com.kliniq.domain.user.Role
import com.kliniq.infra.mail.EmailSender
import com.kliniq.infra.security.SessionCookieService
import com.kliniq.support.TestcontainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.util.UUID

@SpringBootTest
@Import(TestcontainersConfig::class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InvitationControllerIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val mapper: ObjectMapper,
        private val dsl: DSLContext,
        private val redis: StringRedisTemplate,
    ) {
        @MockitoBean
        private lateinit var emailSender: EmailSender

        @BeforeEach
        fun reset() {
            Mockito.reset(emailSender)
            dsl.deleteFrom(BOOKINGS).execute()
            dsl.deleteFrom(OPERATING_ROOMS).execute()
            dsl.deleteFrom(USER_INVITATIONS).execute()
            dsl.deleteFrom(USERS).execute()
            redis.keys("rate-limit:*")?.takeIf { it.isNotEmpty() }?.let(redis::delete)
            redis.keys("kliniq:login-backoff:*")?.takeIf { it.isNotEmpty() }?.let(redis::delete)
            redis.keys("kliniq:session:*")?.takeIf { it.isNotEmpty() }?.let(redis::delete)
        }

        // ---- Helpers ---------------------------------------------------------

        private fun MvcResult.sessionCookieValue(): String? =
            response
                .getHeader("Set-Cookie")
                ?.takeIf { it.startsWith("${SessionCookieService.COOKIE_NAME}=") }
                ?.substringAfter("=")
                ?.substringBefore(";")

        private fun captureVerifyToken(): String {
            val captor = argumentCaptor<String>()
            verify(emailSender).sendEmailVerification(any(), any(), captor.capture())
            return captor.lastValue.substringAfter("token=")
        }

        private fun captureInvitationToken(): String {
            val captor = argumentCaptor<String>()
            verify(emailSender).sendInvitation(any(), any(), captor.capture())
            return captor.lastValue.substringAfter("token=")
        }

        private fun loginAs(
            email: String,
            role: Role,
            password: String = "correct-horse-battery-staple",
        ): String {
            Mockito.reset(emailSender)
            mockMvc
                .post("/api/v1/auth/register") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        mapper.writeValueAsString(
                            mapOf("email" to email, "password" to password, "displayName" to "User $email"),
                        )
                }.andExpect { status { isOk() } }
            val token = captureVerifyToken()
            mockMvc
                .post("/api/v1/auth/verify") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(mapOf("token" to token))
                }.andExpect { status { isOk() } }
            dsl
                .update(USERS)
                .set(USERS.ROLE, role.name)
                .where(USERS.EMAIL_NORMALIZED.eq(email.lowercase()))
                .execute()
            val login =
                mockMvc
                    .post("/api/v1/auth/login") {
                        with(csrf())
                        contentType = MediaType.APPLICATION_JSON
                        content = mapper.writeValueAsString(mapOf("email" to email, "password" to password))
                    }.andExpect { status { isOk() } }
                    .andReturn()
            return checkNotNull(login.sessionCookieValue()) { "no session cookie after login" }
        }

        private fun cookie(value: String) = jakarta.servlet.http.Cookie(SessionCookieService.COOKIE_NAME, value)

        // ---- Tests -----------------------------------------------------------

        @Test
        fun `non-admin cannot list or create invitations`() {
            val staffCookie = loginAs("staff@kliniq.local", Role.STAFF)

            mockMvc
                .get("/api/v1/admin/invitations") { cookie(cookie(staffCookie)) }
                .andExpect { status { isForbidden() } }

            mockMvc
                .post("/api/v1/admin/invitations") {
                    with(csrf())
                    cookie(cookie(staffCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        mapper.writeValueAsString(
                            mapOf("email" to "x@kliniq.local", "role" to "STAFF"),
                        )
                }.andExpect { status { isForbidden() } }
        }

        @Test
        @Suppress("LongMethod") // multi-step happy-path drives 3 endpoints
        fun `admin can issue an invitation, then accept it, then log in as the new user`() {
            val adminCookie = loginAs("admin@kliniq.local", Role.ADMIN)

            // Issue
            val createResp =
                mockMvc
                    .post("/api/v1/admin/invitations") {
                        with(csrf())
                        cookie(cookie(adminCookie))
                        contentType = MediaType.APPLICATION_JSON
                        content =
                            mapper.writeValueAsString(
                                mapOf(
                                    "email" to "newdoc@kliniq.local",
                                    "role" to "MANAGER",
                                    "isSurgeon" to true,
                                    "specialty" to "GENERAL",
                                ),
                            )
                    }.andExpect {
                        status { isCreated() }
                        jsonPath("$.email") { value("newdoc@kliniq.local") }
                        jsonPath("$.role") { value("MANAGER") }
                        jsonPath("$.isSurgeon") { value(true) }
                        jsonPath("$.specialty") { value("GENERAL") }
                        jsonPath("$.tokenHash") { doesNotExist() }
                    }.andReturn()
            val inviteId = mapper.readTree(createResp.response.contentAsString)["id"].asText()

            // Token came out via email — capture it.
            val token = captureInvitationToken()
            assertThat(token).isNotEmpty()

            // Accept (no auth required)
            val acceptResp =
                mockMvc
                    .post("/api/v1/auth/invitation/accept") {
                        with(csrf())
                        contentType = MediaType.APPLICATION_JSON
                        content =
                            mapper.writeValueAsString(
                                mapOf(
                                    "token" to token,
                                    "password" to "fresh-password-123-yes",
                                    "displayName" to "Dr New",
                                ),
                            )
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.email") { value("newdoc@kliniq.local") }
                        jsonPath("$.role") { value("MANAGER") }
                        jsonPath("$.isSurgeon") { value(true) }
                        jsonPath("$.specialty") { value("GENERAL") }
                        jsonPath("$.emailVerifiedAt") { exists() }
                    }.andReturn()
            val newSession = checkNotNull(acceptResp.sessionCookieValue())

            // The session cookie works straight away — no separate login needed.
            mockMvc
                .get("/api/v1/auth/me") { cookie(cookie(newSession)) }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.role") { value("MANAGER") }
                }

            // Invitation row is now accepted, not pending.
            val acceptedAt =
                dsl
                    .select(USER_INVITATIONS.ACCEPTED_AT)
                    .from(USER_INVITATIONS)
                    .where(USER_INVITATIONS.ID.eq(UUID.fromString(inviteId)))
                    .fetchOne(USER_INVITATIONS.ACCEPTED_AT)
            assertThat(acceptedAt).isNotNull()
        }

        @Test
        fun `double-accept of the same token returns INVITATION_ALREADY_ACCEPTED`() {
            val adminCookie = loginAs("admin@kliniq.local", Role.ADMIN)
            mockMvc
                .post("/api/v1/admin/invitations") {
                    with(csrf())
                    cookie(cookie(adminCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        mapper.writeValueAsString(
                            mapOf("email" to "twoaccept@kliniq.local", "role" to "STAFF"),
                        )
                }.andExpect { status { isCreated() } }
            val token = captureInvitationToken()

            mockMvc
                .post("/api/v1/auth/invitation/accept") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        mapper.writeValueAsString(
                            mapOf(
                                "token" to token,
                                "password" to "another-fresh-pass-123",
                                "displayName" to "First",
                            ),
                        )
                }.andExpect { status { isOk() } }

            mockMvc
                .post("/api/v1/auth/invitation/accept") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        mapper.writeValueAsString(
                            mapOf(
                                "token" to token,
                                "password" to "another-fresh-pass-456",
                                "displayName" to "Second",
                            ),
                        )
                }.andExpect {
                    status { isConflict() }
                    jsonPath("$.code") { value("INVITATION_ALREADY_ACCEPTED") }
                }
        }

        @Test
        fun `expired invitation returns INVITATION_EXPIRED`() {
            val adminCookie = loginAs("admin@kliniq.local", Role.ADMIN)
            mockMvc
                .post("/api/v1/admin/invitations") {
                    with(csrf())
                    cookie(cookie(adminCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        mapper.writeValueAsString(mapOf("email" to "stale@kliniq.local", "role" to "STAFF"))
                }.andExpect { status { isCreated() } }
            val token = captureInvitationToken()

            // Push the row back into the past directly via DSL.
            dsl
                .update(USER_INVITATIONS)
                .set(USER_INVITATIONS.EXPIRES_AT, OffsetDateTime.now().minusDays(1))
                .where(USER_INVITATIONS.EMAIL_NORMALIZED.eq("stale@kliniq.local"))
                .execute()

            mockMvc
                .post("/api/v1/auth/invitation/accept") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        mapper.writeValueAsString(
                            mapOf(
                                "token" to token,
                                "password" to "doesnt-matter-yet-12",
                                "displayName" to "Late",
                            ),
                        )
                }.andExpect {
                    status { isGone() }
                    jsonPath("$.code") { value("INVITATION_EXPIRED") }
                }
        }

        @Test
        fun `revoked invitation returns INVITATION_REVOKED`() {
            val adminCookie = loginAs("admin@kliniq.local", Role.ADMIN)
            val createResp =
                mockMvc
                    .post("/api/v1/admin/invitations") {
                        with(csrf())
                        cookie(cookie(adminCookie))
                        contentType = MediaType.APPLICATION_JSON
                        content =
                            mapper.writeValueAsString(mapOf("email" to "drop@kliniq.local", "role" to "STAFF"))
                    }.andExpect { status { isCreated() } }
                    .andReturn()
            val inviteId = mapper.readTree(createResp.response.contentAsString)["id"].asText()
            val token = captureInvitationToken()

            mockMvc
                .delete("/api/v1/admin/invitations/$inviteId") {
                    with(csrf())
                    cookie(cookie(adminCookie))
                }.andExpect { status { isNoContent() } }

            mockMvc
                .post("/api/v1/auth/invitation/accept") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        mapper.writeValueAsString(
                            mapOf(
                                "token" to token,
                                "password" to "doesnt-matter-yet-12",
                                "displayName" to "Too late",
                            ),
                        )
                }.andExpect {
                    status { isGone() }
                    jsonPath("$.code") { value("INVITATION_REVOKED") }
                }
        }

        @Test
        fun `pending invitation for the same email is rejected with INVITATION_PENDING`() {
            val adminCookie = loginAs("admin@kliniq.local", Role.ADMIN)
            mockMvc
                .post("/api/v1/admin/invitations") {
                    with(csrf())
                    cookie(cookie(adminCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        mapper.writeValueAsString(mapOf("email" to "dup@kliniq.local", "role" to "STAFF"))
                }.andExpect { status { isCreated() } }

            mockMvc
                .post("/api/v1/admin/invitations") {
                    with(csrf())
                    cookie(cookie(adminCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        mapper.writeValueAsString(mapOf("email" to "DUP@kliniq.local", "role" to "MANAGER"))
                }.andExpect {
                    status { isConflict() }
                    jsonPath("$.code") { value("INVITATION_PENDING") }
                }
        }

        @Test
        fun `inviting an email that already has a user is rejected with USER_ALREADY_EXISTS`() {
            val adminCookie = loginAs("admin@kliniq.local", Role.ADMIN)
            // The admin themselves is already a user.
            mockMvc
                .post("/api/v1/admin/invitations") {
                    with(csrf())
                    cookie(cookie(adminCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        mapper.writeValueAsString(
                            mapOf("email" to "admin@kliniq.local", "role" to "STAFF"),
                        )
                }.andExpect {
                    status { isConflict() }
                    jsonPath("$.code") { value("USER_ALREADY_EXISTS") }
                }
        }

        @Test
        fun `bogus token returns INVALID_TOKEN`() {
            mockMvc
                .post("/api/v1/auth/invitation/accept") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        mapper.writeValueAsString(
                            mapOf(
                                "token" to "not-a-real-token",
                                "password" to "fresh-pass-12345-ok",
                                "displayName" to "Nope",
                            ),
                        )
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("INVALID_TOKEN") }
                }
        }

        @Test
        fun `preview returns email + role for a pending invitation`() {
            val adminCookie = loginAs("admin@kliniq.local", Role.ADMIN)
            mockMvc
                .post("/api/v1/admin/invitations") {
                    with(csrf())
                    cookie(cookie(adminCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        mapper.writeValueAsString(
                            mapOf(
                                "email" to "preview@kliniq.local",
                                "role" to "MANAGER",
                                "isSurgeon" to true,
                                "specialty" to "ORTHOPEDICS",
                            ),
                        )
                }.andExpect { status { isCreated() } }
            val token = captureInvitationToken()

            mockMvc
                .post("/api/v1/auth/invitation/preview") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(mapOf("token" to token))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.email") { value("preview@kliniq.local") }
                    jsonPath("$.role") { value("MANAGER") }
                    jsonPath("$.isSurgeon") { value(true) }
                    jsonPath("$.specialty") { value("ORTHOPEDICS") }
                    jsonPath("$.expiresAt") { exists() }
                }
        }

        @Test
        fun `preview surfaces the same terminal codes as accept`() {
            val adminCookie = loginAs("admin@kliniq.local", Role.ADMIN)

            // Bogus token → 400 INVALID_TOKEN
            mockMvc
                .post("/api/v1/auth/invitation/preview") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(mapOf("token" to "garbage"))
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("INVALID_TOKEN") }
                }

            // Issue, then revoke → preview returns 410 INVITATION_REVOKED
            val createResp =
                mockMvc
                    .post("/api/v1/admin/invitations") {
                        with(csrf())
                        cookie(cookie(adminCookie))
                        contentType = MediaType.APPLICATION_JSON
                        content =
                            mapper.writeValueAsString(mapOf("email" to "rv@kliniq.local", "role" to "STAFF"))
                    }.andExpect { status { isCreated() } }
                    .andReturn()
            val inviteId = mapper.readTree(createResp.response.contentAsString)["id"].asText()
            val token = captureInvitationToken()
            mockMvc
                .delete("/api/v1/admin/invitations/$inviteId") {
                    with(csrf())
                    cookie(cookie(adminCookie))
                }.andExpect { status { isNoContent() } }

            mockMvc
                .post("/api/v1/auth/invitation/preview") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(mapOf("token" to token))
                }.andExpect {
                    status { isGone() }
                    jsonPath("$.code") { value("INVITATION_REVOKED") }
                }
        }
    }
