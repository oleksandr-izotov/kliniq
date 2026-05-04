package com.kliniq.persistence.user

import com.kliniq.db.tables.references.USERS
import com.kliniq.domain.user.NewUser
import com.kliniq.domain.user.Role
import com.kliniq.domain.user.Specialty
import com.kliniq.domain.user.UserStatus
import com.kliniq.support.TestcontainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DuplicateKeyException
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

@SpringBootTest
@Import(TestcontainersConfig::class)
@ActiveProfiles("test")
class JooqUserRepositoryIntegrationTest
    @Autowired
    constructor(
        private val userRepository: UserRepository,
        private val dsl: DSLContext,
    ) {
        @BeforeEach
        fun cleanUserRows() {
            dsl.deleteFrom(USERS).execute()
        }

        @Suppress("LongParameterList") // a tagged-arg test builder, not production code
        private fun draft(
            email: String = "alice@kliniq.local",
            displayName: String = "Alice",
            role: Role = Role.STAFF,
            passwordHash: String? = "argon2id\$placeholder",
            isSurgeon: Boolean = false,
            specialty: Specialty? = null,
            id: UUID = UUID.randomUUID(),
        ) = NewUser(
            id = id,
            email = email,
            passwordHash = passwordHash,
            displayName = displayName,
            role = role,
            isSurgeon = isSurgeon,
            specialty = specialty,
        )

        @Test
        fun `create persists a user with DB defaults`() {
            val id = UUID.randomUUID()

            val user = userRepository.create(draft(id = id, email = "Alice@Kliniq.local"))

            assertThat(user.id).isEqualTo(id)
            assertThat(user.email).isEqualTo("Alice@Kliniq.local")
            assertThat(user.passwordHash).isEqualTo("argon2id\$placeholder")
            assertThat(user.displayName).isEqualTo("Alice")
            assertThat(user.role).isEqualTo(Role.STAFF)
            assertThat(user.isSurgeon).isFalse()
            assertThat(user.specialty).isNull()
            // DB defaults
            assertThat(user.status).isEqualTo(UserStatus.ACTIVE)
            assertThat(user.emailVerifiedAt).isNull()
            assertThat(user.createdAt).isNotNull()
            assertThat(user.updatedAt).isNotNull()
            // Domain helpers reflect the freshly-created state
            assertThat(user.isEmailVerified).isFalse()
            assertThat(user.isActive).isTrue()
            assertThat(user.canLogin).isFalse()
        }

        @Test
        fun `create with surgeon role and specialty`() {
            val user =
                userRepository.create(
                    draft(
                        email = "bob@kliniq.local",
                        displayName = "Dr Bob",
                        passwordHash = null,
                        isSurgeon = true,
                        specialty = Specialty.CARDIOLOGY,
                    ),
                )

            assertThat(user.isSurgeon).isTrue()
            assertThat(user.specialty).isEqualTo(Specialty.CARDIOLOGY)
        }

        @Test
        fun `findById returns the persisted user`() {
            val id = UUID.randomUUID()
            userRepository.create(draft(id = id, email = "carol@kliniq.local", role = Role.MANAGER))

            val found = userRepository.findById(id)

            assertThat(found).isNotNull
            assertThat(found?.id).isEqualTo(id)
            assertThat(found?.role).isEqualTo(Role.MANAGER)
        }

        @Test
        fun `findById returns null for unknown id`() {
            assertThat(userRepository.findById(UUID.randomUUID())).isNull()
        }

        @Test
        fun `findByEmail is case insensitive via email_normalized`() {
            userRepository.create(draft(email = "Dave@Kliniq.LOCAL"))

            assertThat(userRepository.findByEmail("dave@kliniq.local")).isNotNull
            assertThat(userRepository.findByEmail("DAVE@KLINIQ.LOCAL")).isNotNull
            assertThat(userRepository.findByEmail("Dave@Kliniq.LOCAL")).isNotNull
        }

        @Test
        fun `findByEmail returns null when no row matches`() {
            assertThat(userRepository.findByEmail("ghost@nowhere")).isNull()
        }

        @Test
        fun `existsByEmail mirrors findByEmail without loading the row`() {
            assertThat(userRepository.existsByEmail("eve@kliniq.local")).isFalse()

            userRepository.create(draft(email = "Eve@Kliniq.local", role = Role.ADMIN))

            assertThat(userRepository.existsByEmail("eve@kliniq.local")).isTrue()
        }

        @Test
        fun `unique email constraint is enforced (case-insensitive)`() {
            userRepository.create(draft(email = "Frank@Kliniq.local"))

            assertThatThrownBy {
                // Different case — generated email_normalized still collides
                userRepository.create(draft(email = "FRANK@KLINIQ.LOCAL", displayName = "Frank Two"))
            }.isInstanceOf(DuplicateKeyException::class.java)
        }

        @Test
        fun `surgeon-without-specialty is rejected by DB CHECK`() {
            assertThatThrownBy {
                // Domain init-block would also catch this, so go around it via raw SQL.
                dsl
                    .insertInto(USERS)
                    .set(USERS.ID, UUID.randomUUID())
                    .set(USERS.EMAIL, "broken@kliniq.local")
                    .set(USERS.DISPLAY_NAME, "Broken")
                    .set(USERS.ROLE, Role.STAFF.name)
                    .set(USERS.IS_SURGEON, true)
                    .set(USERS.SPECIALTY, null as String?)
                    .execute()
            }.hasMessageContaining("users_specialty_iff_surgeon")
        }
    }
