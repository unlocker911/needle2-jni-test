package com.example.needle

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.app.Activity
import java.io.ByteArrayOutputStream
import java.io.InputStream

class MainActivity : Activity() {

    private val TAG = "Needle2Test"
    private lateinit var textView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        textView = TextView(this)
        textView.textSize = 16f
        textView.setPadding(16, 16, 16, 16)
        setContentView(textView)

        appendLog("Needle 2 Standalone JNI Test Started")
        appendLog("===================================")

        // Run test in background thread
        Thread {
            runTest()
        }.start()
    }

    private fun runTest() {
        try {
            // Phase 1: Load model
            appendLog("Phase 1: Loading needle2.cact from assets...")
            val modelBytes = loadModelFromAssets()
            appendLog("Model size: ${modelBytes.size} bytes")

            // Verify SHA256
            val sha256 = calculateSHA256(modelBytes)
            appendLog("Model SHA256: $sha256")
            val expectedSha256 = "b43aabfcaf1a6db6acf488076eab71d823c08697c7af4521fc1d174b60ede5ba"
            if (sha256 != expectedSha256) {
                appendLog("WARNING: SHA256 mismatch!")
            }

            val loadResult = NeedleJNI.load(modelBytes)
            appendLog("needle_load() returned: $loadResult")
            if (loadResult < 0) {
                appendLog("FAILED: needle_load failed")
                return
            }

            // Phase 2: Initialize with tool
            appendLog("\nPhase 2: Initializing Needle with tool definition...")
            val toolJson = buildToolJson()
            appendLog("Tool JSON: $toolJson")

            val systemPrompt = "You are a tool-calling assistant. When the user asks to perform a device action, call the appropriate tool."
            val initResult = NeedleJNI.init(systemPrompt, toolJson, null)
            appendLog("needle_init() returned: $initResult")
            if (initResult < 0) {
                appendLog("FAILED: needle_init failed")
                return
            }

            // Phase 3: Positive test - tool call
            appendLog("\nPhase 3: Testing tool call - 'turn on the flashlight'")
            val testInput1 = "turn on the flashlight"
            val result1 = NeedleJNI.complete(testInput1, 512)
            appendLog("Input: $testInput1")
            appendLog("Raw Output:\n$result1")

            // Phase 4: Negative test - no tool call
            appendLog("\nPhase 4: Negative test - 'what is the capital of France?'")
            val testInput2 = "what is the capital of France?"
            val result2 = NeedleJNI.complete(testInput2, 512)
            appendLog("Input: $testInput2")
            appendLog("Raw Output:\n$result2")

            // Phase 5: Reset test
            appendLog("\nPhase 5: Testing reset and reuse...")
            NeedleJNI.reset()
            appendLog("needle_reset() called")

            // Re-initialize after reset
            val initResult2 = NeedleJNI.init(systemPrompt, toolJson, null)
            appendLog("needle_init() after reset returned: $initResult2")
            if (initResult2 >= 0) {
                val result3 = NeedleJNI.complete("turn on the flashlight", 512)
                appendLog("After reset - Input: turn on the flashlight")
                appendLog("Raw Output:\n$result3")
            }

            appendLog("\n===================================")
            appendLog("TEST COMPLETED")

        } catch (e: Exception) {
            appendLog("ERROR: ${e.message}")
            Log.e(TAG, "Test failed", e)
        }
    }

    private fun loadModelFromAssets(): ByteArray {
        val inputStream: InputStream = assets.open("needle2.cact")
        val buffer = ByteArrayOutputStream()
        val data = ByteArray(8192)
        var bytesRead = inputStream.read(data)
        while (bytesRead != -1) {
            buffer.write(data, 0, bytesRead)
            bytesRead = inputStream.read(data)
        }
        inputStream.close()
        return buffer.toByteArray()
    }

    private fun calculateSHA256(data: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(data)
        return digest.joinToString("") { "%02x".format(it) }
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

    private fun appendLog(message: String) {
        runOnUiThread {
            textView.append("$message\n\n")
        }
        Log.d(TAG, message)
    }
}