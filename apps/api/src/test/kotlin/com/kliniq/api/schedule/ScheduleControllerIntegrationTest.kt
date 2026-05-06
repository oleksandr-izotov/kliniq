package com.kliniq.api.schedule

import com.fasterxml.jackson.databind.ObjectMapper
import com.kliniq.db.tables.references.BOOKINGS
import com.kliniq.db.tables.references.OPERATING_ROOMS
import com.kliniq.db.tables.references.USERS
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
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@SpringBootTest
@Import(TestcontainersConfig::class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScheduleControllerIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val mapper: ObjectMapper,
        private val dsl: DSLContext,
        private val redis: StringRedisTemplate,
    ) {
        @MockitoBean
        private lateinit var emailSender: EmailSender

        private lateinit var staffCookie: String
        private lateinit var surgeonId: UUID
        private lateinit var activeOrId: UUID
        private lateinit var maintenanceOrId: UUID
        private lateinit var retiredOrId: UUID

        @BeforeEach
        fun reset() {
            Mockito.reset(emailSender)
            dsl.deleteFrom(BOOKINGS).execute()
            dsl.deleteFrom(OPERATING_ROOMS).execute()
            dsl.deleteFrom(USERS).execute()
            redis.keys("rate-limit:*")?.takeIf { it.isNotEmpty() }?.let(redis::delete)
            redis.keys("kliniq:login-backoff:*")?.takeIf { it.isNotEmpty() }?.let(redis::delete)
            redis.keys("kliniq:session:*")?.takeIf { it.isNotEmpty() }?.let(redis::delete)

            staffCookie = registerVerifyAndLogin("staff@kliniq.local")

            surgeonId = UUID.randomUUID()
            dsl
                .insertInto(USERS)
                .set(USERS.ID, surgeonId)
                .set(USERS.EMAIL, "doc-$surgeonId@kliniq.local")
                .set(USERS.DISPLAY_NAME, "Dr Test")
                .set(USERS.ROLE, "STAFF")
                .set(USERS.IS_SURGEON, true)
                .set(USERS.SPECIALTY, "GENERAL")
                .execute()

            activeOrId = UUID.randomUUID()
            dsl
                .insertInto(OPERATING_ROOMS)
                .set(OPERATING_ROOMS.ID, activeOrId)
                .set(OPERATING_ROOMS.CODE, "OR-A-${activeOrId.toString().take(8)}")
                .set(OPERATING_ROOMS.NAME, "Active OR")
                .execute()

            maintenanceOrId = UUID.randomUUID()
            dsl
                .insertInto(OPERATING_ROOMS)
                .set(OPERATING_ROOMS.ID, maintenanceOrId)
                .set(OPERATING_ROOMS.CODE, "OR-M-${maintenanceOrId.toString().take(8)}")
                .set(OPERATING_ROOMS.NAME, "Maintenance OR")
                .set(OPERATING_ROOMS.STATUS, "MAINTENANCE")
                .execute()

            retiredOrId = UUID.randomUUID()
            dsl
                .insertInto(OPERATING_ROOMS)
                .set(OPERATING_ROOMS.ID, retiredOrId)
                .set(OPERATING_ROOMS.CODE, "OR-R-${retiredOrId.toString().take(8)}")
                .set(OPERATING_ROOMS.NAME, "Retired OR")
                .set(OPERATING_ROOMS.STATUS, "RETIRED")
                .execute()
        }

        // ---- Helpers ---------------------------------------------------------

        private fun registerVerifyAndLogin(email: String): String {
            val password = "correct-horse-battery-staple"
            mockMvc
                .post("/api/v1/auth/register") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        mapper.writeValueAsString(
                            mapOf(
                                "email" to email,
                                "password" to password,
                                "displayName" to "User $email",
                            ),
                        )
                }.andExpect { status { isOk() } }
            val captor = argumentCaptor<String>()
            verify(emailSender).sendEmailVerification(any(), any(), captor.capture())
            val token = captor.lastValue.substringAfter("token=")
            mockMvc
                .post("/api/v1/auth/verify") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(mapOf("token" to token))
                }.andExpect { status { isOk() } }
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

        private fun MvcResult.sessionCookieValue(): String? =
            response
                .getHeader("Set-Cookie")
                ?.takeIf { it.startsWith("${SessionCookieService.COOKIE_NAME}=") }
                ?.substringAfter("=")
                ?.substringBefore(";")

        private fun cookie(value: String) = jakarta.servlet.http.Cookie(SessionCookieService.COOKIE_NAME, value)

        private fun seedBooking(
            opRoom: UUID,
            startsAtUtc: OffsetDateTime,
            endsAtUtc: OffsetDateTime,
            patientRef: String,
            status: String = "SCHEDULED",
        ): UUID {
            val id = UUID.randomUUID()
            dsl
                .insertInto(BOOKINGS)
                .set(BOOKINGS.ID, id)
                .set(BOOKINGS.OPERATING_ROOM_ID, opRoom)
                .set(BOOKINGS.SURGEON_ID, surgeonId)
                .set(BOOKINGS.CREATED_BY_ID, surgeonId)
                .set(BOOKINGS.STARTS_AT, startsAtUtc)
                .set(BOOKINGS.ENDS_AT, endsAtUtc)
                .set(BOOKINGS.OP_TYPE, "Probe")
                .set(BOOKINGS.PATIENT_REF, patientRef)
                .set(BOOKINGS.STATUS, status)
                .execute()
            return id
        }

        // ---- Tests -----------------------------------------------------------

        @Test
        fun `day returns ACTIVE rooms with their bookings, ordered by code and startsAt`() {
            // 09:00-10:00 UTC = 11:00-12:00 in Europe/Berlin (summer DST), inside the day 2026-07-01
            val morning =
                seedBooking(
                    activeOrId,
                    OffsetDateTime.of(2026, 7, 1, 9, 0, 0, 0, ZoneOffset.UTC),
                    OffsetDateTime.of(2026, 7, 1, 10, 0, 0, 0, ZoneOffset.UTC),
                    patientRef = "P-2026-001",
                )
            // Cancelled booking same day — must still appear so the SPA can grey it out.
            val afternoon =
                seedBooking(
                    activeOrId,
                    OffsetDateTime.of(2026, 7, 1, 13, 0, 0, 0, ZoneOffset.UTC),
                    OffsetDateTime.of(2026, 7, 1, 14, 0, 0, 0, ZoneOffset.UTC),
                    patientRef = "P-2026-002",
                    status = "CANCELLED",
                )
            // Different-day booking — must NOT appear.
            seedBooking(
                activeOrId,
                OffsetDateTime.of(2026, 7, 2, 9, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 7, 2, 10, 0, 0, 0, ZoneOffset.UTC),
                patientRef = "P-2026-003",
            )

            mockMvc
                .get("/api/v1/schedule?date=2026-07-01") { cookie(cookie(staffCookie)) }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.date") { value("2026-07-01") }
                    jsonPath("$.timezone") { value("Europe/Berlin") }
                    // Only ACTIVE OR; MAINTENANCE + RETIRED hidden by default.
                    jsonPath("$.operatingRooms.length()") { value(1) }
                    jsonPath("$.operatingRooms[0].id") { value(activeOrId.toString()) }
                    jsonPath("$.operatingRooms[0].bookings.length()") { value(2) }
                    jsonPath("$.operatingRooms[0].bookings[0].id") { value(morning.toString()) }
                    jsonPath("$.operatingRooms[0].bookings[1].id") { value(afternoon.toString()) }
                    jsonPath("$.operatingRooms[0].bookings[1].status") { value("CANCELLED") }
                }
        }

        @Test
        fun `OR with no bookings on the day still appears with empty array`() {
            mockMvc
                .get("/api/v1/schedule?date=2026-07-01") { cookie(cookie(staffCookie)) }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.operatingRooms.length()") { value(1) }
                    jsonPath("$.operatingRooms[0].bookings.length()") { value(0) }
                }
        }

        @Test
        fun `MAINTENANCE rooms are hidden by default and shown with includeMaintenance=true`() {
            mockMvc
                .get("/api/v1/schedule?date=2026-07-01") { cookie(cookie(staffCookie)) }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.operatingRooms.length()") { value(1) }
                }

            mockMvc
                .get("/api/v1/schedule?date=2026-07-01&includeMaintenance=true") { cookie(cookie(staffCookie)) }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.operatingRooms.length()") { value(2) }
                }
        }

        @Test
        fun `RETIRED rooms never appear, even with includeMaintenance=true`() {
            val rooms =
                mockMvc
                    .get("/api/v1/schedule?date=2026-07-01&includeMaintenance=true") {
                        cookie(cookie(staffCookie))
                    }.andReturn()
                    .response.contentAsString
                    .let(mapper::readTree)
                    .get("operatingRooms")
            // Neither room id is the retired one.
            val ids = rooms.map { it.get("id").asText() }
            assert(retiredOrId.toString() !in ids) { "RETIRED OR should not appear in schedule" }
        }

        @Test
        fun `omitting date returns today in clinic timezone (just sanity-checks the wire shape)`() {
            mockMvc
                .get("/api/v1/schedule") { cookie(cookie(staffCookie)) }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.date") { exists() }
                    jsonPath("$.timezone") { value("Europe/Berlin") }
                }
        }

        @Test
        fun `unauthenticated request returns 401`() {
            mockMvc
                .get("/api/v1/schedule?date=2026-07-01")
                .andExpect { status { isUnauthorized() } }
        }
    }
