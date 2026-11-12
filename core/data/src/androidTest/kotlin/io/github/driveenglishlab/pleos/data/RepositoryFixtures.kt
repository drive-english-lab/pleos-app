package io.github.driveenglishlab.pleos.data

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*

internal fun contractFixture(label: String, predicate: (JsonObject) -> Boolean = { true }): JsonObject {
    val context = InstrumentationRegistry.getInstrumentation().context
    val cases = context.assets.open("learning-contract-cases.json").bufferedReader().use { learningJson.parseToJsonElement(it.readText()).jsonArray }
    return cases.map { it.jsonObject }.first {
        it.getValue("valid").jsonPrimitive.boolean && it.getValue("label").jsonPrimitive.content == label && predicate(it.getValue("payload").jsonObject)
    }.getValue("payload").jsonObject
}
internal fun JsonObject.changed(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(toMutableMap().apply { putAll(pairs) })
internal fun jsonResponse(value: JsonObject, status: Int = 200) = LearningHttpResponse(status,
    mapOf("Content-Type" to "application/json"), value.toString().toByteArray(Charsets.UTF_8))
