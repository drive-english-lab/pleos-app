package io.github.driveenglishlab.pleos.data

import android.os.SystemClock
import io.github.driveenglishlab.pleos.domain.*
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Refresh hints are process-local; this object does not authorize output or claim instant revocation. */
class AudioAccessLease internal constructor(val audioAssetId: UUID, val assetHash: String, val url: URI,
    val expiresAt: Instant, private val startedElapsedMs: Long, internal val operationId: UUID) {
    fun needsRefresh(elapsedMs: Long): Boolean = elapsedMs < startedElapsedMs || elapsedMs-startedElapsedMs >= 240_000
    override fun toString() = "AudioAccessLease(url=redacted)"
}

/** A long main, explicit seek/resume or expired HTTP response can refresh only its pinned asset.
 * Callers retain the playback position/run and check their current output gate separately.
 */
class AudioAccessRepository(private val catalog: CatalogRepository, private val api: LearningApi,
    private val sessions: LearningSessionSource, private val elapsed: () -> Long = SystemClock::elapsedRealtime) {
    private data class Key(val owner: OwnerContext, val subject: UUID, val session: UUID, val installation: UUID,
        val script: UUID, val bundle: UUID, val asset: UUID, val textHash: String, val duration: Long)
    private data class Pending(val id: UUID, val started: Long)
    private sealed interface Plan {
        data class Cached(val lease: AudioAccessLease) : Plan
        data class Signing(val pending: Pending) : Plan
    }
    private val mutex = Mutex()
    private var key: Key? = null
    private var pending: Pending? = null
    private var lease: AudioAccessLease? = null

    private suspend fun current(scope: LearningCallScope): Boolean {
        val active = sessions.current() ?: return false
        return active.owner.profileId == scope.owner.profileId && active.owner.privacyEpoch == scope.owner.privacyEpoch &&
            active.owner.revision == scope.owner.revision && active.session.subjectId == scope.session.subjectId &&
            active.session.sessionId == scope.session.sessionId && active.session.installationId == scope.session.installationId
    }

    suspend fun clear() = mutex.withLock { key = null; pending = null; lease = null }

    suspend fun load(scope: LearningCallScope, script: PublishedScript, forceRefresh: Boolean = false): LearningReply<AudioAccessLease> {
        if (!current(scope)) return LearningReply.Failed("SESSION_CHANGED", false)
        val requested = Key(scope.owner, scope.session.subjectId, scope.session.sessionId, scope.session.installationId,
            script.scriptId, script.bundleId, script.audioAssetId, script.textHash, script.durationMs)
        val plan = mutex.withLock {
            if (key != requested) { key = requested; pending = null; lease = null }
            // A credential operation older than the maximum URL lifetime plus request budget
            // is a renewal, not a retry of an expired signing receipt. No learning write is rebased.
            pending?.let { if (elapsed() < it.started || elapsed()-it.started >= 308_000) pending = null }
            val cached = lease
            if (!forceRefresh && cached != null && !cached.needsRefresh(elapsed())) Plan.Cached(cached)
            else Plan.Signing(pending ?: Pending(UUID.randomUUID(), elapsed()).also { pending = it })
        }
        if (plan is Plan.Cached) return if (current(scope)) LearningReply.Stored(scope, plan.lease) else LearningReply.Failed("SESSION_CHANGED", false)
        val signing = (plan as Plan.Signing).pending
        // Signing/network stays outside the cache lock. A new owner/content can invalidate this plan.
        val result = catalog.audioAccess(api, scope, script, signing.id)
        if (!current(scope)) return LearningReply.Failed("SESSION_CHANGED", false)
        return mutex.withLock {
            if (key != requested) return@withLock LearningReply.Failed("SESSION_CHANGED", false)
            if (pending?.id != signing.id) return@withLock lease?.takeIf { it.operationId == signing.id }?.let { LearningReply.Stored(scope, it) }
                ?: LearningReply.Failed("SESSION_CHANGED", false)
            when (result) {
                is LearningReply.Failed -> {
                    if (result.code == "OPERATION_EXPIRED") pending = null
                    if (result.code in setOf("CONTENT_UNAVAILABLE", "SESSION_REVOKED", "OWNER_MISMATCH")) lease = null
                    result
                }
                is LearningReply.Stored -> {
                    val value = result.value
                    if (lease != null && lease!!.assetHash != value.assetHash) return@withLock LearningReply.Failed("INVALID_CONTRACT", false)
                    val stored = AudioAccessLease(script.audioAssetId, value.assetHash, URI(value.signedUrl),
                        OffsetDateTime.parse(value.expiresAt).toInstant(), signing.started, signing.id)
                    lease = stored; pending = null
                    LearningReply.Stored(scope, stored)
                }
            }
        }
    }
}
