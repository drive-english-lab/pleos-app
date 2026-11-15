package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/** Identity of the actual fixed-origin transport, shared by its LearningApi factory. */
internal class NextObservationTransportIdentity(val configuredOrigin:String?,val instance:UUID=UUID.randomUUID()) {
    fun configured():Boolean=runCatching {
        val uri=URI(requireNotNull(configuredOrigin))
        (uri.scheme=="https" || uri.scheme=="http" && uri.host in setOf("localhost","127.0.0.1","10.0.2.2")) &&
            uri.host!=null && uri.userInfo==null && uri.path.isEmpty() && uri.query==null && uri.fragment==null
    }.getOrDefault(false)
    override fun toString()="NextObservationTransportIdentity(origin=redacted)"
}
data class NextObservationRequest(val operationId:UUID,val reviewContractVersion:Int,val requestHash:String,
    val channel:PlaybackRequestChannel=PlaybackRequestChannel.APP) {
    init { require(reviewContractVersion in setOf(2,3) && requestHash.matches(Regex("[a-f0-9]{64}"))) }
    override fun toString()="NextObservationRequest(binding=redacted)"
}
enum class NextObservationResult { FOUND, NOT_ADMITTED }
class NextObservationEvidence internal constructor(val owner:OwnerContext,val operationId:UUID,val reviewContractVersion:Int,
    val requestHash:String,val backendRevision:String,val result:NextObservationResult,
    internal val subject:UUID,internal val session:UUID,internal val installation:UUID,internal val transport:NextObservationTransportIdentity) {
    internal fun matches(scope:LearningCallScope,request:NextObservationRequest,identity:NextObservationTransportIdentity)=
        owner==scope.owner && subject==scope.session.subjectId && session==scope.session.sessionId && installation==scope.session.installationId &&
            operationId==request.operationId && reviewContractVersion==request.reviewContractVersion && requestHash==request.requestHash && transport===identity
    override fun toString()="NextObservationEvidence(binding=redacted)"
}
sealed interface NextObservationValue {
    class Review2(val value:WireReview2ReviewNextResponse2):NextObservationValue
    class Review3(val value:WireReview3ReviewNextResponse3):NextObservationValue
}
sealed interface NextObservationReply {
    class Found(val body:JsonObject,val value:NextObservationValue,val evidence:NextObservationEvidence,val choiceCapabilities:Set<String>):NextObservationReply {
        override fun toString()="NextObservationReply.Found(payload=redacted)"
    }
    class NotAdmitted(val evidence:NextObservationEvidence):NextObservationReply
    class StoredError(val failure:LearningReply.Failed,val evidence:NextObservationEvidence):NextObservationReply
    class Unavailable(val failure:LearningReply.Failed):NextObservationReply
}

/** Every lookup uses GET; old/mixed servers cannot turn it into a normal next admission. */
class NextObservationReader internal constructor(private val api:IncrementalLearningApi,private val codecs:IncrementalContractJson,
    private val transport:LearningHttpTransport,private val identity:NextObservationTransportIdentity) {
    private val mutable=MutableStateFlow<NextObservationEvidence?>(null)
    val lastFreshEvidence=mutable.asStateFlow()
    fun invalidate(){mutable.value=null}

    suspend fun read(scope:LearningCallScope,request:NextObservationRequest):NextObservationReply {
        invalidate() // A previous successful read is never authority for this exchange.
        if(!api.isCurrent(scope))return unavailable("SESSION_CHANGED",false)
        if(!identity.configured() || transport.configuredOrigin!=identity.configuredOrigin)return unavailable("NEXT_OBSERVATION_ORIGIN_UNAVAILABLE",false)
        try {
            val bundle="next-observation"
            val template="/v2/listening/next/observations/{operation_id}"
            val operation=codecs.operation(bundle,template,"get")
            require(operation.getValue("operationId")==JsonPrimitive("observeExistingNextOperation1"))
            val path=template.replace("{operation_id}",request.operationId.toString())
            val query=mapOf("profile_id" to scope.owner.profileId.toString(),"privacy_epoch" to scope.owner.privacyEpoch.toString(),
                "review_contract_version" to request.reviewContractVersion.toString(),"request_hash" to request.requestHash)
            val queryJson=buildJsonObject {
                put("profile_id",scope.owner.profileId.toString());put("privacy_epoch",scope.owner.privacyEpoch)
                put("review_contract_version",request.reviewContractVersion);put("request_hash",request.requestHash)
            }
            codecs.validateDefinition(bundle,"NextObservationQuery1",queryJson)
            val response=withTimeoutOrNull(request.channel.budgetMs) {
                transport.exchange("GET",path,query,mapOf("Authorization" to scope.session.authorizationHeader(),
                    "X-Installation-ID" to scope.session.installationId.toString(),"Accept" to "application/json","Cache-Control" to "no-store"),null)
            }?:return unavailable("DEADLINE_EXCEEDED",true)
            if(!api.isCurrent(scope))return unavailable("SESSION_CHANGED",false)
            if(transport.configuredOrigin!=identity.configuredOrigin)return unavailable("NEXT_OBSERVATION_ORIGIN_CHANGED",false)
            if(response.status in 300..399 || response.status in setOf(401,403,405) || response.body.size>1024*1024)return unavailable()
            val version=single(response.headers,"X-Pleos-Observation-Version")
            val revision=single(response.headers,"X-Pleos-Observation-Backend-Revision")
            val result=single(response.headers,"X-Pleos-Observation-Result")
            if(version!="1" || revision==null || !revision.matches(Regex("[A-Za-z0-9._:-]{1,128}")) || result !in setOf("found","not_admitted"))return unavailable()
            val canonicalHeaders=buildJsonObject {
                put("X-Pleos-Observation-Version",version);put("X-Pleos-Observation-Backend-Revision",revision);put("X-Pleos-Observation-Result",result)
            }
            codecs.validateDefinition(bundle,"NextObservationHeaders1",canonicalHeaders)
            if(single(response.headers,"Content-Type")?.substringBefore(';')?.trim()!="application/json")return unavailable()
            val source=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(response.body)).toString()
            val parsed=codecs.parse(bundle,source) as? JsonObject?:return unavailable()
            val responseSchema=operation["responses"]?.jsonObject?.get(response.status.toString())?.jsonObject?.get("content")?.jsonObject
                ?.get("application/json")?.jsonObject?.get("schema")?.jsonObject?:return unavailable()
            codecs.validate(bundle,"next-observation.openapi.json",responseSchema,parsed)
            val evidence=NextObservationEvidence(scope.owner,request.operationId,request.reviewContractVersion,request.requestHash,revision,
                if(result=="found")NextObservationResult.FOUND else NextObservationResult.NOT_ADMITTED,
                scope.session.subjectId,scope.session.sessionId,scope.session.installationId,identity)
            if(response.status==200) {
                if(result!="found")return unavailable()
                codecs.validateNextObservation(queryJson,request.operationId.toString(),parsed)
                api.requireOwner(parsed,scope)
                require(parsed.getValue("schema_version")==JsonPrimitive("6") && parsed.getValue("review_contract_version")==JsonPrimitive(request.reviewContractVersion))
                val value=if(request.reviewContractVersion==2)NextObservationValue.Review2(codecs.decode(bundle,"ReviewNextResponse2",source,WireReview2ReviewNextResponse2.serializer()))
                    else NextObservationValue.Review3(codecs.decode(bundle,"ReviewNextResponse3",source,WireReview3ReviewNextResponse3.serializer()))
                require(parsed.getValue("operation_id")==JsonPrimitive(request.operationId.toString()))
                val profile=parsed.getValue("profile_context").jsonObject;val admission=parsed.getValue("admission_receipt").jsonObject
                require(profile.getValue("profile_id")==JsonPrimitive(scope.owner.profileId.toString()) && profile.getValue("privacy_epoch")==JsonPrimitive(scope.owner.privacyEpoch) &&
                    admission.getValue("operation_id")==parsed.getValue("operation_id") && admission.getValue("request_hash")==JsonPrimitive(request.requestHash))
                if(!api.isCurrent(scope) || !evidence.matches(scope,request,identity))return unavailable("SESSION_CHANGED",false)
                mutable.value=evidence
                return NextObservationReply.Found(parsed,value,evidence,choiceCapabilities(response.headers))
            }
            val error=parsed.getValue("error").jsonObject
            val failure=LearningReply.Failed(error.getValue("code").jsonPrimitive.content,error.getValue("retryable").jsonPrimitive.boolean,
                single(response.headers,"Retry-After")?.toLongOrNull()?.takeIf{it>=0})
            if(failure.code=="review_operation_not_admitted" && response.status!=404)return unavailable()
            val missing=response.status==404 && failure.code=="review_operation_not_admitted"
            if((result=="not_admitted")!=missing)return unavailable()
            if(!api.isCurrent(scope) || !evidence.matches(scope,request,identity))return unavailable("SESSION_CHANGED",false)
            mutable.value=evidence
            return if(missing)NextObservationReply.NotAdmitted(evidence) else NextObservationReply.StoredError(failure,evidence)
        }catch(error:CancellationException){throw error}
         catch(_:Exception){return unavailable()}
    }
    private fun single(headers:Map<String,String>,name:String)=headers.entries.filter{it.key.equals(name,true)}.singleOrNull()?.value
    private fun choiceCapabilities(headers:Map<String,String>):Set<String> {
        val raw=single(headers,"X-Pleos-Review-Capabilities")?.takeIf{it.length<=512}?:return emptySet()
        val values=raw.split(',').map(String::trim)
        return if(values.all{it.matches(Regex("[a-z0-9_]+"))})values.filter{it in PendingChoiceKind.entries.map{choice->choice.capability}}.toSet() else emptySet()
    }
    private fun unavailable(code:String="NEXT_OBSERVATION_UNCERTAIN",retryable:Boolean=true):NextObservationReply.Unavailable {
        invalidate();return NextObservationReply.Unavailable(LearningReply.Failed(code,retryable))
    }
}
