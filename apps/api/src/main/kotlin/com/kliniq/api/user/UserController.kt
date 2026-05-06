package com.kliniq.api.user

import com.kliniq.domain.user.Specialty
import com.kliniq.domain.user.User
import com.kliniq.usecase.user.ListSurgeonsUseCase
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/users")
class UserController(
    private val listSurgeons: ListSurgeonsUseCase,
) {
    /**
     * Active surgeons, ordered by display name. Open to any authenticated
     * user — anyone who can create a booking needs to pick a surgeon.
     * Email is intentionally omitted from the wire shape; the booking modal
     * doesn't need it and exposing it here would broaden the PII surface
     * for no UX win.
     */
    @GetMapping("/surgeons")
    fun listSurgeons(): List<SurgeonSummaryDto> = listSurgeons.list().map(SurgeonSummaryDto::of)
}

/**
 * Trimmed projection of [User] for the surgeon picker. Mirrors what the
 * SPA actually renders — id, label, specialty pill — and nothing else.
 */
data class SurgeonSummaryDto(
    val id: UUID,
    val displayName: String,
    val specialty: Specialty,
) {
    companion object {
        fun of(user: User): SurgeonSummaryDto =
            SurgeonSummaryDto(
                id = user.id,
                displayName = user.displayName,
                specialty =
                    requireNotNull(user.specialty) {
                        "is_surgeon row must have a specialty (DB CHECK enforces this)"
                    },
            )
    }
}
