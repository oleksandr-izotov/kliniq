package com.kliniq.persistence.booking

import com.kliniq.db.tables.references.BOOKINGS
import com.kliniq.db.tables.references.OPERATING_ROOMS
import com.kliniq.db.tables.references.USERS
import com.kliniq.domain.booking.BookingPatch
import com.kliniq.domain.booking.BookingStatus
import com.kliniq.domain.booking.BookingTimeRange
import com.kliniq.domain.booking.NewBooking
import com.kliniq.support.TestcontainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@SpringBootTest
@Import(TestcontainersConfig::class)
@ActiveProfiles("test")
class JooqBookingRepositoryIntegrationTest
    @Autowired
    constructor(
        private val repository: BookingRepository,
        private val dsl: DSLContext,
    ) {
        // Surgeon + OR seeded once per test via raw DSL — auth/OR full stack
        // is exercised in their own test classes.
        private lateinit var surgeonId: UUID
        private lateinit var operatingRoomId: UUID

        @BeforeEach
        fun seed() {
            dsl.deleteFrom(BOOKINGS).execute()
            dsl.deleteFrom(OPERATING_ROOMS).execute()
            dsl.deleteFrom(USERS).execute()

            surgeonId = UUID.randomUUID()
            operatingRoomId = UUID.randomUUID()

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
                .insertInto(OPERATING_ROOMS)
                .set(OPERATING_ROOMS.ID, operatingRoomId)
                .set(OPERATING_ROOMS.CODE, "OR-T-${operatingRoomId.toString().take(8)}")
                .set(OPERATING_ROOMS.NAME, "Test OR")
                .execute()
        }

        private fun at(
            hour: Int,
            minute: Int = 0,
            day: Int = 1,
        ): OffsetDateTime = OffsetDateTime.of(2026, 7, day, hour, minute, 0, 0, ZoneOffset.UTC)

        @Suppress("LongParameterList") // tagged-arg test builder, not production code
        private fun draft(
            id: UUID = UUID.randomUUID(),
            opRoom: UUID = operatingRoomId,
            startsAt: OffsetDateTime = at(9),
            endsAt: OffsetDateTime = at(10),
            patientRef: String = "P-2026-${(1..999).random().toString().padStart(3, '0')}",
            opType: String = "Probe",
        ) = NewBooking(
            id = id,
            operatingRoomId = opRoom,
            surgeonId = surgeonId,
            createdById = surgeonId,
            startsAt = startsAt,
            endsAt = endsAt,
            opType = opType,
            patientRef = patientRef,
        )

        // ---- create + findById -----------------------------------------------

        @Test
        fun `create persists with status SCHEDULED and round-trips`() {
            val newBooking = draft()
            val created = repository.create(newBooking)

            assertThat(created.id).isEqualTo(newBooking.id)
            assertThat(created.status).isEqualTo(BookingStatus.SCHEDULED)
            assertThat(created.startsAt).isEqualTo(newBooking.startsAt)
            assertThat(created.endsAt).isEqualTo(newBooking.endsAt)

            val fetched = repository.findById(newBooking.id)
            assertThat(fetched).isEqualTo(created)
        }

        @Test
        fun `findById returns null on unknown id`() {
            assertThat(repository.findById(UUID.randomUUID())).isNull()
        }

        // ---- EXCLUDE constraint at the repo edge -----------------------------

        @Test
        fun `overlapping insert on the same OR is rejected by EXCLUDE constraint`() {
            repository.create(draft(startsAt = at(9), endsAt = at(10)))
            assertThatThrownBy {
                repository.create(draft(startsAt = at(9, 30), endsAt = at(10, 30)))
            }.isInstanceOf(DataIntegrityViolationException::class.java)
        }

        @Test
        fun `adjacent bookings on the same OR succeed (half-open ranges)`() {
            repository.create(draft(startsAt = at(9), endsAt = at(10)))
            repository.create(draft(startsAt = at(10), endsAt = at(11)))
            // No exception — both rows persisted.
            assertThat(repository.findActiveOverlapping(operatingRoomId, BookingTimeRange(at(9), at(11))))
                .hasSize(2)
        }

        @Test
        fun `cancelled booking stops blocking the slot`() {
            val first = repository.create(draft(startsAt = at(9), endsAt = at(10)))
            // Move it to CANCELLED so the partial-index predicate filters it out.
            assertThat(repository.transitionStatus(first.id, BookingStatus.SCHEDULED, BookingStatus.CANCELLED))
                .isNotNull
            // Now the same slot is bookable again.
            repository.create(draft(startsAt = at(9), endsAt = at(9, 45)))
        }

        // ---- findActiveOverlapping -------------------------------------------

        @Test
        fun `findActiveOverlapping returns SCHEDULED and IN_PROGRESS bookings inside the window`() {
            val a = repository.create(draft(startsAt = at(9), endsAt = at(10)))
            val b = repository.create(draft(startsAt = at(11), endsAt = at(12)))
            // Cancel b to confirm it is filtered out.
            repository.transitionStatus(b.id, BookingStatus.SCHEDULED, BookingStatus.CANCELLED)

            val overlaps =
                repository.findActiveOverlapping(operatingRoomId, BookingTimeRange(at(8), at(13)))
            assertThat(overlaps).extracting<UUID> { it.id }.containsExactly(a.id)
        }

        @Test
        fun `findActiveOverlapping with excludingId skips the row being updated`() {
            val a = repository.create(draft(startsAt = at(9), endsAt = at(10)))
            // Imagine a is being PATCHed to a slightly different time still
            // overlapping itself — the excludingId lets the use case ignore
            // that "self-conflict".
            val overlaps =
                repository.findActiveOverlapping(
                    operatingRoomId,
                    BookingTimeRange(at(9, 30), at(10, 30)),
                    excludingId = a.id,
                )
            assertThat(overlaps).isEmpty()
        }

        // ---- findByOperatingRoomBetween (schedule view) ----------------------

        @Test
        fun `findByOperatingRoomBetween returns rows by starts_at, regardless of status`() {
            val morning = repository.create(draft(startsAt = at(9), endsAt = at(10)))
            val later = repository.create(draft(startsAt = at(14), endsAt = at(15)))
            repository.transitionStatus(later.id, BookingStatus.SCHEDULED, BookingStatus.CANCELLED)
            val tomorrow = repository.create(draft(startsAt = at(9, day = 2), endsAt = at(10, day = 2)))

            val rows =
                repository.findByOperatingRoomBetween(
                    operatingRoomId,
                    at(0, day = 1),
                    at(0, day = 2),
                )

            // Both today's rows come back (ordered), tomorrow's does not.
            assertThat(rows).extracting<UUID> { it.id }.containsExactly(morning.id, later.id)
            assertThat(rows.none { it.id == tomorrow.id }).isTrue()
        }

        // ---- countActiveByOperatingRoom (used by OR archive flow) -------------

        @Test
        fun `countActiveByOperatingRoom counts only SCHEDULED + IN_PROGRESS`() {
            repository.create(draft(startsAt = at(9), endsAt = at(10)))
            val toCancel = repository.create(draft(startsAt = at(11), endsAt = at(12)))
            val toComplete = repository.create(draft(startsAt = at(13), endsAt = at(14)))
            repository.transitionStatus(toCancel.id, BookingStatus.SCHEDULED, BookingStatus.CANCELLED)
            repository.transitionStatus(toComplete.id, BookingStatus.SCHEDULED, BookingStatus.IN_PROGRESS)
            repository.transitionStatus(toComplete.id, BookingStatus.IN_PROGRESS, BookingStatus.COMPLETED)

            // SCHEDULED (1) + IN_PROGRESS (0, completed already) — 1 in total.
            assertThat(repository.countActiveByOperatingRoom(operatingRoomId)).isEqualTo(1L)
        }

        // ---- update ----------------------------------------------------------

        @Test
        fun `update applies the supplied fields and leaves others unchanged`() {
            val created = repository.create(draft(opType = "Initial", patientRef = "P-2026-001"))
            val patched =
                repository.update(
                    created.id,
                    BookingPatch(opType = "Revised"),
                )
            assertThat(patched).isNotNull
            assertThat(patched!!.opType).isEqualTo("Revised")
            assertThat(patched.patientRef).isEqualTo("P-2026-001")
            assertThat(patched.startsAt).isEqualTo(created.startsAt)
        }

        @Test
        fun `update with empty notes string clears the column`() {
            val created = repository.create(draft().copy(notes = "old"))
            // The init block on NewBooking didn't reject blank notes — the repo
            // normalises blank to null on create. Bypass via copy() to set notes
            // directly for this test.
            dsl
                .update(BOOKINGS)
                .set(BOOKINGS.NOTES, "old")
                .where(BOOKINGS.ID.eq(created.id))
                .execute()

            val patched = repository.update(created.id, BookingPatch(notes = ""))
            assertThat(patched?.notes).isNull()
        }

        @Test
        fun `update that re-creates an overlap is rejected by the EXCLUDE constraint`() {
            val first = repository.create(draft(startsAt = at(9), endsAt = at(10)))
            val second = repository.create(draft(startsAt = at(11), endsAt = at(12)))
            // Try to move `second` into `first`'s slot.
            assertThatThrownBy {
                repository.update(second.id, BookingPatch(startsAt = at(9, 30), endsAt = at(10, 30)))
            }.isInstanceOf(DataIntegrityViolationException::class.java)

            // first stays unchanged
            val refreshed = repository.findById(first.id)
            assertThat(refreshed?.startsAt).isEqualTo(at(9))
        }

        @Test
        fun `update on missing id returns null`() {
            assertThat(repository.update(UUID.randomUUID(), BookingPatch(opType = "x"))).isNull()
        }

        @Test
        fun `update with no fields returns the row unchanged`() {
            val created = repository.create(draft())
            val patched = repository.update(created.id, BookingPatch())
            assertThat(patched).isEqualTo(created)
        }

        // ---- transitionStatus ------------------------------------------------

        @Test
        fun `transitionStatus succeeds when current status matches`() {
            val created = repository.create(draft())
            val started = repository.transitionStatus(created.id, BookingStatus.SCHEDULED, BookingStatus.IN_PROGRESS)
            assertThat(started?.status).isEqualTo(BookingStatus.IN_PROGRESS)
        }

        @Test
        fun `transitionStatus returns null when current status does not match`() {
            val created = repository.create(draft())
            // Try to complete a SCHEDULED booking with the wrong "expected" — the
            // CAS misses and the row stays untouched.
            val attempt =
                repository.transitionStatus(created.id, BookingStatus.IN_PROGRESS, BookingStatus.COMPLETED)
            assertThat(attempt).isNull()
            assertThat(repository.findById(created.id)?.status).isEqualTo(BookingStatus.SCHEDULED)
        }

        @Test
        fun `transitionStatus on missing id returns null`() {
            assertThat(
                repository.transitionStatus(UUID.randomUUID(), BookingStatus.SCHEDULED, BookingStatus.CANCELLED),
            ).isNull()
        }
    }
