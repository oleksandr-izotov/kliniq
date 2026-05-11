package com.kliniq.infra.demo

import com.github.f4b6a3.uuid.UuidCreator
import com.kliniq.domain.booking.NewBooking
import com.kliniq.domain.or.NewOperatingRoom
import com.kliniq.domain.or.OperatingRoom
import com.kliniq.domain.user.NewUser
import com.kliniq.domain.user.Role
import com.kliniq.domain.user.Specialty
import com.kliniq.domain.user.User
import com.kliniq.infra.security.PasswordHasher
import com.kliniq.persistence.booking.BookingRepository
import com.kliniq.persistence.clinic.ClinicSettingsPatch
import com.kliniq.persistence.clinic.ClinicSettingsRepository
import com.kliniq.persistence.or.OperatingRoomRepository
import com.kliniq.persistence.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Demo-data bootstrapper. Activates only when `app.demo.seed=true`
 * (env var `APP_DEMO_SEED=true` on Coolify) so a real clinical deploy
 * never receives this data — the bean simply doesn't exist there.
 *
 * Idempotent by design: if any operating rooms are present, the seed
 * has already run (or a real admin started populating) and the runner
 * exits without touching the DB.
 *
 * Lives in `infra/demo` rather than under `usecase/` because it's an
 * infrastructure concern (one-shot DB initialisation), not a business
 * use case the SPA can invoke.
 */
@Component
@ConditionalOnProperty(name = ["app.demo.seed"], havingValue = "true")
class DemoDataSeeder(
    private val users: UserRepository,
    private val operatingRooms: OperatingRoomRepository,
    private val bookings: BookingRepository,
    private val clinic: ClinicSettingsRepository,
    private val passwordHasher: PasswordHasher,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun run(args: ApplicationArguments) {
        if (operatingRooms.listAll(includeRetired = true).isNotEmpty()) {
            log.info("demo-seed: operating rooms already exist; skipping (idempotent)")
            return
        }

        log.info("demo-seed: starting (app.demo.seed=true)")
        seedClinicSettings()
        val rooms = seedOperatingRooms()
        val surgeons = seedSurgeons()
        val bookingCount = seedBookings(rooms, surgeons)

        log.info(
            "demo-seed: COMPLETE — {} rooms, {} surgeons, {} bookings. " +
                "Login as drsmith / drpatel / drfischer / drkuznetsova @kliniq-demo.local " +
                "with password '{}'.",
            rooms.size,
            surgeons.size,
            bookingCount,
            DEMO_PASSWORD,
        )
    }

    private fun seedClinicSettings() {
        clinic.update(
            ClinicSettingsPatch(
                workingHoursStart = LocalTime.of(WORKING_HOURS_START, 0),
                workingHoursEnd = LocalTime.of(WORKING_HOURS_END, 0),
                timezone = CLINIC_ZONE,
            ),
        )
        // Demo clinic is already fully configured by the seed; the
        // onboarding wizard (Day 58) has nothing to ask. Stamp the column
        // so the first-admin login on demo doesn't trigger the modal.
        clinic.markOnboardedIfUnset(OffsetDateTime.now(ZoneOffset.UTC))
        log.info("demo-seed: clinic settings updated + onboarded (09:00-18:00 Europe/Berlin)")
    }

    private fun seedOperatingRooms(): List<OperatingRoom> {
        val spec =
            listOf(
                Triple("OR-1", "OR 1 — General", "General-purpose theatre."),
                Triple("OR-2", "OR 2 — Cardiology", "Equipped for cardiothoracic procedures."),
                Triple("OR-3", "OR 3 — Orthopedics", "Fluoroscopy + power tools."),
            )
        return spec
            .map { (code, name, notes) ->
                operatingRooms.create(
                    NewOperatingRoom(
                        id = UuidCreator.getTimeOrderedEpoch(),
                        code = code,
                        name = name,
                        notes = notes,
                    ),
                )
            }.also { log.info("demo-seed: ${it.size} operating rooms created") }
    }

    private fun seedSurgeons(): List<User> {
        val passwordHash = passwordHasher.hash(DEMO_PASSWORD)
        val verifiedAt = OffsetDateTime.now(ZoneOffset.UTC)
        val spec =
            listOf(
                SurgeonSpec("drsmith@kliniq-demo.local", "Dr. Anna Smith", Specialty.CARDIOLOGY),
                SurgeonSpec("drpatel@kliniq-demo.local", "Dr. Raj Patel", Specialty.ORTHOPEDICS),
                SurgeonSpec("drfischer@kliniq-demo.local", "Dr. Lukas Fischer", Specialty.NEUROSURGERY),
                SurgeonSpec("drkuznetsova@kliniq-demo.local", "Dr. Elena Kuznetsova", Specialty.GENERAL),
            )
        return spec
            .map { s ->
                val created =
                    users.create(
                        NewUser(
                            id = UuidCreator.getTimeOrderedEpoch(),
                            email = s.email,
                            passwordHash = passwordHash,
                            displayName = s.displayName,
                            role = Role.MANAGER,
                            isSurgeon = true,
                            specialty = s.specialty,
                        ),
                    )
                // NewUser doesn't carry emailVerifiedAt; stamp it explicitly so
                // the surgeon can log in immediately without a verification round-trip.
                users.markEmailVerified(created.id, verifiedAt)
                created
            }.also { log.info("demo-seed: ${it.size} surgeons created + pre-verified") }
    }

    // A flat list of bookings reads more clearly than a builder. Magic-number
    // suppression: each int is a demo-scheduling value (dayOffset, hour,
    // duration); naming each one (DAY_1 = 1, HOUR_9 = 9, …) would only rename
    // the literals, not clarify intent.
    @Suppress("LongMethod", "MagicNumber")
    private fun seedBookings(
        rooms: List<OperatingRoom>,
        surgeons: List<User>,
    ): Int {
        val today = LocalDate.now(CLINIC_ZONE)
        val plan =
            listOf(
                // First week
                BookingPlan(1, 9, 2, 0, rooms[1].id, "Coronary artery bypass graft", "P-2026-101"),
                BookingPlan(1, 11, 1, 1, rooms[2].id, "Knee arthroscopy", "P-2026-102"),
                BookingPlan(2, 9, 3, 2, rooms[0].id, "Craniotomy", "P-2026-103"),
                BookingPlan(2, 13, 2, 3, rooms[0].id, "Laparoscopic cholecystectomy", "P-2026-104"),
                BookingPlan(3, 10, 1, 1, rooms[2].id, "Hip replacement consult", "P-2026-105"),
                BookingPlan(4, 9, 2, 0, rooms[1].id, "Mitral valve repair", "P-2026-106"),
                BookingPlan(5, 11, 1, 3, rooms[0].id, "Appendectomy", "P-2026-107"),
                // Second week
                BookingPlan(8, 9, 4, 2, rooms[0].id, "Spinal fusion", "P-2026-108"),
                BookingPlan(8, 14, 1, 1, rooms[2].id, "Ankle ORIF", "P-2026-109"),
                BookingPlan(9, 10, 2, 0, rooms[1].id, "Pacemaker insertion", "P-2026-110"),
            )

        plan.forEach { p ->
            val startsAt =
                today
                    .plusDays(p.dayOffset.toLong())
                    .atTime(p.hour, 0)
                    .atZone(CLINIC_ZONE)
                    .toOffsetDateTime()
            bookings.create(
                NewBooking(
                    id = UuidCreator.getTimeOrderedEpoch(),
                    operatingRoomId = p.operatingRoomId,
                    surgeonId = surgeons[p.surgeonIdx].id,
                    // Creator is the surgeon themselves — saves needing a separate
                    // manager user for the demo. The schedule UI just shows the
                    // surgeon, so this is invisible in normal browsing.
                    createdById = surgeons[p.surgeonIdx].id,
                    startsAt = startsAt,
                    endsAt = startsAt.plusHours(p.durationHours.toLong()),
                    opType = p.opType,
                    patientRef = p.patientRef,
                ),
            )
        }
        log.info("demo-seed: ${plan.size} bookings created")
        return plan.size
    }

    private data class SurgeonSpec(
        val email: String,
        val displayName: String,
        val specialty: Specialty,
    )

    private data class BookingPlan(
        val dayOffset: Int,
        val hour: Int,
        val durationHours: Int,
        val surgeonIdx: Int,
        val operatingRoomId: java.util.UUID,
        val opType: String,
        val patientRef: String,
    )

    private companion object {
        // Public-knowledge demo password: documented in the README hero and
        // RUNBOOK, intended to be discoverable by anyone clicking through the
        // portfolio link. A real clinical deploy never enables APP_DEMO_SEED.
        const val DEMO_PASSWORD = "DemoSurgeon2026!"
        const val WORKING_HOURS_START = 9
        const val WORKING_HOURS_END = 18
        val CLINIC_ZONE: ZoneId = ZoneId.of("Europe/Berlin")
    }
}
