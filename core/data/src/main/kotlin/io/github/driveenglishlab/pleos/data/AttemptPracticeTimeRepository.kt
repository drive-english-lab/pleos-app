package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.data.wire.*
import java.util.UUID
import kotlinx.serialization.json.*

fun interface OriginalAttemptClock { fun atAcceptance(answer: AcceptedAnswer): OriginalPracticeTime }

class AttemptPracticeTimeRepository(private val api: LearningProgressApi, private val database: LearningDatabase) {
    suspend fun flush(scope: LearningCallScope): LearningReply<Unit> {
        for (row in database.progress().pending(scope.owner.profileId.toString(), scope.owner.privacyEpoch).filter { it.kind.startsWith("practice_time:") }) {
            require(row.ownerRevision == scope.owner.revision && LearningJson.hash(row.payloadJson) == row.payloadHash)
            val id = UUID.fromString(row.kind.removePrefix("practice_time:"))
            // A real original sync ACK/read, not outbox absence, gates metadata submission.
            val acknowledged = database.learning().record(row.profileId,row.privacyEpoch,stableRecordId("server-record:$id").toString()) ?: continue
            require(acknowledged.kind == "server_record_version" && acknowledged.version >= 1)
            if (row.state == "pending" && !database.progress().transition(scope.owner,row.operationId,"pending","sending",false)) continue
            val request = featureJson.decodeFromString(WireProgressPracticeTimeRequest.serializer(),row.payloadJson)
            when(val result = api.recordAttemptPracticeTime(scope,request,id)) {
                is LearningReply.Failed -> {
                    database.progress().transition(scope.owner,row.operationId,"sending",if (result.code in setOf("VERSION_MISMATCH","RECORD_CONFLICT")) "conflict" else "pending",false)
                    return result
                }
                is LearningReply.Stored -> {
                    database.progress().saveCache(scope.owner,LocalProgressCache(row.profileId,row.privacyEpoch,"practice_time",id.toString(),result.value.timeVersion,
                        featureJson.encodeToString(WireProgressPracticeTimeReceipt.serializer(),result.value)))
                    database.progress().transition(scope.owner,row.operationId,"sending","acknowledged",false)
                }
            }
        }
        return LearningReply.Stored(scope,Unit)
    }
}

/** Invoked within the same transaction as AcceptedAnswer, before any network operation. */
internal suspend fun journalPracticeTime(database: LearningDatabase, answer: AcceptedAnswer, value: OriginalPracticeTime) {
    require(database.inTransaction())
    val id=answer.lease.attemptId; val owner=answer.lease.owner
    val evidence: WireProgressPracticeTimeEvidence = when(value) {
        is OriginalPracticeTime.Confirmed -> WireProgressPracticeTimeEvidenceVariant1(value.practicedAt.toString(),value.zone.id,value.evidenceRef)
        OriginalPracticeTime.Unknown -> WireProgressPracticeTimeEvidenceVariant2(JsonNull,JsonNull,JsonNull)
    }
    val request=WireProgressPracticeTimeRequest("5","1",stableRecordId("attempt-time:$id:0").toString(),owner.profileWire(),0,evidence)
    val bytes=featureJson.encodeToString(WireProgressPracticeTimeRequest.serializer(),request)
    database.progress().queue(owner,LocalProgressJournal(request.operationId,owner.profileId.toString(),owner.privacyEpoch,owner.revision,
        "practice_time:$id",bytes,LearningJson.hash(bytes),"pending",false))
}
