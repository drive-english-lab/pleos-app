package io.github.driveenglishlab.pleos.platform

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.driveenglishlab.pleos.domain.ProbeStatus
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

data class PleosProbeResult(
    val vehicle: ProbeStatus,
    val navigation: ProbeStatus,
    val locationSupport: ProbeStatus,
)

private const val CAR_DOORS = "pleos.car.permission.CAR_DOORS"
private const val SDK_TIMEOUT_MS = 8_000L

class PleosProbe @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    suspend fun check(): PleosProbeResult = coroutineScope {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            val unsupported = ProbeStatus.UNSUPPORTED
            return@coroutineScope PleosProbeResult(unsupported, unsupported, unsupported)
        }

        val vehicle = async { runProbe { checkVehicle() } }
        val navigation = async { runProbe { checkNavigation() } }
        val locationSupport = runProbe {
            if (FusedLocation(context).isSupported()) ProbeStatus.READY else ProbeStatus.UNSUPPORTED
        }
        PleosProbeResult(vehicle.await(), navigation.await(), locationSupport)
    }

    private suspend fun checkVehicle(): ProbeStatus {
        if (context.checkSelfPermission(CAR_DOORS) != PackageManager.PERMISSION_GRANTED) {
            return ProbeStatus.PERMISSION_REQUIRED
        }
        val vehicle = Vehicle(context)
        try {
            vehicle.initialize()
            val result = CompletableDeferred<ProbeStatus>()
            vehicle.getDoor().checkDoorPositionCapabilities(
                onComplete = { config, _ ->
                    val readable = config.accessType == AccessType.READ ||
                        config.accessType == AccessType.READ_WRITE
                    result.complete(if (readable) ProbeStatus.READY else ProbeStatus.UNSUPPORTED)
                },
                onFailed = { error -> result.complete(failureStatus(error)) },
            )
            return withTimeoutOrNull(SDK_TIMEOUT_MS) { result.await() } ?: ProbeStatus.TIMED_OUT
        } finally {
            vehicle.release()
        }
    }

    private suspend fun checkNavigation(): ProbeStatus {
        val naviHelper = NaviHelper(context)
        try {
            val result = CompletableDeferred<ProbeStatus>()
            naviHelper.initialize(
                onConnected = { result.complete(ProbeStatus.READY) },
                onFailed = { error -> result.complete(failureStatus(error)) },
            )
            return withTimeoutOrNull(SDK_TIMEOUT_MS) { result.await() } ?: ProbeStatus.TIMED_OUT
        } finally {
            naviHelper.release()
        }
    }

    private suspend fun runProbe(check: suspend () -> ProbeStatus): ProbeStatus = try {
        check()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        failureStatus(error)
    }

    private fun failureStatus(error: Throwable): ProbeStatus =
        if (error is SecurityException) ProbeStatus.PERMISSION_REQUIRED else ProbeStatus.FAILED
}
