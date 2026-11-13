package io.github.driveenglishlab.pleos.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Profile settings survive learning-history deletion; they have no privacy epoch. */
data class ProfileSettingsScope(val profileId: UUID)

enum class DailyListeningDecision { LISTEN_ONLY, EXPLICIT_DEFAULT }

/** An unresolved clock is a first-class state, never a fabricated current day. */
sealed interface DailyListeningBoundary {
    data class Confirmed(
        val selectedLocalDate: LocalDate,
        val selectedZone: ZoneId,
        val selectedAt: Instant,
        val expiresAt: Instant,
        val clockEvidenceRef: String,
    ) : DailyListeningBoundary {
        init {
            requireReference(clockEvidenceRef)
            require(selectedAt.atZone(selectedZone).toLocalDate() == selectedLocalDate)
            require(expiresAt == selectedLocalDate.plusDays(1).atStartOfDay(selectedZone).toInstant())
            require(expiresAt > selectedAt)
        }
    }
    data class Unresolved(val selectedZone: ZoneId?) : DailyListeningBoundary
}

data class DailyListeningChoice(val choiceId: UUID, val decision: DailyListeningDecision, val boundary: DailyListeningBoundary)

enum class PracticeTimeSource { ACCEPTED_ANSWER_CLOCK, RETAINED_ORIGINAL_RECORD }
sealed interface OriginalPracticeTime {
    data class Confirmed(val practicedAt: Instant, val zone: ZoneId, val source: PracticeTimeSource, val evidenceRef: String) : OriginalPracticeTime {
        init { requireReference(evidenceRef) }
    }
    data object Unknown : OriginalPracticeTime
}

enum class SpeakingRecordVisibility { VISIBLE, HIDDEN }
enum class RecordFormStatus { CONFIRMED, UNKNOWN }
enum class RecordSourceAvailability { AVAILABLE, UNAVAILABLE, UNKNOWN }
