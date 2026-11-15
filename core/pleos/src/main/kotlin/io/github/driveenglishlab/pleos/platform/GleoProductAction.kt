package io.github.driveenglishlab.pleos.platform

import io.github.driveenglishlab.pleos.domain.GleoCallback
import io.github.driveenglishlab.pleos.domain.GleoTransportPort
import android.util.JsonReader
import android.util.JsonToken
import java.io.StringReader

/** String tool parameters do not establish original speech language or literal fidelity. */
class GleoProductAction(val function: String, val parameters: Map<String, String>) {
    override fun toString() = "GleoProductAction(parameters=redacted)"
}

/** Only a verified project adapter can supply this port. Broadcast sender identity and the
 * observed ACTION_PARAM representation are separate prerequisites from App Schema approval.
 */
interface GleoProductRuntime {
    val callback: GleoTransportPort
    fun acceptsSender(uid: Int, packageName: String?): Boolean
    val actionParamJsonVerified: Boolean
    suspend fun handle(action: GleoProductAction): GleoCallback
}
interface GleoProductRuntimeProvider { val gleoProduct: GleoProductRuntime? }

object GleoProductActionParser {
    val functions = setOf("DRIVE_ENGLISH_PLAYBACK", "DRIVE_ENGLISH_ARTICLE_QUESTION", "DRIVE_ENGLISH_PRACTICE")
    val pendingChoiceCommands=setOf("pending_original","pending_wait","pending_alternative")
    fun parse(packageName: String, incomingAction: String?, parameters: String?): GleoProductAction? = try {
        val name = incomingAction?.removePrefix("$packageName.")
        require(name in functions && parameters != null && parameters.toByteArray(Charsets.UTF_8).size <= 16_384)
        // The supported envelope is deliberately flat. Reject coercion, duplicates, nested data,
        // trailing data and a parameter shape not covered by the observed adapter contract.
        val values = linkedMapOf<String, String>()
        JsonReader(StringReader(parameters)).use { input ->
            input.isLenient = false
            input.beginObject()
            while (input.hasNext()) {
                val key = input.nextName()
                require(key !in values && input.peek() == JsonToken.STRING)
                val value = input.nextString()
                require(value.isNotBlank() && value.codePointCount(0, value.length) <= 4_000)
                values[key] = value
            }
            input.endObject()
            require(input.peek() == JsonToken.END_DOCUMENT)
        }
        require(values["articleTitle"]?.codePointCount(0, values["articleTitle"]!!.length)?.let { it <= 500 } != false)
        val allowed = when (name) {
            "DRIVE_ENGLISH_PLAYBACK" -> setOf("command", "articleTitle", "mode")
            "DRIVE_ENGLISH_ARTICLE_QUESTION" -> setOf("questionText", "articleTitle")
            else -> setOf("command", "utteranceText", "articleTitle")
        }
        require(values.keys.all { it in allowed })
        when (name) {
            "DRIVE_ENGLISH_PLAYBACK" -> {
                require(values["command"] in setOf("start", "pause", "resume", "next", "stop", "change_format", "listen_only_today", "default_interaction")+pendingChoiceCommands)
                require(values["mode"] == null || values["mode"] in setOf("original_full", "briefing"))
                require(values["command"] != "change_format" || values["mode"] != null)
                if(values["command"] in pendingChoiceCommands)require(values.keys==setOf("command"))
            }
            "DRIVE_ENGLISH_ARTICLE_QUESTION" -> require(values["questionText"] != null)
            else -> {
                require(values["command"] in setOf("start", "answer", "hint", "explain_question", "explain_facts", "sentence_starter", "partial_correction", "complete_example", "retry", "review_assessment", "read_assessment", "finish", "resume") + GleoLearningPreferenceCommand.wires)
                require(values["command"] != "answer" || values["utteranceText"] != null)
                if(values["command"] in GleoLearningPreferenceCommand.wires)require(values.keys==setOf("command"))
            }
        }
        GleoProductAction(requireNotNull(name), values.toMap())
    } catch (_: Exception) { null }
}
