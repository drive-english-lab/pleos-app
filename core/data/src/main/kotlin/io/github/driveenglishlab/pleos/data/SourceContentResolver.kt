package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.data.wire.*

/** Supplied by the existing approved-content/catalog adapter; no second content service. */
fun interface SourceCurrentReadPort {
    suspend fun read(scope:LearningCallScope,ref:WireSource6ContentRef6):SourceCurrentProjection?
    suspend fun readPractice(scope:LearningCallScope,ref:WireSource6PracticeContentRef6):SourceCurrentProjection? = null
}
data class SourceCurrentProjection(val contentRef:WireSource6ContentRef6,val practiceRef:WireSource6PracticeContentRef6?,
    val rightsCurrent:Boolean,val sourceCurrent:Boolean,val mainStatus:String,val practiceStatus:String,
    val scriptText:String?,val segmentManifest:WireSource6SegmentManifest?,val reason:String?)
data class NarrationAvailability(val available:Boolean,val reason:String?)
fun interface SourceNarrationCapabilityPort {
    suspend fun current(language:String,purpose:String):NarrationAvailability
}
sealed interface SourceResolution {
    data class Ready(val value:SourceCurrentProjection):SourceResolution
    data class Unavailable(val reason:String):SourceResolution
}

/** Fixed legacy/prepared identities share rights and readiness checks; unknown source CEFR is not a playback gate. */
class SourceContentResolver(private val api:IncrementalLearningApi,private val codecs:IncrementalContractJson,
    private val content:SourceCurrentReadPort,private val narration:SourceNarrationCapabilityPort) {
    fun contentJson(ref:WireSource6ContentRef6):String=codecs.encode("source6","ContentRef6",ref,WireSource6ContentRef6.serializer())
    fun practiceJson(ref:WireSource6PracticeContentRef6):String=codecs.encode("source6","PracticeContentRef6",ref,WireSource6PracticeContentRef6.serializer())
    fun practiceContent(ref:WireSource6PracticeContentRef6):WireSource6ContentRef6=when(ref){
        is WireSource6LegacyPracticeRef6 -> WireSource6LegacyContentRef6("legacy_bundle",WireSource6LegacyContentRef5(ref.legacyRef.articleId,
            ref.legacyRef.bundleId,ref.legacyRef.sourceSnapshotId,ref.legacyRef.listeningMode,ref.legacyRef.listeningScriptId))
        is WireSource6PreparedPracticeRef6 -> WireSource6PreparedContentRef6("prepared_components",ref.articleId,ref.sourceSnapshotId,ref.sourceHash,
            ref.materialGenerationId,ref.mainComponentId,ref.mainScriptId,ref.mainTextHash,ref.listeningMode)
    }
    suspend fun resolve(scope:LearningCallScope,ref:WireSource6ContentRef6,practice:WireSource6PracticeContentRef6?=null,
        requireNarration:Boolean=false,purpose:String="main"):SourceResolution {
        if(!api.isCurrent(scope))return SourceResolution.Unavailable("SESSION_CHANGED")
        contentJson(ref)
        val value=(if(practice==null)content.read(scope,ref) else content.readPractice(scope,practice))?:return SourceResolution.Unavailable("SOURCE_UNAVAILABLE")
        if(!api.isCurrent(scope))return SourceResolution.Unavailable("SESSION_CHANGED")
        if(value.contentRef!=ref)return SourceResolution.Unavailable("SOURCE_REF_MISMATCH")
        if(!value.rightsCurrent)return SourceResolution.Unavailable("RIGHTS_UNAVAILABLE")
        if(!value.sourceCurrent)return SourceResolution.Unavailable("SOURCE_UNAVAILABLE")
        if(value.mainStatus!="ready")return SourceResolution.Unavailable(value.reason?:"MAIN_${value.mainStatus.uppercase(java.util.Locale.ROOT)}")
        if(practice!=null){practiceJson(practice)
            if(practiceContent(practice)!=ref || value.practiceRef!=practice)return SourceResolution.Unavailable("TASK_REF_MISMATCH")
            if(value.practiceStatus!="ready")return SourceResolution.Unavailable("PRACTICE_UNAVAILABLE")
        }
        if(requireNarration){
            val manifest=value.segmentManifest?:return SourceResolution.Unavailable("SEGMENT_MANIFEST_UNAVAILABLE")
            codecs.encode("source6","SegmentManifest",manifest,WireSource6SegmentManifest.serializer())
            val scriptId=when(ref){is WireSource6LegacyContentRef6->ref.legacyRef.listeningScriptId;is WireSource6PreparedContentRef6->ref.mainScriptId}
            if(manifest.scriptId!=scriptId || ref is WireSource6PreparedContentRef6 && manifest.scriptHash!=ref.mainTextHash)
                return SourceResolution.Unavailable("SEGMENT_MANIFEST_MISMATCH")
            val text=value.scriptText?:return SourceResolution.Unavailable("SCRIPT_UNAVAILABLE")
            if(LearningJson.hash(text)!=manifest.scriptHash)return SourceResolution.Unavailable("SCRIPT_HASH_MISMATCH")
            val capability=narration.current(manifest.language,purpose)
            if(!capability.available)return SourceResolution.Unavailable(capability.reason?:"PLEOS_TTS_UNAVAILABLE")
        }
        return SourceResolution.Ready(value)
    }
}
