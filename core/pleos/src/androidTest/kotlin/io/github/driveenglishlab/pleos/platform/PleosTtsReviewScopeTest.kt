package io.github.driveenglishlab.pleos.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Test-owned scope/identity/permission facts only. No actual Context, SDK instance, output,
 * permission, vehicle signal, source approval or operating review is created or claimed.
 * Nullable/default scope, private Gateway snapshots, binding-token admission and elapsed deadlines
 * belong to Gateway integration tests; these tests exercise only the public static/current policy. */
@RunWith(AndroidJUnit4::class)
class PleosTtsReviewScopeTest {
    private data class Fixture(val program: SegmentProgram, val run: SegmentRun, val text: String,
        val scope: PleosOnDeviceScope1, val identity: TtsRuntimeIdentity1,
        val fact: TtsCurrentScopeFact1, val binding: TtsRunReviewBinding1)

    private fun fixture(language: SegmentLanguage = SegmentLanguage.EN,
        purpose: SegmentPurpose = SegmentPurpose.MAIN): Fixture {
        val pieces = if (language == SegmentLanguage.EN) listOf("One 🚗. ", "Two.") else listOf("첫 문장. ", "다음.")
        val script = pieces.joinToString("")
        var start = 0
        val segments = pieces.mapIndexed { index, text ->
            val end = start + text.codePointCount(0, text.length)
            SpeechSegment(UUID.randomUUID(), index, UUID.randomUUID(), start, end, segmentSha256(text)).also { start = end }
        }
        val pending = SegmentManifest(UUID.randomUUID(), UUID.randomUUID(), segmentSha256(script), 1,
            "0".repeat(64), language, segments)
        val manifest = pending.copy(manifestHash = segmentSha256(pending.canonicalPayload()))
        val owner = OwnerContext(UUID.randomUUID(), 2, 3, UUID.randomUUID())
        val phase = if (purpose == SegmentPurpose.FOLLOWUP) SegmentPhase.FOLLOWUP else SegmentPhase.MAIN
        val program = SegmentProgram(owner, UUID.randomUUID(),
            SegmentSourceKey(if (purpose == SegmentPurpose.SETTINGS_PREVIEW) "settings_demo" else "prepared_components", "a".repeat(64)),
            manifest, script, phase, purpose)
        val run = SegmentRun(UUID.randomUUID(), 1, UUID.randomUUID(), owner, program.selectionId, program.source,
            phase, purpose, manifest.scriptId, manifest.scriptHash, manifest.manifestId, manifest.segmentsVersion,
            manifest.manifestHash, segments[0].segmentId, 0, segments[0].textHash, UUID.randomUUID())
        val identity = TtsRuntimeIdentity1("synthetic.fixture.tts", 11, "syntheticDebug", 34, "synthetic-environment-not-device")
        val limits = TtsOutputLimits1(pieces.maxOf { it.codePointCount(0, it.length) }, 2, 5_000, 7_000, 1_000, 250)
        val scope = PleosOnDeviceScope1("synthetic-review-not-approval", 2, TtsReviewState.REVIEWED,
            identity.applicationId, identity.versionCode, identity.variantId, "synthetic-build-not-evidence",
            "synthetic-environment-not-evidence", setOf(34), identity.environmentKey, setOf(purpose), setOf(language),
            setOf(manifest.manifestHash), true, true, limits)
        val access = AccessState(ownerValid = true, power = PowerAvailability.UNKNOWN,
            safety = SafetyState(VehiclePosition.PARKED, true, true, false, false, 13),
            window = WindowState(true, true, 11),
            components = ComponentPolicies(17, ComponentAccess(true, true, 17, 19), ComponentAccess(true, true, 17, 23)),
            mediaRouteAvailable = true) // No direct mic, Gleo grant or global focus is supplied.
        val fact = TtsCurrentScopeFact1(access, owner, true, true, TtsPermissionState.GRANTED, 29, 31, true, 37)
        val binding = TtsRunReviewBinding1(scope.reviewId, scope.revision, UUID.randomUUID(), run, language,
            run.segmentTextHash, fact.scopeGeneration, access.window.generation, access.components.generation,
            access.components.audio.permissionGeneration, access.components.media.permissionGeneration,
            fact.ttsPermissionGeneration, 1_000, 6_000)
        return Fixture(program, run, pieces[0], scope, identity, fact, binding)
    }

    private fun current(f: Fixture, fact: TtsCurrentScopeFact1 = f.fact,
        scope: PleosOnDeviceScope1 = f.scope, binding: TtsRunReviewBinding1 = f.binding,
        run: SegmentRun = f.run, requireFocus: Boolean = true) =
        PleosTtsScopePolicy.currentAllows(scope, run, fact, requireFocus, binding)

    @Test fun constructorsRejectInvalidScopeLimitsIdentityAndBindingInvariants() {
        val f = fixture()
        val invalid = listOf<() -> Any>(
            { f.scope.limits.copy(maxSegmentCodePoints = 0) }, { f.scope.limits.copy(maxSequentialSegments = 0) },
            { f.scope.limits.copy(initializationTimeoutMs = 999) }, { f.scope.limits.copy(initializationTimeoutMs = 60_001) },
            { f.scope.limits.copy(outputTimeoutMs = 0) }, { f.scope.limits.copy(stopCallbackWaitMs = 999) },
            { f.scope.limits.copy(permissionRefreshIntervalMs = 0) }, { f.scope.copy(scopeVersion = 2) },
            { f.scope.copy(revision = 0) }, { f.scope.copy(versionCode = 0) },
            { f.scope.copy(sdkCoordinate = "ai.pleos.playground:TextToSpeech:other") },
            { f.scope.copy(foregroundOnly = false) }, { f.scope.copy(vehicleRange = "driving") },
            { f.scope.copy(reviewId = " ") }, { f.scope.copy(buildEvidenceRef = "x".repeat(161)) },
            { f.scope.copy(allowedApiLevels = emptySet()) }, { f.scope.copy(allowedApiLevels = setOf(0)) },
            { f.scope.copy(allowedPurposes = emptySet()) }, { f.scope.copy(allowedLanguages = emptySet()) },
            { f.scope.copy(allowedManifestHashes = emptySet()) }, { f.scope.copy(allowedManifestHashes = setOf("A".repeat(64))) },
            { f.identity.copy(versionCode = 0) }, { f.identity.copy(apiLevel = 0) }, { f.identity.copy(environmentKey = "") },
            { f.fact.copy(ttsPermissionGeneration = -1) }, { f.fact.copy(scopeGeneration = -1) },
            { f.fact.copy(outputIntentGeneration = -1) }, { f.binding.copy(bindingVersion = 2) },
            { f.binding.copy(reviewRevision = 0) }, { f.binding.copy(windowGeneration = -1) },
            { f.binding.copy(initializationDeadlineElapsedMs = f.binding.createdElapsedMs) },
            { f.binding.copy(textHash = "f".repeat(64)) })
        invalid.forEachIndexed { index, build ->
            assertThrows("Invalid synthetic constructor case $index", IllegalArgumentException::class.java) { build() }
        }
    }

    @Test fun staticReviewRequiresReviewedExactBuildEnvironmentPurposeLanguageManifestAndLimits() {
        val f = fixture()
        fun allows(scope: PleosOnDeviceScope1 = f.scope, identity: TtsRuntimeIdentity1 = f.identity) =
            PleosTtsScopePolicy.staticAllows(scope, identity, f.program, f.run, f.text, SegmentLanguage.EN)
        assertTrue(allows())
        for (state in listOf(TtsReviewState.DRAFT, TtsReviewState.RETIRED)) assertFalse(allows(f.scope.copy(reviewState = state)))
        for (identity in listOf(f.identity.copy(applicationId = "other.package"), f.identity.copy(versionCode = 12),
            f.identity.copy(variantId = "syntheticRelease"), f.identity.copy(apiLevel = 35), f.identity.copy(environmentKey = "other-environment")))
            assertFalse(allows(identity = identity))
        for (scope in listOf(f.scope.copy(allowedApiLevels = setOf(35)),
            f.scope.copy(allowedPurposes = setOf(SegmentPurpose.FOLLOWUP)), f.scope.copy(allowedLanguages = setOf(SegmentLanguage.KO)),
            f.scope.copy(allowedManifestHashes = setOf("f".repeat(64))),
            f.scope.copy(limits = f.scope.limits.copy(maxSequentialSegments = 1)),
            f.scope.copy(limits = f.scope.limits.copy(maxSegmentCodePoints = f.text.codePointCount(0, f.text.length) - 1))))
            assertFalse(allows(scope))
        // The supplementary character counts as one code point, not two UTF-16 code units.
        assertTrue(f.text.length > f.scope.limits.maxSegmentCodePoints)
        assertTrue(allows())
        val ko = fixture(SegmentLanguage.KO, SegmentPurpose.FOLLOWUP)
        assertTrue(PleosTtsScopePolicy.staticAllows(ko.scope, ko.identity, ko.program, ko.run, ko.text, SegmentLanguage.KO))
    }

    @Test fun staticProgramRunAndExactAuthorizedSegmentSliceCannotBeSubstituted() {
        val f = fixture()
        val alteredRuns = listOf(f.run.copy(owner = f.run.owner.copy(privacyEpoch = 3)),
            f.run.copy(selectionId = UUID.randomUUID()), f.run.copy(source = f.run.source.copy(canonicalHash = "b".repeat(64))),
            f.run.copy(phase = SegmentPhase.FOLLOWUP), f.run.copy(purpose = SegmentPurpose.SENTENCE_HELP),
            f.run.copy(scriptId = UUID.randomUUID()), f.run.copy(scriptHash = "b".repeat(64)),
            f.run.copy(manifestId = UUID.randomUUID()), f.run.copy(manifestVersion = 2),
            f.run.copy(manifestHash = "c".repeat(64)), f.run.copy(segmentId = UUID.randomUUID()),
            f.run.copy(segmentIndex = 1), f.run.copy(segmentTextHash = "d".repeat(64)))
        for (run in alteredRuns)
            assertFalse(PleosTtsScopePolicy.staticAllows(f.scope, f.identity, f.program, run, f.text, SegmentLanguage.EN))
        assertFalse(PleosTtsScopePolicy.staticAllows(f.scope, f.identity, f.program, f.run, f.text.trimEnd(), SegmentLanguage.EN))
        val help = fixture(purpose = SegmentPurpose.SENTENCE_HELP)
        val secondOnly = help.program.copy(segmentIndices = listOf(1))
        assertFalse("A sentence-help program may not admit a different segment from its same manifest.",
            PleosTtsScopePolicy.staticAllows(help.scope, help.identity, secondOnly, help.run, help.text, SegmentLanguage.EN))
        val second = secondOnly.manifest.segments[1]
        val secondRun = help.run.copy(segmentIndex = 1, segmentId = second.segmentId, segmentTextHash = second.textHash)
        assertTrue(PleosTtsScopePolicy.staticAllows(help.scope, help.identity, secondOnly, secondRun,
            segmentText(secondOnly.script, second), SegmentLanguage.EN))
    }

    @Test fun currentScopeRejectsMissingOwnershipLeaseSourceAndEachRelevantVehiclePermission() {
        val f = fixture()
        assertTrue(current(f))
        val a = f.fact.access
        val denied = listOf(f.fact.copy(owner = null), f.fact.copy(owner = f.run.owner.copy(revision = 4)),
            f.fact.copy(owner = f.run.owner.copy(privacyEpoch = 3)), f.fact.copy(owner = f.run.owner.copy(presentationToken = UUID.randomUUID())),
            f.fact.copy(leaseCurrent = false), f.fact.copy(sourceCurrent = false),
            f.fact.copy(ttsPermission = TtsPermissionState.DENIED), f.fact.copy(ttsPermission = TtsPermissionState.UNKNOWN),
            f.fact.copy(access = a.copy(ownerValid = false)), f.fact.copy(access = a.copy(mediaRouteAvailable = false)),
            f.fact.copy(access = a.copy(power = PowerAvailability.SUSPENDING)), f.fact.copy(access = a.copy(power = PowerAvailability.SUSPENDED)),
            f.fact.copy(access = a.copy(window = a.window.copy(visible = false))),
            f.fact.copy(access = a.copy(window = a.window.copy(focused = false))),
            f.fact.copy(access = a.copy(safety = a.safety.copy(uxKnown = false))),
            f.fact.copy(access = a.copy(safety = a.safety.copy(detailsAllowed = false))),
            f.fact.copy(access = a.copy(safety = a.safety.copy(systemBlocked = true))),
            f.fact.copy(access = a.copy(components = a.components.copy(audio = a.components.audio.copy(known = false)))),
            f.fact.copy(access = a.copy(components = a.components.copy(audio = a.components.audio.copy(allowed = false)))),
            f.fact.copy(access = a.copy(components = a.components.copy(media = a.components.media.copy(known = false)))),
            f.fact.copy(access = a.copy(components = a.components.copy(media = a.components.media.copy(allowed = false)))),
            f.fact.copy(access = a.copy(components = a.components.copy(media = a.components.media.copy(sourceGeneration = 16)))))
        for (fact in denied) assertFalse(current(f, fact))
        for (position in listOf(VehiclePosition.DRIVE_STOPPED, VehiclePosition.MOVING_OR_REVERSE_NEUTRAL, VehiclePosition.UNKNOWN))
            assertFalse(current(f, f.fact.copy(access = a.copy(safety = a.safety.copy(position = position)))))
        for (state in listOf(TtsReviewState.DRAFT, TtsReviewState.RETIRED)) assertFalse(current(f, scope = f.scope.copy(reviewState = state)))
        assertTrue(current(f, f.fact.copy(focusCurrent = false), requireFocus = false))
        assertFalse(current(f, f.fact.copy(focusCurrent = false), requireFocus = true))
    }

    @Test fun originalReviewBindingRejectsChangedRunIdentityAndPermissionGenerations() {
        val f = fixture()
        assertTrue(current(f))
        val changedRuns = listOf(f.run.copy(runId = UUID.randomUUID()), f.run.copy(requestId = UUID.randomUUID()),
            f.run.copy(generation = 2), f.run.copy(outputLeaseId = UUID.randomUUID()),
            f.run.copy(selectionId = UUID.randomUUID()), f.run.copy(source = f.run.source.copy(canonicalHash = "b".repeat(64))),
            f.run.copy(scriptId = UUID.randomUUID()), f.run.copy(scriptHash = "c".repeat(64)),
            f.run.copy(manifestId = UUID.randomUUID()), f.run.copy(manifestVersion = 2), f.run.copy(manifestHash = "d".repeat(64)),
            f.run.copy(segmentId = UUID.randomUUID()), f.run.copy(segmentIndex = 1), f.run.copy(segmentTextHash = "e".repeat(64)),
            f.run.copy(phase = SegmentPhase.FOLLOWUP), f.run.copy(purpose = SegmentPurpose.FOLLOWUP))
        for (run in changedRuns) assertFalse(current(f, run = run))
        for (binding in listOf(f.binding.copy(reviewId = "other-review"), f.binding.copy(reviewRevision = 3),
            f.binding.copy(scopeGeneration = 32), f.binding.copy(windowGeneration = 12), f.binding.copy(policyGeneration = 18),
            f.binding.copy(audioPermissionGeneration = 20), f.binding.copy(mediaPermissionGeneration = 24),
            f.binding.copy(ttsPermissionGeneration = 30))) assertFalse(current(f, binding = binding))
        assertFalse(current(f, f.fact.copy(scopeGeneration = 32)))
        assertFalse(current(f, f.fact.copy(ttsPermissionGeneration = 30)))
        assertFalse(current(f, f.fact.copy(access = f.fact.access.copy(window = f.fact.access.window.copy(generation = 12)))))
    }

    @Test fun unrelatedMicrophoneAndNewerPolicySnapshotsCannotStandInForRelevantMediaGrants() {
        val f = fixture()
        val a = f.fact.access
        assertEquals(PowerAvailability.UNKNOWN, a.power)
        assertFalse(a.canCapture)
        assertFalse(a.mediaFocusAllowed)
        assertTrue(current(f)) // Actual-scope facts here are synthetic; no global focus/provider is substituted.
        val next = a.components.copy(generation = 18, audio = a.components.audio.copy(sourceGeneration = 18),
            media = a.components.media.copy(sourceGeneration = 18), microphone = ComponentAccess(true, false, 18, 99))
        assertTrue(current(f, f.fact.copy(access = a.copy(components = next))))
        assertFalse(current(f, f.fact.copy(access = a.copy(components = next.copy(audio = next.audio.copy(permissionGeneration = 20))))))
        assertFalse(current(f, f.fact.copy(access = a.copy(components = next.copy(media = next.media.copy(permissionGeneration = 24))))))
        val lower = next.copy(generation = 16, audio = next.audio.copy(sourceGeneration = 16), media = next.media.copy(sourceGeneration = 16))
        assertFalse(current(f, f.fact.copy(access = a.copy(components = lower))))
        val unfocused = f.fact.copy(access = a.copy(window = a.window.copy(focused = false)))
        val reviewedUnfocusedScope = f.scope.copy(revision = 3, windowFocusRequired = false)
        assertTrue(current(f, unfocused, reviewedUnfocusedScope, f.binding.copy(reviewRevision = 3)))
        assertFalse(current(f, unfocused.copy(access = unfocused.access.copy(window = unfocused.access.window.copy(visible = false))),
            reviewedUnfocusedScope, f.binding.copy(reviewRevision = 3)))
    }
}
