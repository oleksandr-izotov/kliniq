package com.kliniq.api.booking

import com.fasterxml.jackson.databind.ObjectMapper
import com.kliniq.db.tables.references.AUDIT_EVENTS
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@SpringBootTest
@Import(TestcontainersConfig::class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Suppress("LargeClass") // single class keeps cross-feature setup in one place
class BookingControllerLifecycleIntegrationTest
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
        private lateinit var operatingRoomId: UUID
        private lateinit var anotherOrId: UUID

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

            operatingRoomId = UUID.randomUUID()
            dsl
                .insertInto(OPERATING_ROOMS)
                .set(OPERATING_ROOMS.ID, operatingRoomId)
                .set(OPERATING_ROOMS.CODE, "OR-T-${operatingRoomId.toString().take(8)}")
                .set(OPERATING_ROOMS.NAME, "Test OR")
                .execute()

            anotherOrId = UUID.randomUUID()
            dsl
                .insertInto(OPERATING_ROOMS)
                .set(OPERATING_ROOMS.ID, anotherOrId)
                .set(OPERATING_ROOMS.CODE, "OR-T2-${anotherOrId.toString().take(8)}")
                .set(OPERATING_ROOMS.NAME, "Test OR 2")
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

        private fun at(
            hour: Int,
            minute: Int = 0,
            day: Int = 1,
        ): OffsetDateTime = OffsetDateTime.of(2026, 7, day, hour, minute, 0, 0, ZoneOffset.UTC)

        @Suppress("LongParameterList") // tagged-arg test builder
        private fun createBooking(
            opRoom: UUID = operatingRoomId,
            startsAt: OffsetDateTime = at(9),
            endsAt: OffsetDateTime = at(10),
            patientRef: String = "P-2026-${(100..999).random()}",
            opType: String = "Probe",
        ): String {
            val response =
                mockMvc
                    .post("/api/v1/bookings") {
                        with(csrf())
                        cookie(cookie(staffCookie))
                        contentType = MediaType.APPLICATION_JSON
                        content =
                            mapper.writeValueAsString(
                                mapOf(
                                    "operatingRoomId" to opRoom,
                                    "surgeonId" to surgeonId,
                                    "startsAt" to startsAt,
                                    "endsAt" to endsAt,
                                    "opType" to opType,
                                    "patientRef" to patientRef,
                                ),
                            )
                    }.andExpect { status { isCreated() } }
                    .andReturn()
                    .response.contentAsString
            return mapper.readTree(response).get("id").asText()
        }

        // ---- GET / and GET /{id} ---------------------------------------------

        @Test
        fun `GET list returns all bookings ordered by startsAt`() {
            val later = createBooking(startsAt = at(14), endsAt = at(15))
            val earlier = createBooking(startsAt = at(9), endsAt = at(10))

            mockMvc
                .get("/api/v1/bookings") { cookie(cookie(staffCookie)) }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.length()") { value(2) }
                    jsonPath("$[0].id") { value(earlier) }
                    jsonPath("$[1].id") { value(later) }
                }
        }

        @Test
        fun `GET list applies operatingRoomId and status filters`() {
            val onMain = createBooking()
            val onOther = createBooking(opRoom = anotherOrId, startsAt = at(11), endsAt = at(12))
            // Cancel onOther so we can also exercise the status filter.
            mockMvc
                .post("/api/v1/bookings/$onOther/cancel") {
                    with(csrf())
                    cookie(cookie(staffCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = "{}"
                }.andExpect { status { isOk() } }

            mockMvc
                .get("/api/v1/bookings?operatingRoomId=$operatingRoomId") { cookie(cookie(staffCookie)) }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.length()") { value(1) }
                    jsonPath("$[0].id") { value(onMain) }
                }

            mockMvc
                .get("/api/v1/bookings?status=CANCELLED") { cookie(cookie(staffCookie)) }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.length()") { value(1) }
                    jsonPath("$[0].id") { value(onOther) }
                }
        }

        @Test
        fun `GET by id returns booking or 404`() {
            val id = createBooking()
            mockMvc
                .get("/api/v1/bookings/$id") { cookie(cookie(staffCookie)) }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.id") { value(id) }
                }
            mockMvc
                .get("/api/v1/bookings/00000000-0000-0000-0000-000000000999") { cookie(cookie(staffCookie)) }
                .andExpect {
                    status { isNotFound() }
                    jsonPath("$.code") { value("NOT_FOUND") }
                }
        }

        // ---- PATCH ----------------------------------------------------------

        @Test
        fun `PATCH applies partial fields and writes booking_updated audit row`() {
            val id = createBooking(opType = "Initial")
            mockMvc
                .patch("/api/v1/bookings/$id") {
                    with(csrf())
                    cookie(cookie(staffCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(mapOf("opType" to "Revised", "notes" to "see chart"))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.opType") { value("Revised") }
                    jsonPath("$.notes") { value("see chart") }
                }

            val actions =
                dsl
                    .selectFrom(AUDIT_EVENTS)
                    .where(AUDIT_EVENTS.ENTITY_ID.eq(UUID.fromString(id)))
                    .fetch()
                    .map { it.action!! }
            assertThat(actions).contains("booking.created", "booking.updated")
        }

        @Test
        fun `PATCH that recreates an overlap returns 409 BOOKING_CONFLICT with occluding id`() {
            val first = createBooking(startsAt = at(9), endsAt = at(10))
            val second = createBooking(startsAt = at(11), endsAt = at(12))
            mockMvc
                .patch("/api/v1/bookings/$second") {
                    with(csrf())
                    cookie(cookie(staffCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(mapOf("startsAt" to at(9, 30), "endsAt" to at(10, 30)))
                }.andExpect {
                    status { isConflict() }
                    jsonPath("$.code") { value("BOOKING_CONFLICT") }
                    jsonPath("$.occludingBookingId") { value(first) }
                }
        }

        @Test
        fun `PATCH on CANCELLED booking is rejected with 409 BOOKING_TERMINAL`() {
            val id = createBooking()
            mockMvc
                .post("/api/v1/bookings/$id/cancel") {
                    with(csrf())
                    cookie(cookie(staffCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = "{}"
                }.andExpect { status { isOk() } }

            mockMvc
                .patch("/api/v1/bookings/$id") {
                    with(csrf())
                    cookie(cookie(staffCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(mapOf("opType" to "TooLate"))
                }.andExpect {
                    status { isConflict() }
                    jsonPath("$.code") { value("BOOKING_TERMINAL") }
                }
        }

        @Test
        fun `PATCH on missing id returns 404`() {
            mockMvc
                .patch("/api/v1/bookings/00000000-0000-0000-0000-000000000ABC") {
                    with(csrf())
                    cookie(cookie(staffCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(mapOf("opType" to "x"))
                }.andExpect { status { isNotFound() } }
        }

        // ---- Lifecycle transitions -------------------------------------------

        @Test
        fun `cancel transitions SCHEDULED to CANCELLED with audit row`() {
            val id = createBooking()
            mockMvc
                .post("/api/v1/bookings/$id/cancel") {
                    with(csrf())
                    cookie(cookie(staffCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(mapOf("reason" to "patient rescheduled"))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.status") { value("CANCELLED") }
                }
            val actions =
                dsl
                    .selectFrom(AUDIT_EVENTS)
                    .where(AUDIT_EVENTS.ENTITY_ID.eq(UUID.fromString(id)))
                    .fetch()
                    .map { it.action!! }
            assertThat(actions).contains("booking.cancelled")
        }

        @Test
        fun `start then complete transitions through IN_PROGRESS`() {
            val id = createBooking()
            mockMvc
                .post("/api/v1/bookings/$id/start") {
                    with(csrf())
                    cookie(cookie(staffCookie))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.status") { value("IN_PROGRESS") }
                }
            mockMvc
                .post("/api/v1/bookings/$id/complete") {
                    with(csrf())
                    cookie(cookie(staffCookie))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.status") { value("COMPLETED") }
                }

            val actions =
                dsl
                    .selectFrom(AUDIT_EVENTS)
                    .where(AUDIT_EVENTS.ENTITY_ID.eq(UUID.fromString(id)))
                    .fetch()
                    .map { it.action!! }
            assertThat(actions)
                .contains("booking.created", "booking.started", "booking.completed")
        }

        @Test
        fun `complete on SCHEDULED is rejected with 409 ILLEGAL_TRANSITION`() {
            val id = createBooking()
            mockMvc
                .post("/api/v1/bookings/$id/complete") {
                    with(csrf())
                    cookie(cookie(staffCookie))
                }.andExpect {
                    status { isConflict() }
                    jsonPath("$.code") { value("ILLEGAL_TRANSITION") }
                }
        }

        @Test
        fun `cancel on COMPLETED is rejected with 409 ILLEGAL_TRANSITION`() {
            val id = createBooking()
            mockMvc.post("/api/v1/bookings/$id/start") {
                with(csrf())
                cookie(cookie(staffCookie))
            }
            mockMvc.post("/api/v1/bookings/$id/complete") {
                with(csrf())
                cookie(cookie(staffCookie))
            }
            mockMvc
                .post("/api/v1/bookings/$id/cancel") {
                    with(csrf())
                    cookie(cookie(staffCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = "{}"
                }.andExpect {
                    status { isConflict() }
                    jsonPath("$.code") { value("ILLEGAL_TRANSITION") }
                }
        }

        @Test
        fun `unauthenticated cancel is rejected with 401`() {
            val id = createBooking()
            mockMvc
                .post("/api/v1/bookings/$id/cancel") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = "{}"
                }.andExpect { status { isUnauthorized() } }
        }
    }
