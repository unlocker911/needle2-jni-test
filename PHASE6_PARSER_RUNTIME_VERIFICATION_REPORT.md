# PHASE6_PARSER_RUNTIME_VERIFICATION_REPORT

## 1. Exact Commit Verification

**CI Run**: 34932636181 (GitHub Actions Build Needle 2 JNI Test)
- **Head SHA**: `7be10719df80f882eccdade208c70662921ad53d`
- **Head Branch**: `master`
- **Created**: 2026-09-15T05:24:53Z
- **Conclusion**: SUCCESS
- **URL**: https://github.com/unlocker911/needle2-jni-test/actions/runs/34932636181

**Commit History** (relevant):
```
c9cc61e Add PARSER_MAPPING_FIX_REPORT.md (HEAD)
7be1071 Remove stray ParserTest.kt from root directory  ← CI RUN COMMIT
cfe79f4 Fix Gson @SerializedName annotation target for field visibility  ← PARSER FIX
932e87b Add NULLABILITY_RUNTIME_CRASH_FIX_REPORT.md
f020ee5 Fix nullability crash in NeedleResponse parsing
...
27a0d73 Fix JSON parsing: add @SerializedName annotations
```

**Key Finding**: The CI run used commit `7be1071` which is a **descendant** of `cfe79f4` (the parser fix commit). The parser fix IS included in the APK artifact from this run.

---

## 2. Exact APK/CI Verification

### Parser Fix Presence in CI Commit (7be1071)

**NeedleResponse class** (verified via `git show 7be1071:app/src/main/java/.../MainActivity.kt`):

```kotlin
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
    val effectiveReasoning: String
        get() = if (reasoning?.isNotBlank() == true) reasoning!! else (reason ?: "")

    val typeNonNull: String = type ?: ""
    val functionCallsNonNull: List<FunctionCall> = functionCalls ?: emptyList()
    ...
}
```

**FunctionCall class**:
```kotlin
data class FunctionCall(
    @field:SerializedName("name") val name: String = "",
    @field:SerializedName("arguments") val arguments: Map<String, Any?> = emptyMap()
)
```

**Validation class**:
```kotlin
data class Validation(
    @field:SerializedName("ungrounded") val ungrounded: List<String> = emptyList(),
    @field:SerializedName("negation") val negation: Boolean = false
)
```

✅ **VERIFIED**: All `@field:SerializedName` annotations are present in the CI commit.

---

## 3. Exact Parser Source Verification

### parseNeedleResponse() — Single Implementation Used by ALL Phases

```kotlin
private fun parseNeedleResponse(json: String): NeedleResponse {
    return try {
        com.google.gson.Gson().fromJson(json, NeedleResponse::class.java)
    } catch (e: Exception) {
        val errorMsg = "JSON parse failed: ${e.message}\nJSON: $json"
        appendLog(errorMsg)
        NeedleResponse(rawJson = json, parseError = e.message)
    }
}
```

**Used by**: Phase 3, Phase 4, Phase 5, Phase 6, Custom Command — **exact same function**.

### formatParsedResult() — Uses Safe Accessors

```kotlin
private fun formatParsedResult(response: NeedleResponse): String {
    val sb = StringBuilder()
    if (response.parseError != null) {
        sb.append("PARSE ERROR: ${response.parseError}\n")
        sb.append("Raw JSON was preserved in rawJson field\n")
        return sb.toString()
    }
    sb.append("Type: ${response.typeNonNull}\n")
    ...
    if (response.functionCallsNonNull.isNotEmpty()) { ... }
    ...
}
```

Uses `typeNonNull` and `functionCallsNonNull` computed properties.

---

## 4. Exact Phase 3 vs Phase 6 Parsing Path Comparison

### Phase 3 — Positive Tool Call (WORKS on Device)

```kotlin
// runPhase3() — line 402-436
val result = withContext(Dispatchers.IO) {
    NeedleJNI.complete("turn on the flashlight", 512)  // NO reset, NO init
}
phase.rawJson = result
phase.output = result

val parsed = parseNeedleResponse(result)  // SAME FUNCTION
phase.parsedResult = formatParsedResult(parsed)
phase.confidence = parsed.confidenceFloat

// CHECK:
if (parsed.functionCallsNonNull.any { it.name == "device.flashlight_on" }) {
    phase.status = TestStatus.Pass(...)
} else if (result.contains("device.flashlight_on") || result.contains("flashlight_on")) {
    phase.status = TestStatus.Pass(...)  // FALLBACK: raw string search
}
```

**State entering Phase 3**: Needle initialized via Phase 2 (`needle_init()` returned 70).

---

### Phase 6 — Serialized Calls (FAILS on Device)

```kotlin
// runPhase6() — line 517-566
for ((index, cmd) in commands.withIndex()) {
    // RESET before EACH command
    withContext(Dispatchers.IO) {
        NeedleJNI.reset()
    }
    
    // NO INIT after reset!
    val result = withContext(Dispatchers.IO) {
        NeedleJNI.complete(cmd, 512)
    }
    val parsed = parseNeedleResponse(result)  // SAME FUNCTION
    
    val hasFlashlight = parsed.functionCallsNonNull.any { it.name == "device.flashlight_on" }
    val expected = cmd.contains("flashlight", ignoreCase = true)
    val passed = (expected && parsed.functionCallsNonNull.any { it.name == "device.flashlight_on" }) ||
                 (!expected && parsed.functionCallsNonNull.isEmpty())
    
    results.add("Cmd: $cmd\nExpected flashlight: $expected\nGot flashlight: $hasFlashlight\nPassed: $passed\nOutput: $result\n")
}
```

**State entering each iteration**: Needle **reset but NOT re-initialized**.

---

### Critical Difference Summary

| Aspect | Phase 3 | Phase 6 |
|--------|---------|---------|
| `parseNeedleResponse()` | ✅ Same | ✅ Same |
| `functionCallsNonNull` accessor | ✅ Same | ✅ Same |
| Needle state before complete() | Initialized (Phase 2) | **Reset, NOT re-initialized** |
| Fallback check | `result.contains("flashlight_on")` | **None** |
| Raw JSON logged | Yes | Yes (in results) |

---

## 5. Runtime Values — Observability Analysis

### What the Current App Logs

| Value | Logged? | Where |
|-------|---------|-------|
| `result` (raw JSON) | ✅ Yes | `phase.rawJson`, `phase.output`, full log |
| `parsed.parseError` | ❌ **NO** | Only used internally in `formatParsedResult()` |
| `parsed.type` / `parsed.typeNonNull` | ❌ **NO** | Only in `parsedResult` UI string |
| `parsed.functionCalls` | ❌ **NO** | Only in `parsedResult` UI string |
| `parsed.functionCallsNonNull.size` | ❌ **NO** | Not logged |
| `parsed.functionCallsNonNull.firstOrNull()?.name` | ❌ **NO** | Not logged |
| `hasFlashlight` boolean | ❌ **NO** | Only in results string (but not parsed value) |

### What We Know from Device Report

**Device reported for Phase 6, Command 1 ("turn on the flashlight"):**
- Raw JSON: Contains `"function_calls":[{"name":"device.flashlight_on","arguments":{}}]`
- Got flashlight: `false`
- Passed: `false`

**But we CANNOT confirm from existing logs:**
- Was `parseError` null or non-null?
- Was `type` parsed correctly?
- Was `functionCalls` list populated or empty?
- Was `functionCallsNonNull.size` 0 or 1?
- What was the actual `hasFlashlight` boolean value?

---

## 6. Root-Cause Hypotheses (Ranked by Confidence)

### H1: Native State — Reset Without Init (HIGHEST CONFIDENCE: 75%)

**Evidence FOR**:
- Phase 5 (reset + init + complete) works: "✓ Tool call works after reset"
- Phase 6 (reset + complete, NO init) fails
- Native `needle_reset()` likely clears tool schema/context
- `needle_init()` re-establishes system prompt + tool definitions
- Without init, model may hallucinate CALL JSON but native state is invalid

**Evidence AGAINST**:
- Raw JSON shows valid `function_calls` array — if model had no tools, why emit CALL?
- Parser should still parse the JSON regardless of native state

**Refinement**: The model may emit CALL JSON based on conversation history, but without re-init, the tool definitions aren't registered in the native layer. The JSON parsing should still work though.

---

### H2: Stale APK — Device Test Used Pre-Fix Build (CONFIDENCE: 15%)

**Evidence FOR**:
- If physical test used APK from before commit `cfe79f4`, `@SerializedName` without `@field:` would fail to map `function_calls`
- Phase 3 has fallback `result.contains("flashlight_on")` which would pass even with broken parsing
- Phase 6 has NO fallback — purely relies on parsed `functionCallsNonNull`

**Evidence AGAINST**:
- CI run 34932636181 (commit 7be1071) includes the fix
- Device test timing unclear — may have used older APK

---

### H3: Phase 6 Raw JSON Differs from Displayed Output (CONFIDENCE: 5%)

**Evidence FOR**:
- The `result` variable logged to `results` list might not be the exact string passed to parser (unlikely — same variable)

**Evidence AGAINST**:
- Code uses same `result` variable for both logging and parsing

---

### H4: Gson Field Mapping Still Failing at Runtime (CONFIDENCE: 3%)

**Evidence FOR**:
- Edge case with `@field:SerializedName` on data class properties

**Evidence AGAINST**:
- Phase 3 would also fail if parser fundamentally broken
- Phase 3 passes on device

---

### H5: Logic Bug in Comparison/Reporting (CONFIDENCE: 2%)

**Evidence FOR**:
- String formatting in `results.add()` could have subtle bug

**Evidence AGAINST**:
- Code is straightforward: `val hasFlashlight = parsed.functionCallsNonNull.any { it.name == "device.flashlight_on" }`

---

## 7. Proven vs Unknown

### ✅ PROVEN

1. **CI commit 7be1071 includes parser fix** — `@field:SerializedName` annotations present
2. **Same `parseNeedleResponse()` used by Phase 3 and Phase 6** — single function
3. **Same `functionCallsNonNull` accessor used by both phases** — identical code path
4. **Phase 6 does NOT call `init()` after `reset()`** — confirmed by source inspection
5. **Phase 5 DOES call `init()` after `reset()` and works** — confirms pattern
6. **Device raw JSON contains valid `function_calls`** — per report
6. **Current app does NOT log parsed values** — `parseError`, `functionCalls`, `hasFlashlight` not in logs

### ❓ STILL UNKNOWN

1. **Did the physical device test use the CI artifact from run 34932636181?** — Or an older APK?
2. **What was `parsed.parseError` in Phase 6?** — Null (success) or non-null (parse failure)?
3. **What was `parsed.functionCalls` size after parsing?** — 0 or 1?
4. **What was `parsed.typeNonNull`?** — "call" or empty?
5. **Does the native model produce different JSON after reset without init?** — The reported raw JSON shows CALL, but is that the exact string that was parsed?
6. **Is the native `needle_complete()` behavior undefined after reset without init?** — Needle API doesn't specify

---

## 8. Single Smallest Next Diagnostic Action

### Add Minimal Diagnostic Logging to Phase 6 (READ-ONLY TEST)

**Do NOT change production logic. Only add 3 lines of logging inside the Phase 6 loop:**

```kotlin
// In runPhase6(), after line 544 (val parsed = parseNeedleResponse(result))
appendLog("PHASE6_DIAG: parseError=${parsed.parseError} type=${parsed.typeNonNull} funcCallsSize=${parsed.functionCallsNonNull.size} firstCall=${parsed.functionCallsNonNull.firstOrNull()?.name} hasFlashlight=${hasFlashlight}")
```

**This logs to existing fullLog (appendLog) — no UI changes, no new permissions, no architecture changes.**

**Expected outcomes:**
- If `parseError != null` → Parser fix not in APK → **H2 confirmed**
- If `parseError == null` AND `funcCallsSize == 0` → Gson mapping failed despite fix → **H4**
- If `parseError == null` AND `funcCallsSize == 1` AND `firstCall == "device.flashlight_on"` AND `hasFlashlight == false` → **Logic bug in comparison** → **H5**
- If `parseError == null` AND `funcCallsSize == 1` AND `firstCall == "device.flashlight_on"` AND `hasFlashlight == true` → Device report was wrong/misread
- If `parseError == null` AND `funcCallsSize == 1` BUT `firstCall` is different → Native state issue → **H1**

---

## 9. What This Diagnostic Does NOT Do

- ❌ Does NOT modify Phase 6 logic (no init after reset)
- ❌ Does NOT change Needle model/native/tools
- ❌ Does NOT change parser/Gson mappings
- ❌ Does NOT commit/push
- ❌ Does NOT add new tools

---

## 10. Summary

| Check | Result |
|-------|--------|
| CI commit verified | ✅ 7be1071 |
| Parser fix in CI commit | ✅ `@field:SerializedName` present |
| Single parser function | ✅ `parseNeedleResponse()` shared |
| Same accessor used | ✅ `functionCallsNonNull` |
| Phase 6 calls init after reset | ❌ **NO** (critical difference) |
| Runtime parsed values logged | ❌ **NOT LOGGED** |
| Device test APK provenance | ❓ **UNKNOWN** |

**Most likely**: H1 (Native state: reset without init) + H2 (Stale APK) combination. The parser fix is in the CI artifact, but we cannot confirm the physical device used it. The native state issue (no init after reset) is a definite bug regardless.

**Single next action**: Add 1-line diagnostic logging to Phase 6 loop to observe `parseError`, `typeNonNull`, `functionCallsNonNull.size`, and `hasFlashlight` at runtime.