package io.github.driveenglishlab.pleos.data

import android.content.Context
import io.github.driveenglishlab.pleos.data.wire.*
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.plus

/** Independent contract1/source6 codecs. Existing schema5/feature1 loaders are unchanged. */
class IncrementalContractJson(context: Context) {
    private val r7 by lazy { R7ContractJson(context.applicationContext) }
    private val loaders = listOf("review", "preparation", "source6").associateWith { bundle ->
        LearningContractJson(context, "$bundle-contracts", listOf("$bundle.schema.json", "$bundle.openapi.json"))
    }
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        explicitNulls = true
        encodeDefaults = false
        serializersModule = reviewWireSerializers + preparationWireSerializers + source6WireSerializers
    }

    fun parse(bundle: String, source: String): JsonElement = if(bundle in R7ContractJson.bundles)r7.parse(bundle,source) else loader(bundle).parse(source)

    fun validateDefinition(bundle: String, name: String, value: JsonElement) {
        if(bundle in R7ContractJson.bundles){r7.validateDefinition(bundle,name,value);return}
        val codec = loader(bundle)
        val file = "$bundle.schema.json"
        codec.validate(file, codec.schema(file, "#/\$defs/$name"), value)
        if(bundle=="preparation") {
            val root=value as? JsonObject
            val projection=if(name=="CategoryPublicProjection")root else if(name=="CatalogResponse")root?.get("category_config")?.jsonObject else null
            projection?.get("categories")?.jsonArray?.let { categories ->
                if(categories.map{it.jsonObject.getValue("display_order").jsonPrimitive.long}.toSet()!=setOf(0L,1L,2L,3L,4L,5L))throw LearningProtocolException()
            }
            if(name=="ArticleClassification" && root!=null) {
                val primary=root.getValue("primary_category_id")
                if(primary!==JsonNull && primary in root.getValue("secondary_category_ids").jsonArray)throw LearningProtocolException()
            }
        }
    }

    fun <T> decode(bundle: String, name: String, source: String, serializer: DeserializationStrategy<T>): T {
        if(bundle in R7ContractJson.bundles)return r7.decode(bundle,name,source,serializer)
        val value = parse(bundle, source)
        validateDefinition(bundle, name, value)
        return json.decodeFromJsonElement(serializer, value)
    }

    fun <T> encode(bundle: String, name: String, value: T, serializer: SerializationStrategy<T>): String {
        if(bundle in R7ContractJson.bundles)return r7.encode(bundle,name,value,serializer)
        val element = json.encodeToJsonElement(serializer, value)
        validateDefinition(bundle, name, element)
        return element.toString()
    }

    fun <T> decodeReview(name: String, source: String, serializer: DeserializationStrategy<T>): T =
        decode("review", name, source, serializer)
    fun <T> decodePreparation(name: String, source: String, serializer: DeserializationStrategy<T>): T =
        decode("preparation", name, source, serializer)
    fun <T> decodeSource6(name: String, source: String, serializer: DeserializationStrategy<T>): T =
        decode("source6", name, source, serializer)
    fun decodeSource6Content(source: String): WireSource6ContentRef6 =
        decodeSource6("ContentRef6", source, WireSource6ContentRef6.serializer())
    fun decodeSource6Practice(source: String): WireSource6PracticeContentRef6 =
        decodeSource6("PracticeContentRef6", source, WireSource6PracticeContentRef6.serializer())

    fun operation(bundle: String, path: String, method: String): JsonObject =
        if(bundle in R7ContractJson.bundles)r7.operation(bundle,path,method) else loader(bundle).operation("$bundle.openapi.json", path, method)
    fun dereference(bundle: String, file: String, value: JsonObject): Pair<String, JsonObject> =
        if(bundle in R7ContractJson.bundles)r7.dereference(bundle,file,value) else loader(bundle).dereference(file, value)
    fun validate(bundle: String, file: String, schema: JsonObject, value: JsonElement) =
        if(bundle in R7ContractJson.bundles)r7.validate(bundle,file,schema,value) else loader(bundle).validate(file, schema, value)
    fun validateReviewNextTransition(previous:JsonObject,current:JsonObject):Boolean=r7.nextTransition(previous,current)
    fun validateReviewNext3Choice(request:JsonObject,result:JsonObject)=r7.next3Choice(request,result)
    fun validateNextObservation(query:JsonObject,operationId:String,result:JsonObject)=r7.nextObservation(query,operationId,result)
    fun validateAssessmentLookup(query:JsonObject,result:JsonObject,targetId:String)=r7.assessmentLookup(query,result,targetId)

    private fun loader(bundle: String): LearningContractJson = loaders[bundle] ?: throw LearningProtocolException()
}
