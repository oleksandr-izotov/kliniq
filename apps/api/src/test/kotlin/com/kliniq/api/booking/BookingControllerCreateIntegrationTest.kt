package com.kliniq.api.booking

import com.fasterxml.jackson.databind.ObjectMapper
import com.kliniq.db.tables.references.BOOKINGS
import com.kliniq.db.tables.references.OPERATING_ROOMS
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
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@SpringBootTest
@Import(TestcontainersConfig::class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BookingControllerCreateIntegrationTest
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
        private lateinit var nonSurgeonUserId: UUID
        private lateinit var operatingRoomId: UUID
        private lateinit var maintenanceOrId: UUID

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

            nonSurgeonUserId = UUID.randomUUID()
            dsl
                .insertInto(USERS)
                .set(USERS.ID, nonSurgeonUserId)
                .set(USERS.EMAIL, "ns-$nonSurgeonUserId@kliniq.local")
                .set(USERS.DISPLAY_NAME, "Not A Surgeon")
                .set(USERS.ROLE, "STAFF")
                .set(USERS.IS_SURGEON, false)
                .execute()

            operatingRoomId = UUID.randomUUID()
            dsl
                .insertInto(OPERATING_ROOMS)
                .set(OPERATING_ROOMS.ID, operatingRoomId)
                .set(OPERATING_ROOMS.CODE, "OR-T-${operatingRoomId.toString().take(8)}")
                .set(OPERATING_ROOMS.NAME, "Test OR")
                .execute()

            maintenanceOrId = UUID.randomUUID()
            dsl
                .insertInto(OPERATING_ROOMS)
                .set(OPERATING_ROOMS.ID, maintenanceOrId)
                .set(OPERATING_ROOMS.CODE, "OR-M-${maintenanceOrId.toString().take(8)}")
                .set(OPERATING_ROOMS.NAME, "Maintenance OR")
                .set(OPERATING_ROOMS.STATUS, "MAINTENANCE")
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

        @Suppress("LongParameterList") // tagged-arg test builder
        private fun bookingBody(
            opRoom: UUID = operatingRoomId,
            surgeon: UUID = surgeonId,
            startsAt: OffsetDateTime = at(9),
            endsAt: OffsetDateTime = at(10),
            opType: String = "Appendectomy",
            patientRef: String = "P-2026-001",
            notes: String? = null,
        ): String =
            mapper.writeValueAsString(
                buildMap<String, Any?> {
                    put("operatingRoomId", opRoom)
                    put("surgeonId", surgeon)
                    put("startsAt", startsAt)
                    put("endsAt", endsAt)
                    put("opType", opType)
                    put("patientRef", patientRef)
                    if (notes != null) put("notes", notes)
                },
            )

        // The clinic-settings seed (V3) has timezone Europe/Berlin and working
        // hours 08:00-20:00. Our test bookings sit inside that window (UTC
        // times convert to clinic-local 11:00 / 12:00 in summer DST, well
        // inside 08:00-20:00).
        private fun at(
            hour: Int,
            minute: Int = 0,
            day: Int = 1,
        ): OffsetDateTime = OffsetDateTime.of(2026, 7, day, hour, minute, 0, 0, ZoneOffset.UTC)

        // ---- Tests -----------------------------------------------------------

        @Test
        fun `staff can create a booking — happy path`() {
            mockMvc
                .post("/api/v1/bookings") {
                    with(csrf())
                    cookie(cookie(staffCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = bookingBody()
                }.andExpect {
                    status { isCreated() }
                    jsonPath("$.id") { exists() }
                    jsonPath("$.operatingRoomId") { value(operatingRoomId.toString()) }
                    jsonPath("$.surgeonId") { value(surgeonId.toString()) }
                    jsonPath("$.status") { value("SCHEDULED") }
                    jsonPath("$.opType") { value("Appendectomy") }
                    jsonPath("$.patientRef") { value("P-2026-001") }
                }
            assertThat(dsl.fetchCount(BOOKINGS)).isEqualTo(1)
        }

        @Test
        fun `surgeon not found returns 400 SURGEON_NOT_FOUND`() {
            mockMvc
                .post("/api/v1/bookings") {
                    with(csrf())
                    cookie(cookie(staffCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = bookingBody(surgeon = UUID.randomUUID())
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("SURGEON_NOT_FOUND") }
                }
        }

        @Test
        fun `non-surgeon user returns 400 NOT_A_SURGEON`() {
            mockMvc
                .post("/api/v1/bookings") {
                    with(csrf())
                    cookie(cookie(staffCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = bookingBody(surgeon = nonSurgeonUserId)
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("NOT_A_SURGEON") }
                }
        }

        @Test
        fun `operating room not found returns 400 OR_NOT_FOUND`() {
            mockMvc
                .post("/api/v1/bookings") {
                    with(csrf())
                    cookie(cookie(staffCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = bookingBody(opRoom = UUID.randomUUID())
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("OR_NOT_FOUND") }
                }
        }

        @Test
        fun `MAINTENANCE operating room returns 400 OR_INACTIVE`() {
            mockMvc
                .post("/api/v1/bookings") {
                    with(csrf())
                    cookie(cookie(staffCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = bookingBody(opRoom = maintenanceOrId)
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("OR_INACTIVE") }
                }
        }

        @Test
        fun `booking outside working hours returns 400 OUTSIDE_WORKING_HOURS`() {
            // 03:00-04:00 UTC = 05:00-06:00 Europe/Berlin (summer DST) — before 08:00 start.
            mockMvc
                .post("/api/v1/bookings") {
                    with(csrf())
                    cookie(cookie(staffCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = bookingBody(startsAt = at(3), endsAt = at(4))
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("OUTSIDE_WORKING_HOURS") }
                }
        }

        @Test
        fun `bad patient_ref shape is rejected by Bean Validation as 400 VALIDATION_ERROR`() {
            mockMvc
                .post("/api/v1/bookings") {
                    with(csrf())
                    cookie(cookie(staffCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = bookingBody(patientRef = "not-a-patient-code")
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("VALIDATION_ERROR") }
                }
        }

        @Test
        fun `overlapping booking returns 409 BOOKING_CONFLICT with occludingBookingId`() {
            // Seed a 09:00-10:00 booking.
            val first =
                mockMvc
                    .post("/api/v1/bookings") {
                        with(csrf())
                        cookie(cookie(staffCookie))
                        contentType = MediaType.APPLICATION_JSON
                        content = bookingBody()
                    }.andExpect { status { isCreated() } }
                    .andReturn()
                    .response.contentAsString
            val firstId = mapper.readTree(first).get("id").asText()

            // Try 09:30-10:30 — must conflict.
            mockMvc
                .post("/api/v1/bookings") {
                    with(csrf())
                    cookie(cookie(staffCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        bookingBody(
                            startsAt = at(9, 30),
                            endsAt = at(10, 30),
                            patientRef = "P-2026-002",
                        )
                }.andExpect {
                    status { isConflict() }
                    jsonPath("$.code") { value("BOOKING_CONFLICT") }
                    jsonPath("$.occludingBookingId") { value(firstId) }
                }
        }

        @Test
        fun `adjacent bookings on same OR succeed (half-open)`() {
            // Two slots back-to-back: 9-10, 10-11. Both must persist.
            mockMvc
                .post("/api/v1/bookings") {
                    with(csrf())
                    cookie(cookie(staffCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = bookingBody()
                }.andExpect { status { isCreated() } }
            mockMvc
                .post("/api/v1/bookings") {
                    with(csrf())
                    cookie(cookie(staffCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        bookingBody(
                            startsAt = at(10),
                            endsAt = at(11),
                            patientRef = "P-2026-002",
                        )
                }.andExpect { status { isCreated() } }
            assertThat(dsl.fetchCount(BOOKINGS)).isEqualTo(2)
        }

        @Test
        fun `unauthenticated request returns 401`() {
            mockMvc
                .post("/api/v1/bookings") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = bookingBody()
                }.andExpect { status { isUnauthorized() } }
        }
    }
