package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.data.wire.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/** Public content only. Reading a question does not display a support/example or start a lesson. */
data class PreparedPracticeMaterial(val ref:PreparedPracticeRef,val questionEn:String,val manifest:PracticeSupportManifest) {
    override fun toString()="PreparedPracticeMaterial(content=redacted)"
}
data class SourcePublicScript(val contentRef:WireSource6ContentRef6,val componentId:UUID?,val scriptId:UUID,
    val text:String,val textHash:String,val language:String,val manifest:WireSource6SegmentManifest?) {
    override fun toString()="SourcePublicScript(content=redacted)"
}
data class PreparedSourceBinding(val content:PreparedContentRef,val selectedBand:String,val followupComponentId:UUID?,val practiceRef:PreparedPracticeRef?)
private class PreparedContentUnavailable(val code:String):Exception()

/** Direct safe view reads share CatalogRepository's publishable transport. No private registry RPC is exposed. */
class PreparedCatalogRepository internal constructor(private val codecs:IncrementalContractJson,private val legacy:CatalogRepository,
    private val api:IncrementalLearningApi,private val dao:ReviewDao):SourceCurrentReadPort {
    private val columns=listOf("component_id","article_id","dependency_key","source_snapshot_id","source_hash","kind","payload_hash","rights_revision","payload")
    private val payloadKeys=setOf("script_id","script_text","text_hash","segment_manifest","language","band","base_script_id","base_script_hash","format","followup_kind",
        "main_component_id","selected_script_id","selected_script_hash","practice_component_id","basic_question_version_id","task_revision","question_en","support_manifest","items")
    private val immutableHashes=ConcurrentHashMap<String,String>()
    private val bindings=ConcurrentHashMap<String,WireSource6PreparedPracticeRef6>()
    private val bindingColumns=listOf("material_generation_id","profile_id","privacy_epoch","article_id","source_snapshot_id","source_hash","selected_band",
        "main_component_id","followup_component_id","practice_component_id","basic_question_version_id","task_revision")

    /** Catalog metadata remains present when items is empty. This GET never prepares or collects. */
    suspend fun catalog(scope:LearningCallScope,topics:List<String>,limit:Long=20,cursor:String?=null):LearningReply<WirePreparationCatalogResponse> =
        api.catalog(scope,topics,limit,cursor)
    suspend fun readPreparedMain(scope:LearningCallScope,ref:PreparedContentRef):CatalogRead<SourcePublicScript> = main(scope,ref.source6Wire())
    suspend fun readPreparedFollowup(scope:LearningCallScope,ref:PreparedContentRef,kind:String):CatalogRead<SourcePublicScript> = followup(scope,ref.source6Wire(),kind)
    suspend fun readCurrentBinding(scope:LearningCallScope,ref:PreparedContentRef):CatalogRead<PreparedSourceBinding> = readContent(scope){
        val row=binding(scope,ref.source6Wire())
        val followup=row.getValue("followup_component_id").takeUnless{it===JsonNull}?.jsonPrimitive?.content?.let(UUID::fromString)
        val practice=row.getValue("practice_component_id").takeUnless{it===JsonNull}?.jsonPrimitive?.content?.let{
            PreparedPracticeRef(ref,UUID.fromString(it),row.uuid("basic_question_version_id"),row.text("task_revision",128))
        }
        PreparedSourceBinding(ref,row.text("selected_band"),followup,practice)
    }

    suspend fun main(scope:LearningCallScope,ref:WireSource6ContentRef6):CatalogRead<SourcePublicScript> = readContent(scope){
        codecs.encode("source6","ContentRef6",ref,WireSource6ContentRef6.serializer())
        when(ref){
            is WireSource6PreparedContentRef6 -> {
                binding(scope,ref)
                val row=component(ref.mainComponentId,ref.articleId,ref.sourceSnapshotId,ref.sourceHash)
                require(row.text("kind") == if(ref.listeningMode=="original_full")"main_original" else "main_briefing")
                script(ref,row).also{require(it.scriptId.toString()==ref.mainScriptId && it.textHash==ref.mainTextHash && it.language=="en")}
            }
            is WireSource6LegacyContentRef6 -> {
                val source=ref.legacyRef.domain()
                val result=legacy.readMain(source)
                val value=(result as? CatalogRead.Stored)?.value?:throw PreparedContentUnavailable((result as CatalogRead.Unavailable).code)
                val manifestRow=legacyManifestRow(source,value.scriptId)
                val manifest=manifestRow?.let{manifest(it.getValue("payload").jsonObject,value.text,value.scriptId.toString(),value.textHash)}
                require(manifest==null || manifest.language==value.role.language)
                SourcePublicScript(ref,manifestRow?.uuid("component_id"),value.scriptId,value.text,value.textHash,value.role.language,manifest)
            }
        }
    }
    /** The caller supplies the already-bound followup component, never a latest sibling lookup. */
    suspend fun followup(scope:LearningCallScope,ref:WireSource6ContentRef6,kind:String,componentId:UUID?=null):CatalogRead<SourcePublicScript> = readContent(scope){
        require(kind in setOf("en_summary","ko_summary","ko_translation"))
        val mode=when(ref){is WireSource6PreparedContentRef6->ref.listeningMode;is WireSource6LegacyContentRef6->ref.legacyRef.listeningMode}
        if(mode=="briefing" && kind=="en_summary")throw PreparedContentUnavailable("FOLLOWUP_NOT_APPLICABLE")
        when(ref){
            is WireSource6PreparedContentRef6 -> {
                val frozen=binding(scope,ref)["followup_component_id"]?.takeUnless{it===JsonNull}?.jsonPrimitive?.content
                    ?:throw PreparedContentUnavailable("FOLLOWUP_BINDING_NOT_READY")
                require(componentId==null || componentId.toString()==frozen)
                val row=component(frozen,ref.articleId,ref.sourceSnapshotId,ref.sourceHash)
                val payload=row.getValue("payload").jsonObject
                require(row.text("kind")=="followup" && payload.text("base_script_id")==ref.mainScriptId && payload.text("base_script_hash")==ref.mainTextHash &&
                    payload.text("format")==ref.listeningMode && payload.text("followup_kind")==kind)
                script(ref,row).also{require(it.language==if(kind=="en_summary")"en" else "ko")}
            }
            is WireSource6LegacyContentRef6 -> {
                val role=CatalogScriptRole.entries.single{it.wire=="${if(mode=="original_full")"original" else "briefing"}_$kind"}
                val result=legacy.readFollowup(ref.legacyRef.domain(),role)
                val value=(result as? CatalogRead.Stored)?.value?:throw PreparedContentUnavailable((result as CatalogRead.Unavailable).code)
                val manifestRow=legacyManifestRow(ref.legacyRef.domain(),value.scriptId)
                val manifest=manifestRow?.let{manifest(it.getValue("payload").jsonObject,value.text,value.scriptId.toString(),value.textHash)}
                require(manifest==null || manifest.language==value.role.language)
                SourcePublicScript(ref,manifestRow?.uuid("component_id"),value.scriptId,value.text,value.textHash,value.role.language,manifest)
            }
        }
    }

    /** Only an actual owner/epoch-scoped server ACK can introduce a task binding. */
    suspend fun bindPractice(scope:LearningCallScope,receipt:LearningReply.Stored<WireSource6PersonalReadLesson>) {
        require(api.isCurrent(scope) && receipt.scope==scope)
        val ref=receipt.value.payload.contentRef as? WireSource6PreparedPracticeRef6?:error("Prepared lesson binding required.")
        bind(scope,ref)
    }
    private fun bind(scope:LearningCallScope,ref:WireSource6PreparedPracticeRef6){
        codecs.encode("source6","PreparedPracticeRef6",ref,WireSource6PreparedPracticeRef6.serializer())
        val key=bindingKey(scope,ref.materialGenerationId)
        bindings.putIfAbsent(key,ref)?.let{require(it==ref){"Frozen task binding changed."}}
    }
    suspend fun readPreparedPractice(scope:LearningCallScope,contentRef:PreparedContentRef):CatalogRead<PreparedPracticeMaterial> {
        if(!api.isCurrent(scope))return CatalogRead.Unavailable("SESSION_CHANGED",false)
        return readContent(scope) {
            val main=contentRef.source6Wire()
            val fixed=binding(scope,main)
            val component=fixed["practice_component_id"]?.takeUnless{it===JsonNull}?.jsonPrimitive?.content
                ?:throw PreparedContentUnavailable("PRACTICE_BINDING_NOT_READY")
            val ref=WireSource6PreparedPracticeRef6("prepared_components",main.articleId,main.sourceSnapshotId,main.sourceHash,main.materialGenerationId,
                main.mainComponentId,main.mainScriptId,main.mainTextHash,main.listeningMode,component,fixed.text("basic_question_version_id"),fixed.text("task_revision",128))
            bind(scope,ref)
            when(val material=readPreparedPractice(scope,ref)){
                is CatalogRead.Stored->material.value
                is CatalogRead.Unavailable->throw PreparedContentUnavailable(material.code)
            }
        }
    }
    /** Explicit exact PracticeRef6 overload for existing authenticated source/admission adapters. */
    suspend fun readPreparedPractice(scope:LearningCallScope,ref:WireSource6PreparedPracticeRef6):CatalogRead<PreparedPracticeMaterial> = readContent(scope){
        val fixed=binding(scope,content(ref) as WireSource6PreparedContentRef6)
        require(fixed.text("practice_component_id")==ref.practiceComponentId && fixed.text("basic_question_version_id")==ref.basicQuestionVersionId && fixed.text("task_revision")==ref.taskRevision)
        val current=main(scope,content(ref))
        if(current !is CatalogRead.Stored)throw PreparedContentUnavailable((current as CatalogRead.Unavailable).code)
        val row=component(ref.practiceComponentId,ref.articleId,ref.sourceSnapshotId,ref.sourceHash)
        val payload=row.getValue("payload").jsonObject
        require(row.text("kind")=="practice" && payload.text("practice_component_id")==ref.practiceComponentId && payload.text("main_component_id")==ref.mainComponentId &&
            payload.text("selected_script_id")==ref.mainScriptId && payload.text("selected_script_hash")==ref.mainTextHash &&
            payload.text("basic_question_version_id")==ref.basicQuestionVersionId && payload.text("task_revision")==ref.taskRevision)
        val support=payload.getValue("support_manifest")
        codecs.validateDefinition("source6","CommonPracticeSupportManifest",support)
        val manifest=learningJson.decodeFromJsonElement(WireCommonPracticeSupportManifest.serializer(),support).domain()
        require(manifest.questionRef.articleId.toString()==ref.articleId && manifest.questionRef.basicQuestionVersionId.toString()==ref.basicQuestionVersionId)
        val domain=PreparedPracticeRef((content(ref) as WireSource6PreparedContentRef6).domain(),UUID.fromString(ref.practiceComponentId),UUID.fromString(ref.basicQuestionVersionId),ref.taskRevision,
            supportTaskIdentity=manifest.taskId)
        PreparedPracticeMaterial(domain,payload.text("question_en",500),manifest)
    }

    override suspend fun read(scope:LearningCallScope,ref:WireSource6ContentRef6):SourceCurrentProjection? = when(val result=main(scope,ref)){
        is CatalogRead.Unavailable->null
        is CatalogRead.Stored->SourceCurrentProjection(ref,null,true,true,"ready","unavailable",result.value.text,result.value.manifest,null)
    }
    override suspend fun readPractice(scope:LearningCallScope,ref:WireSource6PracticeContentRef6):SourceCurrentProjection? {
        val base=content(ref)
        if(ref is WireSource6PreparedPracticeRef6){
            if(readPreparedPractice(scope,ref) !is CatalogRead.Stored)return null
        }else if(ref is WireSource6LegacyPracticeRef6){
            val result=legacy.readPractice(ref.legacyRef.contentDomain())
            val task=(result as? CatalogRead.Stored)?.value?:return null
            if(task.ref.taskId.toString()!=ref.legacyRef.taskId || task.ref.basicQuestionVersionId.toString()!=ref.legacyRef.basicQuestionVersionId)return null
        }
        val value=read(scope,base)?:return null
        return value.copy(practiceRef=ref,practiceStatus="ready")
    }
    private suspend fun component(id:String,article:String,snapshot:String,hash:String):JsonObject {
        UUID.fromString(id);UUID.fromString(article);UUID.fromString(snapshot);require(hash.matches(Regex("[a-f0-9]{64}")))
        val values=legacy.publicRows("news_material_components",columns,mapOf("component_id" to "eq.$id","article_id" to "eq.$article",
            "source_snapshot_id" to "eq.$snapshot","source_hash" to "eq.$hash","limit" to "2"))
        val row=values.singleOrNull()?:throw PreparedContentUnavailable("MATERIAL_NOT_CURRENT")
        require(row.text("component_id")==id && row.text("article_id")==article && row.text("source_snapshot_id")==snapshot && row.text("source_hash")==hash)
        validateRow(row);return row
    }
    private suspend fun binding(scope:LearningCallScope,ref:WireSource6PreparedContentRef6):JsonObject {
        val values=legacy.bindingRows(scope,bindingColumns,mapOf("material_generation_id" to "eq.${ref.materialGenerationId}","profile_id" to "eq.${scope.owner.profileId}",
            "privacy_epoch" to "eq.${scope.owner.privacyEpoch}","limit" to "2"))
        if(!api.isCurrent(scope))throw PreparedContentUnavailable("SESSION_CHANGED")
        val row=values.singleOrNull()?:throw PreparedContentUnavailable("SOURCE_BINDING_NOT_CURRENT")
        require(row.keys==bindingColumns.toSet() && row.text("material_generation_id")==ref.materialGenerationId && row.text("profile_id")==scope.owner.profileId.toString() &&
            row.getValue("privacy_epoch").jsonPrimitive.long==scope.owner.privacyEpoch && row.text("article_id")==ref.articleId && row.text("source_snapshot_id")==ref.sourceSnapshotId &&
            row.text("source_hash")==ref.sourceHash && row.text("main_component_id")==ref.mainComponentId && row.text("selected_band") in setOf("basic","intermediate","advanced"))
        for(key in listOf("followup_component_id","practice_component_id","basic_question_version_id"))row[key]?.takeUnless{it===JsonNull}?.let{UUID.fromString(it.jsonPrimitive.content)}
        return row
    }
    private suspend fun legacyManifestRow(ref:ContentRef,script:UUID):JsonObject? {
        val values=legacy.publicRows("news_material_components",columns,mapOf("article_id" to "eq.${ref.articleId}","source_snapshot_id" to "eq.${ref.sourceSnapshotId}",
            "payload->>script_id" to "eq.$script","limit" to "2"))
        if(values.isEmpty())return null
        val row=values.single();validateRow(row);return row
    }
    private fun validateRow(row:JsonObject){
        require(row.keys==columns.toSet());row.uuid("component_id");row.uuid("article_id");row.uuid("source_snapshot_id")
        for(key in listOf("dependency_key","source_hash","payload_hash"))require(row.text(key).matches(Regex("[a-f0-9]{64}")))
        require(row.text("rights_revision").isNotBlank())
        val payload=row.getValue("payload").jsonObject;require(payload.keys.all{it in payloadKeys})
        immutableHashes.putIfAbsent(row.text("component_id"),row.text("payload_hash"))?.let{require(it==row.text("payload_hash"))}
    }
    private fun script(ref:WireSource6ContentRef6,row:JsonObject):SourcePublicScript {
        val payload=row.getValue("payload").jsonObject
        val text=payload.text("script_text",200000);val hash=payload.text("text_hash");val id=payload.text("script_id")
        require(LearningJson.hash(text)==hash)
        val language=payload.text("language");require(language in setOf("en","ko"))
        val manifest=manifest(payload,text,id,hash);require(manifest.language==language)
        return SourcePublicScript(ref,row.uuid("component_id"),UUID.fromString(id),text,hash,language,manifest)
    }
    private fun manifest(payload:JsonObject,text:String,scriptId:String,hash:String):WireSource6SegmentManifest {
        val result=codecs.decodeSource6("SegmentManifest",payload.getValue("segment_manifest").toString(),WireSource6SegmentManifest.serializer())
        require(result.scriptId==scriptId && result.scriptHash==hash);result.toSegmentManifest().validate(text);return result
    }
    private suspend fun <T> readContent(scope:LearningCallScope,block:suspend()->T):CatalogRead<T> = try {
        if(!api.isCurrent(scope))CatalogRead.Unavailable("SESSION_CHANGED",false)
        else withTimeoutOrNull(8_000){val result=block();if(api.isCurrent(scope))CatalogRead.Stored(result) else CatalogRead.Unavailable("SESSION_CHANGED",false)}
            ?:CatalogRead.Unavailable("DEADLINE_EXCEEDED",true)
    }catch(error:CancellationException){throw error}
    catch(error:PreparedContentUnavailable){CatalogRead.Unavailable(error.code,false)}
    catch(_:IllegalArgumentException){CatalogRead.Unavailable("INVALID_CONTENT",false)}
    catch(_:Exception){CatalogRead.Unavailable("PUBLIC_CONTENT_UNAVAILABLE",true)}
    private fun bindingKey(scope:LearningCallScope,generation:String)="${scope.owner.profileId}/${scope.owner.privacyEpoch}/${scope.owner.revision}/$generation"
    private fun JsonObject.text(key:String,max:Int=500):String=getValue(key).jsonPrimitive.also{require(it.isString && it.content.codePointCount(0,it.content.length) in 1..max)}.content
    private fun JsonObject.uuid(key:String):UUID=text(key,36).let{value->UUID.fromString(value).also{require(it.toString()==value)}}
}

private fun WireSource6LegacyContentRef5.domain()=ContentRef(UUID.fromString(articleId),UUID.fromString(bundleId),UUID.fromString(sourceSnapshotId),UUID.fromString(listeningScriptId),ListeningMode.valueOf(listeningMode.uppercase()))
private fun WireSource6LegacyPracticeRef5.contentDomain()=ContentRef(UUID.fromString(articleId),UUID.fromString(bundleId),UUID.fromString(sourceSnapshotId),UUID.fromString(listeningScriptId),ListeningMode.valueOf(listeningMode.uppercase()))
private fun WireSource6PreparedContentRef6.domain()=PreparedContentRef(UUID.fromString(articleId),UUID.fromString(sourceSnapshotId),sourceHash,UUID.fromString(materialGenerationId),UUID.fromString(mainComponentId),UUID.fromString(mainScriptId),mainTextHash,ListeningMode.valueOf(listeningMode.uppercase()))
private fun content(ref:WireSource6PracticeContentRef6):WireSource6ContentRef6=when(ref){
    is WireSource6PreparedPracticeRef6->WireSource6PreparedContentRef6("prepared_components",ref.articleId,ref.sourceSnapshotId,ref.sourceHash,ref.materialGenerationId,ref.mainComponentId,ref.mainScriptId,ref.mainTextHash,ref.listeningMode)
    is WireSource6LegacyPracticeRef6->WireSource6LegacyContentRef6("legacy_bundle",WireSource6LegacyContentRef5(ref.legacyRef.articleId,ref.legacyRef.bundleId,ref.legacyRef.sourceSnapshotId,ref.legacyRef.listeningMode,ref.legacyRef.listeningScriptId))
}
