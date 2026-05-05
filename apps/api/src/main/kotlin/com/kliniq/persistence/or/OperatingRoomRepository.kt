package com.kliniq.persistence.or

import com.kliniq.domain.or.NewOperatingRoom
import com.kliniq.domain.or.OperatingRoom
import com.kliniq.domain.or.OperatingRoomPatch
import java.util.UUID

/**
 * Persistence-layer port for [OperatingRoom]. Implementations must:
 *   - reject duplicate codes via the DB unique constraint (let Spring's
 *     [org.springframework.dao.DuplicateKeyException] surface);
 *   - never leak [com.kliniq.db] generated types beyond this layer.
 */
interface OperatingRoomRepository {
    fun create(newOperatingRoom: NewOperatingRoom): OperatingRoom

    fun findById(id: UUID): OperatingRoom?

    fun findByCode(code: String): OperatingRoom?

    /** Default listing excludes RETIRED rooms; admin / archive views opt in. */
    fun listAll(includeRetired: Boolean = false): List<OperatingRoom>

    /**
     * Apply the patch and return the refreshed row. Returns null when no row
     * matches [id]. A no-op patch (all fields null) returns the row unchanged.
     */
    fun update(
        id: UUID,
        patch: OperatingRoomPatch,
    ): OperatingRoom?

    /**
     * Sets `status='RETIRED'`. Returns true when a row was updated. Caller
     * is responsible for the "no active bookings reference this OR" check;
     * the repository does not look at the bookings table.
     */
    fun archive(id: UUID): Boolean
}
