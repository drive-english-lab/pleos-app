package io.github.driveenglishlab.pleos.data

import android.content.Context
import io.github.driveenglishlab.pleos.data.wire.WireSource6CommonSettings
import io.github.driveenglishlab.pleos.domain.LearningBand
import io.github.driveenglishlab.pleos.domain.OwnerContext
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

enum class OwnerDraftField { TOPICS, LISTENING_MODE, FOLLOWUP_POLICY, BAND, APPEARANCE }
enum class OwnerDraftState { EDITING, PENDING, CONFLICT, PRESERVED }
enum class OwnerDraftLeaveState { NONE, CONFIRM_DISCARD, FORCED_CLOSED }

/** Ordinary editable values only. No lesson, selection, return point, audio, or preview authority. */
data class OwnerSettingsDraftSnapshot(
    val originalSettingsJson:String,
    val topics:Set<String>,
    val listeningMode:String,
    val followupPolicy:String?,
    val originalBand:LearningBand,
    val band:LearningBand,
    val originalAppearance:String,
    val appearance:String,
    val dirtyFields:Set<OwnerDraftField>,
    val localRevision:Long,
    val state:OwnerDraftState=OwnerDraftState.EDITING,
    val leaveState:OwnerDraftLeaveState=OwnerDraftLeaveState.NONE,
)
data class OwnerSettingsDraftRecord(val owner:OwnerContext,val storageRevision:Long,val snapshot:OwnerSettingsDraftSnapshot)
class OwnerSettingsDraftException(val code:String):IllegalArgumentException(code)

/**
 * A typed envelope in the existing settings_draft row, separate from CommonSettings-only drafts.
 * Restore is data-only. An epoch change may rebind this same profile's ordinary values, but may
 * never restore a preview callback or implicitly reopen/apply settings.
 */
class OwnerSettingsDraftStore(context:Context,private val reviewDao:ReviewDao) {
    private val codecs=IncrementalContractJson(context.applicationContext)
    private val lane=Mutex()
    private val json=Json { ignoreUnknownKeys=false;isLenient=false;coerceInputValues=false;explicitNulls=true;encodeDefaults=true }

    suspend fun load(owner:OwnerContext):OwnerSettingsDraftRecord?=lane.withLock {
        val rows=reviewDao.ownerDraftRows(owner)
        val latest=rows.firstNotNullOfOrNull { row -> decode(row)?.let { row to it } } ?: return@withLock null
        val (source,snapshot)=latest
        val target=rows.firstOrNull{it.privacyEpoch==owner.privacyEpoch}
        if(target!=null && decode(target)==null)fail("OTHER_DRAFT_KIND")
        if(source.privacyEpoch==owner.privacyEpoch && source.ownerRevision==owner.revision &&
            source.previewToken==null && source.savedOperationId==null) {
            return@withLock OwnerSettingsDraftRecord(owner,source.revision,snapshot)
        }
        // This is an ordinary-values rebind, not recovery of an old epoch's execution authority.
        val revision=increment(maxOf(source.revision,target?.revision?:0))
        val value=row(owner,revision,snapshot)
        reviewDao.writeOwnerDraft(owner,target,value,source)
        OwnerSettingsDraftRecord(owner,revision,snapshot)
    }

    suspend fun save(owner:OwnerContext,expectedRev:Long?,snapshot:OwnerSettingsDraftSnapshot):OwnerSettingsDraftRecord=lane.withLock {
        val original=validate(snapshot)
        val rows=reviewDao.ownerDraftRows(owner)
        val target=rows.firstOrNull{it.privacyEpoch==owner.privacyEpoch}
        val previous=target?.let{decode(it)?:fail("OTHER_DRAFT_KIND")}
        if(expectedRev==null) {
            if(target!=null || rows.any{decode(it)!=null})fail("DRAFT_REVISION_REQUIRED")
        } else if(target==null || target.ownerRevision!=owner.revision || target.revision!=expectedRev)fail("DRAFT_CONFLICT")
        if(target!=null && previous!=null && (snapshot.localRevision<previous.localRevision ||
            original.version<target.baseSettingsVersion))fail("DRAFT_CONFLICT")
        val revision=increment(target?.revision?:0)
        reviewDao.writeOwnerDraft(owner,target,row(owner,revision,snapshot))
        OwnerSettingsDraftRecord(owner,revision,snapshot)
    }

    /** Explicit discard removes this envelope only, for this profile, across preserved epochs. */
    suspend fun discard(owner:OwnerContext):Boolean=lane.withLock {
        val rows=reviewDao.ownerDraftRows(owner)
        val owned=rows.filter{decode(it)!=null}.map{it.privacyEpoch}.toSet()
        reviewDao.discardOwnerDraftRows(owner,rows,owned)
    }

    private fun row(owner:OwnerContext,revision:Long,snapshot:OwnerSettingsDraftSnapshot):LocalSettingsDraft {
        val original=validate(snapshot)
        val envelope=Envelope(kind=KIND,version=1,originalSettingsJson=snapshot.originalSettingsJson,
            topics=snapshot.topics.sorted(),listeningMode=snapshot.listeningMode,followupPolicy=snapshot.followupPolicy,
            originalBand=wire(snapshot.originalBand),band=wire(snapshot.band),originalAppearance=snapshot.originalAppearance,
            appearance=snapshot.appearance,dirtyFields=snapshot.dirtyFields.map(::wire).sorted(),localRevision=snapshot.localRevision,
            state=wire(snapshot.state),leaveState=wire(snapshot.leaveState))
        return LocalSettingsDraft(owner.profileId.toString(),owner.privacyEpoch,owner.revision,revision,original.version,
            snapshot.originalSettingsJson,json.encodeToString(Envelope.serializer(),envelope),null,null)
    }

    private fun decode(row:LocalSettingsDraft):OwnerSettingsDraftSnapshot?=checked {
        // Do not reinterpret old CommonSettings-only records or envelopes owned by another feature.
        val element=json.parseToJsonElement(row.draftJson) as? JsonObject ?: fail("INVALID_DRAFT")
        val marker=element["kind"] ?: return@checked null
        if(marker !is JsonPrimitive || !marker.isString)fail("INVALID_DRAFT")
        if(marker.content!=KIND)return@checked null
        val value=json.decodeFromJsonElement(Envelope.serializer(),element)
        if(value.version!=1 || row.revision<1 || row.ownerRevision<0 || row.privacyEpoch<0)fail("INVALID_DRAFT")
        if(value.topics.distinct().size!=value.topics.size || value.dirtyFields.distinct().size!=value.dirtyFields.size)fail("INVALID_DRAFT")
        val snapshot=OwnerSettingsDraftSnapshot(value.originalSettingsJson,value.topics.toSet(),value.listeningMode,
            value.followupPolicy,enum(value.originalBand),enum(value.band),value.originalAppearance,value.appearance,
            value.dirtyFields.map{enum<OwnerDraftField>(it)}.toSet(),value.localRevision,enum(value.state),enum(value.leaveState))
        val original=validate(snapshot)
        if(row.originalJson!=snapshot.originalSettingsJson || row.baseSettingsVersion!=original.version)fail("INVALID_DRAFT")
        snapshot
    }

    private fun validate(value:OwnerSettingsDraftSnapshot):WireSource6CommonSettings=checked {
        val original=codecs.decodeSource6("CommonSettings",value.originalSettingsJson,WireSource6CommonSettings.serializer())
        if(value.localRevision<1 || value.topics.size>20 || value.topics.any{it.isBlank() || it.codePointCount(0,it.length)>80} ||
            value.listeningMode !in setOf("original_full","briefing") ||
            value.followupPolicy !in setOf(null,"none","en_summary","ko_summary","ko_translation") ||
            value.originalAppearance !in APPEARANCES || value.appearance !in APPEARANCES)fail("INVALID_DRAFT")
        val changed=buildSet {
            if(value.topics!=original.topics.toSet())add(OwnerDraftField.TOPICS)
            if(value.listeningMode!=original.listeningMode)add(OwnerDraftField.LISTENING_MODE)
            if(value.followupPolicy!=original.followupPolicy)add(OwnerDraftField.FOLLOWUP_POLICY)
            if(value.band!=value.originalBand)add(OwnerDraftField.BAND)
            if(value.appearance!=value.originalAppearance)add(OwnerDraftField.APPEARANCE)
        }
        if(!value.dirtyFields.containsAll(changed))fail("INVALID_DIRTY_FIELDS")
        original
    }

    private inline fun <T> checked(block:()->T):T=try { block() } catch(e:CancellationException) { throw e }
        catch(e:OwnerSettingsDraftException) { throw e } catch(_:Exception) { fail("INVALID_DRAFT") }
    private inline fun <reified T:Enum<T>> enum(value:String):T=enumValues<T>().firstOrNull{wire(it)==value}?:fail("INVALID_DRAFT")
    private fun wire(value:Enum<*>)=value.name.lowercase(Locale.ROOT)
    private fun increment(value:Long):Long { if(value==Long.MAX_VALUE)fail("DRAFT_REVISION_EXHAUSTED");return value+1 }
    private fun fail(code:String):Nothing=throw OwnerSettingsDraftException(code)

    @Serializable private data class Envelope(
        val kind:String,val version:Int,
        @SerialName("original_settings_json")val originalSettingsJson:String,
        val topics:List<String>,
        @SerialName("listening_mode")val listeningMode:String,
        @SerialName("followup_policy")val followupPolicy:String?,
        @SerialName("original_band")val originalBand:String,val band:String,
        @SerialName("original_appearance")val originalAppearance:String,val appearance:String,
        @SerialName("dirty_fields")val dirtyFields:List<String>,
        @SerialName("local_revision")val localRevision:Long,val state:String,
        @SerialName("leave_state")val leaveState:String,
    )
    private companion object {
        const val KIND="owner_settings_draft_v1"
        val APPEARANCES=setOf("system","light","dark")
    }
}
