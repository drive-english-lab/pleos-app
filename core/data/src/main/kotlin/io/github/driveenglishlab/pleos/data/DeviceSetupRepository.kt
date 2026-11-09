package io.github.driveenglishlab.pleos.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.driveenglishlab.pleos.domain.ProbeStatus
import io.github.driveenglishlab.pleos.domain.SetupRepository
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.isSuccess
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.setupPreferences by preferencesDataStore(name = "setup")
private val lastCheckedAtKey = longPreferencesKey("last_checked_at")

@Singleton
internal class DeviceSetupRepository @Inject constructor(
    @ApplicationContext context: Context,
) : SetupRepository {
    private val preferences = context.setupPreferences

    private val supabase by lazy {
        if (BuildConfig.SUPABASE_URL.isBlank()) {
            null
        } else {
            createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY) {
                install(Postgrest)
            }
        }
    }

    override val lastCheckedAt = preferences.data
        .catch { error ->
            if (error !is IOException) throw error
            emit(emptyPreferences())
        }
        .map { it[lastCheckedAtKey] }

    override suspend fun checkSupabase(): ProbeStatus = try {
        val client = supabase
        if (client == null) {
            ProbeStatus.NOT_CONFIGURED
        } else {
            // The gateway check works with a publishable key before tables or auth flows exist.
            val response = client.httpClient.get("${client.supabaseHttpUrl}/auth/v1/settings")
            if (response.status.isSuccess()) ProbeStatus.READY else ProbeStatus.FAILED
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: HttpRequestTimeoutException) {
        ProbeStatus.TIMED_OUT
    } catch (_: Exception) {
        ProbeStatus.FAILED
    }

    override suspend fun saveCheckTime(epochMillis: Long) {
        preferences.edit { it[lastCheckedAtKey] = epochMillis }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface SetupDataModule {
    @Binds
    fun bindSetupRepository(repository: DeviceSetupRepository): SetupRepository
}
