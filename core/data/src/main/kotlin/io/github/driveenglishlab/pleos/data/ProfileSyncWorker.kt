package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.domain.LearningCallScope
import io.github.driveenglishlab.pleos.domain.LearningReply
import javax.inject.Inject

/** One explicitly scheduled operation, not a background scheduler or automatic retry policy.
 * Callers retain the original operation and bytes. A late receipt never changes owner/epoch.
 */
class ProfileSyncWorker @Inject constructor(private val lifecycle: FeatureLifecycle) {
    suspend fun <T> runOnce(scope: LearningCallScope, submitOriginal: suspend () -> LearningReply<T>): LearningReply<T> {
        if (!lifecycle.isCurrent(scope.owner)) return LearningReply.Failed("SESSION_CHANGED", false)
        val result = submitOriginal()
        if (!lifecycle.isCurrent(scope.owner)) return LearningReply.Failed("SESSION_CHANGED", false)
        if (result is LearningReply.Stored && result.scope != scope) return LearningReply.Failed("SESSION_CHANGED", false)
        return result
    }
}
