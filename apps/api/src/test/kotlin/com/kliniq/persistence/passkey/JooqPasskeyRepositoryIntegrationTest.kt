package com.kliniq.persistence.passkey

import com.kliniq.db.tables.references.PASSKEYS
import com.kliniq.db.tables.references.USERS
import com.kliniq.domain.passkey.NewPasskey
import com.kliniq.domain.user.NewUser
import com.kliniq.domain.user.Role
import com.kliniq.persistence.user.UserRepository
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
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@SpringBootTest
@Import(TestcontainersConfig::class)
@ActiveProfiles("test")
class JooqPasskeyRepositoryIntegrationTest
    @Autowired
    constructor(
        private val passkeys: PasskeyRepository,
        private val users: UserRepository,
        private val dsl: DSLContext,
    ) {
        @BeforeEach
        fun cleanRows() {
            // passkeys cascade-delete via FK ON DELETE CASCADE on users; nuke
            // both tables in dependency order anyway so cross-test fixtures
            // never bleed.
            dsl.deleteFrom(PASSKEYS).execute()
            dsl.deleteFrom(USERS).execute()
        }

        private fun makeUser(email: String = "alice@kliniq.local"): UUID {
            val id = UUID.randomUUID()
            users.create(
                NewUser(
                    id = id,
                    email = email,
                    passwordHash = "argon2id\$placeholder",
                    displayName = "Alice",
                    role = Role.STAFF,
                    isSurgeon = false,
                    specialty = null,
                ),
            )
            return id
        }

        @Suppress("LongParameterList") // tagged-arg test builder, not production code
        private fun draft(
            userId: UUID,
            credentialId: ByteArray = byteArrayOf(1, 2, 3, 4),
            publicKey: ByteArray = byteArrayOf(9, 8, 7),
            deviceName: String = "TestKey",
            aaguid: UUID? = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            signatureCounter: Long = 0,
        ) = NewPasskey(
            id = UUID.randomUUID(),
            userId = userId,
            credentialId = credentialId,
            publicKey = publicKey,
            signatureCounter = signatureCounter,
            aaguid = aaguid,
            deviceName = deviceName,
        )

        @Test
        fun `create persists and round-trips bytes correctly`() {
            val userId = makeUser()
            val newPasskey = draft(userId, credentialId = byteArrayOf(0x10, 0x20, 0x30, 0x40))

            val stored = passkeys.create(newPasskey)

            assertThat(stored.id).isEqualTo(newPasskey.id)
            assertThat(stored.userId).isEqualTo(userId)
            assertThat(stored.credentialId).isEqualTo(newPasskey.credentialId)
            assertThat(stored.publicKey).isEqualTo(newPasskey.publicKey)
            assertThat(stored.signatureCounter).isZero()
            assertThat(stored.aaguid).isEqualTo(newPasskey.aaguid)
            assertThat(stored.deviceName).isEqualTo("TestKey")
            assertThat(stored.createdAt).isNotNull()
            assertThat(stored.lastUsedAt).isNull()
        }

        @Test
        fun `findByCredentialId returns the row by raw bytes match`() {
            val userId = makeUser()
            val credId = byteArrayOf(0x11, 0x22, 0x33)
            val created = passkeys.create(draft(userId, credentialId = credId))

            val found = passkeys.findByCredentialId(credId)

            assertThat(found?.id).isEqualTo(created.id)
        }

        @Test
        fun `findByCredentialId returns null when bytes don't match`() {
            val userId = makeUser()
            passkeys.create(draft(userId, credentialId = byteArrayOf(1, 2, 3)))

            assertThat(passkeys.findByCredentialId(byteArrayOf(4, 5, 6))).isNull()
        }

        @Test
        fun `findByUserId returns rows newest first`() {
            val userId = makeUser()
            val first = passkeys.create(draft(userId, credentialId = byteArrayOf(0x01), deviceName = "first"))
            // sleep a tick so the second row's created_at is strictly later
            Thread.sleep(SLEEP_FOR_ORDERING_MS)
            val second = passkeys.create(draft(userId, credentialId = byteArrayOf(0x02), deviceName = "second"))

            val list = passkeys.findByUserId(userId)

            assertThat(list).extracting<UUID> { it.id }.containsExactly(second.id, first.id)
        }

        @Test
        fun `unique credential_id is enforced across users`() {
            val aliceId = makeUser(email = "alice@kliniq.local")
            val bobId = makeUser(email = "bob@kliniq.local")
            passkeys.create(draft(aliceId, credentialId = byteArrayOf(7, 7, 7)))

            assertThatThrownBy {
                passkeys.create(draft(bobId, credentialId = byteArrayOf(7, 7, 7)))
            }.isInstanceOf(DuplicateKeyException::class.java)
        }

        @Test
        fun `updateAfterUse bumps counter and stamps last_used_at`() {
            val userId = makeUser()
            val created = passkeys.create(draft(userId, signatureCounter = 5))
            val stamp = OffsetDateTime.now(ZoneOffset.UTC)

            val updated = passkeys.updateAfterUse(created.id, signatureCounter = 6, lastUsedAt = stamp)

            assertThat(updated).isTrue()
            val refreshed = passkeys.findByIdAndUserId(created.id, userId)
            assertThat(refreshed?.signatureCounter).isEqualTo(6)
            assertThat(refreshed?.lastUsedAt).isNotNull()
        }

        @Test
        fun `rename only mutates rows owned by the user`() {
            val aliceId = makeUser(email = "alice@kliniq.local")
            val bobId = makeUser(email = "bob@kliniq.local")
            val passkey = passkeys.create(draft(aliceId))

            // Bob trying to rename Alice's key — must be a no-op.
            val crossUser = passkeys.rename(passkey.id, bobId, "stolen")
            assertThat(crossUser).isFalse()
            assertThat(passkeys.findByIdAndUserId(passkey.id, aliceId)?.deviceName).isEqualTo("TestKey")

            val sameUser = passkeys.rename(passkey.id, aliceId, "MacBook")
            assertThat(sameUser).isTrue()
            assertThat(passkeys.findByIdAndUserId(passkey.id, aliceId)?.deviceName).isEqualTo("MacBook")
        }

        @Test
        fun `delete only removes rows owned by the user`() {
            val aliceId = makeUser(email = "alice@kliniq.local")
            val bobId = makeUser(email = "bob@kliniq.local")
            val passkey = passkeys.create(draft(aliceId))

            assertThat(passkeys.delete(passkey.id, bobId)).isFalse()
            assertThat(passkeys.findByIdAndUserId(passkey.id, aliceId)).isNotNull

            assertThat(passkeys.delete(passkey.id, aliceId)).isTrue()
            assertThat(passkeys.findByIdAndUserId(passkey.id, aliceId)).isNull()
        }

        @Test
        fun `passkeys cascade when their owner is deleted`() {
            val userId = makeUser()
            passkeys.create(draft(userId))
            assertThat(passkeys.findByUserId(userId)).hasSize(1)

            dsl.deleteFrom(USERS).where(USERS.ID.eq(userId)).execute()

            assertThat(passkeys.findByUserId(userId)).isEmpty()
        }

        companion object {
            private const val SLEEP_FOR_ORDERING_MS = 5L
        }
    }
