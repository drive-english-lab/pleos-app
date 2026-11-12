package io.github.driveenglishlab.pleos.platform

import android.content.Context
import android.content.Intent
import io.github.driveenglishlab.pleos.domain.*
import org.json.JSONArray
import org.json.JSONObject

/** Supply only after project/environment verification; no package/permission is guessed. */
data class VerifiedGleoCallbackRoute(val targetPackage: String, val receiverPermission: String?, val evidenceRef: String) {
    init { require(targetPackage.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))); require(evidenceRef.isNotBlank()) }
}

/** Official callback envelope, independently gated from input/output speech capabilities. */
class GleoTransport(private val context: Context, private val route: VerifiedGleoCallbackRoute? = null,
    private val approvedFunctions: Set<String> = emptySet(), override val capabilities: VoiceCapabilities = VoiceCapabilities()) : GleoTransportPort {
    private val functions = setOf("DRIVE_ENGLISH_PLAYBACK", "DRIVE_ENGLISH_ARTICLE_QUESTION", "DRIVE_ENGLISH_PRACTICE")
    init {
        require(approvedFunctions.all { it in functions }); require(approvedFunctions.isEmpty() || route != null)
        require(route != null || capabilities == VoiceCapabilities())
    }

    override fun callback(value: GleoCallback): GleoDelivery {
        val route = route ?: return GleoDelivery.Unavailable("project_callback_route_not_verified")
        if (value.functionName !in approvedFunctions || value.status !in setOf("success", "asking", "fail") ||
            value.mode !in setOf("voice", "beep", "silent") || value.message.isBlank()) return GleoDelivery.Unavailable("invalid_callback")
        val response = JSONObject().put("functionName", value.functionName).put("status", value.status).put("message", value.message).put("mode", value.mode)
        if (value.data != null) {
            try {
                val data = JSONArray(value.data)
                if ((0 until data.length()).any { data.get(it) !is JSONObject }) return GleoDelivery.Unavailable("invalid_callback_data")
                response.put("data", value.data)
            } catch (_: Exception) { return GleoDelivery.Unavailable("invalid_callback_data") }
        }
        if (response.toString().toByteArray(Charsets.UTF_8).size > 16 * 1024) return GleoDelivery.Unavailable("callback_too_large")
        return try {
            val intent = Intent("ai.pleos.playground.intent.action.APP_ACTION_RESPONSE").setPackage(route.targetPackage)
                .putExtra("packageName", context.packageName).putExtra("response", response.toString())
            context.sendBroadcast(intent, route.receiverPermission)
            // This records transmission only. It never emits output-end/listening/exposure events.
            GleoDelivery.CallbackSent
        } catch (_: Exception) { GleoDelivery.Unavailable("callback_unavailable") }
    }
}
