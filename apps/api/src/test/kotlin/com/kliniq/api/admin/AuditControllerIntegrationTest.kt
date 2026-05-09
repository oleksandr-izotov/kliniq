package com.kliniq.api.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.kliniq.db.tables.references.BOOKINGS
import com.kliniq.db.tables.references.OPERATING_ROOMS
import com.kliniq.db.tables.references.USERS
import com.kliniq.db.tables.references.USER_INVITATIONS
import com.kliniq.domain.user.Role
import com.kliniq.infra.audit.AuditEntry
import com.kliniq.infra.audit.AuditWriter
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
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.util.UUID

@SpringBootTest
@Import(TestcontainersConfig::class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditControllerIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val mapper: ObjectMapper,
        private val dsl: DSLContext,
        private val redis: StringRedisTemplate,
        private val auditWriter: AuditWriter,
    ) {
        @MockitoBean
        private lateinit var emailSender: EmailSender

        @BeforeEach
        fun reset() {
            Mockito.reset(emailSender)
            dsl.deleteFrom(BOOKINGS).execute()
            dsl.deleteFrom(OPERATING_ROOMS).execute()
            dsl.deleteFrom(USER_INVITATIONS).execute()
            // V2's BEFORE DELETE trigger forbids row-level deletes on
            // audit_events. TRUNCATE bypasses row triggers (no
            // BEFORE TRUNCATE trigger is defined) and is the right
            // primitive for test cleanup — the trigger is meant to
            // protect against application-layer rewrites, not test
            // hygiene.
            dsl.execute("TRUNCATE TABLE audit_events")
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

        private fun seedAudit(
            action: String,
            entityType: String,
            actorUserId: UUID? = null,
            entityId: UUID? = null,
            after: Map<String, Any?>? = null,
        ) {
            auditWriter.record(
                AuditEntry(
                    action = action,
                    entityType = entityType,
                    actorUserId = actorUserId,
                    entityId = entityId,
                    after = after,
                ),
            )
        }

        // ---- Tests -----------------------------------------------------------

        @Test
        fun `non-admin cannot read the audit log`() {
            val staffCookie = loginAs("staff@kliniq.local", Role.STAFF)
            mockMvc
                .get("/api/v1/admin/audit") { cookie(cookie(staffCookie)) }
                .andExpect { status { isForbidden() } }
        }

        @Test
        fun `unauthenticated request returns 401`() {
            mockMvc
                .get("/api/v1/admin/audit")
                .andExpect { status { isUnauthorized() } }
        }

        @Test
        fun `admin sees the page sorted by created_at DESC with totals`() {
            val adminCookie = loginAs("admin@kliniq.local", Role.ADMIN)
            // The login itself wrote a user.login row. Add some fixtures.
            val actor = UUID.randomUUID()
            seedAudit("booking.created", "booking", actorUserId = actor)
            seedAudit("booking.updated", "booking", actorUserId = actor)
            seedAudit("operating_room.created", "operating_room", actorUserId = actor)

            val resp =
                mockMvc
                    .get("/api/v1/admin/audit") { cookie(cookie(adminCookie)) }
                    .andExpect {
                        status { isOk() }
                        // 3 seeded + at least the actor's own user.registered/login rows
                        jsonPath("$.total") { value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)) }
                    }.andReturn()
            val tree = mapper.readTree(resp.response.contentAsString)
            val items = tree["items"]
            // Newest first.
            val timestamps =
                items.map { OffsetDateTime.parse(it["createdAt"].asText()) }
            for (i in 1 until timestamps.size) {
                assertThat(timestamps[i - 1]).isAfterOrEqualTo(timestamps[i])
            }
        }

        @Test
        fun `filter by entityType narrows the result set`() {
            val adminCookie = loginAs("admin@kliniq.local", Role.ADMIN)
            val actor = UUID.randomUUID()
            seedAudit("booking.created", "booking", actorUserId = actor)
            seedAudit("booking.cancelled", "booking", actorUserId = actor)
            seedAudit("operating_room.created", "operating_room", actorUserId = actor)

            mockMvc
                .get("/api/v1/admin/audit?entityType=booking") { cookie(cookie(adminCookie)) }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.total") { value(2) }
                    jsonPath("$.items[*].entityType") {
                        value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.equalTo("booking")))
                    }
                }
        }

        @Test
        fun `filter by actorUserId narrows the result set`() {
            val adminCookie = loginAs("admin@kliniq.local", Role.ADMIN)
            val actorA = UUID.randomUUID()
            val actorB = UUID.randomUUID()
            seedAudit("op.created", "test", actorUserId = actorA)
            seedAudit("op.created", "test", actorUserId = actorA)
            seedAudit("op.created", "test", actorUserId = actorB)

            mockMvc
                .get("/api/v1/admin/audit?actorUserId=$actorA") { cookie(cookie(adminCookie)) }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.total") { value(2) }
                }
        }

        @Test
        fun `filter by date range honours both bounds`() {
            val adminCookie = loginAs("admin@kliniq.local", Role.ADMIN)
            // Drop login rows so the count is clean (TRUNCATE: same
            // reason as the @BeforeEach reset).
            dsl.execute("TRUNCATE TABLE audit_events")

            // Insert rows with explicit created_at via DSL — auditWriter
            // doesn't expose that knob.
            val now = OffsetDateTime.now()
            for ((id, ts) in listOf(
                UUID.randomUUID() to now.minusHours(3),
                UUID.randomUUID() to now.minusHours(2),
                UUID.randomUUID() to now.minusHours(1),
            )) {
                dsl
                    .insertInto(com.kliniq.db.tables.references.AUDIT_EVENTS)
                    .set(com.kliniq.db.tables.references.AUDIT_EVENTS.ID, id)
                    .set(com.kliniq.db.tables.references.AUDIT_EVENTS.ACTION, "filter.test")
                    .set(com.kliniq.db.tables.references.AUDIT_EVENTS.ENTITY_TYPE, "test")
                    .set(com.kliniq.db.tables.references.AUDIT_EVENTS.CREATED_AT, ts)
                    .execute()
            }

            // Window of [2.5h ago, 1.5h ago) — should pick up exactly the middle row.
            val from = now.minusHours(2).minusMinutes(30)
            val to = now.minusHours(1).minusMinutes(30)
            mockMvc
                .get("/api/v1/admin/audit?from=$from&to=$to") { cookie(cookie(adminCookie)) }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.total") { value(1) }
                }
        }

        @Test
        fun `pagination round-trips with the correct total`() {
            val adminCookie = loginAs("admin@kliniq.local", Role.ADMIN)
            dsl.execute("TRUNCATE TABLE audit_events")
            for (i in 1..7) seedAudit("test.action", "test", actorUserId = UUID.randomUUID())

            mockMvc
                .get("/api/v1/admin/audit?page=0&pageSize=3") { cookie(cookie(adminCookie)) }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.items.length()") { value(3) }
                    jsonPath("$.total") { value(7) }
                    jsonPath("$.pageSize") { value(3) }
                    jsonPath("$.page") { value(0) }
                }

            mockMvc
                .get("/api/v1/admin/audit?page=2&pageSize=3") { cookie(cookie(adminCookie)) }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.items.length()") { value(1) } // 7 - 2*3 = 1 left on page 2
                    jsonPath("$.total") { value(7) }
                }
        }

        @Test
        fun `JSONB columns round-trip as raw JSON, not stringified`() {
            val adminCookie = loginAs("admin@kliniq.local", Role.ADMIN)
            seedAudit(
                "test.shape",
                "test",
                actorUserId = UUID.randomUUID(),
                after = mapOf("foo" to "bar", "n" to 42),
            )

            val resp =
                mockMvc
                    .get("/api/v1/admin/audit?entityType=test") { cookie(cookie(adminCookie)) }
                    .andExpect { status { isOk() } }
                    .andReturn()
            val tree = mapper.readTree(resp.response.contentAsString)
            val after = tree["items"][0]["after"]
            // Should be a JSON object, not a string containing JSON.
            assertThat(after.isObject).isTrue()
            assertThat(after["foo"].asText()).isEqualTo("bar")
            assertThat(after["n"].asInt()).isEqualTo(42)
        }

        @Test
        fun `audit responses never expose patient_ref (PII discipline regression test)`() {
            val adminCookie = loginAs("admin@kliniq.local", Role.ADMIN)

            // Seed a real booking via API so the booking-domain audit
            // writers run end-to-end.
            val surgeonId = UUID.randomUUID()
            dsl
                .insertInto(USERS)
                .set(USERS.ID, surgeonId)
                .set(USERS.EMAIL, "doc-$surgeonId@kliniq.local")
                .set(USERS.DISPLAY_NAME, "Dr Audit")
                .set(USERS.ROLE, "STAFF")
                .set(USERS.IS_SURGEON, true)
                .set(USERS.SPECIALTY, "GENERAL")
                .execute()
            val orId = UUID.randomUUID()
            dsl
                .insertInto(OPERATING_ROOMS)
                .set(OPERATING_ROOMS.ID, orId)
                .set(OPERATING_ROOMS.CODE, "OR-AUD")
                .set(OPERATING_ROOMS.NAME, "Audit OR")
                .execute()

            // Embed an unmistakable digit canary in the patient_ref so we
            // can grep for it in the audit response. Any leak would
            // include the full P-2099-77777777 string.
            val patientRef = "P-2099-77777777"
            mockMvc
                .post("/api/v1/bookings") {
                    with(csrf())
                    cookie(cookie(adminCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        mapper.writeValueAsString(
                            mapOf(
                                "operatingRoomId" to orId.toString(),
                                "surgeonId" to surgeonId.toString(),
                                "startsAt" to "2099-09-01T09:00:00Z",
                                "endsAt" to "2099-09-01T10:00:00Z",
                                "opType" to "Audit smoke",
                                "patientRef" to patientRef,
                            ),
                        )
                }.andExpect { status { isCreated() } }

            val resp =
                mockMvc
                    .get("/api/v1/admin/audit?entityType=booking") { cookie(cookie(adminCookie)) }
                    .andExpect { status { isOk() } }
                    .andReturn()
            val raw = resp.response.contentAsString
            // Belt-and-braces: the canary string we passed in must NOT
            // appear anywhere in the audit response. If a future writer
            // leaks patient_ref into before/after/metadata, this fails.
            assertThat(raw).doesNotContain(patientRef)
            assertThat(raw).doesNotContain("patientRef")
        }
    }
