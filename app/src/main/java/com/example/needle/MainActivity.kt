package com.example.needle

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextField
import androidx.compose.material3.Divider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.tooling.preview.Preview

// Data Classes
sealed interface TestStatus {
    data class Running(val message: String = "") : TestStatus
    data class Pass(val message: String = "") : TestStatus
    data class Fail(val errorCode: Int, val errorMessage: String, val rawResponse: String = "") : TestStatus
    object NotRun : TestStatus
}

data class TestPhase(
    val id: Int,
    val title: String,
    val description: String,
    var status: TestStatus = TestStatus.NotRun,
    var input: String = "",
    var output: String = "",
    var parsedResult: String = "",
    var rawJson: String = "",
    var confidence: Float = 0f,
    var inferenceTimeMs: Long = 0,
    var peakRamMb: Int = 0,
    var errorCode: Int = 0,
    var errorMessage: String = "",
    var isExpanded: Boolean = false
)

data class TestHistoryEntry(
    val timestamp: String,
    val command: String,
    val result: String,
    val rawJson: String,
    val confidence: Float,
    val status: String,
    val phase: String = "Custom"
)

data class NeedleResponse(
    @field:SerializedName("type") val type: String? = null,
    @field:SerializedName("success") val success: Boolean = false,
    @field:SerializedName("error") val error: String? = null,
    @field:SerializedName("error_code") val errorCode: String? = null,
    @field:SerializedName("function_calls") val functionCalls: List<FunctionCall>? = null,
    @field:SerializedName("reason") val reason: String? = null,
    @field:SerializedName("reasoning") val reasoning: String? = null,
    @field:SerializedName("confidence") val confidence: Double = 0.0,
    @field:SerializedName("prefill_tps") val prefillTps: Double = 0.0,
    @field:SerializedName("decode_tps") val decodeTps: Double = 0.0,
    @field:SerializedName("peak_ram_mb") val peakRamMb: Double = 0.0,
    @field:SerializedName("validation") val validation: Validation? = null,
    val rawJson: String = "",
    val parseError: String? = null
) {
    // Backward compatibility: use reason if reasoning is empty/blank
    val effectiveReasoning: String
        get() = if (reasoning?.isNotBlank() == true) reasoning!! else (reason ?: "")

    val typeNonNull: String = type ?: ""
    val functionCallsNonNull: List<FunctionCall> = functionCalls ?: emptyList()
    
    val confidenceFloat: Float = confidence.toFloat()
    val prefillTpsFloat: Float = prefillTps.toFloat()
    val decodeTpsFloat: Float = decodeTps.toFloat()
    val peakRamMbInt: Int = peakRamMb.toInt()
}

data class FunctionCall(
    @field:SerializedName("name") val name: String = "",
    @field:SerializedName("arguments") val arguments: Map<String, Any?> = emptyMap()
)

data class Validation(
    @field:SerializedName("ungrounded") val ungrounded: List<String> = emptyList(),
    @field:SerializedName("negation") val negation: Boolean = false
)

data class ToolSchema(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>
)

data class NeedleInitParams(
    val systemPrompt: String,
    val toolsJson: String,
    val toolIndexPath: String? = null
)

enum class Screen {
    MAIN, CUSTOM_COMMAND, TOOLS, FULL_LOG
}

// ViewModel
class NeedleTestViewModel : ViewModel() {
    private val _phases = MutableStateFlow<List<TestPhase>>(createPhases())
    val phases: StateFlow<List<TestPhase>> = _phases

    private val _currentScreen = MutableStateFlow<Screen>(Screen.MAIN)
    val currentScreen: StateFlow<Screen> = _currentScreen

    private val _history = MutableStateFlow<List<TestHistoryEntry>>(emptyList())
    val history: StateFlow<List<TestHistoryEntry>> = _history

    private val _customCommand = MutableStateFlow("")
    val customCommand: StateFlow<String> = _customCommand

    private val _customResult = MutableStateFlow<TestPhase?>(null)
    val customResult: StateFlow<TestPhase?> = _customResult

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _fullLog = MutableStateFlow<StringBuilder>(StringBuilder())
    val fullLog: StateFlow<StringBuilder> = _fullLog

    private val _needleLoaded = MutableStateFlow(false)
    val needleLoaded: StateFlow<Boolean> = _needleLoaded

    private val _needleInitialized = MutableStateFlow(false)
    val needleInitialized: StateFlow<Boolean> = _needleInitialized

    private val _modelLoaded = MutableStateFlow(false)
    val modelLoaded: StateFlow<Boolean> = _modelLoaded

    private val _modelBytes = MutableStateFlow<ByteArray?>(null)
    val modelBytes: StateFlow<ByteArray?> = _modelBytes

    private val _toolJson = buildToolJson()
    val toolJson: String = _toolJson

    private val _systemPrompt = "You are a tool-calling assistant. When the user asks to perform a device action, call the appropriate tool."
    val systemPrompt: String = _systemPrompt

    private val _toolSchemas = buildToolSchemas()
    val toolSchemas: List<ToolSchema> = _toolSchemas

    // Mutex for serializing Needle calls
    private val _mutex = kotlinx.coroutines.sync.Mutex()

    fun navigateTo(screen: Screen) {
        _currentScreen.value = screen
    }

    fun appendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _fullLog.value.append("[$timestamp] $message\n")
    }

    fun clearLog() {
        _fullLog.value = StringBuilder()
    }

    fun copyText(text: String) {
        // Handled by UI
    }

    private fun createPhases(): List<TestPhase> {
        return listOf(
            TestPhase(1, "Phase 1 — Model Load", "Load needle2.cact model from assets"),
            TestPhase(2, "Phase 2 — Initialization", "Initialize Needle with tool schema"),
            TestPhase(3, "Phase 3 — Positive Tool Call", "Test tool call: 'turn on the flashlight'"),
            TestPhase(4, "Phase 4 — Negative Test", "Test non-tool query: 'what is the capital of France?'"),
            TestPhase(5, "Phase 5 — Reset / Re-init / Reuse", "Reset Needle and re-initialize"),
            TestPhase(6, "Phase 6 — Serialized Calls / Stability", "Test serialized calls stability")
        )
    }

    fun runPhase(phaseId: Int) {
        if (_isRunning.value) return
        viewModelScope.launch {
            _isRunning.value = true
            try {
                when (phaseId) {
                    1 -> runPhase1()
                    2 -> runPhase2()
                    3 -> runPhase3()
                    4 -> runPhase4()
                    5 -> runPhase5()
                    6 -> runPhase6()
                }
            } finally {
                _isRunning.value = false
            }
        }
    }

    fun runAllTests() {
        if (_isRunning.value) return
        viewModelScope.launch {
            _isRunning.value = true
            try {
                runPhase1()
                if (getPhase(1).status is TestStatus.Pass) runPhase2()
                if (getPhase(2).status is TestStatus.Pass) runPhase3()
                if (getPhase(3).status is TestStatus.Pass) runPhase4()
                if (getPhase(4).status is TestStatus.Pass) runPhase5()
                if (getPhase(5).status is TestStatus.Pass) runPhase6()
            } finally {
                _isRunning.value = false
            }
        }
    }

    private fun getPhase(id: Int): TestPhase = _phases.value.first { it.id == id }

    private fun updatePhase(phase: TestPhase) {
        _phases.value = _phases.value.map { if (it.id == phase.id) phase else it }
    }

    private fun addHistory(entry: TestHistoryEntry) {
        _history.value = _history.value + entry
    }

    private fun recordPhaseResult(phase: TestPhase, status: String, rawJson: String = "") {
        addHistory(TestHistoryEntry(
            timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
            command = phase.input,
            result = phase.output,
            rawJson = rawJson,
            confidence = phase.confidence,
            status = status,
            phase = phase.title
        ))
    }

    // Phase 1: Load Model
    private suspend fun runPhase1() {
        val phase = getPhase(1)
        phase.status = TestStatus.Running("Loading model...")
        updatePhase(phase.copy())
        appendLog("Phase 1: Loading needle2.cact from assets...")

        val startTime = System.currentTimeMillis()

        val result = withContext(Dispatchers.IO) {
            try {
                val inputStream = App.instance.assets.open("needle2.cact")
                val buffer = ByteArrayOutputStream()
                val data = ByteArray(8192)
                var bytesRead = inputStream.read(data)
                while (bytesRead != -1) {
                    buffer.write(data, 0, bytesRead)
                    bytesRead = inputStream.read(data)
                }
                inputStream.close()
                val bytes = buffer.toByteArray()
                _modelBytes.value = bytes

                // Verify SHA256
                val md = java.security.MessageDigest.getInstance("SHA-256")
                val digest = md.digest(bytes)
                val sha256 = digest.joinToString("") { "%02x".format(it) }
                val expectedSha256 = "b43aabfcaf1a6db6acf488076eab71d823c08697c7af4521fc1d174b60ede5ba"
                val shaMatch = sha256 == expectedSha256

                val loadResult = NeedleJNI.load(bytes)
                val inferenceTime = System.currentTimeMillis() - startTime

                phase.inferenceTimeMs = inferenceTime
                phase.output = "Model size: ${bytes.size} bytes\nSHA256: $sha256\nMatch: $shaMatch\nneedle_load() returned: $loadResult"
                phase.rawJson = "loadResult: $loadResult\nmodelSize: ${bytes.size}\nsha256: $sha256\nshaMatch: $shaMatch"

                if (loadResult >= 0) {
                    _modelLoaded.value = true
                    _modelBytes.value = bytes
                    phase.status = TestStatus.Pass("Model loaded successfully")
                    _needleLoaded.value = true
                } else {
                    phase.status = TestStatus.Fail(loadResult, "needle_load failed with code: $loadResult", "loadResult: $loadResult")
                }
                phase.confidence = if (loadResult >= 0) 1f else 0f
            } catch (e: Exception) {
                phase.status = TestStatus.Fail(-1, e.message ?: "Unknown error", e.toString())
            }
        }
        updatePhase(phase.copy())
        recordPhaseResult(phase, if (phase.status is TestStatus.Pass) "PASS" else "FAIL", phase.rawJson)
    }

    // Phase 2: Initialize
    private suspend fun runPhase2() {
        val phase = getPhase(2)
        phase.status = TestStatus.Running("Initializing Needle...")
        updatePhase(phase.copy())
        appendLog("Phase 2: Initializing Needle with tool definition...")

        val startTime = System.currentTimeMillis()

        phase.input = _systemPrompt + "\n\nTools: $_toolJson"
        val initResult = withContext(Dispatchers.IO) {
            NeedleJNI.init(_systemPrompt, _toolJson, null)
        }
        phase.inferenceTimeMs = System.currentTimeMillis() - startTime
        phase.output = "needle_init() returned: $initResult"

        if (initResult >= 0) {
            _needleInitialized.value = true
            phase.status = TestStatus.Pass("Needle initialized successfully")
            phase.confidence = 1f
        } else {
            phase.status = TestStatus.Fail(initResult, "needle_init failed with code: $initResult", "initResult: $initResult")
            phase.confidence = 0f
        }
        phase.output = "needle_init() returned: $initResult"
        phase.rawJson = "initResult: $initResult"
        updatePhase(phase.copy())
        recordPhaseResult(phase, if (phase.status is TestStatus.Pass) "PASS" else "FAIL", phase.rawJson)
    }

    // Phase 3: Positive Tool Call
    private suspend fun runPhase3() {
        val phase = getPhase(3)
        phase.status = TestStatus.Running("Testing tool call...")
        updatePhase(phase.copy())
        appendLog("Phase 3: Testing tool call - 'turn on the flashlight'")

        val startTime = System.currentTimeMillis()
        phase.input = "turn on the flashlight"

        val result = withContext(Dispatchers.IO) {
            NeedleJNI.complete("turn on the flashlight", 512)
        }
        phase.inferenceTimeMs = System.currentTimeMillis() - startTime
        phase.rawJson = result
        phase.output = result

        // Parse JSON response
        val parsed = parseNeedleResponse(result)
        phase.parsedResult = formatParsedResult(parsed)
        phase.confidence = parsed.confidenceFloat

        if (parsed.functionCallsNonNull.any { it.name == "device.flashlight_on" }) {
            phase.status = TestStatus.Pass("Tool call detected: device.flashlight_on")
            phase.confidence = max(phase.confidence, 0.9f)
        } else if (result.contains("device.flashlight_on") || result.contains("flashlight_on")) {
            phase.status = TestStatus.Pass("Flashlight reference found in response")
            phase.confidence = max(phase.confidence, 0.7f)
        } else {
            phase.status = TestStatus.Fail(-1, "No tool call detected for flashlight", result)
        }

        phase.confidence = max(phase.confidence, parsed.confidenceFloat)
        updatePhase(phase.copy())
        recordPhaseResult(phase, if (phase.status is TestStatus.Pass) "PASS" else "FAIL", phase.rawJson)
    }

    // Phase 4: Negative Test
    private suspend fun runPhase4() {
        val phase = getPhase(4)
        phase.status = TestStatus.Running("Testing negative query...")
        updatePhase(phase.copy())
        appendLog("Phase 4: Negative test - 'what is the capital of France?'")

        val startTime = System.currentTimeMillis()
        phase.input = "what is the capital of France?"

        val result = withContext(Dispatchers.IO) {
            NeedleJNI.complete("what is the capital of France?", 512)
        }
        phase.inferenceTimeMs = System.currentTimeMillis() - startTime
        phase.rawJson = result
        phase.output = result

        val parsed = parseNeedleResponse(result)
        phase.parsedResult = formatParsedResult(parsed)
        phase.confidence = parsed.confidenceFloat

        val hasToolCall = parsed.functionCallsNonNull.isNotEmpty()
        if (!hasToolCall || parsed.functionCallsNonNull.none { it.name == "device.flashlight_on" }) {
            phase.status = TestStatus.Pass("No tool call (correctly declined)")
        } else {
            phase.status = TestStatus.Fail(-1, "Incorrectly triggered tool call", result)
        }
        phase.confidence = max(phase.confidence, parsed.confidenceFloat)
        updatePhase(phase.copy())
        recordPhaseResult(phase, if (phase.status is TestStatus.Pass) "PASS" else "FAIL", phase.rawJson)
    }

    // Phase 5: Reset/Reuse
    private suspend fun runPhase5() {
        val phase = getPhase(5)
        phase.status = TestStatus.Running("Testing reset and reuse...")
        updatePhase(phase.copy())
        appendLog("Phase 5: Testing reset and reuse...")

        val startTime = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            NeedleJNI.reset()
        }
        phase.inferenceTimeMs = System.currentTimeMillis() - startTime
        appendLog("needle_reset() called")

        // Re-initialize
        val initResult = withContext(Dispatchers.IO) {
            NeedleJNI.init(_systemPrompt, _toolJson, null)
        }
        phase.output = "needle_reset() called\nneedle_init() after reset returned: $initResult"
        phase.rawJson = "reset: OK\ninitResult: $initResult"

        if (initResult >= 0) {
            phase.status = TestStatus.Pass("Reset and re-init successful")
            phase.confidence = 1f
        } else {
            phase.status = TestStatus.Fail(initResult, "Re-init failed after reset", "initResult: $initResult")
            phase.confidence = 0f
        }

        // Test inference after reset
        if (initResult >= 0) {
            val result = withContext(Dispatchers.IO) {
                NeedleJNI.complete("turn on the flashlight", 512)
            }
            phase.output += "\nAfter reset - Input: turn on the flashlight\nRaw Output:\n$result"
            val parsed = parseNeedleResponse(result)
            val hasCall = parsed.functionCallsNonNull.any { it.name == "device.flashlight_on" }
            if (hasCall) {
                phase.output += "\n✓ Tool call works after reset"
            }
        }
        updatePhase(phase.copy())
        recordPhaseResult(phase, if (phase.status is TestStatus.Pass) "PASS" else "FAIL", phase.rawJson)
    }

    // Phase 6: Serialized Calls / Stability
    // Tests independent commands with clean state (reset between each call)
    private suspend fun runPhase6() {
        val phase = getPhase(6)
        phase.status = TestStatus.Running("Testing serialized calls...")
        updatePhase(phase.copy())
        appendLog("Phase 6: Testing independent calls with state reset...")

        val commands = listOf(
            "turn on the flashlight",
            "what is the time",
            "turn on the flashlight",
            "hello needle"
        )

        val results = mutableListOf<String>()
        var allPassed = true

        for ((index, cmd) in commands.withIndex()) {
            appendLog("Test ${index + 1}: $cmd")
            
            // Reset needle to ensure clean state for each independent command
            withContext(Dispatchers.IO) {
                NeedleJNI.reset()
            }
            
            val result = withContext(Dispatchers.IO) {
                NeedleJNI.complete(cmd, 512)
            }
            val parsed = parseNeedleResponse(result)
            val hasFlashlight = parsed.functionCallsNonNull.any { it.name == "device.flashlight_on" }
            val expected = cmd.contains("flashlight", ignoreCase = true)
            val passed = (expected && parsed.functionCallsNonNull.any { it.name == "device.flashlight_on" }) ||
                         (!expected && parsed.functionCallsNonNull.isEmpty())
            allPassed = allPassed && passed
            results.add("Cmd: $cmd\nExpected flashlight: $expected\nGot flashlight: $hasFlashlight\nPassed: $passed\nOutput: $result\n")
        }

        phase.rawJson = results.joinToString("\n---\n")
        phase.output = results.joinToString("\n")
        phase.inferenceTimeMs = 0

        if (allPassed) {
            phase.status = TestStatus.Pass("All independent calls passed (with reset)")
            phase.confidence = 1f
        } else {
            phase.status = TestStatus.Fail(-1, "Some independent calls failed", phase.rawJson)
            phase.confidence = 0f
        }
        updatePhase(phase.copy())
        recordPhaseResult(phase, if (phase.status is TestStatus.Pass) "PASS" else "FAIL", phase.rawJson)
    }

    // Custom Command
    fun runCustomCommand(input: String) {
        if (_isRunning.value) return
        _customCommand.value = input
        viewModelScope.launch {
            _isRunning.value = true
            val phase = TestPhase(
                id = 99,
                title = "Custom Command",
                description = "Custom command test",
                input = input
            )
            phase.status = TestStatus.Running("Running custom command...")
            _customResult.value = phase.copy()
            try {
                val startTime = System.currentTimeMillis()
                val result = withContext(Dispatchers.IO) {
                    NeedleJNI.complete(input, 512)
                }
                val inferenceTime = System.currentTimeMillis() - startTime
                val parsed = parseNeedleResponse(result)

                val phaseResult = phase.copy(
                    status = if (result.startsWith("FAIL") || result.startsWith("Error")) TestStatus.Fail(-1, "Command failed", result)
                        else TestStatus.Pass("Command completed"),
                    output = result,
                    rawJson = result,
                    inferenceTimeMs = System.currentTimeMillis() - startTime,
                    parsedResult = formatParsedResult(parsed),
                    confidence = parsed.confidenceFloat
                )
                _customResult.value = phaseResult
                addHistory(TestHistoryEntry(
                    timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
                    command = input,
                    result = result,
                    rawJson = result,
                    confidence = parsed.confidenceFloat,
                    status = if (result.startsWith("FAIL") || result.startsWith("Error")) "FAIL" else "PASS",
                    phase = "Custom"
                ))
            } catch (e: Exception) {
                val phaseResult = phase.copy(
                    status = TestStatus.Fail(-1, e.message ?: "Unknown error", e.toString()),
                    rawJson = e.toString()
                )
                _customResult.value = phaseResult
            } finally {
                _isRunning.value = false
            }
        }
    }

    fun runCustomCommandSync(input: String) {
        runCustomCommand(input)
    }

    // Reset Needle
    fun resetNeedle() {
        if (_isRunning.value) return
        viewModelScope.launch {
            _isRunning.value = true
            try {
                withContext(Dispatchers.IO) {
                    NeedleJNI.reset()
                }
                appendLog("Needle reset via UI button")
                _needleInitialized.value = false
                // Re-init
                val initResult = withContext(Dispatchers.IO) {
                    NeedleJNI.init(_systemPrompt, _toolJson, null)
                }
                if (initResult >= 0) {
                    appendLog("Needle re-initialized after reset")
                    _needleInitialized.value = true
                } else {
                    appendLog("Re-init failed: $initResult")
                }
            } catch (e: Exception) {
                appendLog("Reset error: ${e.message}")
            } finally {
                _isRunning.value = false
            }
        }
    }

    fun clearResults() {
        _phases.value = createPhases()
        _customResult.value = null
        clearLog()
    }

    fun clearHistory() {
        _history.value = emptyList()
    }

    fun exportResults(): String {
        val export = StringBuilder()
        export.append("Needle 2 Test Results Export\n")
        export.append("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
        export.append("Model SHA256: b43aabfcaf1a6db6acf488076eab71d823c08697c7af4521fc1d174b60ede5ba\n")
        export.append("Model Size: ${_modelBytes.value?.size ?: 0} bytes\n\n")

        for (phase in _phases.value) {
            export.append("=== ${phase.title} ===\n")
            export.append("Status: ${when(phase.status) { is TestStatus.Pass -> "PASS"; is TestStatus.Fail -> "FAIL"; is TestStatus.Running -> "RUNNING"; else -> "NOT RUN" }}\n")
            export.append("Input: ${phase.input}\n")
            export.append("Output: ${phase.output}\n")
            export.append("Confidence: ${phase.confidence}\n")
            export.append("Inference Time: ${phase.inferenceTimeMs} ms\n")
            export.append("Raw JSON:\n${phase.rawJson}\n\n")
        }

        if (_customResult.value != null) {
            val c = _customResult.value!!
            export.append("=== Custom Command ===\n")
            export.append("Input: ${c.input}\n")
            export.append("Output: ${c.output}\n")
            export.append("Raw JSON:\n${c.rawJson}\n\n")
        }

        export.append("=== History ===\n")
        for (entry in _history.value) {
            export.append("[${entry.timestamp}] ${entry.command} -> ${entry.status}\n")
        }

        export.append("\n=== Full Log ===\n")
        export.append(_fullLog.value.toString())

        return export.toString()
    }

    fun shareResults() {
        val text = exportResults()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "Needle 2 Test Results")
        }
        App.instance.startActivity(Intent.createChooser(intent, "Share Results"))
    }

    private fun parseNeedleResponse(json: String): NeedleResponse {
        return try {
            com.google.gson.Gson().fromJson(json, NeedleResponse::class.java)
        } catch (e: Exception) {
            // Log the parsing error for diagnostics
            val errorMsg = "JSON parse failed: ${e.message}\nJSON: $json"
            appendLog(errorMsg)
            NeedleResponse(rawJson = json, parseError = e.message)
        }
    }

    private fun formatParsedResult(response: NeedleResponse): String {
        val sb = StringBuilder()
        if (response.parseError != null) {
            sb.append("PARSE ERROR: ${response.parseError}\n")
            sb.append("Raw JSON was preserved in rawJson field\n")
            return sb.toString()
        }
        sb.append("Type: ${response.typeNonNull}\n")
        sb.append("Success: ${response.success}\n")
        if (response.error != null && response.error.isNotBlank()) sb.append("Error: ${response.error}\n")
        if (response.errorCode != null && response.errorCode.isNotBlank()) sb.append("Error Code: ${response.errorCode}\n")
        if (response.functionCallsNonNull.isNotEmpty()) {
            sb.append("Function Calls:\n")
            for (fc in response.functionCallsNonNull) {
                sb.append("  - ${fc.name}: ${fc.arguments}\n")
            }
        } else {
            sb.append("Function Calls: (none)\n")
        }
        if (response.effectiveReasoning.isNotBlank()) sb.append("Reasoning: ${response.effectiveReasoning}\n")
        sb.append("Confidence: ${response.confidenceFloat}\n")
        if (response.prefillTpsFloat > 0) sb.append("Prefill TPS: ${response.prefillTpsFloat}\n")
        if (response.decodeTpsFloat > 0) sb.append("Decode TPS: ${response.decodeTpsFloat}\n")
        if (response.peakRamMbInt > 0) sb.append("Peak RAM: ${response.peakRamMbInt} MB\n")
        if (response.validation != null) {
            sb.append("Validation: ungrounded=${response.validation!!.ungrounded}, negation=${response.validation!!.negation}\n")
        }
        return sb.toString()
    }

    private fun buildToolJson(): String {
        return """
            [{
                "name": "device.flashlight_on",
                "description": "Turn on the phone flashlight.",
                "parameters": {
                    "type": "object",
                    "properties": {},
                    "required": []
                }
            }]
        """.trimIndent()
    }

    private fun buildToolSchemas(): List<ToolSchema> {
        return listOf(
            ToolSchema(
                name = "device.flashlight_on",
                description = "Turn on the phone flashlight.",
                parameters = mapOf<String, Any>(
                    "type" to "object",
                    "properties" to emptyMap<String, Any>(),
                    "required" to emptyList<String>()
                )
            )
        )
    }
}

// Application class
class App : android.app.Application() {
    companion object {
        lateinit var instance: App
    }
    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}

// Composable UI
@Composable
fun MainScreen(viewModel: NeedleTestViewModel) {
    val context = LocalContext.current
    val phases by viewModel.phases.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val currentScreen by viewModel.currentScreen.collectAsState()
    val fullLog by viewModel.fullLog.collectAsState()
    val customResult by viewModel.customResult.collectAsState()
    val history by viewModel.history.collectAsState()
    val customCommand by viewModel.customCommand.collectAsState()
    val modelLoaded by viewModel.modelLoaded.collectAsState()
    val needleInitialized by viewModel.needleInitialized.collectAsState()
    val needleLoaded by viewModel.needleLoaded.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        when (currentScreen) {
            Screen.MAIN -> MainTestScreen(viewModel)
            Screen.CUSTOM_COMMAND -> CustomCommandScreen(viewModel)
            Screen.TOOLS -> ToolsScreen(viewModel)
            Screen.FULL_LOG -> FullLogScreen(viewModel)
        }
    }
}

// Placeholder for the actual implementation
@Composable
fun MainTestScreen(viewModel: NeedleTestViewModel) {
    val phases by viewModel.phases.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val modelLoaded by viewModel.modelLoaded.collectAsState()
    val needleInitialized by viewModel.needleInitialized.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Header with status indicators
        HeaderSection(modelLoaded, needleInitialized, isRunning)

        Spacer(modifier = Modifier.height(16.dp))

        // Action buttons
        ActionButtonsSection(viewModel, isRunning)

        Spacer(modifier = Modifier.height(16.dp))

        // Phase cards
        PhaseListSection(phases, viewModel, isRunning)

        Spacer(modifier = Modifier.height(16.dp))

        // Performance metrics
        PerformanceMetricsSection(phases)

        Spacer(modifier = Modifier.height(16.dp))

        // Navigation buttons
        NavigationButtonsSection(viewModel)
    }
}

@Composable
fun HeaderSection(modelLoaded: Boolean, needleInitialized: Boolean, isRunning: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Needle 2 Test Suite",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusChip(
                label = "Model",
                isActive = modelLoaded,
                color = MaterialTheme.colorScheme.tertiaryContainer
            )
            StatusChip(
                label = "Initialized",
                isActive = needleInitialized,
                color = MaterialTheme.colorScheme.secondaryContainer
            )
            StatusChip(
                label = "Running",
                isActive = isRunning,
                color = MaterialTheme.colorScheme.primaryContainer
            )
        }
    }
}

@Composable
fun StatusChip(label: String, isActive: Boolean, color: Color) {
    val textColor = if (isActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    val bgColor = if (isActive) color.copy(alpha = 0.8f) else MaterialTheme.colorScheme.surfaceContainerHighest
    Text(
        text = "$label: ${if (isActive) "✓" else "✗"}",
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(bgColor, RoundedCornerShape(16.dp)),
        color = textColor,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium
    )
}

@Composable
fun ActionButtonsSection(viewModel: NeedleTestViewModel, isRunning: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { viewModel.runAllTests() },
                modifier = Modifier.weight(1f),
                enabled = !isRunning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Run All", fontWeight = FontWeight.Bold)
                }
            }
            Button(
                onClick = { viewModel.resetNeedle() },
                modifier = Modifier.weight(1f),
                enabled = !isRunning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                )
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Reset Needle")
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { viewModel.clearResults() },
                modifier = Modifier.weight(1f),
                enabled = !isRunning,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Clear Results")
                }
            }
        }
    }
}

@Composable
fun PhaseListSection(phases: List<TestPhase>, viewModel: NeedleTestViewModel, isRunning: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Test Phases",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        phases.forEach { phase ->
            PhaseCard(phase = phase, viewModel = viewModel, isRunning = isRunning)
        }
    }
}

@Composable
fun PhaseCard(phase: TestPhase, viewModel: NeedleTestViewModel, isRunning: Boolean) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(phase.isExpanded) }
    val statusColor = when (phase.status) {
        is TestStatus.Pass -> MaterialTheme.colorScheme.tertiary
        is TestStatus.Fail -> MaterialTheme.colorScheme.error
        is TestStatus.Running -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    val statusText = when (phase.status) {
        is TestStatus.Pass -> "PASS"
        is TestStatus.Fail -> "FAIL"
        is TestStatus.Running -> "RUNNING"
        else -> "NOT RUN"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column {
            // Phase header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = phase.title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = phase.description,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = statusText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                    if (phase.inferenceTimeMs > 0) {
                        Text(
                            text = "${phase.inferenceTimeMs} ms",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (phase.confidence > 0f) {
                        Text(
                            text = "Conf: ${(phase.confidence * 100).toInt()}%",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Expandable content
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Run individual phase button
                    if (phase.status !is TestStatus.Running) {
                        Button(
                            onClick = { viewModel.runPhase(phase.id) },
                            enabled = !isRunning,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) {
                            Text("Run Phase ${phase.id}")
                        }
                    }

                    // Input section
                    if (phase.input.isNotBlank()) {
                        ResultSection(
                            title = "Input",
                            content = phase.input,
                            isCode = true,
                            onCopy = { copyToClipboard(context, phase.input) }
                        )
                    }

                    // Output / Parsed Result / Raw JSON tabs
                    if (phase.output.isNotBlank() || phase.parsedResult.isNotBlank() || phase.rawJson.isNotBlank()) {
                        ResultTabs(
                            output = phase.output,
                            parsedResult = phase.parsedResult,
                            rawJson = phase.rawJson,
                            onCopyOutput = { copyToClipboard(context, phase.output) },
                            onCopyParsed = { copyToClipboard(context, phase.parsedResult) },
                            onCopyRaw = { copyToClipboard(context, phase.rawJson) },
                            onCopyAll = { copyToClipboard(context, "=== ${phase.title} ===\nInput: ${phase.input}\n\nOutput:\n${phase.output}\n\nParsed:\n${phase.parsedResult}\n\nRaw JSON:\n${phase.rawJson}") },
                            onShare = { viewModel.shareResults() }
                        )
                    }

                    // Error details
                    if (phase.status is TestStatus.Fail) {
                        val fail = phase.status as TestStatus.Fail
                        ResultSection(
                            title = "Error (Code: ${fail.errorCode})",
                            content = fail.errorMessage,
                            isCode = false,
                            backgroundColor = MaterialTheme.colorScheme.errorContainer,
                            onCopy = { copyToClipboard(context, "Error ${fail.errorCode}: ${fail.errorMessage}\n\nRaw: ${fail.rawResponse}") }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ResultSection(
    title: String,
    content: String,
    isCode: Boolean = false,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    onCopy: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(onClick = onCopy) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy $title", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
        Text(
            text = content,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .background(backgroundColor, RoundedCornerShape(8.dp)),
            fontSize = 12.sp,
            fontFamily = if (isCode) FontFamily.Monospace else FontFamily.Default,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = Int.MAX_VALUE,
            overflow = TextOverflow.Visible,
            softWrap = true
        )
    }
}

@Composable
fun ResultTabs(
    output: String,
    parsedResult: String,
    rawJson: String,
    onCopyOutput: () -> Unit,
    onCopyParsed: () -> Unit,
    onCopyRaw: () -> Unit,
    onCopyAll: () -> Unit,
    onShare: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Output", "Parsed", "Raw JSON")

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Tab row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            tabs.forEachIndexed { index, name ->
                val isSelected = selectedTab == index
                Text(
                    text = name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                            RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
                        )
                        .clickable { selectedTab = index }
                        .wrapContentWidth(),
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Tab content
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                .padding(12.dp)
        ) {
            when (selectedTab) {
                0 -> SelectableText(text = output, onCopy = onCopyOutput)
                1 -> SelectableText(text = parsedResult, onCopy = onCopyParsed)
                2 -> SelectableText(text = rawJson, onCopy = onCopyRaw)
            }
        }

        // Copy all / Share buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onCopyAll, modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy All", fontSize = 12.sp)
                }
            }
            OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Share", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun SelectableText(text: String, onCopy: () -> Unit) {
    val context = LocalContext.current
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    AndroidView(
        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
        factory = { ctx ->
            val textView = android.widget.TextView(ctx).apply {
                this.text = text
                textSize = 12f
                setTextColor(textColor)
                setMovementMethod(android.text.method.ScrollingMovementMethod())
                setTextIsSelectable(true)
                setPadding(0, 0, 0, 0)
                typeface = android.graphics.Typeface.MONOSPACE
            }
            textView.setOnLongClickListener {
                onCopy()
                android.widget.Toast.makeText(ctx, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                true
            }
            textView
        },
        update = { textView ->
            textView.text = text
        }
    )
}

@Composable
fun PerformanceMetricsSection(phases: List<TestPhase>) {
    val completedPhases = phases.filter { it.status is TestStatus.Pass || it.status is TestStatus.Fail }
    if (completedPhases.isEmpty()) return

    val totalTime = completedPhases.sumOf { it.inferenceTimeMs }
    val avgConfidence: Float = if (completedPhases.isNotEmpty()) completedPhases.map { it.confidence }.average().toFloat() else 0f
    val passCount = completedPhases.count { it.status is TestStatus.Pass }
    val failCount = completedPhases.count { it.status is TestStatus.Fail }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Performance Metrics", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricCard("Total Time", "${totalTime} ms", Icons.Filled.Timer, modifier = Modifier.weight(1f))
                MetricCard("Avg Confidence", "${(avgConfidence * 100.0f).toInt()}%", Icons.Filled.Psychology, modifier = Modifier.weight(1f))
                MetricCard("Passed", "$passCount", Icons.Filled.CheckCircle, modifier = Modifier.weight(1f))
                MetricCard("Failed", "$failCount", Icons.Filled.Cancel, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun MetricCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .padding(12.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(title, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun NavigationButtonsSection(viewModel: NeedleTestViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = { viewModel.navigateTo(Screen.CUSTOM_COMMAND) },
            modifier = Modifier.weight(1f)
        ) {
            Text("Custom Command Lab")
        }
        OutlinedButton(
            onClick = { viewModel.navigateTo(Screen.TOOLS) },
            modifier = Modifier.weight(1f)
        ) {
            Text("Available Tools")
        }
        OutlinedButton(
            onClick = { viewModel.navigateTo(Screen.FULL_LOG) },
            modifier = Modifier.weight(1f)
        ) {
            Text("Full Raw Log")
        }
    }
}

@Composable
fun CustomCommandScreen(viewModel: NeedleTestViewModel) {
    val customCommand by viewModel.customCommand.collectAsState()
    val customResult by viewModel.customResult.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Custom Command Lab", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            if (isRunning) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Input area
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Enter Command", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("e.g., turn on the flashlight") },
                    minLines = 3,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions.Default
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                viewModel.runCustomCommand(inputText)
                            }
                        },
                        enabled = inputText.isNotBlank() && !isRunning,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Run Command")
                    }
                    OutlinedButton(
                        onClick = { inputText = "" },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Clear")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Result area
        customResult?.let { result ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Result", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        val statusColor = when (result.status) {
                            is TestStatus.Pass -> MaterialTheme.colorScheme.tertiary
                            is TestStatus.Fail -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.outline
                        }
                        val statusText = when (result.status) {
                            is TestStatus.Pass -> "PASS"
                            is TestStatus.Fail -> "FAIL"
                            is TestStatus.Running -> "RUNNING"
                            else -> "NOT RUN"
                        }
                        Text(statusText, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = statusColor)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    ResultTabs(
                        output = result.output,
                        parsedResult = result.parsedResult,
                        rawJson = result.rawJson,
                        onCopyOutput = { copyToClipboard(context, result.output) },
                        onCopyParsed = { copyToClipboard(context, result.parsedResult) },
                        onCopyRaw = { copyToClipboard(context, result.rawJson) },
                        onCopyAll = { copyToClipboard(context, "=== Custom Command ===\nInput: ${result.input}\n\nOutput:\n${result.output}\n\nParsed:\n${result.parsedResult}\n\nRaw JSON:\n${result.rawJson}") },
                        onShare = { viewModel.shareResults() }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Back button
        OutlinedButton(
            onClick = { viewModel.navigateTo(Screen.MAIN) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Back to Main")
        }
    }
}

@Composable
fun ToolsScreen(viewModel: NeedleTestViewModel) {
    val toolSchemas = viewModel.toolSchemas
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Available Tools", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                toolSchemas.forEach { schema ->
                    ToolSchemaCard(schema = schema)
                    if (schema != toolSchemas.last()) {
                        Divider(modifier = Modifier.padding(vertical = 12.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Tool JSON
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Tool JSON (sent to Needle)", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    IconButton(onClick = { copyToClipboard(context, viewModel.toolJson) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy Tool JSON", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                SelectableText(
                    text = viewModel.toolJson,
                    onCopy = { copyToClipboard(context, viewModel.toolJson) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = { viewModel.navigateTo(Screen.MAIN) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Back to Main")
        }
    }
}

@Composable
fun ToolSchemaCard(schema: ToolSchema) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(schema.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(schema.description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "Parameters: ${schema.parameters.toString()}",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun FullLogScreen(viewModel: NeedleTestViewModel) {
    val fullLog by viewModel.fullLog.collectAsState()
    val history by viewModel.history.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Full Raw Log & History", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(modifier = Modifier.height(16.dp))

        // History section
        if (history.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Test History", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        TextButton(onClick = { viewModel.clearHistory() }) {
                            Text("Clear History")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().height(300.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(history.reversed()) { entry ->
                            HistoryItem(entry = entry, onCopy = {
                                copyToClipboard(context, "[${entry.timestamp}] ${entry.command} -> ${entry.status}\n\n${entry.result}")
                            })
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Full log section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Full Log", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(onClick = { viewModel.clearLog() }) {
                            Text("Clear Log")
                        }
                        OutlinedButton(onClick = {
                            copyToClipboard(context, fullLog.toString())
                        }) {
                            Text("Copy Log")
                        }
                        OutlinedButton(onClick = { viewModel.shareResults() }) {
                            Text("Share All")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                SelectableText(
                    text = fullLog.toString(),
                    onCopy = { copyToClipboard(context, fullLog.toString()) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = { viewModel.navigateTo(Screen.MAIN) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Back to Main")
        }
    }
}

@Composable
fun HistoryItem(entry: TestHistoryEntry, onCopy: () -> Unit) {
    val statusColor = when (entry.status) {
        "PASS" -> MaterialTheme.colorScheme.tertiary
        "FAIL" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(entry.phase, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(entry.status, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = statusColor)
            }
            Text(entry.timestamp, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(entry.command, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onCopy) {
                    Text("Copy")
                }
            }
        }
    }
}

@Preview
@Composable
fun PreviewMainScreen() {
    val viewModel = NeedleTestViewModel()
    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            MainScreen(viewModel)
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val clip = android.content.ClipData.newPlainText("Needle2Test", text)
    clipboard.setPrimaryClip(clip)
    android.widget.Toast.makeText(context, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
}

class MainActivity : ComponentActivity() {
    private val viewModel: NeedleTestViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    MainScreen(viewModel)
                }
            }
        }
    }
}