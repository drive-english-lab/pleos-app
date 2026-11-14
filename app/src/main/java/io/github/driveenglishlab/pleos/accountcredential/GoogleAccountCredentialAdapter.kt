package io.github.driveenglishlab.pleos.accountcredential

import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.content.pm.PackageManager
import android.util.Base64
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import io.github.driveenglishlab.pleos.BuildConfig
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed interface GoogleCredentialReadiness {
    data object Ready : GoogleCredentialReadiness
    data class Unavailable(val code: String) : GoogleCredentialReadiness
}

sealed interface GoogleCredentialResult {
    /** Provider credential only. Supabase must still verify signature, nonce and target identity. */
    class Credential internal constructor(val idToken: String, val nonce: String) : GoogleCredentialResult {
        override fun toString() = "GoogleCredentialResult.Credential(credentials=redacted)"
    }
    data object Cancelled : GoogleCredentialResult
    data class Unavailable(val code: String) : GoogleCredentialResult
    data class Failed(val code: String) : GoogleCredentialResult
}

internal data class GoogleCredentialConfiguration(val authOrigin: String, val publishableKey: String, val webClientId: String) {
    override fun toString() = "GoogleCredentialConfiguration(values=redacted)"
}
internal enum class GoogleProviderSetting { ENABLED, DISABLED, UNKNOWN }

/** Explicit account-picker path only. No session creation, owner mutation, merge or automatic login. */
class GoogleAccountCredentialAdapter internal constructor(
    private val config: GoogleCredentialConfiguration,
    private val readProvider: suspend () -> GoogleProviderSetting,
    private val clientAvailable: () -> Boolean,
    private val fetchCredential: suspend (Activity, GetCredentialRequest) -> String,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    constructor(context: Context) : this(
        config = GoogleCredentialConfiguration(io.github.driveenglishlab.pleos.data.BuildConfig.SUPABASE_URL,
            io.github.driveenglishlab.pleos.data.BuildConfig.SUPABASE_PUBLISHABLE_KEY, BuildConfig.GOOGLE_WEB_CLIENT_ID),
        readProvider = {
            readPublicGoogleSetting(io.github.driveenglishlab.pleos.data.BuildConfig.SUPABASE_URL,
                io.github.driveenglishlab.pleos.data.BuildConfig.SUPABASE_PUBLISHABLE_KEY)
        },
        clientAvailable = {
            try {
                @Suppress("DEPRECATION")
                val installed = context.applicationContext.packageManager.getApplicationInfo("com.google.android.gms", 0)
                installed.enabled
            } catch (_: PackageManager.NameNotFoundException) { false }
        },
        fetchCredential = { activity, request ->
            val result = CredentialManager.create(activity.applicationContext)
                .getCredential(context = MutableContextWrapper(activity), request = request)
            val credential = result.credential as? CustomCredential ?: throw InvalidGoogleCredential()
            if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) throw InvalidGoogleCredential()
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        },
    )

    private val lane = Mutex()

    /** Fresh public settings GET. This does not open account UI or prove device login support. */
    suspend fun readiness(): GoogleCredentialReadiness {
        if (!configuredAuth(config.authOrigin, config.publishableKey))
            return GoogleCredentialReadiness.Unavailable("AUTH_PROJECT_NOT_CONFIGURED")
        val provider = try { readProvider() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { GoogleProviderSetting.UNKNOWN }
        if (provider == GoogleProviderSetting.DISABLED) return GoogleCredentialReadiness.Unavailable("GOOGLE_PROVIDER_DISABLED")
        if (provider != GoogleProviderSetting.ENABLED) return GoogleCredentialReadiness.Unavailable("AUTH_SETTINGS_UNAVAILABLE")
        if (config.webClientId.isBlank()) return GoogleCredentialReadiness.Unavailable("GOOGLE_WEB_CLIENT_ID_MISSING")
        if (!config.webClientId.matches(CLIENT_ID)) return GoogleCredentialReadiness.Unavailable("GOOGLE_WEB_CLIENT_ID_INVALID")
        val installed = try { clientAvailable() } catch (_: Exception) { false }
        if (!installed) return GoogleCredentialReadiness.Unavailable("GOOGLE_CREDENTIAL_CLIENT_UNAVAILABLE")
        return GoogleCredentialReadiness.Ready
    }

    /** Call only from an explicit permitted foreground action. Host also fences owner/window/power. */
    suspend fun request(activity: Activity): GoogleCredentialResult {
        if (!lane.tryLock()) return GoogleCredentialResult.Unavailable("GOOGLE_CREDENTIAL_REQUEST_BUSY")
        try {
            val available = readiness()
            if (available is GoogleCredentialReadiness.Unavailable) return GoogleCredentialResult.Unavailable(available.code)
            if (activity.isFinishing || activity.isDestroyed) return GoogleCredentialResult.Unavailable("ACCOUNT_ACTIVITY_UNAVAILABLE")
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val nonce = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            val hashedNonce = sha256(nonce)
            // Only the explicit host action requests the account chooser. Disable automatic
            // selection even when the device has one previously authorized account. The
            // separate GetSignInWithGoogleOption hard-codes autoSelectAllowed=true in 1.2.1.
            // Supabase receives raw nonce, Google receives its SHA-256 hexadecimal digest.
            val option = GetGoogleIdOption.Builder().setServerClientId(config.webClientId)
                .setFilterByAuthorizedAccounts(false).setAutoSelectEnabled(false).setNonce(hashedNonce).build()
            require(!option.isAutoSelectAllowed && !option.autoSelectEnabled && !option.filterByAuthorizedAccounts)
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val token = withContext(Dispatchers.Main.immediate) { fetchCredential(activity, request) }
            if (activity.isFinishing || activity.isDestroyed) return GoogleCredentialResult.Failed("ACCOUNT_CONTEXT_CHANGED")
            // Local checks bind this response to this attempt. They never verify signature or
            // authorize profile adoption: that remains the existing Supabase/server path.
            if (!matchesAttempt(token, hashedNonce, config.webClientId, nowEpochSeconds()))
                return GoogleCredentialResult.Failed("GOOGLE_CREDENTIAL_INVALID")
            return GoogleCredentialResult.Credential(token, nonce)
        } catch (_: GetCredentialCancellationException) {
            return GoogleCredentialResult.Cancelled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: NoCredentialException) {
            return GoogleCredentialResult.Unavailable("GOOGLE_ACCOUNT_CREDENTIAL_UNAVAILABLE")
        } catch (_: GetCredentialProviderConfigurationException) {
            return GoogleCredentialResult.Unavailable("GOOGLE_CREDENTIAL_PROVIDER_UNAVAILABLE")
        } catch (_: GetCredentialUnsupportedException) {
            return GoogleCredentialResult.Unavailable("GOOGLE_CREDENTIAL_CLIENT_UNSUPPORTED")
        } catch (_: GetCredentialException) {
            return GoogleCredentialResult.Failed("GOOGLE_CREDENTIAL_REQUEST_FAILED")
        } catch (_: Exception) {
            // Never expose provider exceptions, ID tokens, nonce, email or account identifiers.
            return GoogleCredentialResult.Failed("GOOGLE_CREDENTIAL_INVALID")
        } finally {
            lane.unlock()
        }
    }

    companion object {
        private val CLIENT_ID = Regex("[0-9]+-[A-Za-z0-9_-]+\\.apps\\.googleusercontent\\.com")
        internal fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        internal fun matchesAttempt(token: String, nonceHash: String, audience: String, now: Long): Boolean = try {
            require(now >= 0 && token.length in 1..32_768)
            val parts = token.split('.')
            require(parts.size == 3 && parts.all { it.isNotEmpty() && it.matches(Regex("[A-Za-z0-9_-]+")) })
            val bytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val payload = JSONObject(Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString())
            require(payload.get("nonce") is String && payload.getString("nonce") == nonceHash)
            require(payload.get("aud") is String && payload.getString("aud") == audience)
            require(payload.getString("iss") in setOf("accounts.google.com", "https://accounts.google.com"))
            val expiry = payload.get("exp")
            require((expiry is Long || expiry is Int) && payload.getLong("exp") > now)
            true
        } catch (_: Exception) { false }
    }
}

private class InvalidGoogleCredential : IllegalArgumentException()

private fun configuredAuth(origin: String, key: String): Boolean = try {
    val uri = URI(origin)
    uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null &&
        uri.fragment == null && uri.path.isNullOrEmpty() && uri.port == -1 && key.matches(Regex("sb_publishable_[A-Za-z0-9_-]+"))
} catch (_: Exception) { false }

/** Publishable-key-only GET; never changes Auth configuration or sends a user's session/token. */
private suspend fun readPublicGoogleSetting(origin: String, publishableKey: String): GoogleProviderSetting = withContext(Dispatchers.IO) {
    if (!configuredAuth(origin, publishableKey)) return@withContext GoogleProviderSetting.UNKNOWN
    val connection = URI("$origin/auth/v1/settings").toURL().openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "GET"; connection.instanceFollowRedirects = false
        connection.connectTimeout = 4_000; connection.readTimeout = 4_000
        connection.setRequestProperty("apikey", publishableKey)
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Cache-Control", "no-store")
        if (connection.responseCode != 200 || connection.contentType?.substringBefore(';')?.trim() != "application/json")
            return@withContext GoogleProviderSetting.UNKNOWN
        val output = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 65_536)
                output.write(buffer, 0, count)
            }
        }
        val json = JSONObject(Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(output.toByteArray())).toString())
        when (json.getJSONObject("external").get("google")) {
            true -> GoogleProviderSetting.ENABLED
            false -> GoogleProviderSetting.DISABLED
            else -> GoogleProviderSetting.UNKNOWN
        }
    } finally { connection.disconnect() }
}
