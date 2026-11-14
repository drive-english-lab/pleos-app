package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

/** Reconnect storage reconciliation only. Callback ports flush existing journals and
 * must never create a capture, preparation, selection, runtime witness or model request.
 * Physical output/entry visibility remains owned by the foreground session reducer.
 */
class IncrementalSyncReconciler(
    private val api: IncrementalLearningApi,
    private val review: ReviewRepository,
    private val practice: Source6PracticeRepository,
    private val listening: Source6ListeningRepository,
) {
    private val lane = Mutex()

    suspend fun reconcile(scope: LearningCallScope,
        flushActualOutputs: suspend (LearningCallScope) -> LearningReply<Unit>,
        flushLegacySync: suspend (LearningCallScope) -> LearningReply<Unit>,
        flushFeatures: suspend (LearningCallScope) -> LearningReply<Unit>,
    ): LearningReply<Source6ProfileSnapshot> {
        if (!lane.tryLock()) return LearningReply.Failed("SYNC_BUSY", true)
        try {
            if (!api.isCurrent(scope)) return LearningReply.Failed("SESSION_CHANGED", false)
            // Actual ticket/media deliveries first, then the original review stream,
            // immutable assistance fences and accepted answers. No evaluation POST.
            val stages: List<suspend () -> LearningReply<Unit>> = listOf(
                { flushActualOutputs(scope) },
                { review.flushOriginalObservations(scope) },
                { practice.flushAccepted(scope) },
                { review.flushOriginalCommands(scope) },
                { flushLegacySync(scope) },
                { flushFeatures(scope) },
            )
            for (stage in stages) {
                if (!api.isCurrent(scope)) return LearningReply.Failed("SESSION_CHANGED", false)
                when (val reply = stage()) {
                    is LearningReply.Failed -> return reply
                    is LearningReply.Stored -> if (reply.scope != scope || !api.isCurrent(scope)) return LearningReply.Failed("SESSION_CHANGED", false)
                }
            }
            // The original v5 snapshot cannot decode prepared refs. The source6 read
            // adapter preserves both variants, versions and opaque high-water cursors.
            return listening.snapshot(scope)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { return LearningReply.Failed(if (api.isCurrent(scope)) "SYNC_UNAVAILABLE" else "SESSION_CHANGED", true) }
        finally { lane.unlock() }
    }
}
