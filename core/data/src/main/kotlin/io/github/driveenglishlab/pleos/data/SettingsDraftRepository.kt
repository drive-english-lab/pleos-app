package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.data.wire.*
import java.util.UUID

/** Settings edits and demo preview tokens never mutate acknowledged preferences/resume. */
class SettingsDraftRepository(private val dao:ReviewDao,private val api:IncrementalLearningApi,private val codecs:IncrementalContractJson) {
    suspend fun open(scope:LearningCallScope,version:Long,value:WireSource6CommonSettings):LocalSettingsDraft {
        require(api.isCurrent(scope) && version>=1)
        val json=codecs.encode("source6","CommonSettings",value,WireSource6CommonSettings.serializer())
        return dao.openDraft(scope.owner,LocalSettingsDraft(scope.owner.profileId.toString(),scope.owner.privacyEpoch,scope.owner.revision,
            1,version,json,json,null,null))
    }
    suspend fun edit(scope:LearningCallScope,expectedRevision:Long,value:WireSource6CommonSettings):LocalSettingsDraft {
        require(api.isCurrent(scope))
        val json=codecs.encode("source6","CommonSettings",value,WireSource6CommonSettings.serializer())
        // Any edit invalidates the previous preview callback, without accepting its late result.
        return dao.changeDraft(scope.owner,expectedRevision,json,null,null)
    }
    suspend fun beginPreview(scope:LearningCallScope,expectedRevision:Long):LocalSettingsDraft {
        require(api.isCurrent(scope));val value=dao.draft(scope.owner.profileId.toString(),scope.owner.privacyEpoch)?:error("Missing draft.")
        require(value.revision==expectedRevision && value.previewToken==null)
        return dao.changeDraft(scope.owner,expectedRevision,value.draftJson,UUID.randomUUID().toString(),null)
    }
    suspend fun previewIsCurrent(scope:LearningCallScope,revision:Long,token:String):Boolean {
        if(!api.isCurrent(scope))return false
        val value=dao.draft(scope.owner.profileId.toString(),scope.owner.privacyEpoch)?:return false
        return value.ownerRevision==scope.owner.revision && value.revision==revision && value.previewToken==token
    }
    suspend fun endPreview(scope:LearningCallScope,revision:Long,token:String):LocalSettingsDraft {
        require(previewIsCurrent(scope,revision,token));val value=dao.draft(scope.owner.profileId.toString(),scope.owner.privacyEpoch)!!
        return dao.changeDraft(scope.owner,revision,value.draftJson,null,value.savedOperationId)
    }
    /** Call only with the actual settings ACK; draft cancellation never writes an inverse patch. */
    suspend fun saved(scope:LearningCallScope,revision:Long,operationId:UUID,receipt:LearningReply.Stored<WirePersonalProfileView>) {
        require(api.isCurrent(scope));val value=dao.draft(scope.owner.profileId.toString(),scope.owner.privacyEpoch)?:error("Missing draft.")
        require(receipt.scope==scope && receipt.value.profileContext.profileId==scope.owner.profileId.toString() && receipt.value.profileContext.privacyEpoch==scope.owner.privacyEpoch)
        val confirmed=codecs.decodeSource6("CommonSettings",learningJson.encodeToString(WireCommonSettings.serializer(),receipt.value.settings),WireSource6CommonSettings.serializer())
        val json=codecs.encode("source6","CommonSettings",confirmed,WireSource6CommonSettings.serializer())
        require(value.revision==revision && receipt.value.version>value.baseSettingsVersion && json==value.draftJson)
        dao.changeDraft(scope.owner,revision,json,null,operationId.toString())
    }
    suspend fun close(scope:LearningCallScope,revision:Long){require(api.isCurrent(scope));dao.closeDraft(scope.owner,revision)}
}
