# PHASE6_GSON_PARSER_FIX_REPORT

## Root Cause

The Gson deserialization was failing silently because the `NeedleResponse` data class had `rawJson` and `parseError` fields in the **primary constructor** without `@SerializedName` annotations. This confused Gson's constructor-based deserialization (enabled by `-java-parameters`), causing all fields to remain at their default values.

## Fix Applied

**File**: `app/src/main/java/com/example/needle/MainActivity.kt`

**Change**: Moved `rawJson` and `parseError` from the primary constructor to the class body as mutable properties (`var`).

```kotlin
// Before (broken):
data class NeedleResponse(
    @field:SerializedName("type") val type: String? = null,
    // ... other fields ...
    val rawJson: String = "",
    val parseError: String? = null
) { ... }

// After (fixed):
data class NeedleResponse(
    @field:SerializedName("type") val type: String? = null,
    // ... other fields ...
) {
    var rawJson: String = ""
    var parseError: String? = null
    // ... computed properties ...
}
```

**Why this works**:
- Only fields with `@field:SerializedName` are in the primary constructor
- Gson's constructor-based deserialization now correctly matches JSON fields to constructor parameters
- `rawJson` and `parseError` are set after parsing in `parseNeedleResponse()`
- No `@Transient` or ProGuard rules needed - fields without `@SerializedName` in class body are ignored by Gson

## Build Verification

| Check | Result |
|-------|--------|
| `compileDebugKotlin` | ✅ SUCCESS |
| `assembleDebug` | ✅ SUCCESS |
| CI Run | 35063376533 |
| Commit SHA | 8474281e4f67f460f5cbc03b0237b52bd3348472 |
| APK Artifact | `needle2-test-apk` from run 35063376533 |

## Diagnostic Logging Added

The APK now logs:
- `DIAG_TEST_JSON` - tests parsing with hardcoded valid JSON
- `PHASE3_RAW_JSON`, `PHASE6_RAW_JSON`, `CUSTOM_RAW_JSON` - actual JSON from Needle
- `PHASE6_DIAG` - parsed results for each command

## Physical Device Test Required

**Install APK** from GitHub Actions Run **35063376533** → Run Phase 6 → Check Full Raw Log for:

### Expected Diagnostic Output

For command "turn on the flashlight":
```
DIAG_TEST_JSON: type=call funcCalls=1 firstCall=device.flashlight_on confidence=1.0 parseError=null
PHASE6_RAW_JSON: len=... preview={"type":"call","success":true,"function_calls":[{"name":"device.flashlight_on"...
PHASE6_DIAG: parseError=null type=call funcCallsSize=1 firstCall=device.flashlight_on hasFlashlight=true
```

For command "what is the time":
```
PHASE6_DIAG: parseError=null type=call funcCallsSize=0 firstCall=null hasFlashlight=false
```

## Interpretation Guide

| `parseError` | `type` | `funcCallsSize` | `firstCall` | `hasFlashlight` | Meaning |
|--------------|--------|-----------------|-------------|-----------------|---------|
| `null` | `call` | `1` | `device.flashlight_on` | `true` | ✅ **FIX WORKS** |
| `null` | `call` | `0` | `null` | `false` | ❌ Parser still broken |
| non-null | any | any | any | any | ❌ Parse exception |

## Next Steps

1. **Install APK** from GitHub Actions Run **35063376533** on physical device
2. **Run Phase 6** (or "Run All Tests")
3. **Check Full Raw Log** for `PHASE6_DIAG` entries
4. **Report exact values** for each command

**Do not claim runtime fix until verified on physical device.**

---

## Files Changed

| File | Change |
|------|--------|
| `app/src/main/java/com/example/needle/MainActivity.kt` | Moved `rawJson`/`parseError` to class body; updated `parseNeedleResponse()` |

## Git Summary

| Item | Value |
|------|-------|
| Commit | 8474281e4f67f460f5cbc03b0237b52bd3348472 |
| CI Run | 35063376533 |
| Status | ✅ Build SUCCESS |
| APK | Available in CI artifacts |

---

**BUILD VERIFIED ✅** — **RUNTIME VERIFIED ⏳ PENDING**