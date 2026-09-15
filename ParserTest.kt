import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

data class FunctionCall(
    @SerializedName("name") val name: String = "",
    @SerializedName("arguments") val arguments: Map<String, Any?> = emptyMap()
)

data class Validation(
    @SerializedName("ungrounded") val ungrounded: List<String> = emptyList(),
    @SerializedName("negation") val negation: Boolean = false
)

data class NeedleResponse(
    @SerializedName("type") val type: String? = null,
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("error") val error: String? = null,
    @SerializedName("error_code") val errorCode: String? = null,
    @SerializedName("function_calls") val functionCalls: List<FunctionCall>? = null,
    @SerializedName("reason") val reason: String? = null,
    @SerializedName("reasoning") val reasoning: String? = null,
    @SerializedName("confidence") val confidence: Double = 0.0,
    @SerializedName("prefill_tps") val prefillTps: Double = 0.0,
    @SerializedName("decode_tps") val decodeTps: Double = 0.0,
    @SerializedName("peak_ram_mb") val peakRamMb: Double = 0.0,
    @SerializedName("validation") val validation: Validation? = null,
    val rawJson: String = "",
    val parseError: String? = null
) {
    val effectiveReasoning: String
        get() = if (reasoning?.isNotBlank() == true) reasoning!! else (reason ?: "")

    val typeNonNull: String = type ?: ""
    val functionCallsNonNull: List<FunctionCall> = functionCalls ?: emptyList()
    
    val confidenceFloat: Float = confidence.toFloat()
    val prefillTpsFloat: Float = prefillTps.toFloat()
    val decodeTpsFloat: Float = decodeTps.toFloat()
    val peakRamMbInt: Int = peakRamMb.toInt()
}

fun main() {
    val gson = Gson()
    
    val json1 = """{"type":"call","success":true,"error":null,"error_code":null,"reason":null,"function_calls":[{"name":"device.flashlight_on","arguments":{}}],"reasoning":null,"confidence":1.0000,"prefill_tps":66.5,"decode_tps":21.6,"peak_ram_mb":286.4,"validation":{"ungrounded":[],"negation":false}}"""
    
    val json2 = """{"type":"respond","success":true,"error":null,"error_code":null,"reason":null,"function_calls":[],"reasoning":"User asked for flashlight on; respond with the result.","confidence":0.5068,"prefill_tps":48.3,"decode_tps":78.6,"peak_ram_mb":290.5}"""
    
    println("=== Test 1: Call response ===")
    val r1 = gson.fromJson(json1, NeedleResponse::class.java)
    println("type: '${r1.type}'")
    println("typeNonNull: '${r1.typeNonNull}'")
    println("success: ${r1.success}")
    println("confidence: ${r1.confidence}")
    println("confidenceFloat: ${r1.confidenceFloat}")
    println("functionCalls: ${r1.functionCalls}")
    println("functionCallsNonNull: ${r1.functionCallsNonNull}")
    println("functionCallsNonNull.size: ${r1.functionCallsNonNull.size}")
    if (r1.functionCallsNonNull.isNotEmpty()) {
        println("  name: '${r1.functionCallsNonNull[0].name}'")
        println("  arguments: '${r1.functionCallsNonNull[0].arguments}'")
    }
    println("reasoning: '${r1.reasoning}'")
    println("effectiveReasoning: '${r1.effectiveReasoning}'")
    println("validation: ${r1.validation}")
    
    println("\n=== Test 2: Respond response ===")
    val r2 = gson.fromJson(json2, NeedleResponse::class.java)
    println("type: '${r2.type}'")
    println("typeNonNull: '${r2.typeNonNull}'")
    println("success: ${r2.success}")
    println("confidence: ${r2.confidence}")
    println("confidenceFloat: ${r2.confidenceFloat}")
    println("functionCalls: ${r2.functionCalls}")
    println("functionCallsNonNull: ${r2.functionCallsNonNull}")
    println("functionCallsNonNull.size: ${r2.functionCallsNonNull.size}")
    println("reasoning: '${r2.reasoning}'")
    println("effectiveReasoning: '${r2.effectiveReasoning}'")
    println("validation: ${r2.validation}")
}
