package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.domain.LearningCallScope
import io.github.driveenglishlab.pleos.domain.LearningReply
import javax.inject.Inject
import android.content.Context

/** Foreground/reconnect orchestration. It never selects resume or grants playback/input. */
class ProfileSyncWorker @Inject constructor(private val lifecycle: FeatureLifecycle) {
    suspend fun <T> runOnce(scope: LearningCallScope, submitOriginal: suspend () -> LearningReply<T>): LearningReply<T> {
        if (!lifecycle.isCurrent(scope.owner)) return LearningReply.Failed("SESSION_CHANGED", false)
        val result = submitOriginal()
        if (!lifecycle.isCurrent(scope.owner)) return LearningReply.Failed("SESSION_CHANGED", false)
        if (result is LearningReply.Stored && result.scope != scope) return LearningReply.Failed("SESSION_CHANGED", false)
        return result
    }

    suspend fun reconcile(context: Context, database: LearningDatabase, profiles: ProfileRepository, api: LearningApi,
        accounts: AccountRepository, flushFeatures: suspend (LearningCallScope) -> LearningReply<Unit> = { LearningReply.Stored(it, Unit) }): LearningReply<ProfileReadSnapshot> {
        accounts.transitions.recoverAndRetry()
        if (accounts.hasPendingDeletion()) {
            val deleted = accounts.retryDeletion()
            if (deleted is LearningReply.Failed) return deleted
        }
        val recovery = accounts.recoverMerge()
        if (recovery is LearningReply.Failed) return recovery
        val profile = profiles.refresh()
        if (profile is LearningReply.Failed) return profile
        // refresh confirms the latest server epoch and erases old local learning first.
        val scope = profiles.current() ?: return LearningReply.Failed("SESSION_UNAVAILABLE", true)
        return runOnce(scope) {
            val presentations = PresentationRepository(api, database) { lifecycle.isCurrent(it) }
            val deliveries = presentations.flush(scope)
            if (deliveries is LearningReply.Failed) return@runOnce deliveries
            val sync = SessionJournal(database.learning(), api, LearningContractJson(context)).flushAll(scope)
            if (sync is LearningReply.Failed) return@runOnce sync
            val features = flushFeatures(scope)
            if (features is LearningReply.Failed) return@runOnce features
            ResumeRepository(api, database).snapshot(scope)
        }
    }
    /** The same recovery/deletion priority with a source6 snapshot boundary. Callers
     * supply only original-output/feature journal flushes, never output or new work. */
    suspend fun reconcileIncremental(context: Context, database: LearningDatabase, profiles: ProfileRepository,
        api: LearningApi, accounts: AccountRepository, reconciler: IncrementalSyncReconciler,
        flushActualOutputs: suspend (LearningCallScope) -> LearningReply<Unit>,
        flushFeatures: suspend (LearningCallScope) -> LearningReply<Unit>): LearningReply<Source6ProfileSnapshot> {
        accounts.transitions.recoverAndRetry()
        if (accounts.hasPendingDeletion()) {
            val deleted = accounts.retryDeletion()
            if (deleted is LearningReply.Failed) return deleted
        }
        val recovery = accounts.recoverMerge()
        if (recovery is LearningReply.Failed) return recovery
        val refreshed = profiles.refresh()
        if (refreshed is LearningReply.Failed) return refreshed
        val scope = profiles.current() ?: return LearningReply.Failed("SESSION_UNAVAILABLE", true)
        return runOnce(scope) {
            reconciler.reconcile(scope,
                flushActualOutputs = { current ->
                    val deliveries = PresentationRepository(api, database) { lifecycle.isCurrent(it) }.flush(current)
                    if (deliveries is LearningReply.Failed) deliveries else flushActualOutputs(current)
                },
                flushLegacySync = { current -> SessionJournal(database.learning(), api, LearningContractJson(context)).flushAll(current) },
                flushFeatures = flushFeatures)
        }
    }

}
