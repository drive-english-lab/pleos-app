package io.github.driveenglishlab.pleos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject

class GleoProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "DRIVE_ENGLISH_CHECK_SETUP") return

        val response = JSONObject()
            .put("functionName", intent.action)
            .put("status", "success")
            .put("message", "The development build received the setup action.")
            .put("mode", "silent")

        context.sendBroadcast(
            Intent("ai.pleos.playground.intent.action.APP_ACTION_RESPONSE")
                .putExtra("packageName", context.packageName)
                .putExtra("response", response.toString()),
        )
        Log.i("GleoProbe", "Setup action received and callback broadcast sent")
    }
}
