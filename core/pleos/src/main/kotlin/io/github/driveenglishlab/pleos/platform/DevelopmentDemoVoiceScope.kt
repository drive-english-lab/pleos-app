package io.github.driveenglishlab.pleos.platform

import io.github.driveenglishlab.pleos.domain.SegmentLanguage
import io.github.driveenglishlab.pleos.domain.SegmentPurpose

/** D32 permits a tool-reviewed fixed development demonstration; it is not external entitlement,
 * deployment approval, neutral-probe-to-product success, or general news/language authorization.
 * Actual owner/source rights/P/UX/window/OS grant/focus/run/DONE/cleanup remain Gateway checks.
 */
object DevelopmentDemoVoiceScope {
    private data class TechnicalReview(
        val runtime:TtsRuntimeIdentity1,
        val manifestHash:String,
        val maximumSegmentCodePoints:Int,
        val segmentCount:Int,
        val limits:TtsOutputLimits1,
    )

    // Exact automatic source/segment review and current public build identity are recorded in
    // development/docs/verification/development-demo-20261115/tts-review.json. Final installed APK
    // and actual product output are separately checked by Root; neutral output is not that result.
    private val review=TechnicalReview(
        runtime=TtsRuntimeIdentity1("io.github.driveenglishlab.pleos",1,"debug",34,
            "android-build:b966d2b4b1dceeb884af6e8f2841ddb6a97971c46e58515a54a373f2f0398910"),
        manifestHash="f0f32c878e1c247c9337402a54c719c0b17bfcee2f339a3f563438107847e590",
        maximumSegmentCodePoints=479,segmentCount=12,
        // Fail-closed technical bounds for these twelve original segments, not measured latency
        // guarantees: 479cp maximum, 10s initialization, 60s per output, original 1s stop wait.
        limits=TtsOutputLimits1(479,12,10_000,60_000,1_000,250),
    )

    fun approval(runtime:TtsRuntimeIdentity1):PleosTtsProjectApproval? {
        val actual=review
        if(runtime.variantId!="debug" || runtime!=actual.runtime)return null
        val scope=PleosOnDeviceScope1(
            reviewId="development-demo-20261115-tts",revision=1,reviewState=TtsReviewState.REVIEWED,
            applicationId=actual.runtime.applicationId,versionCode=actual.runtime.versionCode,variantId=actual.runtime.variantId,
            buildEvidenceRef="development-demo-20261115:tts:build",environmentEvidenceRef="development-demo-20261115:tts:environment",
            allowedApiLevels=setOf(actual.runtime.apiLevel),environmentKey=actual.runtime.environmentKey,
            allowedPurposes=setOf(SegmentPurpose.MAIN),allowedLanguages=setOf(SegmentLanguage.EN),
            allowedManifestHashes=setOf(actual.manifestHash),foregroundOnly=true,windowFocusRequired=true,
            limits=actual.limits.copy(maxSegmentCodePoints=actual.maximumSegmentCodePoints,maxSequentialSegments=actual.segmentCount),
        )
        return PleosTtsProjectApproval("3.3.1",PleosTtsMode.ON_DEVICE,
            projectPermissionEvidence="development-demo-20261115:tts:os-grant-and-technical-scope",
            instanceCorrelationEvidence="development-demo-20261115:tts:original-instance",
            stopOrderingEvidence="development-demo-20261115:tts:stop-cleanup",
            languageEvidence=mapOf(SegmentLanguage.EN to "development-demo-20261115:tts:english-neutral-observation"),
            vehicleMediaEvidence="development-demo-20261115:tts:current-vehicle-required",onDeviceScope=scope)
    }
}
