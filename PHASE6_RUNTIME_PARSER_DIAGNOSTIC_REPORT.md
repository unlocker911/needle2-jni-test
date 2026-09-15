# PHASE6_RUNTIME_PARSER_DIAGNOSTIC_REPORT

## Executive Summary

**Issue**: Phase 6 reports "Got flashlight: false" for command "turn on the flashlight" even though the raw JSON output contains `"function_calls":[{"name":"device.flashlight_on","arguments":{}}]`.

**Key Finding**: Phase 6 calls `NeedleJNI.reset()` followed immediately by `NeedleJNI.complete()` **WITHOUT** calling `NeedleJNI.init()` between them. Phase 5 explicitly re-initializes after reset.

**Diagnosis Status**: READ-ONLY — No code changes made. This report documents the investigation.

---

## 1. Exact Execution Path Comparison

### Phase 3 — Positive Tool Call (WORKS)

```kotlin
runPhase3() {
    // 1. NO reset, NO init — uses state from Phase 2 init
    val result = NeedleJNI.complete("turn on the flashlight", 512)
    
    // 2. Parse
    val parsed = parseNeedleResponse(result)
    
    // 3. Check functionCallsNonNull
    if (parsed.functionCallsNonNull.any { it.name == "device.flashlight_on" }) {
        PASS
    }
}
```

**State entering Phase 3**: Needle initialized via Phase 2 (`needle_init()` returned 70).

---

### Phase 6 — Serialized Calls (BROKEN)

```kotlin
runPhase6() {
    commands = ["turn on the flashlight", "what is the time", "turn on the flashlight", "hello needle"]
    
    for (cmd in commands) {
        // 1. RESET before EACH command
        NeedleJNI.reset()
        
        // 2. NO INIT — directly call complete
        val result = NeedleJNI.complete(cmd, 512)
        
        // 3. Parse
        val parsed = parseNeedleResponse(result)
        
        // 4. Check functionCallsNonNull
        val hasFlashlight = parsed.functionCallsNonNull.any { it.name == "device.flashlight_on" }
    }
}
```

**State entering each iteration**: Needle **reset but NOT re-initialized**.

---

### Phase 5 — Reset/Reuse (WORKS — shows the correct pattern)

```kotlin
runPhase5() {
    NeedleJNI.reset()
    
    // EXPLICIT RE-INIT
    val initResult = NeedleJNI.init(_systemPrompt, _toolJson, null)
    
    if (initResult >= 0) {
        val result = NeedleJNI.complete("turn on the flashlight", 512)
        val parsed = parseNeedleResponse(result)
        val hasCall = parsed.functionCallsNonNull.any { it.name == "device.flashlight_on" }
        // hasCall == true → "✓ Tool call works after reset"
    }
}
```

---

## 2. Critical Difference: Reset Without Init

| Phase | Reset? | Init After Reset? | Result |
|-------|--------|-------------------|--------|
| Phase 3 | No | N/A (uses Phase 2 init) | PASS |
| Phase 5 | Yes | **YES** | PASS (shows "Tool call works after reset") |
| Phase 6 | Yes (per command) | **NO** | FAIL ("Got flashlight: false") |

---

## 3. Native API Analysis

### needle.h (C API)
```c
NEEDLE_API int needle_init(...);       // Must be called after load
NEEDLE_API int needle_complete(...);   // Inference
NEEDLE_API void needle_reset(void);    // Resets state
NEEDLE_API int needle_load(...);       // Load model
```

### needle_jni.cpp — reset()
```cpp
extern "C" JNIEXPORT void JNICALL
Java_com_example_needle_NeedleJNI_reset(JNIEnv* env, jclass clazz) {
    LOGI("Resetting Needle");
    needle_reset();    // Calls native reset
    LOGI("Needle reset complete");
}
```

**No init is called after reset in the JNI layer.**

---

## 4. APK / Commit Verification

### Current Commit
- **HEAD**: `7be10719df80f882eccdade208c70662921ad53d` (Remove stray ParserTest.kt from root directory)
- **Parser Fix Commit**: `c9cc61e` (Add PARSER_MAPPING_FIX_REPORT.md) which includes `7be1071` fix

### Parser Fix Presence
The fix `@field:SerializedName` is in the current HEAD:
```kotlin
@field:SerializedName("function_calls") val functionCalls: List<FunctionCall>? = null
```

**Conclusion**: The APK tested on the device **should** contain the parser fix (commit `7be1071` is the latest on master).

**Caveat**: The physical test may have used an APK from an earlier CI run. Need to verify the APK artifact source.

---

## 5. Parser Logic Analysis

### parseNeedleResponse() — SAME for both phases
```kotlin
private fun parseNeedleResponse(json: String): NeedleResponse {
    return try {
        com.google.gson.Gson().fromJson(json, NeedleResponse::class.java)
    } catch (e: Exception) {
        appendLog("JSON parse failed: ${e.message}\nJSON: $json")
        NeedleResponse(rawJson = json, parseError = e.message)
    }
}
```

### NeedleResponse — functionCallsNonNull
```kotlin
val functionCallsNonNull: List<FunctionCall> = functionCalls ?: emptyList()
```

**Theoretical behavior**: If raw JSON contains `function_calls`, Gson should populate `functionCalls`, and `functionCallsNonNull` should return the list.

**Evidence from device**: Raw JSON **does** contain the function call, but Phase 6 reports empty.

---

## 6. Duplicate Class Check

**Searched**: `NeedleResponse`, `FunctionCall`, `Validation` across entire project.

**Result**: Only ONE definition each in `MainActivity.kt`. No duplicates.

---

## 7. Post-Parsing Modification Check

**Searched**: All references to `functionCalls`, `functionCallsNonNull`, `parsed.functionCalls`

**Result**: No code modifies `functionCalls` after parsing. The value is read-only after `parseNeedleResponse()` returns.

---

## 8. Raw JSON vs Runtime JSON

**Device output shows**:
```
Raw JSON contains:
{"type":"call","success":true,"error":null,"error_code":null,"reason":null,
 "function_calls":[{"name":"device.flashlight_on","arguments":{}}],...}
```

But Phase 6 reports: `Got flashlight: false`

**Possibilities**:
1. **Runtime JSON differs from displayed Raw JSON** — The `result` string logged may not be the same string passed to `parseNeedleResponse()` (unlikely, same variable)
2. **Gson parsing fails silently** — But `parseError` would be set and logged
3. **Native state affects output** — Model produces different JSON after reset without init
4. **Native complete() fails/returns error** — But raw JSON shows success

---

## 9. Hypothesis Ranking

### H1: Reset Without Init Leaves Needle in Invalid State (HIGH CONFIDENCE)
**Evidence FOR**:
- Phase 5 (reset + init + complete) works
- Phase 6 (reset + complete, NO init) fails
- Native `needle_reset()` likely clears the tool schema/context
- `needle_init()` re-establishes system prompt + tool definitions
- Without init, model has no tool definitions → produces "respond" or malformed call

**Evidence AGAINST**:
- Raw JSON shows `type: "call"` and `function_calls` present
- If model had no tools, it would likely return `type: "respond"` with empty calls

**Refinement**: The model may emit a CALL but the native layer might not have the tool registered, so the CALL is a hallucination. But the JSON parsing should still work.

---

### H2: Parser Fix Not in Tested APK (MEDIUM CONFIDENCE)
**Evidence FOR**:
- If APK built from commit before `7be1071`, `@SerializedName` without `@field:` would cause field mapping failure
- Phase 3 might work via fallback `result.contains("flashlight_on")` but Phase 6 uses direct parsing

**Evidence AGAINST**:
- Latest CI build (Run 34932636181) is from commit `7be1071` which includes the fix
- Device test timing unclear

**Test**: Check `fullLog` for "JSON parse failed" messages.

---

### H3: Gson Field Mapping Still Broken Despite @field: (LOW CONFIDENCE)
**Evidence FOR**:
- Kotlin data class field visibility quirks
- `@field:SerializedName` should work but edge cases exist

**Evidence AGAINST**:
- Phase 3 would also fail if parser broken
- Phase 3 passes on device

---

### H4: Race Condition / Coroutine Context Issue (LOW CONFIDENCE)
**Evidence FOR**:
- Multiple `withContext(Dispatchers.IO)` calls
- Shared Needle state across coroutines

**Evidence AGAINST**:
- Sequential loop in Phase 6, each iteration awaits
- Phase 5 also uses coroutines and works

---

### H5: Display Logic Bug — hasFlashlight Computed Correctly But Reported Wrong (LOW CONFIDENCE)
**Evidence FOR**:
- String formatting in `results.add()` could have bug

**Evidence AGAINST**:
- Code is straightforward: `val hasFlashlight = parsed.functionCallsNonNull.any { it.name == "device.flashlight_on" }`

---

## 10. Most Likely Root Cause

### PRIMARY: H1 — Reset Without Init
The native Needle engine likely requires `needle_init()` after `needle_reset()` to re-register the tool schema. Without it:
1. Model may still emit `function_calls` JSON (hallucination)
2. But the CALL is not grounded in actual registered tools
3. **However, the JSON parsing should still work** — this doesn't explain empty `functionCallsNonNull`

### SECONDARY: H2 — Stale APK
If the physical test used an APK without the `@field:SerializedName` fix, Phase 6 would fail to parse `function_calls` while Phase 3 might pass via the `result.contains("flashlight_on")` fallback.

---

## 11. Recommended Next Minimal Test/Fix

### Test 1: Verify APK Commit
Check the installed APK's build info or re-install from latest CI artifact (Run 34932636181).

### Test 2: Add Parse Error Logging to Phase 6
Temporarily log `parsed.parseError` and `parsed.functionCalls` in Phase 6 to see if parsing fails.

### Test 3: Add Init After Reset in Phase 6 (If H1 Confirmed)
```kotlin
// In runPhase6 loop:
NeedleJNI.reset()
NeedleJNI.init(_systemPrompt, _toolJson, null)  // ADD THIS
val result = NeedleJNI.complete(cmd, 512)
```

### Test 4: Compare Phase 3 vs Phase 6 Raw JSON Side-by-Side
Log both raw JSON strings to file for exact comparison.

---

## 12. Explicit Documentation: Phase 6 Reset Without Init

**Finding**: Phase 6 currently performs `NeedleJNI.reset()` followed directly by `NeedleJNI.complete()` **without** calling `NeedleJNI.init()` between them.

**Location**: `MainActivity.kt:537-543`
```kotlin
withContext(Dispatchers.IO) {
    NeedleJNI.reset()
}
val result = withContext(Dispatchers.IO) {
    NeedleJNI.complete(cmd, 512)
}
```

**Contrast with Phase 5** (lines 478-487):
```kotlin
NeedleJNI.reset()
val initResult = NeedleJNI.init(_systemPrompt, _toolJson, null)
```

**DO NOT CHANGE YET** — This diagnostic is read-only. The fix decision requires confirming whether the native API requires init after reset.

---

## 13. Summary Table

| Check | Status | Notes |
|-------|--------|-------|
| Parser fix in HEAD | ✅ Yes | `@field:SerializedName` on all fields |
| Single NeedleResponse class | ✅ Yes | Only in MainActivity.kt |
| Same parseNeedleResponse() used | ✅ Yes | Both Phase 3 and Phase 6 call it |
| No post-parse modification | ✅ Confirmed | functionCallsNonNull is read-only |
| Phase 3 raw JSON has function_calls | ✅ Device confirmed | |
| Phase 6 raw JSON has function_calls | ✅ Device confirmed | But parsed as empty |
| Phase 6 calls init after reset | ❌ **NO** | Critical difference from Phase 5 |
| Native API requires init after reset | ❓ Unknown | Needle.h doesn't specify |

---

## 14. Recommended Action Order

1. **Verify APK** — Install latest CI artifact (Run 34932636181) on device
2. **Add diagnostic logging** — Log `parsed.parseError` and `parsed.functionCalls` in Phase 6
3. **If parseError present** → Parser fix not in APK → Rebuild
4. **If parseError null but functionCalls empty** → Native state issue → Add init after reset in Phase 6
5. **If functionCalls populated but hasFlashlight=false** → Logic bug in comparison

---

**END OF DIAGNOSTIC REPORT — NO CODE CHANGES MADE**