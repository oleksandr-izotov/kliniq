package com.kliniq.usecase.admin

import com.kliniq.db.tables.references.USERS
import com.kliniq.domain.user.Role
import com.kliniq.domain.user.UserStatus
import com.kliniq.support.TestcontainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * Direct use-case probes for the LastAdmin invariant. The controller's
 * `hasRole("ADMIN")` gate makes this branch hard to reach end-to-end —
 * any actor that can call the endpoint is themselves an active admin,
 * and SelfLockout fires before LastAdmin in the only single-admin
 * scenario. The guard is defence-in-depth at the use-case layer; this
 * test exercises it by constructing the state directly via DSL and
 * invoking the use case.
 */
@SpringBootTest
@Import(TestcontainersConfig::class)
@ActiveProfiles("test")
class UpdateUserUseCaseLastAdminTest
    @Autowired
    constructor(
        private val updateUser: UpdateUserUseCase,
        private val dsl: DSLContext,
    ) {
        @BeforeEach
        fun reset() {
            dsl.deleteFrom(USERS).execute()
        }

        private fun seedAdmin(
            email: String,
            status: UserStatus = UserStatus.ACTIVE,
        ): UUID {
            val id = UUID.randomUUID()
            dsl
                .insertInto(USERS)
                .set(USERS.ID, id)
                .set(USERS.EMAIL, email)
                .set(USERS.DISPLAY_NAME, "User $email")
                .set(USERS.ROLE, Role.ADMIN.name)
                .set(USERS.IS_SURGEON, false)
                .set(USERS.STATUS, status.name)
                .execute()
            return id
        }

        @Test
        fun `demoting the last active admin returns LastAdmin`() {
            // Two admins, but the actor is a phantom (not itself an admin —
            // bypassing the controller gate) so SelfLockout doesn't fire
            // for a self-demotion. The actor demotes the only remaining
            // active admin: nothing left, LastAdmin trips.
            val phantomActor = UUID.randomUUID()
            val onlyAdmin = seedAdmin("only@kliniq.local")

            val result =
                updateUser.update(
                    actorUserId = phantomActor,
                    targetId = onlyAdmin,
                    patch = UpdateUserUseCase.Patch(role = Role.MANAGER),
                )

            assertThat(result).isEqualTo(UpdateUserUseCase.Result.LastAdmin)
        }

        @Test
        fun `disabling the last active admin returns LastAdmin`() {
            val phantomActor = UUID.randomUUID()
            val onlyAdmin = seedAdmin("only@kliniq.local")

            val result =
                updateUser.update(
                    actorUserId = phantomActor,
                    targetId = onlyAdmin,
                    patch = UpdateUserUseCase.Patch(status = UserStatus.DISABLED),
                )

            assertThat(result).isEqualTo(UpdateUserUseCase.Result.LastAdmin)
        }

        @Test
        fun `demoting one of two active admins succeeds`() {
            val phantomActor = UUID.randomUUID()
            val first = seedAdmin("first@kliniq.local")
            seedAdmin("second@kliniq.local")

            val result =
                updateUser.update(
                    actorUserId = phantomActor,
                    targetId = first,
                    patch = UpdateUserUseCase.Patch(role = Role.MANAGER),
                )

            assertThat(result).isInstanceOf(UpdateUserUseCase.Result.Success::class.java)
        }

        @Test
        fun `disabled admins do not count toward the active-admin total`() {
            val phantomActor = UUID.randomUUID()
            seedAdmin("disabled@kliniq.local", status = UserStatus.DISABLED)
            val activeOnly = seedAdmin("active@kliniq.local")

            // Demoting the only ACTIVE admin still trips LastAdmin even
            // though a disabled admin row exists — disabled doesn't count.
            val result =
                updateUser.update(
                    actorUserId = phantomActor,
                    targetId = activeOnly,
                    patch = UpdateUserUseCase.Patch(role = Role.MANAGER),
                )

            assertThat(result).isEqualTo(UpdateUserUseCase.Result.LastAdmin)
        }
    }
