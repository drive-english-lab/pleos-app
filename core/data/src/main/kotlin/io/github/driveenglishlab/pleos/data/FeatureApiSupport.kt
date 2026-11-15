package io.github.driveenglishlab.pleos.data

import android.content.Context
import io.github.driveenglishlab.pleos.data.wire.featureWireSerializers
import io.github.driveenglishlab.pleos.domain.LearningSessionSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.plus

internal val featureApiDocuments = setOf("learning-progress.openapi.json", "ai-learning.openapi.json")
internal val featureContractDocuments = listOf("common.schema.json", "personal-data.openapi.json", "inference.openapi.json",
    "learning-progress.schema.json", "learning-progress.openapi.json", "ai-learning.schema.json", "ai-learning.openapi.json")
internal val featureJson = Json(learningJson) {
    serializersModule = learningJson.serializersModule + featureWireSerializers
}

internal fun featureClient(context: Context, origin: String, sessions: LearningSessionSource, allowLocalHttp: Boolean): LearningApi =
    LearningApi(sessions, LearningContractJson(context.applicationContext, "feature-contracts", featureContractDocuments),
        KtorLearningTransport(origin, allowLocalHttp), featureJson)
