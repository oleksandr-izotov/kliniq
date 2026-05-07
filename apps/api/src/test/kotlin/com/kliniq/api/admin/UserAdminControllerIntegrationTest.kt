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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.util.UUID

@SpringBootTest
@Import(TestcontainersConfig::class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserAdminControllerIntegrationTest
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

        private fun userId(email: String): UUID =
            dsl
                .select(USERS.ID)
                .from(USERS)
                .where(USERS.EMAIL_NORMALIZED.eq(email.lowercase()))
                .fetchOne(USERS.ID) ?: error("user $email not seeded")

        // ---- Tests -----------------------------------------------------------

        @Test
        fun `non-admin cannot list or patch users`() {
            val staffCookie = loginAs("staff@kliniq.local", Role.STAFF)

            mockMvc
                .get("/api/v1/admin/users") { cookie(cookie(staffCookie)) }
                .andExpect { status { isForbidden() } }

            mockMvc
                .patch("/api/v1/admin/users/${UUID.randomUUID()}") {
                    with(csrf())
                    cookie(cookie(staffCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(mapOf("role" to "ADMIN"))
                }.andExpect { status { isForbidden() } }
        }

        @Test
        fun `admin can list users with pagination + filter`() {
            loginAs("admin@kliniq.local", Role.ADMIN).also { adminCookie ->
                loginAs("mgr@kliniq.local", Role.MANAGER)
                loginAs("staff@kliniq.local", Role.STAFF)

                mockMvc
                    .get("/api/v1/admin/users") { cookie(cookie(adminCookie)) }
                    .andExpect {
                        status { isOk() }
                        jsonPath("$.total") { value(3) }
                        jsonPath("$.items.length()") { value(3) }
                    }

                mockMvc
                    .get("/api/v1/admin/users?role=MANAGER") { cookie(cookie(adminCookie)) }
                    .andExpect {
                        status { isOk() }
                        jsonPath("$.total") { value(1) }
                        jsonPath("$.items[0].email") { value("mgr@kliniq.local") }
                    }

                mockMvc
                    .get("/api/v1/admin/users?q=staff") { cookie(cookie(adminCookie)) }
                    .andExpect {
                        status { isOk() }
                        jsonPath("$.total") { value(1) }
                        jsonPath("$.items[0].email") { value("staff@kliniq.local") }
                    }
            }
        }

        @Test
        fun `admin can promote a user to MANAGER + surgeon`() {
            val adminCookie = loginAs("admin@kliniq.local", Role.ADMIN)
            loginAs("future-mgr@kliniq.local", Role.STAFF)
            val targetId = userId("future-mgr@kliniq.local")

            mockMvc
                .patch("/api/v1/admin/users/$targetId") {
                    with(csrf())
                    cookie(cookie(adminCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        mapper.writeValueAsString(
                            mapOf("role" to "MANAGER", "isSurgeon" to true, "specialty" to "GENERAL"),
                        )
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.role") { value("MANAGER") }
                    jsonPath("$.isSurgeon") { value(true) }
                    jsonPath("$.specialty") { value("GENERAL") }
                }
        }

        @Test
        fun `setting isSurgeon=true without a specialty returns INVALID_SPECIALTY`() {
            val adminCookie = loginAs("admin@kliniq.local", Role.ADMIN)
            loginAs("victim@kliniq.local", Role.STAFF)
            val targetId = userId("victim@kliniq.local")

            mockMvc
                .patch("/api/v1/admin/users/$targetId") {
                    with(csrf())
                    cookie(cookie(adminCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(mapOf("isSurgeon" to true))
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("INVALID_SPECIALTY") }
                }
        }

        @Test
        fun `setting isSurgeon=false clears specialty`() {
            val adminCookie = loginAs("admin@kliniq.local", Role.ADMIN)
            loginAs("ex-doc@kliniq.local", Role.STAFF)
            val targetId = userId("ex-doc@kliniq.local")
            // Make them a surgeon first.
            dsl
                .update(USERS)
                .set(USERS.IS_SURGEON, true)
                .set(USERS.SPECIALTY, "GENERAL")
                .where(USERS.ID.eq(targetId))
                .execute()

            mockMvc
                .patch("/api/v1/admin/users/$targetId") {
                    with(csrf())
                    cookie(cookie(adminCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(mapOf("isSurgeon" to false))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.isSurgeon") { value(false) }
                    jsonPath("$.specialty") { doesNotExist() }
                }
        }

        @Test
        fun `admin demoting themselves returns SELF_LOCKOUT`() {
            val adminCookie = loginAs("admin@kliniq.local", Role.ADMIN)
            // A second admin so it isn't a LastAdmin case — we want to
            // surface SelfLockout specifically.
            loginAs("other-admin@kliniq.local", Role.ADMIN)
            val selfId = userId("admin@kliniq.local")

            mockMvc
                .patch("/api/v1/admin/users/$selfId") {
                    with(csrf())
                    cookie(cookie(adminCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(mapOf("role" to "MANAGER"))
                }.andExpect {
                    status { isConflict() }
                    jsonPath("$.code") { value("SELF_LOCKOUT") }
                }
        }

        @Test
        fun `admin disabling themselves returns SELF_LOCKOUT`() {
            val adminCookie = loginAs("admin@kliniq.local", Role.ADMIN)
            loginAs("other-admin@kliniq.local", Role.ADMIN)
            val selfId = userId("admin@kliniq.local")

            mockMvc
                .patch("/api/v1/admin/users/$selfId") {
                    with(csrf())
                    cookie(cookie(adminCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(mapOf("status" to "DISABLED"))
                }.andExpect {
                    status { isConflict() }
                    jsonPath("$.code") { value("SELF_LOCKOUT") }
                }
        }

        @Test
        fun `disabling a user kills their open sessions`() {
            val adminCookie = loginAs("admin@kliniq.local", Role.ADMIN)
            val victimCookie = loginAs("victim2@kliniq.local", Role.STAFF)
            val victimId = userId("victim2@kliniq.local")

            // Confirm the victim's session works.
            mockMvc
                .get("/api/v1/auth/me") { cookie(cookie(victimCookie)) }
                .andExpect { status { isOk() } }

            // Admin disables victim.
            mockMvc
                .patch("/api/v1/admin/users/$victimId") {
                    with(csrf())
                    cookie(cookie(adminCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(mapOf("status" to "DISABLED"))
                }.andExpect { status { isOk() } }

            // Victim's session is now invalid.
            mockMvc
                .get("/api/v1/auth/me") { cookie(cookie(victimCookie)) }
                .andExpect { status { isUnauthorized() } }
        }
    }
