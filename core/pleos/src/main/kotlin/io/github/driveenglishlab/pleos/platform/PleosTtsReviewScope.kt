package io.github.driveenglishlab.pleos.platform

import android.content.Context
import android.os.Build
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID

/** Reviewed limits are supplied by actual review data, never inferred from a successful probe. */
data class TtsOutputLimits1(val maxSegmentCodePoints:Int,val maxSequentialSegments:Int,val initializationTimeoutMs:Long,
    val outputTimeoutMs:Long,val stopCallbackWaitMs:Long,val permissionRefreshIntervalMs:Long) {
    init { require(maxSegmentCodePoints>0 && maxSequentialSegments>0 && initializationTimeoutMs in 1_000..60_000 &&
        outputTimeoutMs>0 && stopCallbackWaitMs==1_000L && permissionRefreshIntervalMs>0) }
}
enum class TtsReviewState { DRAFT, REVIEWED, RETIRED }
data class PleosOnDeviceScope1(val reviewId:String,val revision:Long,val reviewState:TtsReviewState,
    val applicationId:String,val versionCode:Long,val variantId:String,val buildEvidenceRef:String,val environmentEvidenceRef:String,
    val allowedApiLevels:Set<Int>,val environmentKey:String,val allowedPurposes:Set<SegmentPurpose>,val allowedLanguages:Set<SegmentLanguage>,
    val allowedManifestHashes:Set<String>,val foregroundOnly:Boolean,val windowFocusRequired:Boolean,val limits:TtsOutputLimits1,
    val scopeVersion:Int=1,val sdkCoordinate:String="ai.pleos.playground:TextToSpeech:3.3.1",val vehicleRange:String="parked_known_uxr") {
    init {
        require(scopeVersion==1 && revision>0 && versionCode>0 && sdkCoordinate=="ai.pleos.playground:TextToSpeech:3.3.1" && foregroundOnly && vehicleRange=="parked_known_uxr")
        listOf(reviewId,applicationId,variantId,buildEvidenceRef,environmentEvidenceRef,environmentKey).forEach(::ttsScopeReference)
        require(allowedApiLevels.isNotEmpty() && allowedApiLevels.all{it>0} && allowedPurposes.isNotEmpty() && allowedLanguages.isNotEmpty() && allowedManifestHashes.isNotEmpty())
        require(allowedManifestHashes.all{it.matches(Regex("[a-f0-9]{64}"))})
    }
}
data class TtsRuntimeIdentity1(val applicationId:String,val versionCode:Long,val variantId:String,val apiLevel:Int,val environmentKey:String) {
    init { require(versionCode>0 && apiLevel>0);listOf(applicationId,variantId,environmentKey).forEach(::ttsScopeReference) }
    companion object {
        /** Public build facts identify the reviewed environment; this is not an entitlement/vehicle grant. */
        @Suppress("DEPRECATION") fun actual(context:Context,variantId:String):TtsRuntimeIdentity1 {
            val info=context.packageManager.getPackageInfo(context.packageName,0)
            val version=if(Build.VERSION.SDK_INT>=28)info.longVersionCode else info.versionCode.toLong()
            return TtsRuntimeIdentity1(context.packageName,version,variantId,Build.VERSION.SDK_INT,
                "android-build:"+segmentSha256("${Build.FINGERPRINT}\u0000${Build.HARDWARE}\u0000${Build.MODEL}"))
        }
    }
}
enum class TtsPermissionState { GRANTED, DENIED, UNKNOWN }
data class TtsCurrentScopeFact1(val access:AccessState,val owner:OwnerContext?,val leaseCurrent:Boolean,val sourceCurrent:Boolean,
    val ttsPermission:TtsPermissionState,val ttsPermissionGeneration:Long,val scopeGeneration:Long,val focusCurrent:Boolean,
    val outputIntentGeneration:Long) {
    init { require(ttsPermissionGeneration>=0 && scopeGeneration>=0 && outputIntentGeneration>=0) }
}
data class TtsRunReviewBinding1(val reviewId:String,val reviewRevision:Long,val bindingToken:UUID,val run:SegmentRun,
    val language:SegmentLanguage,val textHash:String,val scopeGeneration:Long,val windowGeneration:Long,val policyGeneration:Long,
    val audioPermissionGeneration:Long,val mediaPermissionGeneration:Long,val ttsPermissionGeneration:Long,
    val createdElapsedMs:Long,val initializationDeadlineElapsedMs:Long,val bindingVersion:Int=1) {
    init { ttsScopeReference(reviewId);require(bindingVersion==1 && reviewRevision>0 && listOf(scopeGeneration,windowGeneration,policyGeneration,audioPermissionGeneration,mediaPermissionGeneration,ttsPermissionGeneration,createdElapsedMs).all{it>=0} && initializationDeadlineElapsedMs>createdElapsedMs);require(textHash==run.segmentTextHash) }
}
enum class TtsCleanupStatus { NOT_APPLICABLE, NOT_ATTEMPTED, SUCCEEDED, FAILED, UNCONFIRMED }
data class TtsOutputObservation1(val bindingToken:UUID,val runId:UUID,val requestId:UUID,val instanceId:UUID,val scopeGeneration:Long,
    val nativeDoneObserved:Boolean,val doneScopeValid:Boolean,val forwardedKind:SegmentSpeechEventKind?,
    val listenerRemoval:TtsCleanupStatus,val release:TtsCleanupStatus,val focusReturn:TtsCleanupStatus,
    val initializationSettled:Boolean,val stopObserved:Boolean,val quiescent:Boolean,val reasonCode:String?,val observationVersion:Int=1) {
    init { require(observationVersion==1 && scopeGeneration>=0);reasonCode?.let(::ttsScopeReference)
        require(forwardedKind!=SegmentSpeechEventKind.COMPLETED || nativeDoneObserved && doneScopeValid) }
}

object PleosTtsScopePolicy {
    fun staticAllows(scope:PleosOnDeviceScope1,identity:TtsRuntimeIdentity1,program:SegmentProgram,run:SegmentRun,text:String,language:SegmentLanguage):Boolean =
        scope.reviewState==TtsReviewState.REVIEWED && scope.applicationId==identity.applicationId && scope.versionCode==identity.versionCode &&
            scope.variantId==identity.variantId && identity.apiLevel in scope.allowedApiLevels && scope.environmentKey==identity.environmentKey &&
            run.segmentIndex in program.segmentIndices && run.purpose in scope.allowedPurposes && language in scope.allowedLanguages && run.manifestHash in scope.allowedManifestHashes &&
            program.manifest.manifestHash==run.manifestHash && program.manifest.scriptHash==run.scriptHash && program.owner==run.owner &&
            program.selectionId==run.selectionId && program.source==run.source && program.phase==run.phase && program.purpose==run.purpose &&
            program.manifest.language==language && program.manifest.scriptId==run.scriptId && program.manifest.manifestId==run.manifestId &&
            program.manifest.segmentsVersion==run.manifestVersion && program.manifest.segments.size<=scope.limits.maxSequentialSegments &&
            program.manifest.segments.getOrNull(run.segmentIndex)?.let{it.segmentId==run.segmentId && it.textHash==run.segmentTextHash && segmentText(program.script,it)==text}==true &&
            text.codePointCount(0,text.length)<=scope.limits.maxSegmentCodePoints && segmentSha256(text)==run.segmentTextHash
    fun currentAllows(scope:PleosOnDeviceScope1,run:SegmentRun,fact:TtsCurrentScopeFact1,requireFocus:Boolean,binding:TtsRunReviewBinding1?=null):Boolean {
        val a=fact.access
        if(scope.reviewState!=TtsReviewState.REVIEWED || fact.owner!=run.owner || !fact.leaseCurrent || !fact.sourceCurrent ||
            fact.ttsPermission!=TtsPermissionState.GRANTED || !a.mediaReady || a.suspending || a.safety.position!=VehiclePosition.PARKED ||
            !a.safety.uxKnown || !a.safety.detailsAllowed || a.safety.systemBlocked || !a.window.visible || scope.windowFocusRequired && !a.window.focused ||
            requireFocus && !fact.focusCurrent)return false
        if(binding!=null && (binding.run!=run || binding.reviewId!=scope.reviewId || binding.reviewRevision!=scope.revision ||
                binding.scopeGeneration!=fact.scopeGeneration || binding.windowGeneration!=a.window.generation ||
                a.components.generation<binding.policyGeneration || binding.audioPermissionGeneration!=a.components.audio.permissionGeneration ||
                binding.mediaPermissionGeneration!=a.components.media.permissionGeneration || binding.ttsPermissionGeneration!=fact.ttsPermissionGeneration))return false
        return true
    }
}
private fun ttsScopeReference(value:String){require(value.isNotBlank() && value.codePointCount(0,value.length) in 1..160)}
