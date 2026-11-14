package io.github.driveenglishlab.pleos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.driveenglishlab.pleos.domain.GleoCallback
import io.github.driveenglishlab.pleos.platform.GleoProductActionParser
import io.github.driveenglishlab.pleos.platform.GleoProductRuntimeProvider
import kotlinx.coroutines.*

/** Receiving a broadcast is not approval, microphone access or original-English evidence. */
class GleoActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val runtime = (context.applicationContext as? GleoProductRuntimeProvider)?.gleoProduct ?: return
        // Do not trust caller-supplied package/UID extras. The official sending path must expose
        // verified Android sender identity; older/unshared identity stays unavailable.
        if (Build.VERSION.SDK_INT < 34 || sentFromUid < 0 || !runtime.acceptsSender(sentFromUid, sentFromPackage) ||
            !runtime.actionParamJsonVerified) return
        val function = intent.action?.removePrefix("${context.packageName}.")
            ?.takeIf { it in GleoProductActionParser.functions } ?: return
        val action = try { GleoProductActionParser.parse(context.packageName, intent.action, intent.getStringExtra("ACTION_PARAM")) }
            catch (_: Exception) { null }
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                // Below the documented seven-second action window; a callback is transmission only.
                val callback = if (action == null) GleoCallback(function, "asking", "The action parameters are missing or invalid.", null, "voice")
                    else withTimeoutOrNull(5_500) { runtime.handle(action) }
                if (callback != null && callback.functionName == function) runtime.callback.callback(callback)
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) {
                // Callback transport failures must not crash a vehicle process or log user speech.
            } finally { pending.finish(); scope.cancel() }
        }
    }
}
