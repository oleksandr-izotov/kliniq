package com.kliniq.api.or

import com.fasterxml.jackson.databind.ObjectMapper
import com.kliniq.db.tables.references.OPERATING_ROOMS
import com.kliniq.db.tables.references.USERS
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
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@SpringBootTest
@Import(TestcontainersConfig::class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OperatingRoomControllerIntegrationTest
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
            // Bookings FK back to operating_rooms AND users with ON DELETE RESTRICT,
            // so they have to be dropped first when prior test classes left rows.
            dsl.deleteFrom(com.kliniq.db.tables.references.BOOKINGS).execute()
            dsl.deleteFrom(OPERATING_ROOMS).execute()
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

        /**
         * Register + verify + login a user, then upgrade their role via DSL
         * (the API doesn't expose role changes — that's an admin operation
         * outside Sprint 2's scope). Returns the session cookie.
         */
        private fun loginAs(
            email: String,
            role: Role,
            password: String = "correct-horse-battery-staple",
        ): String {
            // Reset the email-sender mock so captureVerifyToken() only sees this
            // helper's single invocation, even when loginAs is called twice in
            // one test (e.g. once for a manager, once for staff).
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

            // Promote to the desired role.
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
        fun `staff can list operating rooms but cannot create one`() {
            val staffCookie = loginAs("staff@kliniq.local", Role.STAFF)

            // Empty list works for staff
            mockMvc
                .get("/api/v1/operating-rooms") { cookie(cookie(staffCookie)) }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.length()") { value(0) }
                }

            // POST is rejected as 403
            mockMvc
                .post("/api/v1/operating-rooms") {
                    with(csrf())
                    cookie(cookie(staffCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(mapOf("code" to "OR-1", "name" to "Suite 1"))
                }.andExpect { status { isForbidden() } }
        }

        @Test
        fun `manager can create + read + update an operating room`() {
            val managerCookie = loginAs("mgr@kliniq.local", Role.MANAGER)

            // Create
            val created =
                mockMvc
                    .post("/api/v1/operating-rooms") {
                        with(csrf())
                        cookie(cookie(managerCookie))
                        contentType = MediaType.APPLICATION_JSON
                        content =
                            mapper.writeValueAsString(
                                mapOf("code" to "OR-1", "name" to "Suite 1", "notes" to "north wing"),
                            )
                    }.andExpect {
                        status { isCreated() }
                        jsonPath("$.code") { value("OR-1") }
                        jsonPath("$.status") { value("ACTIVE") }
                    }.andReturn()
            val createdJson = mapper.readTree(created.response.contentAsString)
            val orId = createdJson["id"].asText()

            // GET by id
            mockMvc
                .get("/api/v1/operating-rooms/$orId") { cookie(cookie(managerCookie)) }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.notes") { value("north wing") }
                }

            // PATCH name + status
            mockMvc
                .patch("/api/v1/operating-rooms/$orId") {
                    with(csrf())
                    cookie(cookie(managerCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(mapOf("name" to "Suite One", "status" to "MAINTENANCE"))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.name") { value("Suite One") }
                    jsonPath("$.status") { value("MAINTENANCE") }
                    jsonPath("$.notes") { value("north wing") } // untouched
                }
        }

        @Test
        fun `duplicate code returns 409 DUPLICATE_CODE`() {
            val managerCookie = loginAs("mgr@kliniq.local", Role.MANAGER)
            val body = mapper.writeValueAsString(mapOf("code" to "OR-DUP", "name" to "First"))

            mockMvc
                .post("/api/v1/operating-rooms") {
                    with(csrf())
                    cookie(cookie(managerCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }.andExpect { status { isCreated() } }

            mockMvc
                .post("/api/v1/operating-rooms") {
                    with(csrf())
                    cookie(cookie(managerCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(mapOf("code" to "OR-DUP", "name" to "Second"))
                }.andExpect {
                    status { isConflict() }
                    jsonPath("$.code") { value("DUPLICATE_CODE") }
                }
        }

        @Test
        fun `validation errors come back as 400 VALIDATION_ERROR`() {
            val managerCookie = loginAs("mgr@kliniq.local", Role.MANAGER)
            mockMvc
                .post("/api/v1/operating-rooms") {
                    with(csrf())
                    cookie(cookie(managerCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(mapOf("code" to "", "name" to "X"))
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("VALIDATION_ERROR") }
                }
        }

        @Test
        fun `unauthenticated requests are rejected with 401`() {
            mockMvc
                .get("/api/v1/operating-rooms")
                .andExpect { status { isUnauthorized() } }
        }

        @Test
        fun `listAll excludes RETIRED by default and includes them with the flag`() {
            val managerCookie = loginAs("mgr@kliniq.local", Role.MANAGER)

            // Two rooms, one retired manually.
            val keep =
                mockMvc
                    .post("/api/v1/operating-rooms") {
                        with(csrf())
                        cookie(cookie(managerCookie))
                        contentType = MediaType.APPLICATION_JSON
                        content = mapper.writeValueAsString(mapOf("code" to "OR-A", "name" to "Alpha"))
                    }.andReturn()
                    .response.contentAsString
            val keepId = mapper.readTree(keep).get("id").asText()
            mockMvc
                .post("/api/v1/operating-rooms") {
                    with(csrf())
                    cookie(cookie(managerCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(mapOf("code" to "OR-Z", "name" to "Zeta"))
                }
            // Drop the second straight in the DB so we don't lean on a yet-to-be-built endpoint.
            dsl
                .update(OPERATING_ROOMS)
                .set(OPERATING_ROOMS.STATUS, "RETIRED")
                .where(OPERATING_ROOMS.CODE.eq("OR-Z"))
                .execute()

            mockMvc
                .get("/api/v1/operating-rooms") { cookie(cookie(managerCookie)) }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.length()") { value(1) }
                    jsonPath("$[0].id") { value(keepId) }
                }

            mockMvc
                .get("/api/v1/operating-rooms?includeRetired=true") { cookie(cookie(managerCookie)) }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.length()") { value(2) }
                }
        }

        @Test
        fun `404 on unknown id`() {
            val managerCookie = loginAs("mgr@kliniq.local", Role.MANAGER)
            mockMvc
                .get("/api/v1/operating-rooms/00000000-0000-0000-0000-000000000123") {
                    cookie(cookie(managerCookie))
                }.andExpect {
                    status { isNotFound() }
                    jsonPath("$.code") { value("NOT_FOUND") }
                }
        }

        @Test
        fun `staff cannot patch even though they can read`() {
            val managerCookie = loginAs("mgr@kliniq.local", Role.MANAGER)
            val staffCookie = loginAs("staff2@kliniq.local", Role.STAFF)

            val created =
                mockMvc
                    .post("/api/v1/operating-rooms") {
                        with(csrf())
                        cookie(cookie(managerCookie))
                        contentType = MediaType.APPLICATION_JSON
                        content = mapper.writeValueAsString(mapOf("code" to "OR-PROT", "name" to "Protected"))
                    }.andReturn()
                    .response.contentAsString
            val id = mapper.readTree(created).get("id").asText()

            // Reads are open to staff.
            mockMvc
                .get("/api/v1/operating-rooms/$id") { cookie(cookie(staffCookie)) }
                .andExpect { status { isOk() } }

            // Patches are not.
            mockMvc
                .patch("/api/v1/operating-rooms/$id") {
                    with(csrf())
                    cookie(cookie(staffCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(mapOf("name" to "Hacked"))
                }.andExpect { status { isForbidden() } }
        }

        @Test
        fun `audit row written for create + update`() {
            val managerCookie = loginAs("mgr@kliniq.local", Role.MANAGER)
            val created =
                mockMvc
                    .post("/api/v1/operating-rooms") {
                        with(csrf())
                        cookie(cookie(managerCookie))
                        contentType = MediaType.APPLICATION_JSON
                        content = mapper.writeValueAsString(mapOf("code" to "OR-AUD", "name" to "AuditMe"))
                    }.andReturn()
                    .response.contentAsString
            val id = mapper.readTree(created).get("id").asText()

            mockMvc
                .patch("/api/v1/operating-rooms/$id") {
                    with(csrf())
                    cookie(cookie(managerCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(mapOf("name" to "AuditedNow"))
                }.andExpect { status { isOk() } }

            val actions =
                dsl
                    .selectFrom(com.kliniq.db.tables.references.AUDIT_EVENTS)
                    .where(
                        com.kliniq.db.tables.references.AUDIT_EVENTS.ENTITY_TYPE
                            .eq("operating_room"),
                    ).and(
                        com.kliniq.db.tables.references.AUDIT_EVENTS.ENTITY_ID
                            .eq(java.util.UUID.fromString(id)),
                    ).fetch()
                    .map { it.action!! }

            assertThat(actions).contains("operating_room.created", "operating_room.updated")
        }

        // ---- DELETE / archive ------------------------------------------------

        @Test
        fun `manager can archive an OR with no active bookings`() {
            val managerCookie = loginAs("mgr@kliniq.local", Role.MANAGER)
            val created =
                mockMvc
                    .post("/api/v1/operating-rooms") {
                        with(csrf())
                        cookie(cookie(managerCookie))
                        contentType = MediaType.APPLICATION_JSON
                        content = mapper.writeValueAsString(mapOf("code" to "OR-DROP", "name" to "Drop"))
                    }.andReturn()
                    .response.contentAsString
            val id = mapper.readTree(created).get("id").asText()

            mockMvc
                .delete("/api/v1/operating-rooms/$id") {
                    with(csrf())
                    cookie(cookie(managerCookie))
                }.andExpect { status { isNoContent() } }

            // Default list (excludes RETIRED) no longer shows it.
            mockMvc
                .get("/api/v1/operating-rooms") { cookie(cookie(managerCookie)) }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.length()") { value(0) }
                }

            // Audit row written.
            val actions =
                dsl
                    .selectFrom(com.kliniq.db.tables.references.AUDIT_EVENTS)
                    .where(
                        com.kliniq.db.tables.references.AUDIT_EVENTS.ENTITY_ID
                            .eq(UUID.fromString(id)),
                    ).fetch()
                    .map { it.action!! }
            assertThat(actions).contains("operating_room.archived")
        }

        @Test
        fun `archive on OR with active bookings is rejected with 409 OR_HAS_ACTIVE_BOOKINGS`() {
            val managerCookie = loginAs("mgr@kliniq.local", Role.MANAGER)
            val created =
                mockMvc
                    .post("/api/v1/operating-rooms") {
                        with(csrf())
                        cookie(cookie(managerCookie))
                        contentType = MediaType.APPLICATION_JSON
                        content = mapper.writeValueAsString(mapOf("code" to "OR-BUSY", "name" to "Busy"))
                    }.andReturn()
                    .response.contentAsString
            val id = mapper.readTree(created).get("id").asText()

            // Seed an active booking on the room directly via DSL — going
            // through the API would just duplicate the test surface.
            val surgeonId = UUID.randomUUID()
            dsl
                .insertInto(USERS)
                .set(USERS.ID, surgeonId)
                .set(USERS.EMAIL, "doc-$surgeonId@kliniq.local")
                .set(USERS.DISPLAY_NAME, "Dr Test")
                .set(USERS.ROLE, "STAFF")
                .set(USERS.IS_SURGEON, true)
                .set(USERS.SPECIALTY, "GENERAL")
                .execute()
            dsl
                .insertInto(com.kliniq.db.tables.references.BOOKINGS)
                .set(com.kliniq.db.tables.references.BOOKINGS.ID, UUID.randomUUID())
                .set(com.kliniq.db.tables.references.BOOKINGS.OPERATING_ROOM_ID, UUID.fromString(id))
                .set(com.kliniq.db.tables.references.BOOKINGS.SURGEON_ID, surgeonId)
                .set(com.kliniq.db.tables.references.BOOKINGS.CREATED_BY_ID, surgeonId)
                .set(
                    com.kliniq.db.tables.references.BOOKINGS.STARTS_AT,
                    OffsetDateTime.of(2026, 7, 1, 9, 0, 0, 0, ZoneOffset.UTC),
                ).set(
                    com.kliniq.db.tables.references.BOOKINGS.ENDS_AT,
                    OffsetDateTime.of(2026, 7, 1, 10, 0, 0, 0, ZoneOffset.UTC),
                ).set(com.kliniq.db.tables.references.BOOKINGS.OP_TYPE, "Probe")
                .set(com.kliniq.db.tables.references.BOOKINGS.PATIENT_REF, "P-2026-001")
                .execute()

            mockMvc
                .delete("/api/v1/operating-rooms/$id") {
                    with(csrf())
                    cookie(cookie(managerCookie))
                }.andExpect {
                    status { isConflict() }
                    jsonPath("$.code") { value("OR_HAS_ACTIVE_BOOKINGS") }
                }
        }

        @Test
        fun `archive on missing id returns 404`() {
            val managerCookie = loginAs("mgr@kliniq.local", Role.MANAGER)
            mockMvc
                .delete("/api/v1/operating-rooms/00000000-0000-0000-0000-000000000123") {
                    with(csrf())
                    cookie(cookie(managerCookie))
                }.andExpect { status { isNotFound() } }
        }

        @Test
        fun `archive on already-RETIRED OR returns 409 ALREADY_ARCHIVED`() {
            val managerCookie = loginAs("mgr@kliniq.local", Role.MANAGER)
            val created =
                mockMvc
                    .post("/api/v1/operating-rooms") {
                        with(csrf())
                        cookie(cookie(managerCookie))
                        contentType = MediaType.APPLICATION_JSON
                        content = mapper.writeValueAsString(mapOf("code" to "OR-RTD", "name" to "Retired"))
                    }.andReturn()
                    .response.contentAsString
            val id = mapper.readTree(created).get("id").asText()

            mockMvc
                .delete("/api/v1/operating-rooms/$id") {
                    with(csrf())
                    cookie(cookie(managerCookie))
                }.andExpect { status { isNoContent() } }

            mockMvc
                .delete("/api/v1/operating-rooms/$id") {
                    with(csrf())
                    cookie(cookie(managerCookie))
                }.andExpect {
                    status { isConflict() }
                    jsonPath("$.code") { value("ALREADY_ARCHIVED") }
                }
        }

        @Test
        fun `staff cannot archive`() {
            val managerCookie = loginAs("mgr@kliniq.local", Role.MANAGER)
            val staffCookie = loginAs("staff3@kliniq.local", Role.STAFF)
            val created =
                mockMvc
                    .post("/api/v1/operating-rooms") {
                        with(csrf())
                        cookie(cookie(managerCookie))
                        contentType = MediaType.APPLICATION_JSON
                        content = mapper.writeValueAsString(mapOf("code" to "OR-LOCK", "name" to "Locked"))
                    }.andReturn()
                    .response.contentAsString
            val id = mapper.readTree(created).get("id").asText()

            mockMvc
                .delete("/api/v1/operating-rooms/$id") {
                    with(csrf())
                    cookie(cookie(staffCookie))
                }.andExpect { status { isForbidden() } }
        }
    }
