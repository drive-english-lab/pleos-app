package io.github.driveenglishlab.pleos.accountcredential

import android.app.Activity
import android.util.Base64
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic transport/claim fixtures only. No Google account UI, live token or Auth mutation. */
@RunWith(AndroidJUnit4::class)
class GoogleAccountCredentialAdapterTest {
    private val client = "12345-synthetic.apps.googleusercontent.com"
    private val config = GoogleCredentialConfiguration("https://synthetic-project.invalid", "sb_publishable_synthetic", client)
    private var provider = GoogleProviderSetting.ENABLED
    private var available = true
    private var gets = 0
    private var requests = 0
    private fun activity(): Activity {
        // Activity creates its Handler on construction, even without launching a real UI.
        lateinit var result: Activity
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = Activity() }
        return result
    }
    private fun adapter(configuration: GoogleCredentialConfiguration = config,
        fetch: suspend (Activity, GetCredentialRequest) -> String = { _, request ->
            val option = request.credentialOptions.single() as GetGoogleIdOption
            assertEquals(client, option.serverClientId); assertFalse(option.isAutoSelectAllowed)
            assertFalse(option.autoSelectEnabled); assertFalse(option.filterByAuthorizedAccounts)
            token(requireNotNull(option.nonce))
        }) = GoogleAccountCredentialAdapter(configuration, { gets++; provider }, { available },
            { activity, request -> requests++; fetch(activity, request) }, { 1_000 })
    private fun token(nonce: String, audience: String = client, expiry: Long = 2_000): String {
        fun base64(value: String) = Base64.encodeToString(value.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val payload = JSONObject().put("nonce", nonce).put("aud", audience).put("iss", "https://accounts.google.com").put("exp", expiry)
        return base64("{\"alg\":\"RS256\"}") + "." + base64(payload.toString()) + "." + base64("synthetic-unverified-signature")
    }
    private fun unavailable(result: GoogleCredentialReadiness, code: String) {
        assertEquals(code, (result as GoogleCredentialReadiness.Unavailable).code)
    }

    @Test fun disabledProviderMissingConfigAndUnknownSettingsDoNotOpenAccountUi() = runBlocking {
        provider = GoogleProviderSetting.DISABLED
        val disabled = adapter()
        unavailable(disabled.readiness(), "GOOGLE_PROVIDER_DISABLED")
        assertEquals("GOOGLE_PROVIDER_DISABLED", (disabled.request(activity()) as GoogleCredentialResult.Unavailable).code)
        provider = GoogleProviderSetting.UNKNOWN
        unavailable(adapter().readiness(), "AUTH_SETTINGS_UNAVAILABLE")
        provider = GoogleProviderSetting.ENABLED
        unavailable(adapter(config.copy(webClientId = "")).readiness(), "GOOGLE_WEB_CLIENT_ID_MISSING")
        unavailable(adapter(config.copy(webClientId = "not-a-client")).readiness(), "GOOGLE_WEB_CLIENT_ID_INVALID")
        unavailable(adapter(config.copy(authOrigin = "http://synthetic-project.invalid")).readiness(), "AUTH_PROJECT_NOT_CONFIGURED")
        assertEquals(0, requests)
    }
    @Test fun settingsAreFreshAndReadinessNeverRequestsCredentials() = runBlocking {
        val adapter = adapter()
        assertEquals(GoogleCredentialReadiness.Ready, adapter.readiness())
        provider = GoogleProviderSetting.DISABLED
        unavailable(adapter.readiness(), "GOOGLE_PROVIDER_DISABLED")
        assertEquals(2, gets); assertEquals(0, requests)
    }
    @Test fun clientUnavailableDoesNotAttemptProviderUi() = runBlocking {
        available = false
        val adapter = adapter()
        unavailable(adapter.readiness(), "GOOGLE_CREDENTIAL_CLIENT_UNAVAILABLE")
        assertTrue(adapter.request(activity()) is GoogleCredentialResult.Unavailable)
        assertEquals(0, requests)
    }
    @Test fun explicitSelectionReturnsRawNonceMatchingHashedGoogleRequestAndRedactsCredential() = runBlocking {
        var googleNonce: String? = null
        val result = adapter { _, request ->
            val option = request.credentialOptions.single() as GetGoogleIdOption
            assertFalse(option.isAutoSelectAllowed)
            assertFalse(option.autoSelectEnabled); assertFalse(option.filterByAuthorizedAccounts)
            googleNonce = option.nonce
            token(requireNotNull(googleNonce))
        }.request(activity()) as GoogleCredentialResult.Credential
        assertEquals(googleNonce, GoogleAccountCredentialAdapter.sha256(result.nonce))
        assertNotEquals(googleNonce, result.nonce)
        assertFalse(result.toString().contains(result.idToken)); assertFalse(result.toString().contains(result.nonce))
        assertEquals(1, requests)
    }
    @Test fun wrongNonceAudienceOrExpiredTokenCannotBecomeCredential() = runBlocking {
        for (failure in listOf("nonce", "audience", "expiry")) {
            val before = requests
            val result = adapter { _, request ->
                val option = request.credentialOptions.single() as GetGoogleIdOption
                assertFalse(option.isAutoSelectAllowed); assertFalse(option.autoSelectEnabled)
                val nonce = requireNotNull(option.nonce)
                when (failure) {
                    "nonce" -> token("different-attempt")
                    "audience" -> token(nonce, "other-client")
                    else -> token(nonce, expiry = 1_000)
                }
            }.request(activity())
            assertEquals("The invalid-token fixture must reach the actual provider port", before + 1, requests)
            assertEquals("GOOGLE_CREDENTIAL_INVALID", (result as GoogleCredentialResult.Failed).code)
        }
    }
    @Test fun providerCancelAndUnavailableAreTypedAndContainNoProviderException() = runBlocking {
        assertEquals(GoogleCredentialResult.Cancelled, adapter { _, _ -> throw GetCredentialCancellationException("synthetic-private-detail") }.request(activity()))
        for ((error, code) in listOf(
            NoCredentialException("synthetic-private-detail") to "GOOGLE_ACCOUNT_CREDENTIAL_UNAVAILABLE",
            GetCredentialProviderConfigurationException("synthetic-private-detail") to "GOOGLE_CREDENTIAL_PROVIDER_UNAVAILABLE",
            GetCredentialUnsupportedException("synthetic-private-detail") to "GOOGLE_CREDENTIAL_CLIENT_UNSUPPORTED",
        )) {
            val result = adapter { _, _ -> throw error }.request(activity())
            assertEquals(code, (result as GoogleCredentialResult.Unavailable).code)
            assertFalse(result.toString().contains("synthetic-private-detail"))
        }
    }
    @Test fun concurrentRequestIsBlockedAndCancelledHostDoesNotBecomeUserSuccess() = runBlocking {
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val adapter = adapter { _, _ -> started.complete(Unit); finish.await(); error("Cancelled request must not finish") }
        val first = async { adapter.request(activity()) }
        try {
            withTimeout(5_000) {
                select<Unit> {
                    started.onAwait { }
                    first.onAwait { result -> error("Request ended before reaching provider: ${(result as? GoogleCredentialResult.Failed)?.code}") }
                }
                assertEquals(1, requests)
                assertEquals("GOOGLE_CREDENTIAL_REQUEST_BUSY", (adapter.request(activity()) as GoogleCredentialResult.Unavailable).code)
                first.cancel(CancellationException("synthetic-host-left"))
                try { first.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
                assertEquals(1, requests)
            }
        } finally {
            first.cancel(); first.join()
        }
    }
}
