package io.github.driveenglishlab.pleos.domain

import kotlinx.coroutines.flow.Flow

enum class ProbeStatus {
    READY,
    NOT_CONFIGURED,
    UNSUPPORTED,
    PERMISSION_REQUIRED,
    TIMED_OUT,
    FAILED,
}

interface SetupRepository {
    val lastCheckedAt: Flow<Long?>

    suspend fun checkSupabase(): ProbeStatus
    suspend fun saveCheckTime(epochMillis: Long)
}
