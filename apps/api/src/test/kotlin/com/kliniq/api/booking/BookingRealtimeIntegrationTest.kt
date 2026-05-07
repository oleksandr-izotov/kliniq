package com.kliniq.api.booking

import com.fasterxml.jackson.databind.ObjectMapper
import com.kliniq.db.tables.references.BOOKINGS
import com.kliniq.db.tables.references.OPERATING_ROOMS
import com.kliniq.db.tables.references.USERS
import com.kliniq.infra.mail.EmailSender
import com.kliniq.infra.realtime.BookingEvent
import com.kliniq.infra.realtime.BookingEventKind
import com.kliniq.infra.realtime.SseService
import com.kliniq.infra.security.SessionCookieService
import com.kliniq.support.TestcontainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.timeout
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * End-to-end wiring check: a successful booking write goes through
 * BookingEventPublisher → Redis pub/sub → RedisBookingEventSubscriber
 * → LocalEmitterRegistry. Day 29's pub/sub test covered the publisher
 * side directly; this one drives the full pipe through a real HTTP
 * action so we'd notice if a future refactor dropped the publish call
 * out of the use case.
 *
 * The registry itself is mocked because the real SseService writes to
 * SseEmitter instances that need a live HTTP response — out of scope
 * for this layer; covered by the Day 31 Playwright two-context test.
 */
@SpringBootTest
@Import(TestcontainersConfig::class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BookingRealtimeIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val mapper: ObjectMapper,
        private val dsl: DSLContext,
        private val redis: StringRedisTemplate,
    ) {
        @MockitoBean
        private lateinit var emailSender: EmailSender

        // Spy preserves the concrete type so EventsController's dependency
        // still resolves; broadcast is a no-op without connected clients.
        @MockitoSpyBean
        private lateinit var registry: SseService

        private lateinit var staffCookie: String
        private lateinit var surgeonId: UUID
        private lateinit var operatingRoomId: UUID

        @BeforeEach
        fun reset() {
            Mockito.reset(emailSender, registry)
            dsl.deleteFrom(BOOKINGS).execute()
            dsl.deleteFrom(OPERATING_ROOMS).execute()
            dsl.deleteFrom(USERS).execute()
            redis.keys("rate-limit:*")?.takeIf { it.isNotEmpty() }?.let(redis::delete)
            redis.keys("kliniq:login-backoff:*")?.takeIf { it.isNotEmpty() }?.let(redis::delete)
            redis.keys("kliniq:session:*")?.takeIf { it.isNotEmpty() }?.let(redis::delete)

            staffCookie = registerVerifyAndLogin("rt-staff@kliniq.local")

            surgeonId = UUID.randomUUID()
            dsl
                .insertInto(USERS)
                .set(USERS.ID, surgeonId)
                .set(USERS.EMAIL, "rt-surgeon@kliniq.local")
                .set(USERS.DISPLAY_NAME, "Dr Realtime")
                .set(USERS.ROLE, "STAFF")
                .set(USERS.IS_SURGEON, true)
                .set(USERS.SPECIALTY, "GENERAL")
                .execute()

            operatingRoomId = UUID.randomUUID()
            dsl
                .insertInto(OPERATING_ROOMS)
                .set(OPERATING_ROOMS.ID, operatingRoomId)
                .set(OPERATING_ROOMS.CODE, "RT-${operatingRoomId.toString().take(6)}")
                .set(OPERATING_ROOMS.NAME, "Realtime OR")
                .execute()
        }

        @Test
        fun `successful POST bookings publishes a CREATED event end-to-end`() {
            val starts = OffsetDateTime.of(2099, 6, 15, 9, 0, 0, 0, ZoneOffset.UTC)
            val ends = OffsetDateTime.of(2099, 6, 15, 10, 0, 0, 0, ZoneOffset.UTC)

            mockMvc
                .post("/api/v1/bookings") {
                    with(csrf())
                    cookie(cookie(staffCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        mapper.writeValueAsString(
                            mapOf(
                                "operatingRoomId" to operatingRoomId.toString(),
                                "surgeonId" to surgeonId.toString(),
                                "startsAt" to starts.toString(),
                                "endsAt" to ends.toString(),
                                "opType" to "Realtime smoke",
                                "patientRef" to "P-2099-001",
                            ),
                        )
                }.andExpect { status { isCreated() } }

            // Pub/sub is async — the subscriber container delivers on its
            // own thread. Allow up to 2s for the round-trip.
            val captor = argumentCaptor<BookingEvent>()
            verify(registry, timeout(EVENT_TIMEOUT_MS)).broadcast(captor.capture())

            val event = captor.firstValue
            assertThat(event.kind).isEqualTo(BookingEventKind.CREATED)
            assertThat(event.operatingRoomId).isEqualTo(operatingRoomId)
        }

        @Test
        fun `cancel transition publishes a CANCELLED event end-to-end`() {
            // Seed a booking through the API so the create path runs once
            // (and emits its own event), then cancel it and assert the
            // second event flows through.
            val starts = OffsetDateTime.of(2099, 7, 1, 9, 0, 0, 0, ZoneOffset.UTC)
            val ends = OffsetDateTime.of(2099, 7, 1, 10, 0, 0, 0, ZoneOffset.UTC)
            val created =
                mockMvc
                    .post("/api/v1/bookings") {
                        with(csrf())
                        cookie(cookie(staffCookie))
                        contentType = MediaType.APPLICATION_JSON
                        content =
                            mapper.writeValueAsString(
                                mapOf(
                                    "operatingRoomId" to operatingRoomId.toString(),
                                    "surgeonId" to surgeonId.toString(),
                                    "startsAt" to starts.toString(),
                                    "endsAt" to ends.toString(),
                                    "opType" to "Will be cancelled",
                                    "patientRef" to "P-2099-002",
                                ),
                            )
                    }.andExpect { status { isCreated() } }
                    .andReturn()
            val bookingId = UUID.fromString(mapper.readTree(created.response.contentAsString)["id"].asText())

            // Wait for the CREATED event to flush so the next verify
            // doesn't pick it up by accident.
            verify(registry, timeout(EVENT_TIMEOUT_MS)).broadcast(any())
            Mockito.reset(registry)

            mockMvc
                .post("/api/v1/bookings/$bookingId/cancel") {
                    with(csrf())
                    cookie(cookie(staffCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = "{}"
                }.andExpect { status { isOk() } }

            val captor = argumentCaptor<BookingEvent>()
            verify(registry, timeout(EVENT_TIMEOUT_MS)).broadcast(captor.capture())
            assertThat(captor.firstValue.kind).isEqualTo(BookingEventKind.CANCELLED)
            assertThat(captor.firstValue.bookingId).isEqualTo(bookingId)
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

        companion object {
            private const val EVENT_TIMEOUT_MS = 2_000L
        }
    }
