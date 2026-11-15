package io.github.driveenglishlab.pleos.data

import android.content.Context
import io.github.driveenglishlab.pleos.data.wire.*
import java.time.OffsetDateTime
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.plus

/** Independent R7 loaders. No old asset/validator semantics or feedback payload is rewritten. */
class R7ContractJson(context:Context) {
    companion object { val bundles=setOf("assessment-review","catalog-views","review2","review3","next-observation") }
    private val loaders=bundles.associateWith{bundle->LearningContractJson(context,"$bundle-contracts",
        listOf("$bundle.schema.json","$bundle.openapi.json")+if(bundle=="next-observation")listOf("review2.schema.json","review3.schema.json")else emptyList())}
    private val json=Json {
        ignoreUnknownKeys=false;isLenient=false;coerceInputValues=false;explicitNulls=true;encodeDefaults=false
        serializersModule=assessmentReviewWireSerializers+catalogViewsWireSerializers+review2WireSerializers+review3WireSerializers
    }
    init {
        fun asset(path:String)=context.assets.open(path).bufferedReader().use{learningJson.parseToJsonElement(it.readText()).jsonObject}
        val frozen=asset("next-observation-contracts/manifest.json")
        val provenance=asset("next-observation-contracts/next-observation.schema.json").getValue("x-frozen-origin-sha256").jsonObject
        check(provenance.keys==setOf("review2.schema.json","review3.schema.json"))
        for(origin in listOf("review2","review3")){
            val name="$origin.schema.json";val expected=asset("$origin-contracts/manifest.json").getValue(name)
            check(frozen[name]==expected && provenance[name]==expected)
        }
    }
    private fun loader(bundle:String)=loaders[bundle]?:throw LearningProtocolException()
    fun parse(bundle:String,source:String):JsonElement=loader(bundle).parse(source)
    fun operation(bundle:String,path:String,method:String):JsonObject=loader(bundle).operation("$bundle.openapi.json",path,method)
    fun dereference(bundle:String,file:String,value:JsonObject):Pair<String,JsonObject> = loader(bundle).dereference(file,value)
    fun validate(bundle:String,file:String,schema:JsonObject,value:JsonElement)=loader(bundle).validate(file,schema,value)
    fun validateDefinition(bundle:String,name:String,value:JsonElement){
        val codec=loader(bundle);val file="$bundle.schema.json"
        codec.validate(file,codec.schema(file,"#/\$defs/$name"),value)
        semantic(name,value)
    }
    fun <T> decode(bundle:String,name:String,source:String,serializer:DeserializationStrategy<T>):T {
        val value=parse(bundle,source);validateDefinition(bundle,name,value)
        return json.decodeFromJsonElement(serializer,value)
    }
    fun <T> encode(bundle:String,name:String,value:T,serializer:SerializationStrategy<T>):String {
        val element=json.encodeToJsonElement(serializer,value);validateDefinition(bundle,name,element);return element.toString()
    }
    /** Immutable admission receipt is separate from a monotonically versioned current outcome. */
    fun nextTransition(previous:JsonObject,current:JsonObject):Boolean {
        val version=(previous["review_contract_version"] as? JsonPrimitive)?.longOrNull?:throw LearningProtocolException()
        check((current["review_contract_version"] as? JsonPrimitive)?.longOrNull==version)
        val bundle=if(version==3L)"review3" else "review2";val definition=if(version==3L)"ReviewNextResponse3" else "ReviewNextResponse2"
        validateDefinition(bundle,definition,previous);validateDefinition(bundle,definition,current)
        for(key in listOf("operation_id","profile_context","stream_id","admission_receipt"))check(previous[key]==current[key])
        val before=previous.obj("outcome_projection");val after=current.obj("outcome_projection")
        if(after.long("version")<before.long("version"))return false
        if(after.long("version")==before.long("version"))check(before.filterKeys{it!="observed_at"}==after.filterKeys{it!="observed_at"})
        if(version==3L){
            if(before.text("state")=="no_candidate" && (before["no_candidate_reason"]==JsonPrimitive("original_unavailable") || before["no_candidate_reason"]==JsonPrimitive("ready_alternative_unavailable")))
                check(before.filterKeys{it!="observed_at"}==after.filterKeys{it!="observed_at"})
            val oldTarget=previous["pending_target"]?.takeUnless{it===JsonNull}?.jsonObject
            val newTarget=current["pending_target"]?.takeUnless{it===JsonNull}?.jsonObject
            if(oldTarget!=null && newTarget!=null)for(key in listOf("article_id","source_snapshot_id","source_hash"))check(oldTarget[key]==newTarget[key])
        }
        return true
    }
    /** Kind-aware protocol guard. Availability/capability and actual B/C selection remain authoritative on the server. */
    fun next3Choice(request:JsonObject,result:JsonObject){
        validateDefinition("review3","ReviewNextRequest3",request);validateDefinition("review3","ReviewNextResponse3",result)
        for(key in listOf("operation_id","profile_context","stream_id"))check(request[key]==result[key])
        check(result.obj("admission_receipt")["intent_kind"]==request["intent_kind"])
        val override=request["selection_override"]?.takeUnless{it===JsonNull}?.jsonObject?:return
        val outcome=result.obj("outcome_projection");val state=outcome.text("state")
        when(override.text("kind")){
            "original_of_pending"->if(state=="selected"){
                val source=outcome.obj("selection").obj("content_ref");val mode=source["listening_mode"]?:source.obj("legacy_ref")["listening_mode"]
                val checkpoint=outcome.obj("checkpoint").obj("payload");val cursor=checkpoint.obj("cursor")
                check(mode==JsonPrimitive("original_full") && checkpoint["active_run_id"]===JsonNull && cursor.text("kind")=="segment" && cursor.text("phase")=="main" && cursor.long("segment_index")==0L)
            }else check(state=="no_candidate" && outcome["no_candidate_reason"]==JsonPrimitive("original_unavailable"))
            "ready_alternative_of_pending"->if(state=="no_candidate")check(outcome["no_candidate_reason"]==JsonPrimitive("ready_alternative_unavailable"))
                else check(state in setOf("selected","resumed"))
            "continue_pending"->{
                check(state in setOf("selected","resumed","pending","expired_waiting_explicit"))
                val target=result["pending_target"]?.takeUnless{it===JsonNull}?.jsonObject
                if(target!=null && request["requested_article_id"]!==JsonNull)check(target["article_id"]==request["requested_article_id"] && target["source_snapshot_id"]==request["requested_source_snapshot_id"])
            }
            else->check(false)
        }
        if(override.text("kind") in setOf("original_of_pending","continue_pending") && request["requested_article_id"]!==JsonNull){
            outcome["selection"]?.takeUnless{it===JsonNull}?.jsonObject?.let{selection->
                val ref=selection.obj("content_ref");val source=ref["legacy_ref"]?.jsonObject?:ref
                check(source["article_id"]==request["requested_article_id"] && source["source_snapshot_id"]==request["requested_source_snapshot_id"])
            }
        }
    }
    /** Correlates only the safe read with its original op/protocol/hash and owner. */
    fun nextObservation(query:JsonObject,operationId:String,result:JsonObject){
        validateDefinition("next-observation","NextObservationQuery1",query)
        validateDefinition("next-observation","NextObservationResponse1",result)
        check(result["review_contract_version"]==query["review_contract_version"] && result.text("operation_id")==operationId)
        val owner=result.obj("profile_context")
        check(owner["profile_id"]==query["profile_id"] && owner["privacy_epoch"]==query["privacy_epoch"])
        check(result.obj("admission_receipt")["request_hash"]==query["request_hash"])
    }
    fun assessmentLookup(query:JsonObject,result:JsonObject,targetId:String){
        validateDefinition("assessment-review","AssessmentReviewQuery",query)
        validateDefinition("assessment-review","AssessmentReviewResponse",result)
        check(result.obj("profile_context")["profile_id"]==query["profile_id"] && result.obj("profile_context")["privacy_epoch"]==query["privacy_epoch"])
        if(query.text("lookup")=="review")check(result["review"]!==JsonNull && result.obj("review").text("review_id")==targetId)
        else check(result.obj("effective_assessment").text("attempt_id")==targetId)
    }
    private fun check(ok:Boolean){if(!ok)throw LearningProtocolException()}
    private fun JsonObject.obj(key:String)=getValue(key).jsonObject
    private fun JsonObject.text(key:String)=getValue(key).jsonPrimitive.content
    private fun JsonObject.long(key:String)=getValue(key).jsonPrimitive.long
    private fun instant(value:JsonElement)=OffsetDateTime.parse(value.jsonPrimitive.content).toInstant()
    private fun semantic(original:String,element:JsonElement){
        val value=element as? JsonObject?:return
        val name=when(original){"AdmissionReceipt"->"AssessmentReviewAdmissionResponse";"ReadQuery"->"AssessmentReviewQuery";"ReviewResponse"->"AssessmentReviewResponse";else->original}
        when(name){
            "NextObservationHeaders1"->check(value.text("X-Pleos-Observation-Version")=="1" &&
                Regex("[A-Za-z0-9._:-]{1,128}").matches(value.text("X-Pleos-Observation-Backend-Revision")) &&
                value.text("X-Pleos-Observation-Result") in setOf("found","not_admitted"))
            "NextObservationResponse1"->{
                val version=value.long("review_contract_version");check(version in setOf(2L,3L))
                semantic(if(version==2L)"ReviewNextResponse2" else "ReviewNextResponse3",value)
            }
            "AssessmentReviewRequest"->{val client=value.obj("client_context");val profile=value.obj("profile_context")
                check(client["profile_id"]==profile["profile_id"] && client["privacy_epoch"]==profile["privacy_epoch"])}
            "AssessmentReviewView"->{check(instant(value.getValue("requested_at"))<=instant(value.getValue("updated_at")))
                value["completed_at"]?.takeUnless{it===JsonNull}?.let{check(instant(it)>=instant(value.getValue("requested_at")) && instant(it)<=instant(value.getValue("updated_at")))}}
            "EffectiveAssessment"->if(value.text("evaluation_status")=="valid"){
                check(value["effective_feedback_hash"]==value["basis_assessment_hash"] && value.obj("feedback")["attempt_id"]==value["attempt_id"])}
            "AssessmentReviewResponse"->{val effective=value.obj("effective_assessment");semantic("EffectiveAssessment",effective)
                value["review"]?.takeUnless{it===JsonNull}?.let{val review=it.jsonObject;semantic("AssessmentReviewView",review)
                    check(review["lesson_id"]==effective["lesson_id"] && review["attempt_id"]==effective["attempt_id"])}}
            "CorrectionEvent"->check(value.long("to_assessment_revision")>value.long("from_assessment_revision") && value["prior_feedback_hash"]!=value["new_feedback_hash"])
            "IndependentEstimate"->if(value.text("status")=="estimated")check((value.text("language")=="en")== (value.text("unit")=="word"))
            "ArticleDiscoveryView"->{semantic("IndependentEstimate",value.getValue("main_estimate"));semantic("IndependentEstimate",value.getValue("followup_estimate"))}
            "IssueEvidence"->{val groups=value.getValue("independent_origin_groups").jsonArray;val asOf=instant(value.getValue("as_of"))
                if(value.text("independence_status")=="confirmed")check(groups.isNotEmpty() && value.long("independent_origin_count")==groups.size.toLong())
                for(row in value.getValue("headline_observations").jsonArray)check(instant(row.jsonObject.getValue("observed_at"))<=asOf)
                value["latest_original_published_at"]?.takeUnless{it===JsonNull}?.let{check(instant(it)<=asOf)}}
            "IssueView"->{semantic("ArticleDiscoveryView",value.getValue("article"));semantic("IssueEvidence",value.getValue("evidence"))
                check(value["representative_article_id"]==value.obj("article")["article_id"] && value["representative_article_id"] in value.getValue("member_article_ids").jsonArray)
                val evidence=value.obj("evidence");val headline=evidence.getValue("headline_placement_confirmed").jsonPrimitive.boolean && evidence.getValue("headline_observations").jsonArray.isNotEmpty()
                val independent=evidence.text("independence_status")=="confirmed" && (evidence["independent_origin_count"]?.jsonPrimitive?.longOrNull?:0)>=2
                check(headline || independent);check(instant(evidence.getValue("as_of"))<=instant(value.getValue("issue_as_of")))}
            "CatalogViewResponse"->{val categories=value.obj("category_config").getValue("categories").jsonArray
                check(categories.map{it.jsonObject.long("display_order")}.toSet()==setOf(0L,1L,2L,3L,4L,5L))
                val items=value.getValue("items").jsonArray.map{it.jsonObject}
                if(value.text("view")=="interests"){
                    check(items.map{it.text("article_id")}.distinct().size==items.size);items.forEach{semantic("ArticleDiscoveryView",it)}
                    if(items.all{it.obj("source")["published_at_utc"]!==JsonNull})check(items==items.sortedWith(compareByDescending<JsonObject>{instant(it.obj("source").getValue("published_at_utc"))}.thenBy{it.text("article_id")}))
                }else{
                    check(items.map{it.text("issue_id")}.distinct().size==items.size)
                    items.forEach{semantic("IssueView",it);check(value["issue_as_of"]!==JsonNull && instant(it.getValue("issue_as_of"))<=instant(value.getValue("issue_as_of")))}
                }}
            "ReviewNextRequest3"->{val override=value["selection_override"]
                if(override===JsonNull)semantic("ReviewNextRequest2",value)
                else{
                    val parent=override!!.jsonObject;val intent=value.text("intent_kind");val kind=value.text("selection_kind")
                    check(value["operation_id"]!=parent["pending_operation_id"] && value.text("trigger")=="explicit")
                    check((value["requested_article_id"]===JsonNull)==(value["requested_source_snapshot_id"]===JsonNull))
                    when(parent.text("kind")){
                        "ready_alternative_of_pending"->check(intent=="ready_alternative" && kind=="automatic_unheard" && value["requested_article_id"]===JsonNull)
                        "original_of_pending","continue_pending"->check(when(intent){
                            "explicit_article"->kind=="explicit_article"
                            "explicit_start"->kind in setOf("automatic_unheard","explicit_article")
                            "explicit_next"->kind=="automatic_unheard"
                            "ready_alternative"->parent.text("kind")=="continue_pending" && kind=="automatic_unheard"
                            else->false
                        })
                        else->check(false)
                    }
                }}
            "PendingTarget3"->{val availability=value.text("original_availability");val component=value["original_component"]?.takeUnless{it===JsonNull}?.jsonObject
                if(component!=null)check(component.text("kind")=="main_original" && (component.text("status")=="ready")== (availability=="ready"))
                else check(availability!="ready")
                val head=value.obj("progress_head");if(head["current_auto_progress_id"]===JsonNull)check(head["current_selection_id"]===JsonNull)
            }
            "ReviewNextRequest2"->{val intent=value.text("intent_kind");val kind=value.text("selection_kind")
                val explicit=kind=="explicit_article"
                check((value["requested_article_id"]!==JsonNull)==explicit && (value["requested_source_snapshot_id"]!==JsonNull)==explicit)
                check(when(intent){"explicit_article"->explicit;"explicit_start"->kind in setOf("automatic_unheard","explicit_article");else->kind=="automatic_unheard"})
            }
            "ReviewNextResponse2","ReviewNextResponse3"->{val receipt=value.obj("admission_receipt");val outcome=value.obj("outcome_projection");val state=outcome.text("state")
                check(receipt["operation_id"]==value["operation_id"])
                val active=state in setOf("selected","resumed","pending","expired_waiting_explicit")
                val hasIntent=receipt["selection_intent_id"]!==JsonNull;val hasDeadline=receipt["activation_deadline_at"]!==JsonNull
                check(if(active)hasIntent && hasDeadline else !hasIntent && !hasDeadline)
                if(active)check(instant(receipt.getValue("activation_deadline_at"))>instant(receipt.getValue("accepted_at")))
                val selection=outcome["selection"]?.takeUnless{it===JsonNull}?.jsonObject;val checkpoint=outcome["checkpoint"]?.takeUnless{it===JsonNull}?.jsonObject
                check((selection==null)==(checkpoint==null))
                if(selection!=null && checkpoint!=null){val payload=checkpoint.obj("payload")
                    check(payload["selection_id"]==selection["selection_id"] && payload["content_ref"]==selection["content_ref"] && payload["stream_id"]==selection["stream_id"])}
                if(state=="selected")check(selection?.get("stream_id")==value["stream_id"])
                if(state in setOf("selected","resumed")){
                    val context=outcome.obj("context");val profile=value.obj("profile_context")
                    check(context["selection_id"]==selection?.get("selection_id") && context["profile_id"]==profile["profile_id"] && context["learning_epoch"]==profile["privacy_epoch"])
                    check(context.obj("auto_progress_ref")["auto_progress_id"]==value["stream_id"] && context.obj("auto_progress_ref")["version"]==outcome["stream_version"])
                    val source=selection!!.obj("content_ref");val mode=source["listening_mode"]?:source.obj("legacy_ref")["listening_mode"]
                    val kind=if(mode==JsonPrimitive("original_full"))"main_original" else "main_briefing"
                    check(outcome.getValue("source_status").jsonArray.any{it.jsonObject.text("kind")==kind && it.jsonObject.text("status")=="ready"})
                }
                if(name=="ReviewNextResponse3"){
                    val target=value["pending_target"]?.takeUnless{it===JsonNull}
                    check((target!=null)==(state in setOf("pending","expired_waiting_explicit")))
                    if(target!=null)semantic("PendingTarget3",target)
                }}
        }
    }
}
