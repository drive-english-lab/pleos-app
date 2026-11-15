package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Product adapter for the media service. Gate and EOS are supplied by the single coordinator;
 * neither signed-URL access nor a persisted checkpoint grants audio output.
 */
class NewsPlaybackRepository(private val sessions: LearningSessionSource, private val listening: ListeningRepository,
    private val catalog: CatalogRepository, private val audio: AudioAccessRepository, private val journal: MediaPlaybackJournal,
    private val outputAllowed: suspend (NewsPlaybackKey) -> Boolean,
    private val onEnded: suspend (NewsPlaybackKey) -> Unit) : NewsPlaybackPort {
    private data class Binding(val scope: LearningCallScope, val checkpoint: WirePersonalReadCheckpoint,
        val key: NewsPlaybackKey, val script: PublishedScript, val title: String)
    private val mutex = Mutex()
    private var pending: CompletableDeferred<NewsPlaybackResource?>? = null
    private var pendingRun: Triple<OwnerContext, String, UUID>? = null
    private val bindings = linkedMapOf<NewsPlaybackKey, Binding>()

    override suspend fun current(): NewsPlaybackResource? {
        val (deferred, create) = mutex.withLock {
            pending?.let { it to false } ?: (CompletableDeferred<NewsPlaybackResource?>().also { pending = it } to true)
        }
        if (!create) return deferred.await()
        try {
            val result = prepare()
            deferred.complete(result)
            return result
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { return null }
        finally {
            deferred.complete(null)
            mutex.withLock { if (pending === deferred) pending = null }
        }
    }

    private suspend fun prepare(): NewsPlaybackResource? {
        val scope = sessions.current() ?: return null
        var frame = listening.snapshot()?.takeIf { it.owner == scope.owner } ?: return null
        val initial = frame.checkpoint ?: return null
        if (initial.payload.phase !in setOf("main", "followup")) return null
        val existing = mutex.withLock { bindings.values.lastOrNull { it.scope.owner == scope.owner &&
            it.scope.session.sessionId == scope.session.sessionId && it.scope.session.installationId == scope.session.installationId &&
            it.checkpoint.recordId == initial.recordId && it.key.phase == initial.payload.phase &&
            it.key.assetId.toString() == initial.payload.currentAssetId && it.key.mainRunId?.toString() == initial.payload.activeRunId } }
        if (existing != null) {
            val local = journal.position(scope.owner, initial.recordId)?.takeIf { it.contentRef == initial.payload.contentRef &&
                it.selectionId == initial.payload.selectionId && it.phase == initial.payload.phase && it.currentAssetId == initial.payload.currentAssetId }
            return access(existing, local?.positionMs ?: initial.payload.positionMs)
        }
        if (initial.payload.phase == "main") {
            val operation = mutex.withLock {
                pendingRun?.takeIf { it.first == scope.owner && it.second == initial.recordId }?.third
                    ?: UUID.randomUUID().also { pendingRun = Triple(scope.owner, initial.recordId, it) }
            }
            val started = listening.startRun(scope, operation)
            if (started !is ListeningResult.Ready) return null
            mutex.withLock { if (pendingRun?.third == operation) pendingRun = null }
            frame = started.frame
        }
        val checkpoint = frame.checkpoint ?: return null
        val point = checkpoint.payload
        val ref = point.contentRef.toDomainContentRef()
        val script = (catalog.readCheckpointScript(frame) as? CatalogRead.Stored)?.value ?: return null
        val title = (catalog.readTitle(ref) as? CatalogRead.Stored)?.value ?: return null
        val key = NewsPlaybackKey(scope.owner, UUID.fromString(point.selectionId), UUID.fromString(point.checkpointId), ref,
            script.scriptId, script.audioAssetId, script.durationMs, point.activeRunId?.let(UUID::fromString), point.phase)
        val binding = Binding(scope, checkpoint, key, script, title)
        if (!matchesLive(binding) || !outputAllowed(key)) return null
        mutex.withLock {
            bindings[key] = binding
            while (bindings.size > 8) bindings.remove(bindings.keys.first())
        }
        val local = journal.position(scope.owner, checkpoint.recordId)?.takeIf { it.contentRef == point.contentRef &&
            it.phase == point.phase && it.currentAssetId == point.currentAssetId && it.selectionId == point.selectionId }
        return access(binding, local?.positionMs ?: point.positionMs)
    }

    override suspend fun renew(key: NewsPlaybackKey, positionMs: Long): NewsPlaybackResource? {
        val binding = mutex.withLock { bindings[key] } ?: return null
        return access(binding, positionMs)
    }

    override suspend fun streamAccess(key: NewsPlaybackKey): java.net.URI? {
        val binding = mutex.withLock { bindings[key] } ?: return null
        return access(binding, 0, forceRefresh = false)?.uri
    }

    private suspend fun access(binding: Binding, position: Long, forceRefresh: Boolean = true): NewsPlaybackResource? {
        if (!mayPlay(binding.key)) return null
        val lease = audio.load(binding.scope, binding.script, forceRefresh = forceRefresh)
        if (lease !is LearningReply.Stored || !mayPlay(binding.key)) return null
        return NewsPlaybackResource(binding.key, binding.title, lease.value.url, position.coerceIn(0, binding.key.durationMs))
    }

    private suspend fun sameSession(binding: Binding): Boolean {
        val now = sessions.current() ?: return false
        return now.owner == binding.scope.owner && now.session.subjectId == binding.scope.session.subjectId &&
            now.session.sessionId == binding.scope.session.sessionId && now.session.installationId == binding.scope.session.installationId
    }

    private suspend fun matchesLive(binding: Binding): Boolean {
        if (!sameSession(binding)) return false
        val frame = listening.snapshot() ?: return false
        val point = frame.checkpoint?.payload ?: return false
        return frame.owner == binding.key.owner && point.checkpointId == binding.key.checkpointId.toString() &&
            point.selectionId == binding.key.selectionId.toString() && point.contentRef == binding.checkpoint.payload.contentRef &&
            point.phase == binding.key.phase && point.currentAssetId == binding.key.assetId.toString() &&
            point.activeRunId?.let(UUID::fromString) == binding.key.mainRunId
    }

    override suspend fun mayPlay(key: NewsPlaybackKey): Boolean {
        val binding = mutex.withLock { bindings[key] } ?: return false
        return matchesLive(binding) && outputAllowed(key)
    }

    override suspend fun persist(position: NewsPlaybackPosition, interval: PlayedInterval?) {
        val binding = mutex.withLock { bindings[position.key] } ?: error("Media binding is no longer available.")
        check(sameSession(binding)) { "Media owner or session changed." }
        val original = binding.checkpoint.payload
        val point = WirePersonalCheckpointInput(original.checkpointId, original.contentRef, position.key.phase,
            position.key.assetId.toString(), position.positionMs, position.key.durationMs, position.playIntent,
            when (position.pauseReason) { "none", "user" -> position.pauseReason; else -> "system" },
            original.streamId, original.selectionId)
        journal.persist(position.key.owner, binding.checkpoint.version, point,
            position.key.mainRunId, if (position.key.phase == "main") interval else null)
    }

    override suspend fun ended(key: NewsPlaybackKey) { if (mayPlay(key)) onEnded(key) }

}
