package com.kliniq.api.user

import com.fasterxml.jackson.databind.ObjectMapper
import com.kliniq.db.tables.references.USERS
import com.kliniq.domain.user.Role
import com.kliniq.infra.mail.EmailSender
import com.kliniq.infra.security.SessionCookieService
import com.kliniq.support.TestcontainersConfig
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
import org.springframework.test.web.servlet.post
import java.util.UUID

@SpringBootTest
@Import(TestcontainersConfig::class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserControllerIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val mapper: ObjectMapper,
        private val dsl: org.jooq.DSLContext,
        private val redis: StringRedisTemplate,
    ) {
        @MockitoBean
        private lateinit var emailSender: EmailSender

        @BeforeEach
        fun reset() {
            Mockito.reset(emailSender)
            // Bookings + ORs FK back to users with ON DELETE RESTRICT.
            dsl.deleteFrom(com.kliniq.db.tables.references.BOOKINGS).execute()
            dsl.deleteFrom(com.kliniq.db.tables.references.OPERATING_ROOMS).execute()
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

        private fun seedSurgeon(
            displayName: String,
            specialty: String,
            isSurgeon: Boolean = true,
            disabled: Boolean = false,
        ): UUID {
            val id = UUID.randomUUID()
            dsl
                .insertInto(USERS)
                .set(USERS.ID, id)
                .set(USERS.EMAIL, "doc-$id@kliniq.local")
                .set(USERS.DISPLAY_NAME, displayName)
                .set(USERS.ROLE, "STAFF")
                .set(USERS.IS_SURGEON, isSurgeon)
                .set(USERS.SPECIALTY, if (isSurgeon) specialty else null)
                .set(USERS.STATUS, if (disabled) "DISABLED" else "ACTIVE")
                .execute()
            return id
        }

        // ---- Tests -----------------------------------------------------------

        @Test
        fun `unauthenticated request is rejected with 401`() {
            mockMvc
                .get("/api/v1/users/surgeons")
                .andExpect { status { isUnauthorized() } }
        }

        @Test
        fun `staff can read the surgeon list`() {
            val cookie = loginAs("staff@kliniq.local", Role.STAFF)
            mockMvc
                .get("/api/v1/users/surgeons") { cookie(cookie(cookie)) }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.length()") { value(0) }
                }
        }

        @Test
        fun `returns active surgeons sorted by displayName, hides non-surgeons + disabled`() {
            val cookie = loginAs("staff@kliniq.local", Role.STAFF)
            seedSurgeon("Dr Zach", "GENERAL")
            seedSurgeon("Dr Alex", "CARDIOLOGY")
            seedSurgeon("Disabled Doc", "ORTHOPEDICS", disabled = true)
            seedSurgeon("Not a Surgeon", "GENERAL", isSurgeon = false)

            mockMvc
                .get("/api/v1/users/surgeons") { cookie(cookie(cookie)) }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.length()") { value(2) }
                    jsonPath("$[0].displayName") { value("Dr Alex") }
                    jsonPath("$[0].specialty") { value("CARDIOLOGY") }
                    jsonPath("$[1].displayName") { value("Dr Zach") }
                }
        }

        @Test
        fun `surgeon dto exposes only id, displayName, specialty (no email)`() {
            val cookie = loginAs("staff@kliniq.local", Role.STAFF)
            seedSurgeon("Dr Alex", "GENERAL")

            val response =
                mockMvc
                    .get("/api/v1/users/surgeons") { cookie(cookie(cookie)) }
                    .andReturn()
                    .response.contentAsString
            val tree = mapper.readTree(response)
            val keys = tree[0].fieldNames().asSequence().toSet()
            assert(keys == setOf("id", "displayName", "specialty")) {
                "expected only id/displayName/specialty, got $keys"
            }
        }
    }
