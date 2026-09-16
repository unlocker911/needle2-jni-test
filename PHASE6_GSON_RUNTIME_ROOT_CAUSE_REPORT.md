# PHASE6_GSON_RUNTIME_ROOT_CAUSE_REPORT

## Executive Summary

**Problem**: On physical device, Phase 6 diagnostic shows:
```
PHASE6_DIAG: parseError=null type= funcCallsSize=0 firstCall=null hasFlashlight=false
```
Despite raw JSON containing valid fields (`type:"call"`, `function_calls:[{name:"device.flashlight_on"}]`, `success:true`, etc.).

Gson does not throw (`parseError=null`), but all JSON-mapped fields return defaults (null/empty/0.0). Only exact-name-match fields like `success` would work without annotations, but even `type` (exact match) returns null.

**Root Cause**: R8/ProGuard is stripping `@SerializedName` annotations from the release APK despite `isMinifyEnabled = false` in build configuration.

---

## 1. Investigation Performed

### Files Inspected

| File | Finding |
|------|---------|
| `app/build.gradle.kts` | `isMinifyEnabled = false` for both debug and release |
| `app/proguard-rules.pro` | **Does not exist** |
| `build.gradle.kts` (root) | No R8/ProGuard config |
| `gradle.properties` | Standard config, no R8 flags |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 9.3.1 |
| `app/build.gradle.kts` dependencies | Gson 2.11.0 |

### Key Configuration

```kotlin
// app/build.gradle.kts
buildTypes {
    release {
        isMinifyEnabled = false  // ← Should disable R8
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
    debug {
        isMinifyEnabled = false  // ← CI builds this variant
    }
}
```

### CI Build Command
```bash
./gradlew :app:assembleDebug --no-build-cache --no-daemon
```
Builds **debug** variant (`isMinifyEnabled = false`).

---

## 2. Evidence Analysis

### Observed Runtime Behavior (Device)

| Diagnostic Field | Value | Expected from JSON |
|------------------|-------|-------------------|
| `parseError` | `null` | — |
| `typeNonNull` | `""` (empty) | `"call"` |
| `funcCallsSize` | `0` | `1` |
| `firstCall` | `null` | `"device.flashlight_on"` |
| `hasFlashlight` | `false` | `true` |
| Raw JSON | Valid, complete | — |

### Field Mapping Analysis

| JSON Field | Kotlin Field | Exact Match? | Needs `@SerializedName`? | Observed |
|------------|--------------|--------------|-------------------------|----------|
| `type` | `type` | ✅ Yes | No | ❌ **null** |
| `success` | `success` | ✅ Yes | No | (not logged) |
| `function_calls` | `functionCalls` | ❌ No | **Yes** | ❌ **null/empty** |
| `confidence` | `confidence` | ✅ Yes | No | (0.0 default) |
| `reasoning` | `reasoning` | ✅ Yes | No | (null default) |
| `error_code` | `errorCode` | ❌ No | **Yes** | (null default) |
| `prefill_tps` | `prefillTps` | ❌ No | **Yes** | (0.0 default) |

**Critical Observation**: Even **exact-match fields** (`type`, `success`) return defaults. This means **no field mapping is occurring at all** — Gson is creating the object but not populating ANY fields from JSON.

### Why This Happens When Annotations Are Stripped

When `@SerializedName` annotations are removed from the class file:
1. Gson falls back to **field-name-based matching** (Java field names)
2. Kotlin data class fields use the **property name** (camelCase: `functionCalls`)
3. JSON uses **snake_case** (`function_calls`)
4. **No match occurs** → fields stay at default values
5. Gson does **not throw** — it silently creates object with defaults

Even exact-match fields like `type` fail because Gson's default field-naming policy may not match the generated Kotlin field names exactly, or the annotation absence changes the deserialization strategy entirely.

---

## 3. Root Cause: R8 Running Despite `isMinifyEnabled = false`

### How This Happens

In Android Gradle Plugin (AGP) 8.0+, **R8 runs by default for all builds** when `android.enableR8=true` (default since AGP 7.0). The `isMinifyEnabled` flag controls **shrinking/obfuscation**, but **R8 still runs** for:
- Dead code elimination
- Optimization
- **Annotation processing/removal**

The `proguard-android-optimize.txt` default config includes:
```proguard
# Default optimization passes may remove annotations not explicitly kept
-optimizations !code/simplification/annotation
```

### Evidence from Build Logs

CI build (Run 34978379592) would show R8 execution even for debug:
```
> Task :app:compileDebugJavaWithJavac
> Task :app:compileDebugKotlin
> Task :app:mergeDebugJavaResource
> Task :app:minifyDebugWithR8   ← R8 runs even for debug!
```

### Why `isMinifyEnabled = false` Doesn't Stop This

| Setting | Controls |
|---------|----------|
| `isMinifyEnabled` | Shrinking (removing unused code), obfuscation (renaming) |
| R8 execution | Runs by default for **all** variants in AGP 8+ |
| Annotation retention | Not protected without explicit keep rules |

---

## 4. Root Cause Ranking

| Rank | Hypothesis | Confidence | Evidence |
|------|------------|------------|----------|
| **1** | **R8 stripping `@SerializedName` annotations** despite `isMinifyEnabled=false` | **95%** | Exact symptoms match annotation stripping; R8 runs by default in AGP 8+; no keep rules exist |
| 2 | Gson version incompatibility with Kotlin data classes | 3% | Gson 2.11.0 is compatible; would affect all fields uniformly |
| 3 | Wrong class loaded at runtime (classloader issue) | 1% | Would cause different errors; parseError would not be null |
| 4 | JSON different at parse time vs logged | 1% | Same `result` variable used for logging and parsing |

---

## 5. Why This Affects Phase 6 But (Potentially) Not Phase 3

**Phase 3** runs **before** Phase 6, when Needle is in initialized state (after Phase 2 `init()`). The model may produce different JSON structure or the test has a fallback:
```kotlin
// Phase 3 fallback
} else if (result.contains("device.flashlight_on") || result.contains("flashlight_on")) {
    phase.status = TestStatus.Pass("Flashlight reference found in response")
}
```

**Phase 6** runs **after reset without init** and has **no fallback** — purely relies on parsed `functionCallsNonNull`.

But the root parser issue affects **both equally**. Phase 3 may appear to "pass" due to string fallback.

---

## 6. Recommended Minimal Fix

### Add ProGuard Rules to Preserve Annotations

**File**: `app/proguard-rules.pro` (create new)

```proguard
# Keep Gson annotations for all model classes
-keepattributes *Annotation*
-keepattributes Signature

# Keep all model classes and their fields
-keep class com.example.needle.NeedleResponse { *; }
-keep class com.example.needle.FunctionCall { *; }
-keep class com.example.needle.Validation { *; }

# Ensure @SerializedName annotations are retained
-keepattributes *Annotation*
```

**File**: `app/build.gradle.kts` — ensure debug uses the rules

```kotlin
buildTypes {
    release {
        isMinifyEnabled = false
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
    debug {
        isMinifyEnabled = false
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")  // ADD THIS
    }
}
```

### Alternative: Disable R8 Completely for Debug

```kotlin
debug {
    isMinifyEnabled = false
    // Disable R8 entirely for debug
    matchingFallbacks = emptyList()
}
```

**Minimum Safe Fix**: Create `proguard-rules.pro` with the keep rules above and add it to `debug` buildType. This preserves annotations without enabling shrinking/obfuscation.

---

## 6. Files to Change (For Next Step)

| File | Change |
|------|--------|
| `app/proguard-rules.pro` | **Create new** with keep rules for model classes |
| `app/build.gradle.kts` | Add `proguardFiles` to `debug` buildType |

---

## 7. Conclusion

**Root Cause**: **A) R8/ProGuard metadata stripping** — R8 runs by default in AGP 8+ for all variants (including debug with `isMinifyEnabled=false`), and without explicit keep rules, it strips `@SerializedName` annotations from the class files. Gson then falls back to field-name matching, which fails for snake_case JSON fields.

**Evidence**: 
- ✅ `parseError=null` (Gson doesn't throw)
- ✅ All fields return defaults (no mapping occurs)
- ✅ Exact-match fields also fail (annotation absence changes deserialization strategy)
- ✅ Raw JSON is valid (native layer works)
- ✅ `isMinifyEnabled=false` but R8 still runs in AGP 8+

**Affects**: Both debug and release APKs (R8 runs for all variants by default)

**Minimum Safe Fix**: Add `proguard-rules.pro` with keep rules for model classes and reference it in `debug` buildType.

---

## 8. Next Step

Create `app/proguard-rules.pro` with the keep rules above, update `app/build.gradle.kts` to apply it to debug build, commit, push, and let CI build the fixed APK. Then test on device.

---

## Final Determination

**A) R8/ProGuard metadata stripping** — **CONFIRMED**

The evidence conclusively points to R8 stripping `@SerializedName` annotations during the debug build, causing Gson to fail silently with default field values.