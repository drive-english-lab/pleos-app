package io.github.driveenglishlab.pleos.platform

import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Supply one process instance to all segment main/followup/help/demo consumers. Actual
 * product/vehicle/input permission is checked separately before grant and every SDK request.
 */
class SegmentOutputLease(private val authorized: suspend (OwnerContext, SegmentPurpose) -> Boolean) : SegmentOutputLeasePort {
    private data class Lease(val id: UUID, val owner: OwnerContext, val purpose: SegmentPurpose)
    private val lane = Mutex()
    private var current: Lease? = null
    private val cleanupHolds=mutableMapOf<UUID,CompletionContinuation1>()
    override suspend fun acquire(owner: OwnerContext, purpose: SegmentPurpose): UUID? = lane.withLock {
        if (cleanupHolds.isNotEmpty() || current != null || !authorized(owner, purpose)) return@withLock null
        UUID.randomUUID().also { current = Lease(it, owner, purpose) }
    }
    override suspend fun owns(id: UUID, owner: OwnerContext, purpose: SegmentPurpose): Boolean = lane.withLock {
        current == Lease(id, owner, purpose) && authorized(owner, purpose)
    }
    override suspend fun retains(id:UUID,owner:OwnerContext,purpose:SegmentPurpose):Boolean=lane.withLock{current==Lease(id,owner,purpose)}
    override suspend fun adopt(previous: UUID, authoritative: UUID, owner: OwnerContext, purpose: SegmentPurpose): Boolean = lane.withLock {
        if (current != Lease(previous, owner, purpose) || !authorized(owner, purpose)) return@withLock false
        current = Lease(authoritative, owner, purpose)
        true
    }
    override suspend fun retainCleanupHold(value:CompletionContinuation1) = lane.withLock {
        if(value.state!=OutputContinuationState.READY)cleanupHolds[value.completedEventId]=value
    }
    override suspend fun clearCleanupHold(value:CompletionContinuation1) = lane.withLock {
        cleanupHolds[value.completedEventId]?.takeIf{it.completedRun==value.completedRun}?.let{cleanupHolds.remove(value.completedEventId)}
        Unit
    }
    override suspend fun release(id: UUID) = lane.withLock { if (current?.id == id) current = null }
}
