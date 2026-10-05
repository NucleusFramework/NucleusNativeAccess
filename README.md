# Nucleus Native Access

[![Gradle Plugin Portal](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Fplugins.gradle.org%2Fm2%2Fdev%2Fnucleusframework%2Fnna%2Fdev.nucleusframework.nna.gradle.plugin%2Fmaven-metadata.xml&label=Gradle%20Plugin%20Portal)](https://plugins.gradle.org/plugin/dev.nucleusframework.nna)

A Gradle plugin that lets you use **Kotlin/Native code directly from the JVM** as if it were a regular JVM library. Classes, methods, properties, enums, nullable types, companion objects, exception propagation, callbacks &mdash; everything is transparent to the JVM developer.

Under the hood, the plugin generates [FFM (Foreign Function & Memory API)](https://openjdk.org/jeps/454) bindings inspired by [swift-java](https://github.com/swiftlang/swift-java) and [swift-export-standalone](https://github.com/JetBrains/kotlin/tree/master/native/swift/swift-export-standalone).

## How it works

```
Kotlin/Native source           Plugin generates              JVM developer sees
──────────────────            ────────────────              ──────────────────
class Calculator {      →     @CName bridges (native)   →   class Calculator : AutoCloseable {
  fun add(value: Int)         + StableRef lifecycle           fun add(value: Int): Int
  val current: Int            + FFM MethodHandles             val current: Int
}                             + output-buffer strings         // backed by native, via FFM
                                                          }
```

**Pipeline:**

1. Plugin parses your `nativeMain` sources via Kotlin PSI and extracts the public API
2. Generates `@CName` bridge functions with `StableRef` for object lifecycle (native side)
3. Generates JVM proxy classes with FFM `MethodHandle` downcalls (JVM side)
4. Compiles to a shared library (`.so` / `.dylib` / `.dll`)
5. Bundles the native library into the JAR under `kne/native/{os}-{arch}/`
6. Generates GraalVM reachability metadata (reflection, resources, FFM downcall descriptors)
7. JVM code calls the proxies transparently &mdash; every call crosses the FFM boundary into native

## Quick start

### 1. Apply the plugin

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

```kotlin
// build.gradle.kts
plugins {
    kotlin("multiplatform") version "2.4.20"
    id("dev.nucleusframework.nna") version "0.1.0"
}
```

### 2. Configure targets

```kotlin
kotlin {
    jvmToolchain(25) // FFM is stable since JDK 22 (JEP 454), recommended JDK 25

    linuxX64()       // use the real platform name (KMP convention)
    // macosArm64()  // on macOS
    // mingwX64()    // on Windows

    jvm()

    sourceSets {
        jvmMain {
            dependencies {
                // your JVM dependencies (Compose, Ktor, etc.)
            }
        }
    }
}

kotlinNativeExport {
    nativeLibName = "mylib"         // output: libmylib.so (release ~700KB)
    // nativePackage is auto-detected from your Kotlin source package declarations
    // nativePackage = "com.example"   // override only if needed
    buildType = "release"           // "release" (default, optimized) or "debug"
}
```

### 3. Write Kotlin/Native code

```kotlin
// src/nativeMain/kotlin/com/example/Calculator.kt
package com.example

class Calculator(initial: Int = 0) {
    private var acc = initial

    fun add(value: Int): Int { acc += value; return acc }
    fun subtract(value: Int): Int { acc -= value; return acc }
    val current: Int get() = acc
    fun describe(): String = "Calculator(current=$acc)"
}
```

### 4. Use it from JVM as if it were a normal class

```kotlin
// src/jvmMain/kotlin/com/example/Main.kt
package com.example

fun main() {
    val calc = Calculator(0)  // allocates a Kotlin/Native object
    calc.add(5)               // FFM → native → StableRef → add()
    calc.add(3)
    println(calc.current)     // 8
    println(calc.describe())  // "Calculator(current=8)"
    calc.close()              // releases the native object (also auto-GC'd via Cleaner)
}
```

No JNI. No annotations. No boilerplate. Just write Kotlin/Native and use it from JVM.

### 5. Run

```bash
./gradlew jvmTest    # compiles native + generates bridges + runs JVM tests
./gradlew run        # if using Compose Desktop / Nucleus
```

## What's supported

### Types — test coverage (1700+ end-to-end FFM tests)

Every test compiles Kotlin/Native → `libcalculator.so` (470+ exported symbols) → loads via FFM `MethodHandle` → verifies on JVM. Zero mocks — all 1700+ tests cross the real native boundary. Includes load tests (500K+ FFM calls), concurrent stress tests, 110+ suspend function tests with cancellation, 50+ Flow tests, and 300+ inheritance/interface/extension tests.

| Feature | As param | As return | As property | CB param | CB return | Notes |
|---------|----------|-----------|-------------|----------|-----------|-------|
| `Int` | ✅ 5t | ✅ 5t | ✅ 2t | ✅ 3t | ✅ 2t | direct pass-through |
| `Long` | ✅ 2t | ✅ 2t | &mdash; | ✅ 2t | ✅ 2t | direct pass-through |
| `Double` | ✅ 2t | ✅ 2t | ✅ 1t | ✅ 2t | ✅ 2t | direct pass-through |
| `Float` | ✅ 1t | ✅ 1t | &mdash; | ✅ 1t | ✅ 1t | direct pass-through |
| `Boolean` | ✅ 3t | ✅ 2t | ✅ 1t | ✅ 3t | ✅ 1t | 0/1 convention over FFM |
| `Byte` | ✅ 1t | ✅ 1t | &mdash; | ✅ 1t | ✅ 1t | direct pass-through |
| `Short` | ✅ 1t | ✅ 1t | &mdash; | ✅ 1t | ✅ 1t | direct pass-through |
| `String` | ✅ 4t | ✅ 4t | ✅ 3t | ✅ 4t | ✅ 3t | output-buffer pattern |
| `Unit` | &mdash; | ✅ 1t | &mdash; | &mdash; | ✅ 3t | `FunctionDescriptor.ofVoid(...)` |
| `enum class` | ✅ 3t | ✅ 2t | ✅ 2t | ✅ 2t | ✅ 3t | ordinal mapping |
| Classes | ✅ 3t | ✅ 4t | &mdash; | ✅ 19t | ✅ 2t | opaque handle via `StableRef` (incl. Object in callbacks) |
| Nested classes | ✅ | ✅ | ✅ | &mdash; | &mdash; | exported as `Outer_Inner`, supports 3+ nesting levels |
| `T?` (nullable) | ✅ 3t | ✅ 8t | ✅ 3t | ❌ | &mdash; | sentinel-based null encoding (incl. `DataClass?`) |
| `data class` | ✅ 4t | ✅ 6t | &mdash; | ✅ 5t | ✅ 3t | all field types: primitive, String, Enum, Object, nested DC, List, Set, Map |
| `ByteArray` | ✅ 2t | ✅ 2t | &mdash; | ❌ | &mdash; | pointer + size pattern, suspend ✅ |
| `List<T>` | ✅ 26t | ✅ 17t | &mdash; | ✅ 12t | ✅ 5t | Int, Long, Double, Float, Short, Byte, Boolean, String, Enum, Object |
| `List<DC>` | &mdash; | ✅ 15t | &mdash; | &mdash; | &mdash; | opaque handle + size/get/dispose bridges (Point, NamedValue, TaggedPoint) |
| `List<T>?` | ✅ 7t | ✅ 8t | &mdash; | &mdash; | &mdash; | -1 count = null sentinel |
| `Set<T>` | ✅ 9t | ✅ 13t | &mdash; | &mdash; | &mdash; | Int, String, Enum + intersect/empty edge cases |
| `Set<T>?` | &mdash; | ✅ 5t | &mdash; | &mdash; | &mdash; | -1 count = null sentinel |
| `Map<K, V>` | ✅ 12t | ✅ 12t | &mdash; | ✅ 2t | ✅ 2t | String→Int, Int→String, Int→Int, String→String + merge/empty |
| `Map<K, V>?` | &mdash; | ✅ 4t | &mdash; | &mdash; | &mdash; | -1 count = null sentinel |
| `(T) -> R` (lambda) | ✅ 15t | &mdash; | &mdash; | &mdash; | &mdash; | per-call upcall arena, exceptions propagate with their original type |
| `Flow<T>` | &mdash; | ✅ 50t+ | &mdash; | &mdash; | &mdash; | static onNext/onError/onComplete stubs + credit-based backpressure, incl. `Flow<DataClass>` |

### Declarations

| Feature | Supported | Notes |
|---------|-----------|-------|
| Top-level classes | ✅ | `StableRef` lifecycle, `AutoCloseable` on JVM |
| Open / abstract classes | ✅ | `open class Shape` → JVM `open class Shape`, hierarchy mirrored |
| Inheritance | ✅ | `class Circle : Shape` → JVM `class Circle : Shape(handle)`, multi-level (3+) |
| Interfaces | ✅ | `interface Measurable` → JVM `interface Measurable`, multi-interface impl |
| Sealed classes | ✅ | `sealed class AppResult` → JVM `sealed class`, subclass ordinal bridges |
| Extension functions | ✅ | `fun Shape.displayName()` → real Kotlin extension on JVM proxy |
| Nested classes | ✅ | exported as `Outer_Inner`, qualified bridge symbols |
| Methods (fun) | ✅ | instance methods with any supported param/return types, `override` preserved |
| Properties (val/var) | ✅ | getters + setters, all supported types, including constructor `val`/`var` params |
| Constructors | ✅ | primary constructor with supported param types |
| Constructor default params | ✅ | generates overloads for trailing default parameters |
| Companion objects | ✅ | static methods and properties on JVM proxy |
| Top-level functions | ✅ | grouped into a singleton `object` on JVM |
| Enum classes | ✅ | auto-generated JVM enum with ordinal mapping |
| Data classes (nativeMain) | ✅ | auto-generates JVM data class + field marshalling |
| Data classes (commonMain) | ✅ | reuses existing JVM type, no proxy generated |
| Suspend functions | ✅ | `suspendCancellableCoroutine` + bidirectional cancellation (110+ tests) |
| Flow&lt;T&gt; return | ✅ | onNext/onError/onComplete callbacks with backpressure, no element dropped (50+ tests) |
| Exception propagation | ✅ | `try/catch` wrapping, `KotlinNativeException` on JVM (thread-local, safe under concurrency) |
| Object lifecycle | ✅ | `Cleaner` for GC + `close()` for explicit release; calls after `close()` throw `IllegalStateException` |

### Suspend functions

Kotlin/Native `suspend fun` is transparently mapped to JVM `suspend fun`. The developer writes coroutines on both sides — no callbacks, no `CompletableFuture`.

```kotlin
// Kotlin/Native
suspend fun fetchData(query: String): String {
    delay(100)
    return "result for $query"
}

// JVM — transparent, just a suspend fun
val result = calc.fetchData("test")  // suspends the coroutine
```

**How it works**: the native bridge launches a `CoroutineScope` with a `Job` and reports completion through two static FFM upcall stubs, dispatched by a per-call id. The JVM proxy uses `suspendCancellableCoroutine` to suspend until the native coroutine completes.

**Cancellation**: JVM coroutine cancel → `Job.cancel()` on native side. Native `CancellationException` → JVM `CancellationException`. Bidirectional, automatic.

**Supported return types**: `Int`, `Long`, `Double`, `Float`, `Boolean`, `Byte`, `Short`, `String`, `ByteArray`, `Unit`, `enum class`, `Object`, `data class`, `List<T>`, `Set<T>`, `Map<K,V>`, nullable variants.

### Flow&lt;T&gt;

Kotlin/Native `Flow<T>` is transparently mapped to JVM `Flow<T>` via `channelFlow`. Event streams, tickers, and reactive patterns work naturally.

```kotlin
// Kotlin/Native
fun countUp(max: Int): Flow<Int> = flow {
    for (i in 1..max) { delay(10); emit(i) }
}

// JVM — transparent Flow collection
calc.countUp(5).collect { println(it) }  // 1, 2, 3, 4, 5
calc.countUp(100).toList()               // [1, 2, ..., 100]
calc.infiniteFlow().take(3).toList()     // [0, 1, 2] — auto-cancelled
```

**How it works**: 3 static FFM upcall stubs (`onNext`, `onError`, `onComplete`) are dispatched by a per-call id. The native side collects the Flow in a `CoroutineScope` and only emits against credits granted as the JVM collector consumes elements, so a slow collector applies backpressure instead of losing elements.

**Cancellation**: collecting only N elements (via `take`, `first`) automatically cancels the native Flow collection. Manual `Job.cancel()` also propagates.

**Supported element types**: `Int`, `Long`, `Double`, `Float`, `Boolean`, `Byte`, `Short`, `String`, `enum class`, `Object`, `data class` (including nested data classes).

**Data class in Flow**: data classes are serialized element-by-element via `StableRef` + per-type reader bridges. Nested data classes (e.g. `Flow<Rect>` where `Rect` contains two `Point`) are fully supported.

```kotlin
// Kotlin/Native
data class MemoryInfo(val totalMB: Long, val availableMB: Long)

fun memoryFlow(intervalMs: Long = 1000L): Flow<MemoryInfo> = flow {
    while (true) { emit(MemoryInfo(getTotalMemoryMB(), getAvailableMemoryMB())); delay(intervalMs) }
}

// JVM — transparent Flow<DataClass> collection
desktop.memoryFlow(2000L).collect { info ->
    println("${info.availableMB} MB / ${info.totalMB} MB")
}
```

### Callbacks & lambdas

JVM lambdas cross the FFM boundary via upcall stubs. The plugin generates all the FFM infrastructure automatically.

**Lifecycle**: upcall stubs are allocated in a per-call arena and released when the call returns, so a callback is valid **for the duration of the call only** (as with JNI). Native code must not store a callback and invoke it later; expose event streams as `Flow<T>` instead.

**Exceptions**: an exception thrown by a JVM callback unwinds the native caller and is rethrown on the JVM with its original type and message.

**Supported callback signatures**:
- Params: `Int`, `Long`, `Double`, `Float`, `Boolean`, `Byte`, `Short`, `String`, `enum class`, `data class`
- Returns: `Int`, `Long`, `Double`, `Float`, `Boolean`, `Byte`, `Short`, `String`, `Unit`, `enum class`, `data class`
- Multi-param: `(T, U) -> R` with any supported types
- Data class params are decomposed into individual fields at C ABI level

```kotlin
// Kotlin/Native
fun onValueChanged(callback: (Int) -> Unit) { callback(accumulator) }
fun transform(fn: (Int) -> Int): Int { accumulator = fn(accumulator); return accumulator }
fun formatWith(formatter: (Int) -> String): String = formatter(accumulator)

// JVM — transparent
calc.onValueChanged { value -> println("Value: $value") }
calc.transform { it * 2 }
calc.formatWith { "Result: $it" }

// Event streams: use Flow rather than stored callbacks
desktop.trayClicks().collect { index -> println("Clicked: $index") }
```

### Collections

`List<T>`, `Set<T>`, and `Map<K, V>` cross the FFM boundary using flat arrays (pointer + size), inspired by swift-java's `[UInt8]` lowering.

There is no size limit: results that do not fit the initial transfer buffers (4096 elements / 8 KB of packed strings) are parked natively and read back with a larger buffer, without calling the function twice. The same applies to large `String`, `ByteArray` and data class `String` fields.

**Supported element types**: `Int`, `Long`, `Double`, `Float`, `Short`, `Byte`, `Boolean`, `String`, `enum class`

```kotlin
// Kotlin/Native
fun getScores(): List<Int> = listOf(accumulator, accumulator * 2, accumulator * 3)
fun sumAll(values: List<Int>): Int { accumulator = values.sum(); return accumulator }
fun getMetadata(): Map<String, Int> = mapOf("current" to accumulator, "scale" to scale.toInt())

// JVM — transparent
val scores = calc.getScores()              // [10, 20, 30]
calc.sumAll(listOf(1, 2, 3, 4, 5))        // 15
val meta = calc.getMetadata()              // {current=42, scale=3}
```

| Collection | C ABI encoding |
|---|---|
| `List<primitive>` | `CPointer<XxxVar>` + `size: Int` |
| `List<String>` | packed null-terminated buffer + count |
| `List<Enum>` | ordinal array + count |
| `Set<T>` | same as `List<T>` (converted at boundary) |
| `Map<K, V>` | parallel key + value arrays + count |

### Data classes

Data classes are marshalled **by value** (field decomposition) &mdash; each field becomes a separate C ABI argument. Supported field types: all primitives + `String`.

The JVM data class (and `enum class`) is a verbatim copy of the native declaration: default values, body members, companion objects, KDoc and annotations are preserved. Imports of native-only packages (`kotlinx.cinterop`, `platform.*`, `kotlin.native`) are dropped, so bodies must only use APIs available on the JVM.

```kotlin
// Can be in commonMain or nativeMain
data class Point(val x: Int, val y: Int)

// nativeMain
fun getPoint(): Point = Point(accumulator, accumulator * 2)
fun addPoint(p: Point): Int { accumulator += p.x + p.y; return accumulator }

// JVM — uses the real data class (not an opaque handle)
val p = calc.getPoint()          // Point(x=5, y=10)
calc.addPoint(Point(3, 7))       // 10
```

- **commonMain data classes**: the JVM already has the type &mdash; no proxy generated, field marshalling only
- **nativeMain data classes**: the plugin generates the JVM `data class` file automatically

### Exception propagation

All native bridge functions are wrapped in `try/catch`. When an exception occurs:

1. The native side captures the error message in a `@ThreadLocal` variable
2. The JVM proxy calls `kne_hasError()` after every downcall
3. If an error is detected, `kne_getLastError()` retrieves the message
4. A `KotlinNativeException(message)` is thrown on the JVM side

```kotlin
try { calc.divide(0) } catch (e: KotlinNativeException) { println(e.message) }
calc.add(5) // works normally after exception
```

### Nullable type encoding

| Nullable type | Wire type | Null sentinel |
|---------------|-----------|---------------|
| `String?` | output-buffer `Int` | -1 = null |
| `Object?` | `JAVA_LONG` | 0L = null |
| `Enum?` | `JAVA_INT` | -1 = null |
| `Boolean?` | `JAVA_INT` | -1 = null, 0 = false, 1 = true |
| `Int?` | `JAVA_LONG` | `Long.MIN_VALUE` = null |
| `Long?` | `JAVA_LONG` | `Long.MIN_VALUE` = null |
| `Short?` | `JAVA_INT` | `Int.MIN_VALUE` = null |
| `Byte?` | `JAVA_INT` | `Int.MIN_VALUE` = null |
| `Float?` | `JAVA_LONG` (raw bits) | `Long.MIN_VALUE` = null |
| `Double?` | `JAVA_LONG` (raw bits) | `Long.MIN_VALUE` = null |

## Benchmarks — Native (FFM) vs Pure JVM

Measured on Intel Core i5-14600 (20 cores), 45 GB RAM, Ubuntu 25.10, JDK 25 (GraalVM), Kotlin 2.4.20.

**Methodology**: each benchmark runs the operation in a tight loop. 3 warmup iterations are discarded, then 5 measured iterations are averaged. "Native" creates a proxy object via FFM and calls into the Kotlin/Native shared library (.so). "JVM" runs the equivalent Kotlin/JVM code directly. Ratio = native/jvm (>1 = native slower due to FFM overhead). Memory is measured via `Runtime.totalMemory() - freeMemory()` before/after with explicit GC.

### Compute-bound (work stays in native, single FFM call)

| Benchmark | Native | JVM | Ratio | Analysis |
|-----------|--------|-----|-------|----------|
| Fibonacci recursive (n=35) | 18.07 ms | 23.85 ms | **0.76x** | Native faster (no JIT warmup needed) |
| Fibonacci iterative (n=1M) | 0.30 ms | 0.21 ms | 1.43x | Near-equal, JVM JIT slightly ahead |
| Pi Leibniz series (10M iter) | 8.60 ms | 8.56 ms | **1.01x** | Identical performance |
| String concat loop (10K) | 21.65 ms | 17.62 ms | 1.23x | Near-equal |
| Bubble sort (5K elements) | 13.13 ms | 4.78 ms | 2.75x | JVM JIT optimizes array access better |

### FFM call overhead (many small downcalls)

| Benchmark | Native | JVM | Ratio | Analysis |
|-----------|--------|-----|-------|----------|
| 100K trivial calls | 4.94 ms | 0.31 ms | 16x | ~49 ns/call FFM overhead |
| 10K create+close cycles | 4.12 ms | 0.11 ms | 36x | StableRef alloc+dispose cost |
| 10K data class returns | 4.32 ms | 0.15 ms | 29x | Out-param marshaling cost |
| 10K string returns | 6.45 ms | 0.59 ms | 11x | Output-buffer + UTF-8 copy |
| 10K data class params | 0.96 ms | 0.01 ms | 65x | Field expansion overhead |
| 5K list params (100 elems) | 7.78 ms | 2.89 ms | 2.70x | Arena alloc + memcpy |

### Concurrent (10 threads, separate instances)

| Benchmark | Native | JVM | Ratio |
|-----------|--------|-----|-------|
| 10t &times; 1K fib(100) | 3.83 ms | 0.49 ms | 7.87x |
| 10t &times; 1K string reverse | 2.91 ms | 0.69 ms | 4.24x |
| 10t &times; 1K create+close | 2.25 ms | 0.44 ms | 5.07x |
| 10t &times; 1K DC roundtrip | 2.11 ms | 0.92 ms | 2.28x |

### Memory allocation

| Benchmark | Native | JVM | Analysis |
|-----------|--------|-----|----------|
| 100K point allocations | **0 KB** | 3,071 KB | Native: no JVM heap pressure |
| 10t &times; 10K points (concurrent) | **1,151 KB** | 5,124 KB | Native uses 4.5x less JVM memory |
| String concat (10K) | **0 KB** | 131,680 KB | Native: strings stay on native heap |

**Key takeaways**:
- **Compute-bound workloads** (fibonacci, pi, sorting) run at near-native speed &mdash; the FFM boundary is crossed once, then all work happens in Kotlin/Native
- **FFM call overhead** is ~49 ns/call &mdash; negligible for methods that do real work, visible only in micro-benchmarks with 100K+ trivial calls
- **Memory advantage**: native allocations don't touch the JVM heap, reducing GC pressure significantly (0 KB vs 131 MB for string-heavy workloads)
- **Thread-safe**: all concurrent benchmarks pass with zero crashes (AtomicReference error state, idempotent dispose)

## What's NOT supported

### Not yet implemented

| Feature | Notes |
|---------|-------|
| Generics (`class Box<T>`) | Complex type erasure at FFM boundary — use concrete types |
| Interface / sealed class as return type | Methods must return the concrete type, not the interface/sealed parent |
| Operator overloading (`operator fun plus`) | Use named methods (`fun add()`) |
| Infix functions | Use regular method syntax |
| Extension functions on stdlib types | Only extensions on project classes are bridged |

### By design

| Feature | Reason | Alternative |
|---------|--------|-------------|
| Private/internal/protected members | Only public API is exported | Use `public` modifier |
| Expect/actual declarations | KMP's responsibility | Use platform-specific source sets |
| Storing a JVM callback on the native side | Upcall stubs are released when the call returns | Expose events as `Flow<T>` |
| NUL characters in `String` arguments | Native strings are NUL-terminated; rejected with `IllegalArgumentException` | Encode the data (e.g. `ByteArray`) |
| `ByteArray` in collections | Buffer lifecycle complexity across FFM | Use `List<Int>` or Base64 String |
| `ByteArray` as data class field | Out-param buffer not wired for DC fields | Use separate method or String |
| `ByteArray` as callback param | Buffer lifecycle across callback boundary | Use String (Base64) |
| Lambda as callback return type | Callbacks supported as parameters only | Return object with methods |
| CInterop types in public API (`CPointer`, `COpaque`) | Kotlin/Native-only types, not marshallable | Wrap behind a Kotlin API |
| Subclassing from JVM | JVM proxy classes are handles, not real native objects | Subclass on native side |

### Scope limitations

The bridge is designed for **Kotlin-level APIs** — clean classes, interfaces, data classes, functions. It is **not** a C FFI wrapper. Projects that expose raw C types in their public API (like GTK bindings with `CPointer<GtkWidget>`) are not compatible. Wrap them behind a clean Kotlin API first.



## Configuration reference

```kotlin
kotlinNativeExport {
    // Name of the shared library (required)
    // Produces: libmylib.so (Linux), libmylib.dylib (macOS), mylib.dll (Windows)
    nativeLibName = "mylib"

    // Package for JVM proxies — auto-detected from your Kotlin source package declarations
    // Only set this if you have multiple packages and want to override the auto-detection
    // nativePackage = "com.example"

    // Build type: "release" (default, ~700KB .so) or "debug" (~6MB .so)
    buildType = "release"
}
```

### JVM runtime requirements

The generated FFM proxies require:

- **JDK 22+** (FFM API finalized in JDK 22 via [JEP 454](https://openjdk.org/jeps/454), recommended JDK 25)
- **`--enable-native-access=ALL-UNNAMED`** JVM arg (auto-configured for tests by the plugin)

The native library is automatically bundled in the JAR and extracted at runtime &mdash; no manual `java.library.path` configuration needed.

### Zero-config native library loading

The generated `KneRuntime` uses a three-tier loading strategy:

1. **`java.library.path`** &mdash; for development, packaged apps, or manual override
2. **JAR extraction** &mdash; extracts from `kne/native/{os}-{arch}/` in the classpath to a persistent cache (`~/.cache/kne/`)
3. **Loader lookup** &mdash; fallback for GraalVM native-image (native lib beside the executable)

### Build performance & Gradle Cache

`generateKneNativeBridges` is a `@CacheableTask` and works incrementally between executions:

- **Build cache**: outputs depend only on relative source paths/contents, the PSI classpath and the extension configuration, so they are restored `FROM-CACHE` across checkouts and machines (enable with `org.gradle.caching=true`). Sources are merged in a deterministic order, so the generated code is reproducible.
- **Source file → IR mapping**: each source file maps to a cached prescan (its type declarations) and IR fragment, kept in `build/kne/incremental` (Gradle local state). A file is re-parsed only if its content changed, or if a symbol it looked up now resolves differently (e.g. a referenced data class gained a field). The PSI environment is not even started when nothing needs parsing.
- **IR → outputs mapping**: when the merged API is unchanged (e.g. only function bodies were edited), code generation is skipped. Otherwise only generated files whose content changed are rewritten and stale ones are deleted, so the downstream incremental Kotlin compilation only recompiles what actually changed.

### GraalVM native-image support

The plugin auto-generates GraalVM reachability metadata under `META-INF/native-image/kne/{libName}/`:

- `reflect-config.json` &mdash; all generated proxy classes
- `resource-config.json` &mdash; bundled native library resources
- `reachability-metadata.json` &mdash; FFM foreign downcall + upcall descriptors, reflection, and resources

For GraalVM native-image builds, the native `.so`/`.dylib` must be placed next to the executable (the plugin bundles it in the JAR for JVM, but native-image can't extract at runtime).

### Using with Compose Desktop / Nucleus

The Compose compiler plugin and Nucleus Native Access both add Kotlin/Native targets, but Compose doesn't support arbitrary native compilations (e.g. `linuxX64`, `mingwX64` for FFM bridges). **The recommended approach is to put your native code in a separate Gradle module** without the Compose compiler plugin:

```
my-app/
├── native/              ← Kotlin/Native + nna (no Compose)
│   └── build.gradle.kts
├── app/                 ← Compose Desktop + depends on :native
│   └── build.gradle.kts
└── settings.gradle.kts
```

**`:native/build.gradle.kts`** — native bridge module:

```kotlin
plugins {
    kotlin("multiplatform") version "2.4.20"
    id("dev.nucleusframework.nna")
}

kotlin {
    jvmToolchain(25)
    linuxX64()  // or macosArm64(), mingwX64()
    jvm()
}

kotlinNativeExport {
    nativeLibName = "mylib"
    // nativePackage auto-detected from source package declarations
}
```

**`:app/build.gradle.kts`** — Compose Desktop module:

```kotlin
plugins {
    kotlin("multiplatform") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("io.github.kdroidfilter.nucleus") version "1.7.2"
}

kotlin {
    jvmToolchain(25)
    jvm()

    sourceSets {
        jvmMain {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(project(":native"))
            }
        }
    }
}

nucleus.application {
    mainClass = "com.example.MainKt"
    jvmArgs += listOf("--enable-native-access=ALL-UNNAMED")
}
```

This avoids any conflict between the Compose compiler and Kotlin/Native targets used for FFM bridges.

### Using with C interop (e.g. libnotify)

You can combine the plugin with Kotlin/Native cinterop to wrap native C libraries and expose them to JVM:

```kotlin
// build.gradle.kts
kotlin {
    linuxX64().compilations["main"].cinterops {
        create("libnotify") {
            defFile(project.file("src/nativeInterop/cinterop/libnotify.def"))
        }
    }
}
```

```kotlin
// src/nativeMain/kotlin/LinuxDesktop.kt
class LinuxDesktop {
    fun sendNotification(title: String, body: String, icon: String): Boolean {
        // calls libnotify via cinterop — impossible from JVM without JNI
        notify_init("MyApp")
        val n = notify_notification_new(title, body, icon) ?: return false
        return notify_notification_show(n, null) != 0
    }

    fun getHostname(): String = memScoped {
        val buf = allocArray<ByteVar>(256)
        gethostname(buf, 256u)
        buf.toKString()
    }
}
```

```kotlin
// src/jvmMain/kotlin/Main.kt — transparent usage
val desktop = LinuxDesktop()
desktop.sendNotification("Hello", "From Kotlin/Native via FFM!", "dialog-information")
println(desktop.getHostname())
desktop.close()
```

## Examples

The repository includes two complete examples in [`examples/`](examples/):

| Example | Description |
|---------|-------------|
| [`calculator/`](examples/calculator/) | Stateful Calculator class with 1700+ end-to-end tests: all types, callbacks, collections, suspend, Flow, nested classes, inheritance hierarchies, interfaces, sealed classes, extension functions, concurrency |
| [`systeminfo/`](examples/systeminfo/) | Linux system info (`/proc`, POSIX, `gethostname`) + native notifications via `libnotify` cinterop, with Compose Desktop UI |
| [`benchmark/`](examples/benchmark/) | Performance benchmarks: native vs JVM (fibonacci, pi, sort, string, allocation, concurrent) |

Run them:

```bash
./gradlew :examples:calculator:run
./gradlew :examples:systeminfo:run
./gradlew :examples:calculator:jvmTest    # 1700+ end-to-end FFM tests
./gradlew :examples:benchmark:jvmTest     # Performance benchmarks (native vs JVM)
```

## Architecture

The plugin is inspired by two projects:

- **[swift-export-standalone](https://github.com/JetBrains/kotlin/tree/master/native/swift/swift-export-standalone)** (JetBrains) &mdash; how Kotlin exports its API to Swift via C bridges. We adapted the approach: scan Kotlin sources, generate `@CName` bridge functions with `StableRef` for object lifecycle.

- **[swift-java](https://github.com/swiftlang/swift-java)** (Apple) &mdash; how Swift code is made callable from Java via FFM `MethodHandle` downcalls. We adapted the FFM binding generation: each method gets a `FunctionDescriptor` + `MethodHandle`, classes use `Cleaner` for GC safety.

```
plugin-build/plugin/src/main/kotlin/dev/nucleusframework/nna/plugin/
├── ir/                          # Intermediate representation (inspired by SirModule)
│   └── KneIR.kt                 # KneModule, KneClass, KneFunction, KneType...
├── analysis/
│   ├── PsiSourceParser.kt       # Kotlin PSI-based source parser (kotlin-compiler-embeddable), incremental per file
│   ├── SymbolTable.kt           # PSI-free symbol table + type resolution with lookup tracking
│   ├── ParseCache.kt            # Persistent source file → IR / IR → outputs cache
│   └── PsiParseWorkAction.kt    # Gradle Worker for isolated PSI classloader
├── codegen/
│   ├── NativeBridgeGenerator.kt # @CName + StableRef bridges (inspired by @_cdecl thunks)
│   ├── FfmProxyGenerator.kt     # JVM proxy classes with FFM (inspired by FFMSwift2JavaGenerator)
│   └── GeneratedOutputs.kt      # Write-if-changed output sync + stale file removal
├── tasks/
│   └── GenerateNativeBridgesTask.kt  # Single task: PSI parse + native bridges + JVM proxies + GraalVM metadata
├── KotlinNativeExportExtension.kt
└── KotlinNativeExportPlugin.kt       # Task wiring, native lib JAR bundling, test configuration
```

**Source analysis**: the plugin uses Kotlin PSI (`kotlin-compiler-embeddable`) for proper AST-based parsing, running in an isolated Gradle Worker classloader. This handles nested generics, function types, default parameters, multi-line constructors, and `suspend`/`Flow` detection natively &mdash; no regex.

### Design philosophy

This plugin bridges **Kotlin/Native code to the JVM transparently** &mdash; platform-specific APIs (cinterop, POSIX, C libraries), performance-critical native code, and types that cannot exist in common Kotlin.

The bridge supports the full Kotlin OOP model: inheritance hierarchies are mirrored on the JVM side (`open class Shape` → `class Circle : Shape`), interfaces generate JVM interfaces with `val handle: Long`, and `override` modifiers are preserved. Extension functions become real Kotlin extensions on the JVM proxy. Sealed classes generate sealed JVM classes with subclass dispatch.

Data classes are supported as value types (field marshalling), and `commonMain` data classes are reused directly without generating duplicates.

## Requirements

- **Kotlin** 2.4.20+
- **Gradle** 9.1+ (for JDK 25 support)
- **JDK** 22+ (FFM stable since JDK 22 / JEP 454), recommended 25
- **Kotlin/Native** toolchain (bundled with KMP plugin)

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
