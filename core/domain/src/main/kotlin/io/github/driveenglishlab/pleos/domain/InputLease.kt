package io.github.driveenglishlab.pleos.domain

import java.util.UUID

data class CaptureLease(
    val captureId: UUID,
    val attemptId: UUID,
    val owner: OwnerContext,
    val task: PracticeRef,
    val lessonId: UUID,
    val safetyGeneration: Long,
    val windowGeneration: Long,
)

data class AcceptedAnswer(val lease: CaptureLease, val text: String)
enum class InputPhase { IDLE, CAPTURING, FINALIZING, ACCEPTED }

/** Confined to the coordinator's serialized event lane, never owned by Compose. */
class InputLease {
    private var current: CaptureLease? = null
    private var finalizing = false
    val phase: InputPhase get() = if (current == null) InputPhase.IDLE else if (finalizing) InputPhase.FINALIZING else InputPhase.CAPTURING

    fun start(owner: OwnerContext, task: PracticeRef, lessonId: UUID, access: AccessState): CaptureLease? {
        if (!access.canCapture || current != null) return null
        return CaptureLease(UUID.randomUUID(), UUID.randomUUID(), owner, task, lessonId,
                            access.safety.generation, access.window.generation).also {
            current = it; finalizing = false
        }
    }

    fun finish(captureId: UUID): Boolean {
        if (current?.captureId != captureId || finalizing) return false
        finalizing = true
        return true
    }

    fun invalidate() { current = null; finalizing = false }

    fun recheck(access: AccessState, owner: OwnerContext?, task: PracticeRef?) {
        val lease = current ?: return
        if (!valid(lease, access, owner, task)) invalidate()
    }

    /** The returned submission must be durably accepted before any request is sent. */
    fun finalText(captureId: UUID, text: String, access: AccessState,
                  owner: OwnerContext?, task: PracticeRef?): AcceptedAnswer? {
        val lease = current ?: return null
        if (captureId != lease.captureId || !finalizing) return null
        if (!valid(lease, access, owner, task)) { invalidate(); return null }
        val answer = text.trim()
        if (answer.codePointCount(0, answer.length) !in 1..1200) { invalidate(); return null }
        invalidate()
        return AcceptedAnswer(lease, answer)
    }

    private fun valid(lease: CaptureLease, access: AccessState, owner: OwnerContext?, task: PracticeRef?) =
        access.canCapture && lease.owner == owner && lease.task == task &&
        lease.safetyGeneration == access.safety.generation && lease.windowGeneration == access.window.generation
}
