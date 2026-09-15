# PHASE6_PARAMETERS_FIX_BUILD_REPORT

## 1. Exact File Changed

**File**: `app/build.gradle.kts`  
**Line**: 57 (inside `kotlinOptions` block)  
**Change**: Added Kotlin compiler flag `-java-parameters`

```diff
 kotlinOptions {
     jvmTarget = "17"
+    freeCompilerArgs += "-java-parameters"
 }
```

---

## 2. Why `-java-parameters` Was Added

**Root Cause** (from `PHASE6_GSON_ROOT_CAUSE_VERIFICATION_REPORT.md`):

- NeedleResponse is a **Kotlin data class**
- Gson 2.11.0 prefers **constructor-based deserialization** for data classes
- Without parameter names in class files, Gson cannot match JSON fields to constructor parameters
- Falls back to **field-based deserialization** → fails on Android (private Kotlin fields)
- All fields silently remain at defaults: `type=""`, `functionCalls=[]`, `confidence=0.0`

**Fix**: `-java-parameters` emits constructor parameter names to class files, enabling correct constructor-based deserialization.

---

## 3. Confirmation: No Other Production Files Changed

| File | Changed? |
|------|----------|
| `app/build.gradle.kts` | ✅ Yes (1 line) |
| `MainActivity.kt` | ❌ No |
| Phase 6 logic | ❌ No |
| NeedleJNI / native | ❌ No |
| Gson models | ❌ No |
| ProGuard rules | ❌ No |
| Dependencies | ❌ No |

---

## 4. Git Diff Summary

```bash
$ git diff --check
# No whitespace errors

$ git status --short
 M app/build.gradle.kts
?? PHASE6_GSON_RUNTIME_ROOT_CAUSE_REPORT.md
?? PHASE6_GSON_ROOT_CAUSE_VERIFICATION_REPORT.md
?? PHASE6_RUNTIME_PARSER_DIAGNOSTIC_REPORT.md
?? PHASE6_PARSER_RUNTIME_VERIFICATION_REPORT.md
?? PHASE6_RUNTIME_DIAGNOSTIC_BUILD_REPORT.md
?? PHASE6_PARSER_RUNTIME_FIX_REPORT.md
?? PHASE6_GSON_RUNTIME_ROOT_CAUSE_REPORT.md
?? PHASE6_GSON_RUNTIME_ROOT_CAUSE_VERIFICATION_REPORT.md
?? NULLABILITY_RUNTIME_CRASH_FIX_REPORT.md
?? PARSER_MAPPING_FIX_REPORT.md
?? TEST_SUITE_DIAGNOSTIC_AND_FIX_REPORT.md
?? TEST_SUITE_RUNTIME_FIX_REPORT.md

$ git diff -- app/build.gradle.kts
diff --git a/app/build.gradle.kts b/app/build.gradle.kts
index 722bbbd..a3265e3 100644
--- a/app/build.gradle.kts
+++ b/app/build.gradle.kts
@@ -54,6 +54,7 @@ android {
 
     kotlinOptions {
         jvmTarget = "17"
+        freeCompilerArgs += "-java-parameters"
     }
```

---

## 5. Git Commit SHA

**Commit**: `378b17bbe1d7aa107cc575a07ee487adbb087bc3`  
**Message**: "Fix Kotlin compiler flag: use -java-parameters instead of -parameters"  
**Branch**: `master`  
**Parent**: `7351688` (Fix Gson Kotlin constructor parameter metadata)

---

## 6. CI Run ID

**Run ID**: `35021849791`  
**URL**: https://github.com/unlocker911/needle2-jni-test/actions/runs/35021849791  
**Status**: ✅ **SUCCESS** (completed in ~3m)  
**Head SHA**: `378b17bbe1d7aa107cc575a07ee487adbb087bc3`

---

## 7. Build Result

| Step | Result |
|------|--------|
| `compileDebugKotlin` | ✅ SUCCESS |
| `assembleDebug` | ✅ SUCCESS |
| APK Generation | ✅ SUCCESS |
| Native Libraries | ✅ Packaged |
| Artifacts Uploaded | ✅ APK + native libs |

---

## 8. APK Artifact Information

| Artifact | Name | Path |
|----------|------|------|
| Debug APK | `needle2-test-apk` | `app/build/outputs/apk/debug/app-debug.apk` |
| Native Library | `libneedle2jni-so` | `lib/arm64-v8a/libneedle2jni.so` |
| C++ Runtime | — | `lib/arm64-v8a/libc++_shared.so` |
| Model Asset | — | `assets/needle2.cact` |

---

## 9. Physical Device Test Instructions

### Install APK
Download `needle2-test-apk` from GitHub Actions Run **35021849791** artifacts and install on ARM64 Android device (API 24+).

### Test Procedure

1. **Launch app** → Tap "Run All Tests" (or run Phase 1 → 2 → 6 sequentially)
2. **Verify Phase 1**: PASS (Model size, SHA match, loadResult: 0)
3. **Verify Phase 2**: PASS (needle_init() returned: 70)
4. **Run Phase 6**: Observe **Full Raw Log** tab for `PHASE6_DIAG:` entries

### Expected PHASE6_DIAG Output (Command 1: "turn on the flashlight")

```
PHASE6_DIAG: parseError=null type=call funcCallsSize=1 firstCall=device.flashlight_on hasFlashlight=true
```

### Expected PHASE6_DIAG Output (Command 2: "what is the time")

```
PHASE6_DIAG: parseError=null type=call funcCallsSize=0 firstCall=null hasFlashlight=false
```

### Interpretation Guide

| `parseError` | `type` | `funcCallsSize` | `firstCall` | `hasFlashlight` | Meaning |
|--------------|--------|-----------------|-------------|-----------------|---------|
| `null` | `call` | `1` | `device.flashlight_on` | `true` | ✅ **FIX WORKS** — Parser correct |
| `null` | `call` | `0` | `null` | `false` | ❌ Parser still broken |
| non-null | any | any | any | any | ❌ Parse exception |
| `null` | `respond` | `0` | `null` | `false` | 🔄 Native state issue (model returned respond) |

---

## 10. What Is Proven vs Unknown

### ✅ PROVEN
- CI build passes with `-java-parameters` flag
- Flag is accepted by Kotlin compiler (Kotlin 2.0.21)
- APK builds and packages correctly
- No R8/ProGuard issues (debug build, `isMinifyEnabled=false`)

### ❓ STILL UNKNOWN (Requires Device Test)
- Actual `PHASE6_DIAG` log values on device
- Whether constructor-based deserialization now works
- Whether Phase 6 correctly detects flashlight calls
- Whether Phase 3/4/5 continue to pass

---

## 11. Next Step

**Install APK from Run 35021849791 on physical device → Run Phase 6 → Check Full Raw Log for `PHASE6_DIAG:` entries → Report exact values for Command 1 ("turn on the flashlight")**

---

## BUILD VERIFIED ✅

- `compileDebugKotlin`: SUCCESS (Run 35021849791)
- `assembleDebug`: SUCCESS  
- APK Generation: SUCCESS
- Native Libraries: Packaged correctly

---

## RUNTIME VERIFIED ⏳ (PENDING)

The parser fix **must be verified on physical device**:

| Behavior | Status | Verification Method |
|----------|--------|---------------------|
| Phase 6 `PHASE6_DIAG` shows correct parse | ⏳ UNTESTED | Run Phase 6, check Full Raw Log |
| Phase 3/4/5 still PASS | ⏳ UNTESTED | Run All Tests |
| Custom Command parsing works | ⏳ UNTESTED | Test "turn on the flashlight" |

**DO NOT** claim runtime parser fix until manually verified on the APK installed on a physical Android device.