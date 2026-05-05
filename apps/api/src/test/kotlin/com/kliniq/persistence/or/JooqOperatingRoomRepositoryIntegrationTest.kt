package com.kliniq.persistence.or

import com.kliniq.db.tables.references.OPERATING_ROOMS
import com.kliniq.domain.or.NewOperatingRoom
import com.kliniq.domain.or.OperatingRoomPatch
import com.kliniq.domain.or.OperatingRoomStatus
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
class JooqOperatingRoomRepositoryIntegrationTest
    @Autowired
    constructor(
        private val repository: OperatingRoomRepository,
        private val dsl: DSLContext,
    ) {
        @BeforeEach
        fun cleanRows() {
            // Bookings FK to operating_rooms with ON DELETE RESTRICT — drop
            // them first if a prior test class left any.
            dsl.deleteFrom(com.kliniq.db.tables.references.BOOKINGS).execute()
            dsl.deleteFrom(OPERATING_ROOMS).execute()
        }

        private fun draft(
            id: UUID = UUID.randomUUID(),
            code: String = "OR-1",
            name: String = "Operating Room 1",
            notes: String? = null,
        ) = NewOperatingRoom(id = id, code = code, name = name, notes = notes)

        @Test
        fun `create persists a row with DB defaults`() {
            val id = UUID.randomUUID()
            val created = repository.create(draft(id = id))

            assertThat(created.id).isEqualTo(id)
            assertThat(created.code).isEqualTo("OR-1")
            assertThat(created.status).isEqualTo(OperatingRoomStatus.ACTIVE)
            assertThat(created.notes).isNull()
            assertThat(created.createdAt).isNotNull()
        }

        @Test
        fun `create normalises blank notes to null`() {
            val created = repository.create(draft(notes = "   "))
            assertThat(created.notes).isNull()
        }

        @Test
        fun `unique code is enforced — second create throws DuplicateKeyException`() {
            repository.create(draft(code = "OR-DUP"))

            assertThatThrownBy {
                repository.create(draft(code = "OR-DUP", name = "Other"))
            }.isInstanceOf(DuplicateKeyException::class.java)
        }

        @Test
        fun `findById and findByCode round-trip the row`() {
            val created = repository.create(draft(code = "OR-FIND"))
            assertThat(repository.findById(created.id)?.code).isEqualTo("OR-FIND")
            assertThat(repository.findByCode("OR-FIND")?.id).isEqualTo(created.id)
            assertThat(repository.findById(UUID.randomUUID())).isNull()
            assertThat(repository.findByCode("never")).isNull()
        }

        @Test
        fun `listAll excludes RETIRED by default and orders by code`() {
            repository.create(draft(code = "OR-B", name = "Beta"))
            repository.create(draft(code = "OR-A", name = "Alpha"))
            val retired = repository.create(draft(code = "OR-Z", name = "Zombie"))
            repository.archive(retired.id)

            val active = repository.listAll(includeRetired = false)
            assertThat(active).extracting<String> { it.code }.containsExactly("OR-A", "OR-B")

            val all = repository.listAll(includeRetired = true)
            assertThat(all).extracting<String> { it.code }.containsExactly("OR-A", "OR-B", "OR-Z")
        }

        @Test
        fun `update with no fields returns the row unchanged`() {
            val created = repository.create(draft())
            val updated = repository.update(created.id, OperatingRoomPatch())
            assertThat(updated).isEqualTo(created)
        }

        @Test
        fun `update applies only the supplied fields`() {
            val created = repository.create(draft(name = "Old", notes = "old notes"))

            val patched =
                repository.update(
                    created.id,
                    OperatingRoomPatch(name = "New", status = OperatingRoomStatus.MAINTENANCE),
                )

            assertThat(patched).isNotNull
            assertThat(patched!!.name).isEqualTo("New")
            assertThat(patched.notes).isEqualTo("old notes") // untouched
            assertThat(patched.status).isEqualTo(OperatingRoomStatus.MAINTENANCE)
        }

        @Test
        fun `update with empty notes string clears the column to null`() {
            val created = repository.create(draft(notes = "to be cleared"))
            val patched = repository.update(created.id, OperatingRoomPatch(notes = ""))
            assertThat(patched?.notes).isNull()
        }

        @Test
        fun `update on missing id returns null`() {
            assertThat(repository.update(UUID.randomUUID(), OperatingRoomPatch(name = "x"))).isNull()
        }

        @Test
        fun `archive sets status to RETIRED once and is then a no-op`() {
            val created = repository.create(draft())
            assertThat(repository.archive(created.id)).isTrue()
            assertThat(repository.findById(created.id)?.status).isEqualTo(OperatingRoomStatus.RETIRED)

            // Second archive on an already-RETIRED row reports false.
            assertThat(repository.archive(created.id)).isFalse()
        }
    }
