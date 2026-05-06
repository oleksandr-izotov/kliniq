package com.kliniq.api.clinic

import com.fasterxml.jackson.databind.ObjectMapper
import com.kliniq.db.tables.references.BOOKINGS
import com.kliniq.db.tables.references.CLINIC_SETTINGS
import com.kliniq.db.tables.references.OPERATING_ROOMS
import com.kliniq.db.tables.references.USERS
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

@SpringBootTest
@Import(TestcontainersConfig::class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClinicSettingsControllerIntegrationTest
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
            dsl.deleteFrom(USERS).execute()
            // The singleton row is shared across tests — reset to V3 defaults
            // before each so previous mutations don't leak.
            dsl
                .update(CLINIC_SETTINGS)
                .set(CLINIC_SETTINGS.NAME, "Kliniq")
                .set(CLINIC_SETTINGS.TIMEZONE, "Europe/Berlin")
                .set(CLINIC_SETTINGS.WORKING_HOURS_START, java.time.LocalTime.of(8, 0))
                .set(CLINIC_SETTINGS.WORKING_HOURS_END, java.time.LocalTime.of(20, 0))
                .set(CLINIC_SETTINGS.DEFAULT_BOOKING_MINUTES, 60)
                .where(CLINIC_SETTINGS.ID.eq(1))
                .execute()
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

        // ---- Tests -----------------------------------------------------------

        @Test
        fun `GET clinic settings returns the seeded defaults to any authenticated user`() {
            val staff = loginAs("staff@kliniq.local", Role.STAFF)
            mockMvc
                .get("/api/v1/clinic/settings") { cookie(cookie(staff)) }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.name") { value("Kliniq") }
                    jsonPath("$.timezone") { value("Europe/Berlin") }
                    jsonPath("$.workingHoursStart") { value("08:00:00") }
                    jsonPath("$.workingHoursEnd") { value("20:00:00") }
                    jsonPath("$.defaultBookingMinutes") { value(60) }
                }
        }

        @Test
        fun `STAFF cannot patch clinic settings`() {
            val staff = loginAs("staff@kliniq.local", Role.STAFF)
            mockMvc
                .patch("/api/v1/clinic/settings") {
                    with(csrf())
                    cookie(cookie(staff))
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(mapOf("name" to "Hacked"))
                }.andExpect { status { isForbidden() } }
        }

        @Test
        fun `MANAGER cannot patch clinic settings — only ADMIN`() {
            val manager = loginAs("mgr@kliniq.local", Role.MANAGER)
            mockMvc
                .patch("/api/v1/clinic/settings") {
                    with(csrf())
                    cookie(cookie(manager))
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(mapOf("name" to "Manager Tried"))
                }.andExpect { status { isForbidden() } }
        }

        @Test
        fun `ADMIN can patch clinic settings`() {
            val admin = loginAs("admin@kliniq.local", Role.ADMIN)
            mockMvc
                .patch("/api/v1/clinic/settings") {
                    with(csrf())
                    cookie(cookie(admin))
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        mapper.writeValueAsString(
                            mapOf(
                                "name" to "Updated Clinic",
                                "timezone" to "Europe/London",
                                "workingHoursStart" to "07:30:00",
                                "workingHoursEnd" to "21:00:00",
                                "defaultBookingMinutes" to 45,
                            ),
                        )
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.name") { value("Updated Clinic") }
                    jsonPath("$.timezone") { value("Europe/London") }
                    jsonPath("$.workingHoursStart") { value("07:30:00") }
                    jsonPath("$.defaultBookingMinutes") { value(45) }
                }
        }

        @Test
        fun `bad timezone returns 400 INVALID_TIMEZONE`() {
            val admin = loginAs("admin@kliniq.local", Role.ADMIN)
            mockMvc
                .patch("/api/v1/clinic/settings") {
                    with(csrf())
                    cookie(cookie(admin))
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(mapOf("timezone" to "Mars/Olympus_Mons"))
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("INVALID_TIMEZONE") }
                }
        }

        @Test
        fun `working hours end before start returns 400 VALIDATION_ERROR`() {
            val admin = loginAs("admin@kliniq.local", Role.ADMIN)
            mockMvc
                .patch("/api/v1/clinic/settings") {
                    with(csrf())
                    cookie(cookie(admin))
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        mapper.writeValueAsString(
                            mapOf("workingHoursStart" to "10:00:00", "workingHoursEnd" to "09:00:00"),
                        )
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("VALIDATION_ERROR") }
                }
        }

        @Test
        fun `unauthenticated GET is rejected with 401`() {
            mockMvc
                .get("/api/v1/clinic/settings")
                .andExpect { status { isUnauthorized() } }
        }
    }
