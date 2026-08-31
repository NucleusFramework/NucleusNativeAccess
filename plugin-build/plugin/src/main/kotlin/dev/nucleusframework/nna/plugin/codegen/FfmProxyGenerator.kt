package dev.nucleusframework.nna.plugin.codegen

import dev.nucleusframework.nna.plugin.ir.KneClass
import dev.nucleusframework.nna.plugin.ir.KneDataClass
import dev.nucleusframework.nna.plugin.ir.KneEnum
import dev.nucleusframework.nna.plugin.ir.KneFunction
import dev.nucleusframework.nna.plugin.ir.KneModule
import dev.nucleusframework.nna.plugin.ir.KneParam
import dev.nucleusframework.nna.plugin.ir.KneProperty
import dev.nucleusframework.nna.plugin.ir.KneType

/**
 * Generates Kotlin/JVM FFM proxy code.
 *
 * Inspired by swift-java's FFMSwift2JavaGenerator:
 *  - Each exported symbol gets a descriptor class with FunctionDescriptor + MethodHandle
 *    (mirroring swift-java's inner descriptor classes per method).
 *  - Object lifecycle uses Java Cleaner + a captured Long handle
 *    (mirrors swift-java's AllocatingSwiftArena + swift_retain/swift_release pattern).
 *  - String I/O uses the output-buffer pattern (Arena.allocate + get/set ADDRESS).
 *  - Exception propagation: every downcall is followed by KneRuntime.checkError()
 *    which queries the native @ThreadLocal error state.
 *
 * Generated classes have the exact same API as the native Kotlin classes,
 * making the bridge fully transparent to the JVM developer.
 */
class FfmProxyGenerator {

    companion object {
        private const val STRING_BUF_SIZE = 8192
        private const val ERR_BUF_SIZE = 8192
        private const val MAX_COLLECTION_SIZE = 4096

        /** Map FFM layout constants to GraalVM reachability-metadata type names. */
        private val LAYOUT_TO_GRAAL = mapOf(
            "JAVA_INT" to "int",
            "JAVA_LONG" to "long long",
            "JAVA_DOUBLE" to "double",
            "JAVA_FLOAT" to "float",
            "JAVA_BYTE" to "char",
            "JAVA_SHORT" to "short",
            "ADDRESS" to "void*",
        )
    }

    /**
     * Collect all unique FFM downcall descriptors required by the generated code.
     * Returns a set of (parameterTypes, returnType) pairs in GraalVM reachability-metadata format.
     * null returnType means void.
     */
    fun collectGraalVmDowncalls(module: KneModule): Set<Pair<List<String>, String?>> {
        val descriptors = mutableSetOf<Pair<List<String>, String?>>()

        fun reg(params: List<String>, ret: String?) {
            descriptors.add(params.map { LAYOUT_TO_GRAAL[it] ?: it } to ret?.let { LAYOUT_TO_GRAAL[it] ?: it })
        }

        fun regFromDescriptor(descriptor: String) {
            // Parse "FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)" or "FunctionDescriptor.ofVoid(JAVA_LONG)"
            val isVoid = descriptor.startsWith("FunctionDescriptor.ofVoid")
            val inner = descriptor.substringAfter("(").substringBeforeLast(")")
            val layouts = inner.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (isVoid) {
                reg(layouts, null)
            } else {
                val ret = layouts.firstOrNull()
                val params = layouts.drop(1)
                reg(params, ret)
            }
        }

        // Runtime: hasError, getLastError
        reg(emptyList(), "int")
        reg(listOf("void*", "int"), "int")

        // Dispose handle (used by all classes)
        reg(listOf("long long"), null)

        // Suspend/Flow helpers
        val hasSuspend = module.classes.any { c -> c.methods.any { it.isSuspend } } || module.functions.any { it.isSuspend }
        val hasFlow = module.classes.any { c -> c.methods.any { it.returnType is KneType.FLOW } } || module.functions.any { it.returnType is KneType.FLOW }
        if (hasSuspend || hasFlow) {
            reg(listOf("long long", "void*", "int"), "int")  // readStringRef
        }

        // All class constructors, methods, properties
        for (cls in module.classes) {
            regFromDescriptor(buildCtorDescriptor(cls.constructor.params))
            val trailingDefaults = cls.constructor.params.reversed().takeWhile { it.hasDefault }.size
            for (drop in 1..trailingDefaults) {
                regFromDescriptor(buildCtorDescriptor(cls.constructor.params.dropLast(drop)))
            }
            for (method in cls.methods) regFromDescriptor(buildMethodDescriptor(method))
            for (prop in cls.properties) {
                regFromDescriptor(buildGetterDescriptor(prop))
                if (prop.mutable) reg(listOf("long long") + listOf(prop.type).filter { it.ffmLayout.isNotEmpty() }.map { LAYOUT_TO_GRAAL[it.ffmLayout] ?: it.ffmLayout }, null)
            }
            for (method in cls.companionMethods) regFromDescriptor(buildTopLevelDescriptor(method))
            for (prop in cls.companionProperties) {
                regFromDescriptor(buildCompanionGetterDescriptor(prop))
                if (prop.mutable) reg(listOf(LAYOUT_TO_GRAAL[prop.type.ffmLayout] ?: prop.type.ffmLayout), null)
            }
            // List<DC> accessor handles
            val dcListTypes = mutableSetOf<KneType.DATA_CLASS>()
            fun scanForDcList(type: KneType) {
                val inner = type.unwrapCollection()
                val elem = when (inner) { is KneType.LIST -> inner.elementType; is KneType.SET -> inner.elementType; else -> null }
                if (elem is KneType.DATA_CLASS) dcListTypes.add(elem)
            }
            cls.methods.forEach { m -> scanForDcList(m.returnType) }
            cls.companionMethods.forEach { m -> scanForDcList(m.returnType) }
            for (dc in dcListTypes) {
                reg(listOf("long long"), "int")  // list_size
                reg(listOf("long long"), null)    // list_dispose (same as dispose)
                // list_get: JAVA_LONG, JAVA_INT, then ADDRESS per field (STRING/ByteArray get ADDRESS+JAVA_INT)
                val getParams = buildList {
                    add("long long"); add("int")
                    flattenDcFields(dc, "").forEach { (_, type) ->
                        when (type) { KneType.STRING, KneType.BYTE_ARRAY -> { add("void*"); add("int") }; else -> add("void*") }
                    }
                }
                reg(getParams, null)
            }

            // Flow<DC> reader handles
            cls.methods.forEach { m ->
                val rt = m.returnType
                if (rt is KneType.FLOW && rt.elementType is KneType.DATA_CLASS) {
                    val dc = rt.elementType
                    val readParams = buildList {
                        add("long long") // handle
                        flattenDcFields(dc, "").forEach { (_, type) ->
                            when (type) { KneType.STRING, KneType.BYTE_ARRAY -> { add("void*"); add("int") }; else -> add("void*") }
                        }
                    }
                    reg(readParams, null)
                }
            }

            // Suspend DC reader handles
            cls.methods.forEach { m ->
                if (!m.isSuspend) return@forEach
                val dc = when (val rt = m.returnType) {
                    is KneType.DATA_CLASS -> rt
                    is KneType.NULLABLE -> rt.inner as? KneType.DATA_CLASS
                    else -> null
                } ?: return@forEach
                val readParams = buildList {
                    add("long long") // handle
                    flattenDcFields(dc, "").forEach { (_, type) ->
                        when (type) { KneType.STRING, KneType.BYTE_ARRAY -> { add("void*"); add("int") }; else -> add("void*") }
                    }
                }
                reg(readParams, null)
            }

            // Suspend collection reader handles
            cls.methods.forEach { m ->
                if (!m.isSuspend) return@forEach
                val rt = when (val t = m.returnType) {
                    is KneType.NULLABLE -> t.inner
                    else -> t
                }
                when (rt) {
                    is KneType.LIST, is KneType.SET -> {
                        val elemType = when (rt) { is KneType.LIST -> rt.elementType; is KneType.SET -> rt.elementType; else -> return@forEach }
                        if (elemType !is KneType.DATA_CLASS) {
                            // handle, outBuf, outLen -> count
                            reg(listOf("long long", "void*", "int"), "int")
                        }
                    }
                    is KneType.MAP -> {
                        // handle, keysBuf, keysLen, valsBuf, valsLen -> count
                        reg(listOf("long long", "void*", "int", "void*", "int"), "int")
                    }
                    else -> {}
                }
            }
        }

        // List<DC> param create/add handles
        val dcListParamTypesGraal = mutableSetOf<KneType.DATA_CLASS>()
        fun scanDcListParamGraal(type: KneType) {
            val inner = when (type) { is KneType.NULLABLE -> type.inner; else -> type }
            when (inner) {
                is KneType.LIST -> if (inner.elementType is KneType.DATA_CLASS) dcListParamTypesGraal.add(inner.elementType)
                is KneType.SET -> if (inner.elementType is KneType.DATA_CLASS) dcListParamTypesGraal.add(inner.elementType)
                else -> {}
            }
        }
        for (cls in module.classes) {
            cls.methods.forEach { m -> m.params.forEach { scanDcListParamGraal(it.type) } }
            cls.companionMethods.forEach { m -> m.params.forEach { scanDcListParamGraal(it.type) } }
        }
        module.functions.forEach { fn -> fn.params.forEach { scanDcListParamGraal(it.type) } }
        for (dc in dcListParamTypesGraal) {
            reg(listOf("int"), "long long") // create(capacity) -> handle
            val addParams = buildList {
                add("long long") // handle
                flattenDcFields(dc, "").forEach { (_, type) ->
                    when (type) { KneType.STRING -> add("void*"); KneType.BYTE_ARRAY -> { add("void*"); add("int") }; else -> add(LAYOUT_TO_GRAAL[type.ffmLayout] ?: type.ffmLayout) }
                }
            }
            reg(addParams, null) // add(handle, fields...) -> void
        }

        // Top-level functions
        for (fn in module.functions) regFromDescriptor(buildTopLevelDescriptor(fn))

        return descriptors
    }

    /**
     * Collect all unique FFM upcall descriptors required by the generated code.
     * Returns a set of (parameterTypes, returnType) pairs in GraalVM reachability-metadata format.
     * null returnType means void.
     */
    fun collectGraalVmUpcalls(module: KneModule): Set<Pair<List<String>, String?>> {
        val descriptors = mutableSetOf<Pair<List<String>, String?>>()

        fun reg(params: List<String>, ret: String?) {
            descriptors.add(params.map { LAYOUT_TO_GRAAL[it] ?: it } to ret?.let { LAYOUT_TO_GRAAL[it] ?: it })
        }

        val hasSuspend = module.classes.any { c -> c.methods.any { it.isSuspend } } || module.functions.any { it.isSuspend }
        val hasFlow = module.classes.any { c -> c.methods.any { it.returnType is KneType.FLOW } } || module.functions.any { it.returnType is KneType.FLOW }

        if (hasSuspend || hasFlow) {
            // SUSPEND_CONT_DESC: (Int, Long) -> void
            reg(listOf("JAVA_INT", "JAVA_LONG"), null)
            // SUSPEND_EXC_DESC: (Long) -> void
            reg(listOf("JAVA_LONG"), null)
        }

        if (hasFlow) {
            // FLOW_COMPLETE_DESC: () -> void
            reg(emptyList(), null)
        }

        // Lambda callback upcalls
        for (sig in collectCallbackSignatures(module)) {
            val flatLayouts = mutableListOf<String>()
            sig.paramTypes.forEach { t ->
                when (t) {
                    is KneType.DATA_CLASS -> t.fields.forEach { f -> flatLayouts.add(upcallLayout(f.type)) }
                    is KneType.LIST, is KneType.SET -> { flatLayouts.add("ADDRESS"); flatLayouts.add("JAVA_INT") }
                    is KneType.MAP -> { flatLayouts.add("ADDRESS"); flatLayouts.add("ADDRESS"); flatLayouts.add("JAVA_INT") }
                    else -> flatLayouts.add(upcallLayout(t))
                }
            }
            val ret = if (sig.returnType == KneType.UNIT) null else upcallLayout(sig.returnType)
            reg(flatLayouts, ret)
        }

        return descriptors
    }

    private fun KneType.returnsViaBuffer(): Boolean =
        this == KneType.STRING || this == KneType.BYTE_ARRAY ||
        (this is KneType.NULLABLE && (inner == KneType.STRING || inner == KneType.BYTE_ARRAY))

    private fun KneType.isStringLike(): Boolean =
        this == KneType.STRING || (this is KneType.NULLABLE && inner == KneType.STRING)

    private fun KneType.isByteArrayType(): Boolean =
        this == KneType.BYTE_ARRAY

    private fun KneType.isFunctionType(): Boolean = this is KneType.FUNCTION

    private fun KneType.isCollection(): Boolean = when (this) {
        is KneType.LIST, is KneType.SET, is KneType.MAP -> true
        is KneType.NULLABLE -> inner is KneType.LIST || inner is KneType.SET || inner is KneType.MAP
        else -> false
    }

    private fun KneType.unwrapCollection(): KneType = when (this) {
        is KneType.NULLABLE -> inner
        else -> this
    }

    /** Check if a function needs a confined arena (for string/byte/collection alloc — callbacks use persistent arena). */
    private fun needsConfinedArena(params: List<KneParam>, returnType: KneType): Boolean =
        params.any { it.type.isStringLike() || it.type.isByteArrayType() || it.type.isCollection() } ||
        returnType.isStringLike() || returnType.isByteArrayType() || returnType.isCollection()

    private fun isDataClassReturn(type: KneType): Boolean =
        type is KneType.DATA_CLASS || (type is KneType.NULLABLE && type.inner is KneType.DATA_CLASS)

    private fun extractDataClass(type: KneType): KneType.DATA_CLASS? = when (type) {
        is KneType.DATA_CLASS -> type
        is KneType.NULLABLE -> type.inner as? KneType.DATA_CLASS
        else -> null
    }

    private fun classHasCallbacks(cls: KneClass): Boolean =
        cls.methods.any { fn -> fn.params.any { it.type is KneType.FUNCTION } || fn.isSuspend || fn.returnType is KneType.FLOW } ||
        cls.companionMethods.any { fn -> fn.params.any { it.type is KneType.FUNCTION } || fn.isSuspend }

    /**
     * Generates all proxy files for the module.
     * Returns a map of filename → file content.
     */
    fun generate(module: KneModule, jvmPackage: String): Map<String, String> {
        val files = mutableMapOf<String, String>()

        // Collect all unique callback signatures used across the module
        val callbackSignatures = collectCallbackSignatures(module)

        val moduleSuspend = module.classes.any { cls -> cls.methods.any { it.isSuspend } } || module.functions.any { it.isSuspend }
        val moduleFlow = module.classes.any { cls -> cls.methods.any { it.returnType is KneType.FLOW } } || module.functions.any { it.returnType is KneType.FLOW }
        files["KneRuntime.kt"] = generateRuntime(module.libName, jvmPackage, callbackSignatures, moduleSuspend || moduleFlow, moduleFlow)
        files["KotlinNativeException.kt"] = generateException(jvmPackage)

        module.dataClasses.filter { !it.isCommon }.forEach { dc ->
            files["${dc.simpleName}.kt"] = generateDataClassFile(dc, jvmPackage)
        }

        module.classes.filter { !it.isCommon }.forEach { cls ->
            files["${cls.simpleName}.kt"] = generateClassProxy(cls, module, jvmPackage)
        }

        module.interfaces.filter { !it.isCommon }.forEach { iface ->
            files["${iface.simpleName}.kt"] = generateInterfaceProxy(iface, module, jvmPackage)
        }

        module.enums.forEach { enum ->
            files["${enum.simpleName}.kt"] = generateEnumProxy(enum, module, jvmPackage)
        }

        // Non-extension top-level functions
        val regularFunctions = module.functions.filter { !it.isExtension }
        if (regularFunctions.isNotEmpty()) {
            val objectName = module.libName.replaceFirstChar { it.uppercaseChar() }
            files["$objectName.kt"] = generateFunctionObject(regularFunctions, objectName, module, jvmPackage)
        }

        // Extension functions grouped by receiver type
        val extensionFunctions = module.functions.filter { it.isExtension && it.receiverType != null }
        if (extensionFunctions.isNotEmpty()) {
            files["Extensions.kt"] = generateExtensionsFile(extensionFunctions, module, jvmPackage)
        }

        return files
    }

    // ── Runtime helper ────────────────────────────────────────────────────────

    /** Collect all unique KneType.FUNCTION signatures used as parameters in the module (including nullable). */
    private fun collectCallbackSignatures(module: KneModule): Set<KneType.FUNCTION> {
        val signatures = mutableSetOf<KneType.FUNCTION>()
        fun scanParams(params: List<KneParam>) {
            params.forEach { p ->
                if (p.type is KneType.FUNCTION) signatures.add(p.type)
                else if (p.type is KneType.NULLABLE && (p.type as KneType.NULLABLE).inner is KneType.FUNCTION)
                    signatures.add((p.type as KneType.NULLABLE).inner as KneType.FUNCTION)
            }
        }
        module.classes.forEach { cls ->
            cls.methods.forEach { scanParams(it.params) }
            cls.companionMethods.forEach { scanParams(it.params) }
        }
        module.functions.forEach { scanParams(it.params) }
        return signatures
    }

    /** Generate a unique identifier for a callback signature. */
    private fun callbackId(fnType: KneType.FUNCTION): String {
        fun sanitize(s: String) = s.replace("<", "_").replace(">", "").replace(", ", "_").replace("?", "N")
        val params = fnType.paramTypes.joinToString("_") { sanitize(it.jvmTypeName) }
        val ret = sanitize(fnType.returnType.jvmTypeName)
        return "${params.ifEmpty { "Void" }}_to_$ret"
    }

    private fun fnInvokeId(fnType: KneType.FUNCTION): String = callbackId(fnType)

    private fun generateRuntime(libName: String, pkg: String, callbackSignatures: Set<KneType.FUNCTION> = emptySet(), hasSuspend: Boolean = false, hasFlow: Boolean = false): String = buildString {
        appendLine("// Auto-generated by kotlin-native-export plugin. Do not modify.")
        appendLine("package $pkg")
        appendLine()
        appendLine("import java.lang.foreign.Arena")
        appendLine("import java.lang.foreign.FunctionDescriptor")
        appendLine("import java.lang.foreign.Linker")
        appendLine("import java.lang.foreign.SymbolLookup")
        appendLine("import java.lang.foreign.MemorySegment")
        appendLine("import java.lang.foreign.ValueLayout.*")
        appendLine("import java.lang.invoke.MethodHandle")
        appendLine("import java.nio.file.Files")
        appendLine("import java.nio.file.Paths")
        appendLine("import java.nio.file.StandardCopyOption")
        appendLine()
        appendLine("/**")
        appendLine(" * Shared FFM runtime: loads the native library, resolves MethodHandles,")
        appendLine(" * and propagates exceptions from the native side.")
        appendLine(" *")
        appendLine(" * Library loading is two-tier:")
        appendLine(" *  1. Search java.library.path (works for packaged apps, manual config)")
        appendLine(" *  2. Extract from JAR resources to persistent cache (zero-config)")
        appendLine(" */")
        appendLine("internal object KneRuntime {")
        appendLine()
        appendLine("    val linker: Linker = Linker.nativeLinker()")
        appendLine("    private val globalArena: Arena = Arena.global()")
        appendLine()
        appendLine("    val library: SymbolLookup by lazy { loadLibrary(\"$libName\") }")
        appendLine()
        appendLine("    private fun loadLibrary(name: String): SymbolLookup {")
        appendLine("        val fileName = System.mapLibraryName(name)")
        appendLine()
        appendLine("        // Tier 1: Search java.library.path + working dir")
        appendLine("        val sep = if (System.getProperty(\"os.name\").lowercase().contains(\"win\")) \";\" else \":\"")
        appendLine("        val basePaths = System.getProperty(\"java.library.path\", \"\").split(sep)")
        appendLine("        val extraDirs = mutableListOf(System.getProperty(\"user.dir\", \".\"))")
        appendLine("        try { ProcessHandle.current().info().command().ifPresent { cmd -> Paths.get(cmd).parent?.toString()?.let { extraDirs.add(it) } } } catch (_: Exception) {}")
        appendLine("        for (dir in (basePaths + extraDirs).distinct().filter { it.isNotBlank() }) {")
        appendLine("            val file = Paths.get(dir, fileName).toFile()")
        appendLine("            if (file.exists()) return SymbolLookup.libraryLookup(file.toPath(), globalArena)")
        appendLine("            val libFile = Paths.get(dir, \"lib\", fileName).toFile()")
        appendLine("            if (libFile.exists()) return SymbolLookup.libraryLookup(libFile.toPath(), globalArena)")
        appendLine("        }")
        appendLine()
        appendLine("        // Tier 2: Extract from JAR resources to persistent cache")
        appendLine("        val platform = detectPlatform()")
        appendLine("        val resourcePath = \"/kne/native/\$platform/\$fileName\"")
        appendLine("        val stream = KneRuntime::class.java.getResourceAsStream(resourcePath)")
        appendLine("        if (stream != null) {")
        appendLine("            val cacheDir = resolveCacheDir(platform)")
        appendLine("            Files.createDirectories(cacheDir)")
        appendLine("            val target = cacheDir.resolve(fileName)")
        appendLine("            stream.use { input ->")
        appendLine("                val bytes = input.readAllBytes()")
        appendLine("                // Size match alone is not enough: same-size builds can carry different")
        appendLine("                // symbols (e.g. after an API rename), so compare content before trusting the cache.")
        appendLine("                val stale = !Files.exists(target) ||")
        appendLine("                    Files.size(target) != bytes.size.toLong() ||")
        appendLine("                    !Files.readAllBytes(target).contentEquals(bytes)")
        appendLine("                if (stale) {")
        appendLine("                    val tmp = Files.createTempFile(cacheDir, name, \".tmp\")")
        appendLine("                    Files.write(tmp, bytes)")
        appendLine("                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)")
        appendLine("                }")
        appendLine("            }")
        appendLine("            return SymbolLookup.libraryLookup(target, globalArena)")
        appendLine("        }")
        appendLine()
        appendLine("        // Tier 3: Fallback to loader lookup (GraalVM native-image)")
        appendLine("        return SymbolLookup.loaderLookup()")
        appendLine("    }")
        appendLine()
        appendLine("    private fun detectPlatform(): String {")
        appendLine("        val os = System.getProperty(\"os.name\", \"\").lowercase()")
        appendLine("        val arch = System.getProperty(\"os.arch\").let { if (it == \"aarch64\" || it == \"arm64\") \"aarch64\" else \"x64\" }")
        appendLine("        val osName = when { os.contains(\"mac\") || os.contains(\"darwin\") -> \"darwin\"; os.contains(\"win\") -> \"win32\"; else -> \"linux\" }")
        appendLine("        return \"\$osName-\$arch\"")
        appendLine("    }")
        appendLine()
        appendLine("    private fun resolveCacheDir(platform: String): java.nio.file.Path {")
        appendLine("        val os = System.getProperty(\"os.name\", \"\").lowercase()")
        appendLine("        val base = when {")
        appendLine("            os.contains(\"mac\") -> Paths.get(System.getProperty(\"user.home\"), \"Library\", \"Caches\")")
        appendLine("            os.contains(\"win\") -> Paths.get(System.getenv(\"LOCALAPPDATA\") ?: System.getProperty(\"user.home\"))")
        appendLine("            else -> Paths.get(System.getenv(\"XDG_CACHE_HOME\") ?: \"\${System.getProperty(\"user.home\")}/.cache\")")
        appendLine("        }")
        appendLine("        return base.resolve(\"kne\").resolve(\"native\").resolve(platform)")
        appendLine("    }")
        appendLine()
        appendLine("    fun handle(symbol: String, descriptor: FunctionDescriptor): MethodHandle =")
        appendLine("        linker.downcallHandle(")
        appendLine("            library.find(symbol).orElseThrow { UnsatisfiedLinkError(\"Symbol not found: \$symbol\") },")
        appendLine("            descriptor,")
        appendLine("        )")
        appendLine()
        appendLine("    // ── Exception propagation ────────────────────────────────────────────")
        appendLine()
        appendLine("    private val HAS_ERROR_HANDLE: MethodHandle by lazy {")
        appendLine("        handle(\"${libName}_kne_hasError\", FunctionDescriptor.of(JAVA_INT))")
        appendLine("    }")
        appendLine("    private val GET_LAST_ERROR_HANDLE: MethodHandle by lazy {")
        appendLine("        handle(\"${libName}_kne_getLastError\", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))")
        appendLine("    }")
        appendLine()
        appendLine("    fun checkError() {")
        appendLine("        val hasError = HAS_ERROR_HANDLE.invoke() as Int")
        appendLine("        if (hasError != 0) {")
        appendLine("            Arena.ofConfined().use { arena ->")
        appendLine("                val buf = arena.allocate($ERR_BUF_SIZE.toLong())")
        appendLine("                GET_LAST_ERROR_HANDLE.invoke(buf, $ERR_BUF_SIZE)")
        appendLine("                throw KotlinNativeException(buf.getString(0))")
        appendLine("            }")
        appendLine("        }")
        appendLine("    }")

        // Generate upcall infrastructure for each callback signature
        if (callbackSignatures.isNotEmpty()) {
            appendLine()
            appendLine("    // ── Callback upcall stubs ────────────────────────────────────────────")
            for (sig in callbackSignatures) {
                appendUpcallInfrastructure(sig)
            }
        }

        // Generate suspend function infrastructure
        if (hasSuspend) {
            appendLine()
            appendLine("    // ── Suspend function upcall stubs ────────────────────────────────────")
            appendLine()
            appendLine("    // Continuation callback: (hasValue: Int, value: Long) -> Unit")
            appendLine("    @JvmStatic")
            appendLine("    fun _upcall_suspendCont(fn: Any, hasValue: Int, value: Long) {")
            appendLine("        @Suppress(\"UNCHECKED_CAST\")")
            appendLine("        (fn as (Int, Long) -> Unit).invoke(hasValue, value)")
            appendLine("    }")
            appendLine("    val SUSPEND_CONT_MH: java.lang.invoke.MethodHandle by lazy {")
            appendLine("        java.lang.invoke.MethodHandles.lookup().findStatic(KneRuntime::class.java, \"_upcall_suspendCont\",")
            appendLine("            java.lang.invoke.MethodType.methodType(Void.TYPE, Any::class.java, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType))")
            appendLine("    }")
            appendLine("    val SUSPEND_CONT_DESC: FunctionDescriptor = FunctionDescriptor.ofVoid(JAVA_INT, JAVA_LONG)")
            appendLine("    fun createSuspendContStub(fn: (Int, Long) -> Unit, arena: Arena): Long {")
            appendLine("        return linker.upcallStub(SUSPEND_CONT_MH.bindTo(fn), SUSPEND_CONT_DESC, arena).address()")
            appendLine("    }")
            appendLine()
            appendLine("    // Exception callback: (msgHandle: Long) -> Unit")
            appendLine("    @JvmStatic")
            appendLine("    fun _upcall_suspendExc(fn: Any, msgHandle: Long) {")
            appendLine("        @Suppress(\"UNCHECKED_CAST\")")
            appendLine("        (fn as (Long) -> Unit).invoke(msgHandle)")
            appendLine("    }")
            appendLine("    val SUSPEND_EXC_MH: java.lang.invoke.MethodHandle by lazy {")
            appendLine("        java.lang.invoke.MethodHandles.lookup().findStatic(KneRuntime::class.java, \"_upcall_suspendExc\",")
            appendLine("            java.lang.invoke.MethodType.methodType(Void.TYPE, Any::class.java, Long::class.javaPrimitiveType))")
            appendLine("    }")
            appendLine("    val SUSPEND_EXC_DESC: FunctionDescriptor = FunctionDescriptor.ofVoid(JAVA_LONG)")
            appendLine("    fun createSuspendExcStub(fn: (Long) -> Unit, arena: Arena): Long {")
            appendLine("        return linker.upcallStub(SUSPEND_EXC_MH.bindTo(fn), SUSPEND_EXC_DESC, arena).address()")
            appendLine("    }")
            appendLine()
            appendLine("    // Module-level handles for suspend helpers")
            appendLine("    val CANCEL_JOB_HANDLE: MethodHandle by lazy {")
            appendLine("        handle(\"${libName}_kne_cancelJob\", FunctionDescriptor.ofVoid(JAVA_LONG))")
            appendLine("    }")
            appendLine("    val DISPOSE_REF_HANDLE: MethodHandle by lazy {")
            appendLine("        handle(\"${libName}_kne_disposeRef\", FunctionDescriptor.ofVoid(JAVA_LONG))")
            appendLine("    }")
            appendLine("    val READ_STRING_REF_HANDLE: MethodHandle by lazy {")
            appendLine("        handle(\"${libName}_kne_readStringRef\", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT))")
            appendLine("    }")
            appendLine("    fun readStringFromRef(handle: Long): String {")
            appendLine("        Arena.ofConfined().use { arena ->")
            appendLine("            var _bufSize = $STRING_BUF_SIZE")
            appendLine("            var _buf = arena.allocate(_bufSize.toLong())")
            appendLine("            val _len = READ_STRING_REF_HANDLE.invoke(handle, _buf, _bufSize) as Int")
            appendLine("            if (_len >= _bufSize) {")
            appendLine("                _bufSize = _len + 1")
            appendLine("                _buf = arena.allocate(_bufSize.toLong())")
            appendLine("                READ_STRING_REF_HANDLE.invoke(handle, _buf, _bufSize)")
            appendLine("            }")
            appendLine("            DISPOSE_REF_HANDLE.invoke(handle)")
            appendLine("            return _buf.getString(0)")
            appendLine("        }")
            appendLine("    }")
            appendLine("    val READ_BYTEARRAY_REF_HANDLE: MethodHandle by lazy {")
            appendLine("        handle(\"${libName}_kne_readByteArrayRef\", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT))")
            appendLine("    }")
            appendLine("    fun readByteArrayFromRef(handle: Long): ByteArray {")
            appendLine("        Arena.ofConfined().use { arena ->")
            appendLine("            var _bufSize = $STRING_BUF_SIZE")
            appendLine("            var _buf = arena.allocate(_bufSize.toLong())")
            appendLine("            val _len = READ_BYTEARRAY_REF_HANDLE.invoke(handle, _buf, _bufSize) as Int")
            appendLine("            if (_len > _bufSize) {")
            appendLine("                _bufSize = _len")
            appendLine("                _buf = arena.allocate(_bufSize.toLong())")
            appendLine("                READ_BYTEARRAY_REF_HANDLE.invoke(handle, _buf, _bufSize)")
            appendLine("            }")
            appendLine("            DISPOSE_REF_HANDLE.invoke(handle)")
            appendLine("            return _buf.asSlice(0, _len.toLong()).toArray(JAVA_BYTE)")
            appendLine("        }")
            appendLine("    }")
        }

        // Generate flow infrastructure (onNext reuses suspendExc stub, onComplete is new)
        if (hasFlow) {
            appendLine()
            appendLine("    // ── Flow upcall stubs ────────────────────────────────────────────────")
            appendLine("    // onNext: (value: Long) -> Unit — reuses SUSPEND_EXC signature")
            appendLine("    fun createFlowNextStub(fn: (Long) -> Unit, arena: Arena): Long =")
            appendLine("        createSuspendExcStub(fn, arena)")
            appendLine()
            appendLine("    // onError: (msgHandle: Long) -> Unit — reuses SUSPEND_EXC signature")
            appendLine("    fun createFlowErrorStub(fn: (Long) -> Unit, arena: Arena): Long =")
            appendLine("        createSuspendExcStub(fn, arena)")
            appendLine()
            appendLine("    // onComplete: () -> Unit")
            appendLine("    @JvmStatic")
            appendLine("    fun _upcall_flowComplete(fn: Any) {")
            appendLine("        @Suppress(\"UNCHECKED_CAST\")")
            appendLine("        (fn as () -> Unit).invoke()")
            appendLine("    }")
            appendLine("    val FLOW_COMPLETE_MH: java.lang.invoke.MethodHandle by lazy {")
            appendLine("        java.lang.invoke.MethodHandles.lookup().findStatic(KneRuntime::class.java, \"_upcall_flowComplete\",")
            appendLine("            java.lang.invoke.MethodType.methodType(Void.TYPE, Any::class.java))")
            appendLine("    }")
            appendLine("    val FLOW_COMPLETE_DESC: FunctionDescriptor = FunctionDescriptor.ofVoid()")
            appendLine("    fun createFlowCompleteStub(fn: () -> Unit, arena: Arena): Long {")
            appendLine("        return linker.upcallStub(FLOW_COMPLETE_MH.bindTo(fn), FLOW_COMPLETE_DESC, arena).address()")
            appendLine("    }")
        }

        appendLine("}")
    }

    // ── Exception class ──────────────────────────────────────────────────────

    private fun generateException(pkg: String): String = buildString {
        appendLine("// Auto-generated by kotlin-native-export plugin. Do not modify.")
        appendLine("package $pkg")
        appendLine()
        appendLine("/**")
        appendLine(" * Exception thrown when a Kotlin/Native function throws across the FFM boundary.")
        appendLine(" * The message contains the original Kotlin exception's message.")
        appendLine(" */")
        appendLine("class KotlinNativeException(message: String) : RuntimeException(message)")
    }

    /**
     * Generates upcall infrastructure for a callback signature:
     * - A JVM static target method that invokes the lambda
     * - A lazy MethodHandle for the target
     * - A factory method to create upcall stubs
     */
    private fun StringBuilder.appendUpcallInfrastructure(sig: KneType.FUNCTION) {
        val id = callbackId(sig)
        val paramCount = sig.paramTypes.size

        // Static upcall target method
        // Expand DATA_CLASS params into individual field params at C ABI level
        // Expand LIST/SET params into MemorySegment + Int (pointer + size)
        data class FlatParam(val name: String, val type: KneType)
        val flatParams = mutableListOf<FlatParam>()
        sig.paramTypes.forEachIndexed { i, t ->
            when (t) {
                KneType.BYTE_ARRAY -> {
                    flatParams.add(FlatParam("p${i}_ptr", KneType.STRING)) // MemorySegment (ADDRESS)
                    flatParams.add(FlatParam("p${i}_size", KneType.INT))
                }
                is KneType.DATA_CLASS -> t.fields.forEach { f -> flatParams.add(FlatParam("p${i}_${f.name}", f.type)) }
                is KneType.LIST, is KneType.SET -> {
                    flatParams.add(FlatParam("p${i}_ptr", KneType.STRING)) // MemorySegment (ADDRESS)
                    flatParams.add(FlatParam("p${i}_size", KneType.INT))
                }
                is KneType.MAP -> {
                    flatParams.add(FlatParam("p${i}_keys", KneType.STRING)) // ADDRESS
                    flatParams.add(FlatParam("p${i}_values", KneType.STRING)) // ADDRESS
                    flatParams.add(FlatParam("p${i}_size", KneType.INT))
                }
                else -> flatParams.add(FlatParam("p$i", t))
            }
        }

        val targetParams = buildList {
            add("fn: Any")
            flatParams.forEach { add("${it.name}: ${upcallJvmType(it.type)}") }
        }.joinToString(", ")

        val returnJvmType = upcallJvmType(sig.returnType)
        val returnDecl = if (sig.returnType == KneType.UNIT) "" else ": $returnJvmType"

        appendLine()
        appendLine("    @JvmStatic")
        appendLine("    fun _upcall_$id($targetParams)$returnDecl {")

        appendLine("        @Suppress(\"UNCHECKED_CAST\")")
        appendLine("        val _fn = fn as ${sig.jvmTypeName}")

        // Reconstruct DATA_CLASS/LIST/SET/ByteArray params from flat fields, convert others
        // First emit ByteArray/collection reconstruction as local vals
        sig.paramTypes.forEachIndexed { i, t ->
            if (t == KneType.BYTE_ARRAY) {
                appendLine("        val _ba$i = p${i}_ptr.reinterpret(p${i}_size.toLong()).toArray(JAVA_BYTE)")
            }
            // MAP reconstruction
            if (t is KneType.MAP) {
                appendUpcallMapReconstruction(i, t)
            }
            val elemType = when (t) { is KneType.LIST -> t.elementType; is KneType.SET -> (t as KneType.SET).elementType; else -> null }
            if (elemType != null) {
                val layout = KneType.collectionElementLayout(elemType)
                when (elemType) {
                    KneType.STRING -> {
                        appendLine("        val _list$i = mutableListOf<String>()")
                        appendLine("        var _off$i = 0L")
                        appendLine("        val _seg$i = p${i}_ptr.reinterpret(Long.MAX_VALUE)")
                        appendLine("        repeat(p${i}_size) { _list${i}.add(_seg${i}.getString(_off${i})); _off$i += _list${i}.last().toByteArray(Charsets.UTF_8).size + 1 }")
                    }
                    KneType.BOOLEAN -> {
                        appendLine("        val _seg$i = p${i}_ptr.reinterpret(p${i}_size.toLong() * 4)")
                        appendLine("        val _list$i = List(p${i}_size) { _seg${i}.getAtIndex(JAVA_INT, it.toLong()) != 0 }")
                    }
                    is KneType.ENUM -> {
                        appendLine("        val _seg$i = p${i}_ptr.reinterpret(p${i}_size.toLong() * 4)")
                        appendLine("        val _list$i = List(p${i}_size) { ${elemType.simpleName}.entries[_seg${i}.getAtIndex(JAVA_INT, it.toLong())] }")
                    }
                    else -> {
                        val elemSize = fieldSize(elemType)
                        appendLine("        val _seg$i = p${i}_ptr.reinterpret(p${i}_size.toLong() * $elemSize)")
                        appendLine("        val _list$i = List(p${i}_size) { _seg${i}.getAtIndex($layout, it.toLong()) as ${elemType.jvmTypeName} }")
                    }
                }
                if (t is KneType.SET) {
                    appendLine("        val _set$i = _list${i}.toSet()")
                }
            }
        }

        val invokeConvertedArgs = sig.paramTypes.mapIndexed { i, t ->
            when (t) {
                KneType.BOOLEAN -> "p$i != 0"
                KneType.BYTE_ARRAY -> "_ba$i"
                KneType.STRING -> "p$i.reinterpret(Long.MAX_VALUE).getString(0)"
                is KneType.ENUM -> "${t.simpleName}.entries[p$i]"
                is KneType.OBJECT -> "${t.simpleName}.fromNativeHandle(p$i)"
                is KneType.DATA_CLASS -> {
                    val fieldArgs = t.fields.joinToString(", ") { f ->
                        val pName = "p${i}_${f.name}"
                        when (f.type) {
                            KneType.BOOLEAN -> "${f.name} = $pName != 0"
                            KneType.STRING -> "${f.name} = $pName.reinterpret(Long.MAX_VALUE).getString(0)"
                            else -> "${f.name} = $pName"
                        }
                    }
                    "${t.simpleName}($fieldArgs)"
                }
                is KneType.LIST -> "_list$i"
                is KneType.SET -> "_set$i"
                is KneType.MAP -> "_map$i"
                else -> "p$i"
            }
        }.joinToString(", ")

        if (sig.returnType == KneType.UNIT) {
            appendLine("        _fn.invoke($invokeConvertedArgs)")
        } else if (sig.returnType == KneType.BOOLEAN) {
            appendLine("        return if (_fn.invoke($invokeConvertedArgs)) 1 else 0")
        } else if (sig.returnType == KneType.BYTE_ARRAY) {
            // Return ByteArray as packed buffer: [size:Int32][padding:4bytes][data...] via auto Arena
            appendLine("        val _result = _fn.invoke($invokeConvertedArgs)")
            appendLine("        val _arena = Arena.ofAuto()")
            appendLine("        val _buf = _arena.allocate((8 + _result.size).toLong())")
            appendLine("        _buf.set(JAVA_INT, 0, _result.size)")
            appendLine("        MemorySegment.copy(_result, 0, _buf, JAVA_BYTE, 8, _result.size)")
            appendLine("        return _buf")
        } else if (sig.returnType == KneType.STRING) {
            appendLine("        return Arena.ofAuto().allocateFrom(_fn.invoke($invokeConvertedArgs))")
        } else if (sig.returnType is KneType.ENUM) {
            appendLine("        return _fn.invoke($invokeConvertedArgs).ordinal")
        } else if (sig.returnType is KneType.OBJECT) {
            appendLine("        return _fn.invoke($invokeConvertedArgs).handle")
        } else if (sig.returnType is KneType.DATA_CLASS) {
            val dc = sig.returnType
            appendLine("        val _result = _fn.invoke($invokeConvertedArgs)")
            appendLine("        val _arena = Arena.ofAuto()")
            val structSize = dc.fields.sumOf { fieldSize(it.type) }
            appendLine("        val _buf = _arena.allocate(${structSize}.toLong())")
            var offset = 0
            dc.fields.forEach { f ->
                when (f.type) {
                    KneType.INT -> { appendLine("        _buf.set(JAVA_INT, ${offset}.toLong(), _result.${f.name})"); offset += 4 }
                    KneType.LONG -> { appendLine("        _buf.set(JAVA_LONG, ${offset}.toLong(), _result.${f.name})"); offset += 8 }
                    KneType.DOUBLE -> { appendLine("        _buf.set(JAVA_DOUBLE, ${offset}.toLong(), _result.${f.name})"); offset += 8 }
                    KneType.FLOAT -> { appendLine("        _buf.set(JAVA_FLOAT, ${offset}.toLong(), _result.${f.name})"); offset += 4 }
                    KneType.BOOLEAN -> { appendLine("        _buf.set(JAVA_INT, ${offset}.toLong(), if (_result.${f.name}) 1 else 0)"); offset += 4 }
                    KneType.SHORT -> { appendLine("        _buf.set(JAVA_SHORT, ${offset}.toLong(), _result.${f.name})"); offset += 2 }
                    KneType.BYTE -> { appendLine("        _buf.set(JAVA_BYTE, ${offset}.toLong(), _result.${f.name})"); offset += 1 }
                    KneType.STRING -> { appendLine("        _buf.set(ADDRESS, ${offset}.toLong(), _arena.allocateFrom(_result.${f.name}))"); offset += 8 }
                    else -> offset += 8
                }
            }
            appendLine("        return _buf")
        } else if (sig.returnType is KneType.LIST || sig.returnType is KneType.SET) {
            val elemType = when (sig.returnType) { is KneType.LIST -> sig.returnType.elementType; is KneType.SET -> (sig.returnType as KneType.SET).elementType; else -> KneType.INT }
            appendLine("        val _result = _fn.invoke($invokeConvertedArgs)")
            val src = if (sig.returnType is KneType.SET) "_result.toList()" else "_result"
            appendUpcallCollectionReturn(elemType, src)
        } else if (sig.returnType is KneType.MAP) {
            val mapType = sig.returnType as KneType.MAP
            appendLine("        val _result = _fn.invoke($invokeConvertedArgs)")
            appendUpcallMapReturn(mapType)
        } else {
            appendLine("        return _fn.invoke($invokeConvertedArgs)")
        }
        appendLine("    }")

        // MethodHandle for the upcall target
        val methodTypeArgs = buildList {
            add(upcallMethodTypeArg(sig.returnType))
            add("Any::class.java")
            flatParams.forEach { add(upcallMethodTypeArg(it.type)) }
        }.joinToString(", ")

        appendLine()
        appendLine("    val UPCALL_MH_$id: MethodHandle by lazy {")
        appendLine("        java.lang.invoke.MethodHandles.lookup().findStatic(")
        appendLine("            KneRuntime::class.java, \"_upcall_$id\",")
        appendLine("            java.lang.invoke.MethodType.methodType($methodTypeArgs)")
        appendLine("        )")
        appendLine("    }")

        // FunctionDescriptor for the callback's C ABI (DATA_CLASS expanded to fields)
        val descLayouts = flatParams.joinToString(", ") { upcallLayout(it.type) }
        val descExpr = if (sig.returnType == KneType.UNIT) {
            if (descLayouts.isEmpty()) "FunctionDescriptor.ofVoid()"
            else "FunctionDescriptor.ofVoid($descLayouts)"
        } else {
            val retLayout = upcallLayout(sig.returnType)
            if (descLayouts.isEmpty()) "FunctionDescriptor.of($retLayout)"
            else "FunctionDescriptor.of($retLayout, $descLayouts)"
        }
        appendLine("    val UPCALL_DESC_$id: FunctionDescriptor = $descExpr")

        // Factory method to create upcall stubs
        appendLine()
        appendLine("    fun createUpcallStub_$id(fn: ${sig.jvmTypeName}, arena: Arena): Long {")
        appendLine("        val bound = UPCALL_MH_$id.bindTo(fn)")
        appendLine("        return linker.upcallStub(bound, UPCALL_DESC_$id, arena).address()")
        appendLine("    }")
    }

    /** Emit collection return as packed buffer [count:Int64][ elements...] from upcall. 8-byte header for alignment. */
    private fun StringBuilder.appendUpcallCollectionReturn(elemType: KneType, srcExpr: String) {
        val H = 8 // header: 8 bytes (Int at offset 0, 4 bytes padding) — ensures 8-byte alignment for all element types
        appendLine("        val _list = $srcExpr")
        appendLine("        val _arena = Arena.ofAuto()")
        when (elemType) {
            KneType.STRING -> {
                appendLine("        val _totalBytes = $H + _list.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 }")
                appendLine("        val _buf = _arena.allocate(_totalBytes.toLong())")
                appendLine("        _buf.set(JAVA_INT, 0L, _list.size)")
                appendLine("        var _off = ${H}L")
                appendLine("        for (_s in _list) { _buf.setString(_off, _s); _off += _s.toByteArray(Charsets.UTF_8).size + 1 }")
            }
            else -> {
                val layout = KneType.collectionElementLayout(elemType)
                val elemSize = fieldSize(elemType)
                appendLine("        val _buf = _arena.allocate(($H + _list.size * $elemSize).toLong())")
                appendLine("        _buf.set(JAVA_INT, 0L, _list.size)")
                when (elemType) {
                    KneType.BOOLEAN -> appendLine("        _list.forEachIndexed { i, v -> _buf.set(JAVA_INT, ($H + i * 4).toLong(), if (v) 1 else 0) }")
                    is KneType.ENUM -> appendLine("        _list.forEachIndexed { i, v -> _buf.set(JAVA_INT, ($H + i * 4).toLong(), v.ordinal) }")
                    else -> appendLine("        _list.forEachIndexed { i, v -> _buf.set($layout, ($H + i * $elemSize).toLong(), v) }")
                }
            }
        }
        appendLine("        return _buf")
    }

    /** Emit map return as packed buffer from upcall. 8-byte header for alignment. */
    private fun StringBuilder.appendUpcallMapReturn(mapType: KneType.MAP) {
        val H = 8 // 8-byte header
        appendLine("        val _keys = _result.keys.toList()")
        appendLine("        val _values = _result.values.toList()")
        appendLine("        val _arena = Arena.ofAuto()")
        val kSize = if (mapType.keyType == KneType.STRING) 0 else fieldSize(mapType.keyType)
        val vSize = if (mapType.valueType == KneType.STRING) 0 else fieldSize(mapType.valueType)
        if (kSize > 0 && vSize > 0) {
            appendLine("        val _buf = _arena.allocate(($H + _keys.size * ${kSize + vSize}).toLong())")
        } else {
            appendLine("        val _totalBytes = $H + ${if (kSize > 0) "_keys.size * $kSize" else "_keys.sumOf { it.toString().toByteArray(Charsets.UTF_8).size + 1 }"} + ${if (vSize > 0) "_values.size * $vSize + $vSize" else "_values.sumOf { it.toString().toByteArray(Charsets.UTF_8).size + 1 }"} // extra padding for alignment")
            appendLine("        val _buf = _arena.allocate(_totalBytes.toLong())")
        }
        appendLine("        _buf.set(JAVA_INT, 0L, _keys.size)")
        appendUpcallWriteArray("_keys", mapType.keyType, "${H}L")
        if (kSize > 0) {
            appendUpcallWriteArray("_values", mapType.valueType, "($H + _keys.size * $kSize).toLong()")
        } else {
            if (vSize > 1) {
                appendLine("        val _valStart = (_keysEndOff + ${vSize - 1}) / $vSize * $vSize")
                appendUpcallWriteArray("_values", mapType.valueType, "_valStart")
            } else {
                appendUpcallWriteArray("_values", mapType.valueType, "_keysEndOff")
            }
        }
        appendLine("        return _buf")
    }

    private fun StringBuilder.appendUpcallWriteArray(listExpr: String, elemType: KneType, startOffset: String) {
        when (elemType) {
            KneType.STRING -> {
                appendLine("        var _${listExpr}Off = $startOffset")
                appendLine("        for (_s in $listExpr) { _buf.setString(_${listExpr}Off, _s.toString()); _${listExpr}Off += _s.toString().toByteArray(Charsets.UTF_8).size + 1 }")
                appendLine("        val _${listExpr.removePrefix("_")}EndOff = _${listExpr}Off")
            }
            KneType.BOOLEAN -> appendLine("        $listExpr.forEachIndexed { i, v -> _buf.set(JAVA_INT, $startOffset + i * 4, if (v) 1 else 0) }")
            is KneType.ENUM -> appendLine("        $listExpr.forEachIndexed { i, v -> _buf.set(JAVA_INT, $startOffset + i * 4, v.ordinal) }")
            else -> {
                val layout = KneType.collectionElementLayout(elemType)
                val elemSize = fieldSize(elemType)
                appendLine("        $listExpr.forEachIndexed { i, v -> _buf.set($layout, $startOffset + i * $elemSize, v) }")
            }
        }
    }

    /** Emit MAP reconstruction code in upcall target. */
    private fun StringBuilder.appendUpcallMapReconstruction(i: Int, t: KneType.MAP) {
        // Read keys
        appendUpcallCollectionRead(i, t.keyType, "keys", "p${i}_keys")
        // Read values
        appendUpcallCollectionRead(i, t.valueType, "values", "p${i}_values")
        appendLine("        val _map$i = _keys${i}.zip(_values${i}).toMap()")
    }

    /** Emit collection array read for a specific role (keys/values) in upcall context. */
    private fun StringBuilder.appendUpcallCollectionRead(i: Int, elemType: KneType, role: String, ptrName: String) {
        val layout = KneType.collectionElementLayout(elemType)
        when (elemType) {
            KneType.STRING -> {
                appendLine("        val _${role}${i} = mutableListOf<String>()")
                appendLine("        var _${role}Off${i} = 0L")
                appendLine("        val _${role}Seg${i} = $ptrName.reinterpret(Long.MAX_VALUE)")
                appendLine("        repeat(p${i}_size) { _${role}${i}.add(_${role}Seg${i}.getString(_${role}Off${i})); _${role}Off$i += _${role}${i}.last().toByteArray(Charsets.UTF_8).size + 1 }")
            }
            KneType.BOOLEAN -> {
                appendLine("        val _${role}Seg${i} = $ptrName.reinterpret(p${i}_size.toLong() * 4)")
                appendLine("        val _${role}${i} = List(p${i}_size) { _${role}Seg${i}.getAtIndex(JAVA_INT, it.toLong()) != 0 }")
            }
            is KneType.ENUM -> {
                appendLine("        val _${role}Seg${i} = $ptrName.reinterpret(p${i}_size.toLong() * 4)")
                appendLine("        val _${role}${i} = List(p${i}_size) { ${elemType.simpleName}.entries[_${role}Seg${i}.getAtIndex(JAVA_INT, it.toLong())] }")
            }
            else -> {
                val elemSize = fieldSize(elemType)
                appendLine("        val _${role}Seg${i} = $ptrName.reinterpret(p${i}_size.toLong() * $elemSize)")
                appendLine("        val _${role}${i} = List(p${i}_size) { _${role}Seg${i}.getAtIndex($layout, it.toLong()) as ${elemType.jvmTypeName} }")
            }
        }
    }

    /** The Java type name for upcall method signatures (C ABI compatible). */
    private fun upcallJvmType(type: KneType): String = when (type) {
        KneType.INT -> "Int"
        KneType.LONG -> "Long"
        KneType.DOUBLE -> "Double"
        KneType.FLOAT -> "Float"
        KneType.BOOLEAN -> "Int" // C ABI: int for bool
        KneType.BYTE -> "Byte"
        KneType.SHORT -> "Short"
        KneType.STRING -> "MemorySegment"
        KneType.UNIT -> "Unit"
        KneType.BYTE_ARRAY -> "MemorySegment" // packed buffer for return; expanded to ADDRESS+INT for params
        is KneType.OBJECT -> "Long" // opaque StableRef handle
        is KneType.DATA_CLASS -> "MemorySegment" // returns struct pointer
        is KneType.LIST, is KneType.SET, is KneType.MAP -> "MemorySegment" // packed buffer
        else -> "Int"
    }

    /** The MethodType argument for a callback param/return type. */
    private fun upcallMethodTypeArg(type: KneType): String = when (type) {
        KneType.UNIT -> "Void.TYPE"
        KneType.STRING -> "java.lang.foreign.MemorySegment::class.java"
        KneType.BYTE_ARRAY -> "java.lang.foreign.MemorySegment::class.java"
        is KneType.OBJECT -> "Long::class.javaPrimitiveType"
        is KneType.DATA_CLASS -> "java.lang.foreign.MemorySegment::class.java"
        is KneType.LIST, is KneType.SET, is KneType.MAP -> "java.lang.foreign.MemorySegment::class.java"
        else -> "${upcallJvmType(type)}::class.javaPrimitiveType"
    }

    /** The FFM ValueLayout for a callback parameter/return type. */
    private fun upcallLayout(type: KneType): String = when (type) {
        KneType.INT -> "JAVA_INT"
        KneType.LONG -> "JAVA_LONG"
        KneType.DOUBLE -> "JAVA_DOUBLE"
        KneType.FLOAT -> "JAVA_FLOAT"
        KneType.BOOLEAN -> "JAVA_INT"
        KneType.BYTE -> "JAVA_BYTE"
        KneType.SHORT -> "JAVA_SHORT"
        KneType.STRING -> "ADDRESS"
        KneType.BYTE_ARRAY -> "ADDRESS" // packed buffer for return; expanded for params
        is KneType.OBJECT -> "JAVA_LONG" // opaque handle
        is KneType.DATA_CLASS -> "ADDRESS" // struct pointer
        is KneType.LIST, is KneType.SET, is KneType.MAP -> "ADDRESS" // packed buffer
        else -> "JAVA_INT"
    }

    private fun fieldSize(type: KneType): Int = when (type) {
        KneType.INT, KneType.FLOAT, KneType.BOOLEAN -> 4
        KneType.LONG, KneType.DOUBLE, KneType.STRING -> 8
        KneType.BYTE -> 1
        KneType.SHORT -> 2
        else -> 8
    }

    // ── Class proxy ──────────────────────────────────────────────────────────

    private fun generateClassProxy(cls: KneClass, module: KneModule, pkg: String): String = buildString {
        val p = module.libName
        val n = cls.simpleName

        appendLine("// Auto-generated by kotlin-native-export plugin. Do not modify.")
        appendLine("package $pkg")
        appendLine()
        appendLine("import java.lang.foreign.Arena")
        appendLine("import java.lang.foreign.FunctionDescriptor")
        appendLine("import java.lang.foreign.MemorySegment")
        appendLine("import java.lang.foreign.ValueLayout.*")
        appendLine("import java.lang.invoke.MethodHandle")
        appendLine("import java.lang.ref.Cleaner")
        val classHasSuspend = cls.methods.any { it.isSuspend || it.returnType is KneType.FLOW } || cls.companionMethods.any { it.isSuspend }
        val classHasFlow = cls.methods.any { it.returnType is KneType.FLOW }
        if (classHasSuspend) {
            appendLine("import kotlinx.coroutines.suspendCancellableCoroutine")
            appendLine("import kotlin.coroutines.resume")
            appendLine("import kotlin.coroutines.resumeWithException")
        }
        if (classHasFlow) {
            appendLine("import kotlinx.coroutines.flow.Flow")
            appendLine("import kotlinx.coroutines.flow.channelFlow")
            appendLine("import kotlinx.coroutines.channels.awaitClose")
        }
        appendLine()

        appendLine("/**")
        appendLine(" * JVM proxy for Kotlin/Native class [$n].")
        appendLine(" * Uses FFM MethodHandles to dispatch every call to the native shared library.")
        appendLine(" * Object lifecycle is managed via Java Cleaner (automatic GC) or explicit close().")
        appendLine(" */")
        val hasCallbacks = classHasCallbacks(cls)
        val isRoot = cls.superClass == null
        val hasSuperClass = cls.superClass != null

        // Class modifiers: open/abstract/sealed
        val modifier = when {
            cls.isSealed -> "sealed "
            cls.isAbstract -> "abstract "
            cls.isOpen -> "open "
            else -> ""
        }

        val isInstantiable = !cls.isAbstract && !cls.isSealed
        val hasHierarchy = hasSuperClass || cls.isOpen || cls.isAbstract || cls.isSealed || cls.interfaces.isNotEmpty()

        // Constructor visibility: private for flat classes (original behavior), internal/protected for hierarchy
        val ctorVisibility = when {
            cls.isSealed -> "protected"
            hasHierarchy -> "internal"
            else -> "private"
        }

        // Superclass/interface clause
        val superParts = mutableListOf<String>()
        if (hasSuperClass) {
            val parentSimple = cls.superClass!!.substringAfterLast(".")
            superParts.add("$parentSimple(handle)")
        } else {
            superParts.add("AutoCloseable")
        }
        cls.interfaces.forEach { ifaceFq ->
            val ifaceSimple = ifaceFq.substringAfterLast(".")
            superParts.add(ifaceSimple)
        }
        val superClause = superParts.joinToString(", ")

        // Handle declaration: only on root class. Preserve original visibility for flat classes.
        val implementsInterface = cls.interfaces.isNotEmpty()
        val handleDecl = when {
            !isRoot -> "handle: Long"  // subclass: just a param, inherited from parent
            implementsInterface -> "override val handle: Long"  // implements interface with handle
            hasHierarchy -> "val handle: Long"  // open/abstract/sealed: public for subclass access
            else -> "internal val handle: Long"  // flat class: original behavior
        }

        appendLine("${modifier}class $n $ctorVisibility constructor($handleDecl) : $superClause {")
        if (isRoot) {
            val disposedVisibility = if (hasHierarchy) "protected" else "private"
            appendLine("    @Volatile $disposedVisibility var _disposed = false")
        }
        if (hasCallbacks) {
            appendLine("    internal val _callbackArena: Arena = Arena.ofShared()")
        }
        if (classHasSuspend) {
            appendLine("    private val _suspendInFlight = java.util.concurrent.atomic.AtomicInteger(0)")
        }
        appendLine()

        val companionHasCallbacks = cls.companionMethods.any { fn -> fn.params.any { it.type is KneType.FUNCTION } }

        // Companion: MethodHandles + factory
        appendLine("    companion object {")
        if (isInstantiable) {
            appendLine("        private val CLEANER = Cleaner.create()")
        }
        if (companionHasCallbacks) {
            appendLine("        private val _companionCallbackArena: Arena = Arena.ofShared()")
        }
        appendLine()

        if (isInstantiable) {
            val ctorDescriptor = buildCtorDescriptor(cls.constructor.params)
            appendLine("        private val NEW_HANDLE: MethodHandle by lazy {")
            appendLine("            KneRuntime.handle(\"${p}_${n}_new\",")
            appendLine("                $ctorDescriptor)")
            appendLine("        }")

            // Handles for constructor overloads (trailing default params dropped)
            val trailingDefaults = cls.constructor.params.reversed().takeWhile { it.hasDefault }.size
            for (drop in 1..trailingDefaults) {
                val requiredParams = cls.constructor.params.dropLast(drop)
                val suffix = requiredParams.size.toString()
                val overloadDescriptor = buildCtorDescriptor(requiredParams)
                appendLine("        private val NEW_HANDLE_$suffix: MethodHandle by lazy {")
                appendLine("            KneRuntime.handle(\"${p}_${n}_new$suffix\",")
                appendLine("                $overloadDescriptor)")
                appendLine("        }")
            }
            appendLine("        private val DISPOSE_HANDLE: MethodHandle by lazy {")
            appendLine("            KneRuntime.handle(\"${p}_${n}_dispose\",")
            appendLine("                FunctionDescriptor.ofVoid(JAVA_LONG))")
            appendLine("        }")
        }

        cls.methods.forEach { method ->
            val handleName = "${method.name.uppercase()}_HANDLE"
            val descriptor = buildMethodDescriptor(method)
            appendLine("        private val $handleName: MethodHandle by lazy {")
            appendLine("            KneRuntime.handle(\"${p}_${n}_${method.name}\",")
            appendLine("                $descriptor)")
            appendLine("        }")
        }

        cls.properties.forEach { prop ->
            val getHandleName = "GET_${prop.name.uppercase()}_HANDLE"
            val getDescriptor = buildGetterDescriptor(prop)
            appendLine("        private val $getHandleName: MethodHandle by lazy {")
            appendLine("            KneRuntime.handle(\"${p}_${n}_get_${prop.name}\",")
            appendLine("                $getDescriptor)")
            appendLine("        }")
            if (prop.mutable) {
                val setHandleName = "SET_${prop.name.uppercase()}_HANDLE"
                val setLayouts = if (prop.type.isCollection()) ", JAVA_LONG" else buildLayouts(listOf(prop.type))
                appendLine("        private val $setHandleName: MethodHandle by lazy {")
                appendLine("            KneRuntime.handle(\"${p}_${n}_set_${prop.name}\",")
                appendLine("                FunctionDescriptor.ofVoid(JAVA_LONG$setLayouts))")
                appendLine("        }")
            }
        }

        cls.companionMethods.forEach { method ->
            val handleName = "COMPANION_${method.name.uppercase()}_HANDLE"
            val descriptor = buildTopLevelDescriptor(method)
            appendLine("        private val $handleName: MethodHandle by lazy {")
            appendLine("            KneRuntime.handle(\"${p}_${n}_companion_${method.name}\",")
            appendLine("                $descriptor)")
            appendLine("        }")
        }
        cls.companionProperties.forEach { prop ->
            val getHandleName = "COMPANION_GET_${prop.name.uppercase()}_HANDLE"
            val getDescriptor = buildCompanionGetterDescriptor(prop)
            appendLine("        private val $getHandleName: MethodHandle by lazy {")
            appendLine("            KneRuntime.handle(\"${p}_${n}_companion_get_${prop.name}\",")
            appendLine("                $getDescriptor)")
            appendLine("        }")
            if (prop.mutable) {
                val setHandleName = "COMPANION_SET_${prop.name.uppercase()}_HANDLE"
                appendLine("        private val $setHandleName: MethodHandle by lazy {")
                appendLine("            KneRuntime.handle(\"${p}_${n}_companion_set_${prop.name}\",")
                appendLine("                FunctionDescriptor.ofVoid(${prop.type.ffmLayout}))")
                appendLine("        }")
            }
        }

        // List<DC> accessor handles
        val dcListTypes = mutableSetOf<KneType.DATA_CLASS>()
        fun scanForDcList(type: KneType) {
            val inner = type.unwrapCollection()
            val elem = when (inner) { is KneType.LIST -> inner.elementType; is KneType.SET -> inner.elementType; else -> null }
            if (elem is KneType.DATA_CLASS) dcListTypes.add(elem)
        }
        cls.methods.forEach { m -> scanForDcList(m.returnType) }
        cls.companionMethods.forEach { m -> scanForDcList(m.returnType) }
        for (dc in dcListTypes) {
            val dn = dc.simpleName.uppercase()
            val flatFields = flattenDcFields(dc, "")
            val getLayouts = buildList {
                add("JAVA_LONG"); add("JAVA_INT") // handle, index
                flatFields.forEach { (_, type) ->
                    when (type) { KneType.STRING, KneType.BYTE_ARRAY -> { add("ADDRESS"); add("JAVA_INT") }; else -> add("ADDRESS") }
                }
            }.joinToString(", ")
            appendLine("        private val LIST_${dn}_SIZE_HANDLE: MethodHandle by lazy {")
            appendLine("            KneRuntime.handle(\"${p}_list_${dc.simpleName}_size\", FunctionDescriptor.of(JAVA_INT, JAVA_LONG))")
            appendLine("        }")
            appendLine("        private val LIST_${dn}_GET_HANDLE: MethodHandle by lazy {")
            appendLine("            KneRuntime.handle(\"${p}_list_${dc.simpleName}_get\", FunctionDescriptor.ofVoid($getLayouts))")
            appendLine("        }")
            appendLine("        private val LIST_${dn}_DISPOSE_HANDLE: MethodHandle by lazy {")
            appendLine("            KneRuntime.handle(\"${p}_list_${dc.simpleName}_dispose\", FunctionDescriptor.ofVoid(JAVA_LONG))")
            appendLine("        }")
        }

        // List<DC> param create/add handles
        val dcListParamTypes = mutableSetOf<KneType.DATA_CLASS>()
        fun scanForDcListParam(type: KneType) {
            val inner = when (type) { is KneType.NULLABLE -> type.inner; else -> type }
            when (inner) {
                is KneType.LIST -> if (inner.elementType is KneType.DATA_CLASS) dcListParamTypes.add(inner.elementType)
                is KneType.SET -> if (inner.elementType is KneType.DATA_CLASS) dcListParamTypes.add(inner.elementType)
                else -> {}
            }
        }
        cls.methods.forEach { m -> m.params.forEach { scanForDcListParam(it.type) } }
        cls.companionMethods.forEach { m -> m.params.forEach { scanForDcListParam(it.type) } }
        for (dc in dcListParamTypes) {
            val dn = dc.simpleName.uppercase()
            appendLine("        private val LISTPARAM_${dn}_CREATE_HANDLE: MethodHandle by lazy {")
            appendLine("            KneRuntime.handle(\"${p}_listparam_${dc.simpleName}_create\", FunctionDescriptor.of(JAVA_LONG, JAVA_INT))")
            appendLine("        }")
            val flatFields = flattenDcFields(dc, "")
            val addLayouts = buildList {
                add("JAVA_LONG") // handle
                flatFields.forEach { (_, type) ->
                    when (type) { KneType.STRING -> add("ADDRESS"); KneType.BYTE_ARRAY -> { add("ADDRESS"); add("JAVA_INT") }; else -> add(type.ffmLayout) }
                }
            }.joinToString(", ")
            appendLine("        private val LISTPARAM_${dn}_ADD_HANDLE: MethodHandle by lazy {")
            appendLine("            KneRuntime.handle(\"${p}_listparam_${dc.simpleName}_add\", FunctionDescriptor.ofVoid($addLayouts))")
            appendLine("        }")
        }

        // DC collection field wrap handles
        val dcFieldCollKeys = mutableSetOf<String>()
        val dcFieldMapKeys = mutableSetOf<Pair<String, String>>()
        fun scanDcFieldColls(dc: KneType.DATA_CLASS) {
            dc.fields.forEach { f ->
                when (f.type) {
                    is KneType.LIST -> if (f.type.elementType !is KneType.DATA_CLASS) dcFieldCollKeys.add(suspendCollElemKey(f.type.elementType))
                    is KneType.SET -> if (f.type.elementType !is KneType.DATA_CLASS) dcFieldCollKeys.add(suspendCollElemKey(f.type.elementType))
                    is KneType.MAP -> dcFieldMapKeys.add(Pair(suspendCollElemKey(f.type.keyType), suspendCollElemKey(f.type.valueType)))
                    is KneType.DATA_CLASS -> scanDcFieldColls(f.type)
                    else -> {}
                }
            }
        }
        // Scan DC params for collection fields + mutable collection properties + collection params with ByteArray/nested elements
        cls.methods.forEach { m ->
            m.params.forEach { pp ->
                extractDataClass(pp.type)?.let { scanDcFieldColls(it) }
                // Scan collection params for ByteArray/nested collection element wrapping
                val inner = pp.type.unwrapCollection()
                when (inner) {
                    is KneType.LIST -> if (inner.elementType == KneType.BYTE_ARRAY || inner.elementType is KneType.LIST || inner.elementType is KneType.SET || inner.elementType is KneType.MAP) dcFieldCollKeys.add(suspendCollElemKey(inner.elementType))
                    is KneType.SET -> if (inner.elementType == KneType.BYTE_ARRAY) dcFieldCollKeys.add(suspendCollElemKey(inner.elementType))
                    else -> {}
                }
            }
        }
        cls.properties.filter { it.mutable && it.type.isCollection() }.forEach { prop ->
            val inner = prop.type.unwrapCollection()
            when (inner) {
                is KneType.LIST -> if (inner.elementType !is KneType.DATA_CLASS) dcFieldCollKeys.add(suspendCollElemKey(inner.elementType))
                is KneType.SET -> if (inner.elementType !is KneType.DATA_CLASS) dcFieldCollKeys.add(suspendCollElemKey(inner.elementType))
                is KneType.MAP -> dcFieldMapKeys.add(Pair(suspendCollElemKey(inner.keyType), suspendCollElemKey(inner.valueType)))
                else -> {}
            }
        }
        for (key in dcFieldCollKeys) {
            val uk = key.uppercase()
            appendLine("        private val WRAP_COLL_${uk}_HANDLE: MethodHandle by lazy {")
            appendLine("            KneRuntime.handle(\"${p}_kne_wrapColl$key\", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT))")
            appendLine("        }")
        }
        for ((kk, vk) in dcFieldMapKeys) {
            val ukk = kk.uppercase()
            val uvk = vk.uppercase()
            appendLine("        private val WRAP_MAP_${ukk}_${uvk}_HANDLE: MethodHandle by lazy {")
            appendLine("            KneRuntime.handle(\"${p}_kne_wrapMap$kk$vk\", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT))")
            appendLine("        }")
        }

        // Flow<DC> reader handles
        val dcFlowTypes = mutableSetOf<KneType.DATA_CLASS>()
        cls.methods.forEach { m -> if (m.returnType is KneType.FLOW && (m.returnType as KneType.FLOW).elementType is KneType.DATA_CLASS) dcFlowTypes.add((m.returnType as KneType.FLOW).elementType as KneType.DATA_CLASS) }
        cls.companionMethods.forEach { m -> if (m.returnType is KneType.FLOW && (m.returnType as KneType.FLOW).elementType is KneType.DATA_CLASS) dcFlowTypes.add((m.returnType as KneType.FLOW).elementType as KneType.DATA_CLASS) }
        for (dc in dcFlowTypes) {
            val dn = dc.simpleName.uppercase()
            val flatFields = flattenDcFields(dc, "")
            val readLayouts = buildList {
                add("JAVA_LONG") // handle
                flatFields.forEach { (_, type) ->
                    when (type) { KneType.STRING, KneType.BYTE_ARRAY -> { add("ADDRESS"); add("JAVA_INT") }; else -> add("ADDRESS") }
                }
            }.joinToString(", ")
            appendLine("        private val FLOWDC_${dn}_READ_HANDLE: MethodHandle by lazy {")
            appendLine("            KneRuntime.handle(\"${p}_flowdc_${dc.simpleName}_read\", FunctionDescriptor.ofVoid($readLayouts))")
            appendLine("        }")
        }

        // Suspend DC reader handles
        val dcSuspendTypes = mutableSetOf<KneType.DATA_CLASS>()
        fun extractSuspendDc(fn: KneFunction) {
            if (!fn.isSuspend) return
            val dc = when (val rt = fn.returnType) {
                is KneType.DATA_CLASS -> rt
                is KneType.NULLABLE -> rt.inner as? KneType.DATA_CLASS
                else -> null
            }
            if (dc != null) dcSuspendTypes.add(dc)
        }
        cls.methods.forEach { extractSuspendDc(it) }
        cls.companionMethods.forEach { extractSuspendDc(it) }
        for (dc in dcSuspendTypes) {
            val dn = dc.simpleName.uppercase()
            val flatFields = flattenDcFields(dc, "")
            val readLayouts = buildList {
                add("JAVA_LONG") // handle
                flatFields.forEach { (_, type) ->
                    when (type) { KneType.STRING, KneType.BYTE_ARRAY -> { add("ADDRESS"); add("JAVA_INT") }; else -> add("ADDRESS") }
                }
            }.joinToString(", ")
            appendLine("        private val SUSPENDDC_${dn}_READ_HANDLE: MethodHandle by lazy {")
            appendLine("            KneRuntime.handle(\"${p}_suspenddc_${dc.simpleName}_read\", FunctionDescriptor.ofVoid($readLayouts))")
            appendLine("        }")
        }

        // Suspend collection reader handles (also used for DC collection fields)
        val suspendCollKeys = mutableSetOf<String>()
        val suspendMapKeys = mutableSetOf<Pair<String, String>>()
        fun extractSuspendColl(fn: KneFunction) {
            if (!fn.isSuspend) return
            val rt = when (val t = fn.returnType) {
                is KneType.NULLABLE -> t.inner
                else -> t
            }
            when (rt) {
                is KneType.LIST -> if (rt.elementType !is KneType.DATA_CLASS) suspendCollKeys.add(suspendCollElemKey(rt.elementType))
                is KneType.SET -> if (rt.elementType !is KneType.DATA_CLASS) suspendCollKeys.add(suspendCollElemKey(rt.elementType))
                is KneType.MAP -> suspendMapKeys.add(Pair(suspendCollElemKey(rt.keyType), suspendCollElemKey(rt.valueType)))
                else -> {}
            }
        }
        cls.methods.forEach { extractSuspendColl(it) }
        cls.companionMethods.forEach { extractSuspendColl(it) }
        // Also scan DC return types for collection fields (reader bridges needed)
        fun scanDcFieldsForCollReaders(dc: KneType.DATA_CLASS) {
            dc.fields.forEach { f ->
                when (f.type) {
                    is KneType.LIST -> if (f.type.elementType !is KneType.DATA_CLASS) suspendCollKeys.add(suspendCollElemKey(f.type.elementType))
                    is KneType.SET -> if (f.type.elementType !is KneType.DATA_CLASS) suspendCollKeys.add(suspendCollElemKey(f.type.elementType))
                    is KneType.MAP -> suspendMapKeys.add(Pair(suspendCollElemKey(f.type.keyType), suspendCollElemKey(f.type.valueType)))
                    is KneType.DATA_CLASS -> scanDcFieldsForCollReaders(f.type)
                    else -> {}
                }
            }
        }
        cls.methods.forEach { m ->
            extractDataClass(m.returnType)?.let { scanDcFieldsForCollReaders(it) }
            m.params.forEach { pp -> extractDataClass(pp.type)?.let { scanDcFieldsForCollReaders(it) } }
            // Scan Flow<Collection> return types
            if (m.returnType is KneType.FLOW) {
                when (val fe = (m.returnType as KneType.FLOW).elementType) {
                    is KneType.LIST -> if (fe.elementType !is KneType.DATA_CLASS) suspendCollKeys.add(suspendCollElemKey(fe.elementType))
                    is KneType.SET -> if (fe.elementType !is KneType.DATA_CLASS) suspendCollKeys.add(suspendCollElemKey(fe.elementType))
                    is KneType.MAP -> suspendMapKeys.add(Pair(suspendCollElemKey(fe.keyType), suspendCollElemKey(fe.valueType)))
                    else -> {}
                }
            }
        }
        cls.companionMethods.forEach { m -> extractDataClass(m.returnType)?.let { scanDcFieldsForCollReaders(it) } }
        for (key in suspendCollKeys) {
            val uk = key.uppercase()
            val layout = when (key) {
                "String" -> "ADDRESS, JAVA_INT" // outBuf + outBufLen
                "Long", "ObjHandle" -> "ADDRESS, JAVA_INT" // outBuf + outLen (LongVar*)
                "Double" -> "ADDRESS, JAVA_INT"
                "Float" -> "ADDRESS, JAVA_INT"
                else -> "ADDRESS, JAVA_INT" // IntVar* for Int, Boolean, Short, Byte, Enum
            }
            appendLine("        private val SUSPEND_READCOLL_${uk}_HANDLE: MethodHandle by lazy {")
            appendLine("            KneRuntime.handle(\"${p}_kne_suspend_readColl$key\", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, $layout))")
            appendLine("        }")
        }
        for ((kk, vk) in suspendMapKeys) {
            val ukk = kk.uppercase()
            val uvk = vk.uppercase()
            // Map reader: handle, keysBuf, keysLen, valsBuf, valsLen -> count
            appendLine("        private val SUSPEND_READMAP_${ukk}_${uvk}_HANDLE: MethodHandle by lazy {")
            appendLine("            KneRuntime.handle(\"${p}_kne_suspend_readMap$kk$vk\", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT))")
            appendLine("        }")
        }

        // Function return invoke handles
        val fnReturnTypes = mutableSetOf<KneType.FUNCTION>()
        cls.methods.forEach { m -> if (m.returnType is KneType.FUNCTION) fnReturnTypes.add(m.returnType as KneType.FUNCTION) }
        cls.companionMethods.forEach { m -> if (m.returnType is KneType.FUNCTION) fnReturnTypes.add(m.returnType as KneType.FUNCTION) }
        for (fnType in fnReturnTypes) {
            val fnId = fnInvokeId(fnType)
            val layouts = buildList {
                add("JAVA_LONG") // handle
                fnType.paramTypes.forEach { t ->
                    when (t) {
                        KneType.STRING -> add("ADDRESS")
                        KneType.BYTE_ARRAY -> { add("ADDRESS"); add("JAVA_INT") }
                        KneType.BOOLEAN -> add("JAVA_INT")
                        is KneType.ENUM -> add("JAVA_INT")
                        is KneType.OBJECT -> add("JAVA_LONG")
                        else -> add(t.ffmLayout)
                    }
                }
            }.joinToString(", ")
            val retLayout = when (fnType.returnType) {
                KneType.UNIT -> null
                KneType.BOOLEAN -> "JAVA_INT"
                KneType.STRING, KneType.BYTE_ARRAY -> "JAVA_LONG"
                is KneType.ENUM -> "JAVA_INT"
                is KneType.OBJECT -> "JAVA_LONG"
                else -> fnType.returnType.ffmLayout
            }
            val descriptor = if (retLayout == null) "FunctionDescriptor.ofVoid($layouts)" else "FunctionDescriptor.of($retLayout, $layouts)"
            appendLine("        private val INVOKE_FN_${fnId.uppercase()}_HANDLE: MethodHandle by lazy {")
            appendLine("            KneRuntime.handle(\"${p}_kne_invokeFn_$fnId\", $descriptor)")
            appendLine("        }")
        }

        // Factory (only for instantiable classes)
        if (isInstantiable) {
            val ctorParams = cls.constructor.params.joinToString(", ") { "${it.name}: ${it.type.jvmTypeName}" }
            appendLine()
            appendLine("        operator fun invoke($ctorParams): $n {")
            appendCtorInvokeBody("            ", cls.constructor.params, "NEW_HANDLE")
            appendLine("        }")
            appendLine()

            // Constructor overloads for default parameters
            val trailingDefaults = cls.constructor.params.reversed().takeWhile { it.hasDefault }.size
            for (drop in 1..trailingDefaults) {
                val requiredParams = cls.constructor.params.dropLast(drop)
                val suffix = requiredParams.size.toString()
                val overloadParams = requiredParams.joinToString(", ") { "${it.name}: ${it.type.jvmTypeName}" }
                appendLine()
                appendLine("        operator fun invoke($overloadParams): $n {")
                appendCtorInvokeBody("            ", requiredParams, "NEW_HANDLE_$suffix")
                appendLine("        }")
            }
        }
        appendLine()

        if (isInstantiable) {
            appendLine("        internal fun fromNativeHandle(h: Long): $n {")
            appendLine("            val obj = $n(h)")
            if (hasCallbacks) {
                appendLine("            val cbArena = obj._callbackArena")
                if (classHasSuspend) {
                    appendLine("            val inFlight = obj._suspendInFlight")
                    appendLine("            CLEANER.register(obj) { if (!obj._disposed) { obj._disposed = true; repeat(1000) { if (inFlight.get() <= 0) return@repeat; Thread.sleep(1) }; runCatching { cbArena.close() }; runCatching { DISPOSE_HANDLE.invoke(h) } } }")
                } else {
                    appendLine("            CLEANER.register(obj) { if (!obj._disposed) { obj._disposed = true; runCatching { cbArena.close() }; runCatching { DISPOSE_HANDLE.invoke(h) } } }")
                }
            } else {
                appendLine("            CLEANER.register(obj) { if (!obj._disposed) { obj._disposed = true; runCatching { DISPOSE_HANDLE.invoke(h) } } }")
            }
            appendLine("            return obj")
            appendLine("        }")
        }

        // Companion methods
        cls.companionMethods.forEach { method -> appendCompanionMethodProxy(method) }
        cls.companionProperties.forEach { prop -> appendCompanionPropertyProxy(prop) }

        appendLine("    }")
        appendLine()

        // Methods (dispatch suspend / flow / regular)
        cls.methods.forEach { method ->
            if (method.isSuspend) appendSuspendMethodProxy(method, cls, p)
            else if (method.returnType is KneType.FLOW) appendFlowMethodProxy(method, cls, p)
            else appendMethodProxy(method, cls, p)
        }
        cls.properties.forEach { prop -> appendPropertyProxy(prop, cls) }

        // close() — idempotent, thread-safe, waits for in-flight suspend calls
        if (isRoot) {
            val openClose = if (cls.isOpen || cls.isAbstract || cls.isSealed) "open " else ""
            appendLine("    ${openClose}override fun close() {")
            appendLine("        if (_disposed) return")
            appendLine("        _disposed = true")
            if (classHasSuspend) {
                appendLine("        while (_suspendInFlight.get() > 0) { Thread.sleep(1) }")
            }
            if (hasCallbacks) {
                appendLine("        runCatching { _callbackArena.close() }")
            }
            if (isInstantiable) {
                appendLine("        runCatching { DISPOSE_HANDLE.invoke(handle) }")
            }
            appendLine("    }")
        }
        appendLine("}")
    }

    // ── Interface Proxy ────────────────────────────────────────────────────

    private fun generateInterfaceProxy(iface: dev.nucleusframework.nna.plugin.ir.KneInterface, module: KneModule, pkg: String): String = buildString {
        appendLine("// Auto-generated by kotlin-native-export plugin. Do not modify.")
        appendLine("package $pkg")
        appendLine()
        appendLine("/**")
        appendLine(" * JVM interface proxy for Kotlin/Native interface [${iface.simpleName}].")
        appendLine(" */")
        appendLine("interface ${iface.simpleName} {")
        appendLine("    val handle: Long")
        appendLine()
        // Interface methods as abstract declarations
        iface.methods.forEach { method ->
            val params = method.params.joinToString(", ") { "${it.name}: ${it.type.jvmTypeName}" }
            val returnDecl = if (method.returnType == KneType.UNIT) "" else ": ${method.returnType.jvmTypeName}"
            val suspendMod = if (method.isSuspend) "suspend " else ""
            appendLine("    ${suspendMod}fun ${method.name}($params)$returnDecl")
        }
        // Interface properties as abstract declarations
        iface.properties.forEach { prop ->
            if (prop.mutable) {
                appendLine("    var ${prop.name}: ${prop.type.jvmTypeName}")
            } else {
                appendLine("    val ${prop.name}: ${prop.type.jvmTypeName}")
            }
        }
        appendLine("}")
    }

    // ── Extension Functions File ─────────────────────────────────────────

    private fun generateExtensionsFile(functions: List<KneFunction>, module: KneModule, pkg: String): String = buildString {
        val p = module.libName
        appendLine("// Auto-generated by kotlin-native-export plugin. Do not modify.")
        appendLine("package $pkg")
        appendLine()
        appendLine("import java.lang.foreign.Arena")
        appendLine("import java.lang.foreign.FunctionDescriptor")
        appendLine("import java.lang.foreign.MemorySegment")
        appendLine("import java.lang.foreign.ValueLayout.*")
        appendLine("import java.lang.invoke.MethodHandle")
        appendLine()

        // MethodHandle declarations as top-level private vals
        functions.forEach { fn ->
            val receiverType = fn.receiverType ?: return@forEach
            val receiverSimpleName = when (receiverType) {
                is KneType.OBJECT -> receiverType.simpleName
                is KneType.INTERFACE -> receiverType.simpleName
                else -> return@forEach
            }
            val handleName = "EXT_${receiverSimpleName.uppercase()}_${fn.name.uppercase()}_HANDLE"
            val descriptor = buildExtensionDescriptor(fn)
            appendLine("private val $handleName: MethodHandle by lazy {")
            appendLine("    KneRuntime.handle(\"${p}_${receiverSimpleName}_ext_${fn.name}\",")
            appendLine("        $descriptor)")
            appendLine("}")
            appendLine()
        }

        // Extension function implementations
        functions.forEach { fn ->
            val receiverType = fn.receiverType ?: return@forEach
            val receiverSimpleName = when (receiverType) {
                is KneType.OBJECT -> receiverType.simpleName
                is KneType.INTERFACE -> receiverType.simpleName
                else -> return@forEach
            }
            val handleName = "EXT_${receiverSimpleName.uppercase()}_${fn.name.uppercase()}_HANDLE"
            val params = fn.params.joinToString(", ") { "${it.name}: ${it.type.jvmTypeName}" }
            val returnDecl = if (fn.returnType == KneType.UNIT) "" else ": ${fn.returnType.jvmTypeName}"

            appendLine("fun $receiverSimpleName.${fn.name}($params)$returnDecl {")
            // Simple case: primitives only, no arena needed
            val needsArena = fn.returnType == KneType.STRING || fn.returnType == KneType.BYTE_ARRAY ||
                fn.params.any { it.type == KneType.STRING }
            if (needsArena) {
                appendLine("    Arena.ofConfined().use { arena ->")
                fn.params.filter { it.type == KneType.STRING }.forEach { param ->
                    appendLine("        val ${param.name}Seg = arena.allocateFrom(${param.name})")
                }
                val invokeArgs = buildList {
                    add("this.handle")
                    fn.params.forEach { param ->
                        when (param.type) {
                            KneType.STRING -> add("${param.name}Seg")
                            KneType.BOOLEAN -> add("if (${param.name}) 1 else 0")
                            is KneType.ENUM -> add("${param.name}.ordinal")
                            is KneType.OBJECT -> add("${param.name}.handle")
                            else -> add(param.name)
                        }
                    }
                    if (fn.returnType == KneType.STRING || fn.returnType == KneType.BYTE_ARRAY) {
                        add("arena.allocate(${STRING_BUF_SIZE}.toLong())")
                        add(STRING_BUF_SIZE.toString())
                    }
                }.joinToString(", ")
                when (fn.returnType) {
                    KneType.UNIT -> {
                        appendLine("        $handleName.invoke($invokeArgs)")
                        appendLine("        KneRuntime.checkError()")
                    }
                    KneType.STRING -> {
                        appendLine("        val _buf = arena.allocate(${STRING_BUF_SIZE}.toLong())")
                        appendLine("        val _len = $handleName.invoke(this.handle${if (fn.params.isNotEmpty()) ", " + fn.params.joinToString(", ") { when (it.type) { KneType.STRING -> "${it.name}Seg"; KneType.BOOLEAN -> "if (${it.name}) 1 else 0"; else -> it.name } } else ""}, _buf, $STRING_BUF_SIZE) as Int")
                        appendLine("        KneRuntime.checkError()")
                        appendLine("        return _buf.getString(0)")
                    }
                    else -> {
                        appendLine("        val _r = $handleName.invoke($invokeArgs)")
                        appendLine("        KneRuntime.checkError()")
                        appendLine("        return _r as ${fn.returnType.jvmTypeName}")
                    }
                }
                appendLine("    }")
            } else {
                val invokeArgs = buildList {
                    add("this.handle")
                    fn.params.forEach { param ->
                        when (param.type) {
                            KneType.BOOLEAN -> add("if (${param.name}) 1 else 0")
                            is KneType.ENUM -> add("${param.name}.ordinal")
                            is KneType.OBJECT -> add("${param.name}.handle")
                            else -> add(param.name)
                        }
                    }
                }.joinToString(", ")
                when (fn.returnType) {
                    KneType.UNIT -> {
                        appendLine("    $handleName.invoke($invokeArgs)")
                        appendLine("    KneRuntime.checkError()")
                    }
                    KneType.BOOLEAN -> {
                        appendLine("    val _r = $handleName.invoke($invokeArgs) as Int")
                        appendLine("    KneRuntime.checkError()")
                        appendLine("    return _r != 0")
                    }
                    KneType.INT -> {
                        appendLine("    val _r = $handleName.invoke($invokeArgs) as Int")
                        appendLine("    KneRuntime.checkError()")
                        appendLine("    return _r")
                    }
                    KneType.LONG -> {
                        appendLine("    val _r = $handleName.invoke($invokeArgs) as Long")
                        appendLine("    KneRuntime.checkError()")
                        appendLine("    return _r")
                    }
                    KneType.DOUBLE -> {
                        appendLine("    val _r = $handleName.invoke($invokeArgs) as Double")
                        appendLine("    KneRuntime.checkError()")
                        appendLine("    return _r")
                    }
                    is KneType.OBJECT -> {
                        appendLine("    val _r = $handleName.invoke($invokeArgs) as Long")
                        appendLine("    KneRuntime.checkError()")
                        appendLine("    return ${fn.returnType.simpleName}.fromNativeHandle(_r)")
                    }
                    else -> {
                        appendLine("    val _r = $handleName.invoke($invokeArgs)")
                        appendLine("    KneRuntime.checkError()")
                        appendLine("    return _r as ${fn.returnType.jvmTypeName}")
                    }
                }
            }
            appendLine("}")
            appendLine()
        }
    }

    /** Build FFM descriptor for an extension function (receiver handle as first param). */
    private fun buildExtensionDescriptor(fn: KneFunction): String {
        val paramLayouts = buildList {
            add("JAVA_LONG") // receiver handle
            fn.params.forEach { p -> add(p.type.ffmLayout) }
            if (fn.returnType == KneType.STRING || fn.returnType == KneType.BYTE_ARRAY) {
                add("ADDRESS"); add("JAVA_INT") // out buffer
            }
        }
        val effectiveReturn = when {
            fn.returnType == KneType.STRING || fn.returnType == KneType.BYTE_ARRAY -> KneType.INT
            else -> fn.returnType
        }
        return buildDescriptor(effectiveReturn, paramLayouts)
    }

    /** Generate the invoke body for a constructor (handles DC/String/ByteArray/collection params). */
    private fun StringBuilder.appendCtorInvokeBody(indent: String, params: List<KneParam>, handleName: String) {
        val hasDc = params.any { extractDataClass(it.type) != null }
        val hasCollection = params.any { it.type.isCollection() }
        val needsArena = params.any { it.type.isStringLike() || it.type.isByteArrayType() || it.type.isFunctionType() } || hasDc || hasCollection
        if (needsArena) {
            appendLine("${indent}Arena.ofConfined().use { arena ->")
            appendStringInvokeArgsAlloc("$indent    ", params)
            appendCollectionParamAlloc("$indent    ", params)
            val args = buildList { params.forEach { p -> addAll(buildExpandedInvokeArgs(p)) } }.joinToString(", ")
            appendLine("$indent    val h = $handleName.invoke($args) as Long")
            appendLine("$indent    KneRuntime.checkError()")
            appendLine("$indent    return fromNativeHandle(h)")
            appendLine("${indent}}")
        } else {
            val args = params.joinToString(", ") { buildJvmInvokeArg(it.name, it.type) }
            appendLine("${indent}val h = $handleName.invoke($args) as Long")
            appendLine("${indent}KneRuntime.checkError()")
            appendLine("${indent}return fromNativeHandle(h)")
        }
    }

    /** Generate a Flow-returning method proxy using channelFlow. */
    private fun StringBuilder.appendFlowMethodProxy(fn: KneFunction, cls: KneClass, prefix: String) {
        val handleName = "${fn.name.uppercase()}_HANDLE"
        val flowType = fn.returnType as KneType.FLOW
        val elemType = flowType.elementType
        val params = fn.params.joinToString(", ") { "${it.name}: ${it.type.jvmTypeName}" }

        appendLine("    fun ${fn.name}($params): Flow<${elemType.jvmTypeName}> = channelFlow {")
        appendLine("        _suspendInFlight.incrementAndGet()")

        // onNext stub — decode element and send to channel
        appendLine("        val _nextStub = KneRuntime.createFlowNextStub({ _value ->")
        appendFlowElementDecode("            ", elemType)
        appendLine("        }, _callbackArena)")

        // onError stub
        appendLine("        val _errorStub = KneRuntime.createFlowErrorStub({ _msgHandle ->")
        appendLine("            channel.close(KotlinNativeException(KneRuntime.readStringFromRef(_msgHandle)))")
        appendLine("        }, _callbackArena)")

        // onComplete stub
        appendLine("        val _completeStub = KneRuntime.createFlowCompleteStub({")
        appendLine("            channel.close()")
        appendLine("            _suspendInFlight.decrementAndGet()")
        appendLine("        }, _callbackArena)")

        // Invoke native bridge — read jobHandle synchronously, then awaitClose
        appendLine("        val _jobHandle: Long")
        appendLine("        Arena.ofConfined().use { _callArena ->")
        appendStringInvokeArgsAlloc("            ", fn.params)
        appendLine("            val _cancelOut = _callArena.allocate(JAVA_LONG)")

        val invokeArgs = buildList {
            add("handle")
            fn.params.forEach { p -> addAll(buildExpandedInvokeArgs(p)) }
            add("_nextStub"); add("_errorStub"); add("_completeStub"); add("_cancelOut")
        }.joinToString(", ")

        appendLine("            $handleName.invoke($invokeArgs)")
        appendLine("            _jobHandle = _cancelOut.get(JAVA_LONG, 0L) as Long")
        appendLine("        }")
        appendLine("        awaitClose {")
        appendLine("            KneRuntime.CANCEL_JOB_HANDLE.invoke(_jobHandle)")
        appendLine("            _suspendInFlight.decrementAndGet()")
        appendLine("        }")
        appendLine("    }")
        appendLine()
    }

    /** Decode a flow element from Long value and trySend to channel. */
    private fun StringBuilder.appendFlowElementDecode(indent: String, elemType: KneType) {
        when (elemType) {
            KneType.INT -> appendLine("${indent}trySend(_value.toInt())")
            KneType.LONG -> appendLine("${indent}trySend(_value)")
            KneType.SHORT -> appendLine("${indent}trySend(_value.toShort())")
            KneType.BYTE -> appendLine("${indent}trySend(_value.toByte())")
            KneType.FLOAT -> appendLine("${indent}trySend(Float.fromBits(_value.toInt()))")
            KneType.DOUBLE -> appendLine("${indent}trySend(Double.fromBits(_value))")
            KneType.BOOLEAN -> appendLine("${indent}trySend(_value != 0L)")
            KneType.STRING -> appendLine("${indent}trySend(KneRuntime.readStringFromRef(_value))")
            KneType.BYTE_ARRAY -> appendLine("${indent}trySend(KneRuntime.readByteArrayFromRef(_value))")
            is KneType.OBJECT -> appendLine("${indent}trySend(${elemType.simpleName}.fromNativeHandle(_value))")
            is KneType.ENUM -> appendLine("${indent}trySend(${elemType.simpleName}.entries[_value.toInt()])")
            is KneType.DATA_CLASS -> {
                appendLine("${indent}Arena.ofConfined().use { _dcArena ->")
                val flatFields = flattenDcFields(elemType, "out")
                flatFields.forEach { (name, type) ->
                    when (type) {
                        KneType.STRING, KneType.BYTE_ARRAY ->
                            appendLine("${indent}    val $name = _dcArena.allocate($STRING_BUF_SIZE.toLong())")
                        else ->
                            appendLine("${indent}    val $name = _dcArena.allocate(${type.ffmLayout})")
                    }
                }
                val readArgs = buildList {
                    add("_value")
                    flatFields.forEach { (name, type) ->
                        when (type) {
                            KneType.STRING, KneType.BYTE_ARRAY -> { add(name); add("$STRING_BUF_SIZE") }
                            else -> add(name)
                        }
                    }
                }.joinToString(", ")
                val dn = elemType.simpleName.uppercase()
                appendLine("${indent}    FLOWDC_${dn}_READ_HANDLE.invoke($readArgs)")
                if (dcHasCollectionFields(elemType)) appendDcCollectionFieldReads("${indent}    ", elemType, "out")
                appendLine("${indent}    trySend(${buildDcCtorFromOutParams(elemType, "out")})")
                appendLine("${indent}}")
            }
            is KneType.LIST -> {
                appendFlowCollectionDecode(indent, elemType, "List")
            }
            is KneType.SET -> {
                appendFlowCollectionDecode(indent, elemType, "Set")
            }
            is KneType.MAP -> {
                appendFlowMapDecode(indent, elemType)
            }
            else -> appendLine("${indent}trySend(_value.toInt())")
        }
    }

    /** Decode a Flow<List<T>> or Flow<Set<T>> element from StableRef handle. */
    private fun StringBuilder.appendFlowCollectionDecode(indent: String, elemType: KneType, collType: String) {
        val innerElem = when (elemType) { is KneType.LIST -> elemType.elementType; is KneType.SET -> elemType.elementType; else -> KneType.INT }
        if (innerElem is KneType.DATA_CLASS) {
            val dn = innerElem.simpleName.uppercase()
            appendLine("${indent}val _listHandle = _value")
            appendLine("${indent}try {")
            appendLine("${indent}    val _size = LIST_${dn}_SIZE_HANDLE.invoke(_listHandle) as Int")
            appendLine("${indent}    val _list = ArrayList<${innerElem.simpleName}>(_size)")
            appendLine("${indent}    Arena.ofConfined().use { dcArena ->")
            val flatFields = flattenDcFields(innerElem, "out")
            flatFields.forEach { (name, type) ->
                when (type) {
                    KneType.STRING, KneType.BYTE_ARRAY -> appendLine("${indent}        val $name = dcArena.allocate($STRING_BUF_SIZE.toLong())")
                    else -> appendLine("${indent}        val $name = dcArena.allocate(${type.ffmLayout})")
                }
            }
            appendLine("${indent}        for (_i in 0 until _size) {")
            val getArgs = buildList {
                add("_listHandle"); add("_i")
                flatFields.forEach { (name, type) -> when (type) { KneType.STRING, KneType.BYTE_ARRAY -> { add(name); add("$STRING_BUF_SIZE") }; else -> add(name) } }
            }.joinToString(", ")
            appendLine("${indent}            LIST_${dn}_GET_HANDLE.invoke($getArgs)")
            if (dcHasCollectionFields(innerElem)) appendDcCollectionFieldReads("${indent}            ", innerElem, "out")
            appendLine("${indent}            _list.add(${buildDcCtorFromOutParams(innerElem, "out")})")
            appendLine("${indent}        }")
            appendLine("${indent}    }")
            if (collType == "Set") appendLine("${indent}    trySend(_list.toSet())")
            else appendLine("${indent}    trySend(_list)")
            appendLine("${indent}} finally {")
            appendLine("${indent}    LIST_${dn}_DISPOSE_HANDLE.invoke(_listHandle)")
            appendLine("${indent}}")
        } else {
            val key = suspendCollElemKey(innerElem)
            appendLine("${indent}Arena.ofConfined().use { _collArena ->")
            if (innerElem == KneType.STRING) {
                appendLine("${indent}    val _outBuf = _collArena.allocate($STRING_BUF_SIZE.toLong())")
                appendLine("${indent}    val _count = SUSPEND_READCOLL_${key.uppercase()}_HANDLE.invoke(_value, _outBuf, $STRING_BUF_SIZE) as Int")
                appendLine("${indent}    val _list = mutableListOf<String>()")
                appendLine("${indent}    var _off = 0L")
                appendLine("${indent}    repeat(_count) { _list.add(_outBuf.getString(_off)); _off += _list.last().toByteArray(Charsets.UTF_8).size + 1 }")
            } else {
                val layout = KneType.collectionElementLayout(innerElem)
                appendLine("${indent}    val _outBuf = _collArena.allocate($layout, $MAX_COLLECTION_SIZE.toLong())")
                appendLine("${indent}    val _count = SUSPEND_READCOLL_${key.uppercase()}_HANDLE.invoke(_value, _outBuf, $MAX_COLLECTION_SIZE) as Int")
                when (innerElem) {
                    KneType.BOOLEAN -> appendLine("${indent}    val _list = List(_count) { _outBuf.getAtIndex(JAVA_INT, it.toLong()) != 0 }")
                    is KneType.ENUM -> appendLine("${indent}    val _list = List(_count) { ${innerElem.simpleName}.entries[_outBuf.getAtIndex(JAVA_INT, it.toLong())] }")
                    is KneType.OBJECT -> appendLine("${indent}    val _list = List(_count) { ${innerElem.simpleName}.fromNativeHandle(_outBuf.getAtIndex(JAVA_LONG, it.toLong()) as Long) }")
                    KneType.BYTE_ARRAY -> appendLine("${indent}    val _list = List(_count) { KneRuntime.readByteArrayFromRef(_outBuf.getAtIndex(JAVA_LONG, it.toLong()) as Long) }")
                    is KneType.LIST, is KneType.SET, is KneType.MAP -> {
                        appendLine("${indent}    val _list = List(_count) { _idx ->")
                        appendLine("${indent}        val _innerHandle = _outBuf.getAtIndex(JAVA_LONG, _idx.toLong()) as Long")
                        appendNestedCollectionRead("${indent}        ", innerElem)
                        appendLine("${indent}    }")
                    }
                    else -> appendLine("${indent}    val _list = List(_count) { _outBuf.getAtIndex($layout, it.toLong()) as ${innerElem.jvmTypeName} }")
                }
            }
            if (collType == "Set") appendLine("${indent}    trySend(_list.toSet())")
            else appendLine("${indent}    trySend(_list)")
            appendLine("${indent}}")
        }
    }

    /** Decode a Flow<Map<K,V>> element from StableRef handle. */
    private fun StringBuilder.appendFlowMapDecode(indent: String, elemType: KneType.MAP) {
        val kk = suspendCollElemKey(elemType.keyType)
        val vk = suspendCollElemKey(elemType.valueType)
        val isKeyString = elemType.keyType == KneType.STRING
        val isValString = elemType.valueType == KneType.STRING
        appendLine("${indent}Arena.ofConfined().use { _mapArena ->")
        if (isKeyString) appendLine("${indent}    val _keysBuf = _mapArena.allocate($STRING_BUF_SIZE.toLong())")
        else appendLine("${indent}    val _keysBuf = _mapArena.allocate(${KneType.collectionElementLayout(elemType.keyType)}, $MAX_COLLECTION_SIZE.toLong())")
        if (isValString) appendLine("${indent}    val _valsBuf = _mapArena.allocate($STRING_BUF_SIZE.toLong())")
        else appendLine("${indent}    val _valsBuf = _mapArena.allocate(${KneType.collectionElementLayout(elemType.valueType)}, $MAX_COLLECTION_SIZE.toLong())")
        val keySizeArg = if (isKeyString) "$STRING_BUF_SIZE" else "$MAX_COLLECTION_SIZE"
        val valSizeArg = if (isValString) "$STRING_BUF_SIZE" else "$MAX_COLLECTION_SIZE"
        appendLine("${indent}    val _count = SUSPEND_READMAP_${kk.uppercase()}_${vk.uppercase()}_HANDLE.invoke(_value, _keysBuf, $keySizeArg, _valsBuf, $valSizeArg) as Int")
        if (isKeyString) {
            appendLine("${indent}    val _keys = mutableListOf<String>(); var _kOff = 0L")
            appendLine("${indent}    repeat(_count) { _keys.add(_keysBuf.getString(_kOff)); _kOff += _keys.last().toByteArray(Charsets.UTF_8).size + 1 }")
        } else {
            appendLine("${indent}    val _keys = List(_count) { _keysBuf.getAtIndex(${KneType.collectionElementLayout(elemType.keyType)}, it.toLong()) as ${elemType.keyType.jvmTypeName} }")
        }
        if (isValString) {
            appendLine("${indent}    val _vals = mutableListOf<String>(); var _vOff = 0L")
            appendLine("${indent}    repeat(_count) { _vals.add(_valsBuf.getString(_vOff)); _vOff += _vals.last().toByteArray(Charsets.UTF_8).size + 1 }")
        } else {
            appendLine("${indent}    val _vals = List(_count) { _valsBuf.getAtIndex(${KneType.collectionElementLayout(elemType.valueType)}, it.toLong()) as ${elemType.valueType.jvmTypeName} }")
        }
        appendLine("${indent}    trySend(_keys.zip(_vals).toMap())")
        appendLine("${indent}}")
    }

    /** Generate a suspend method proxy using suspendCancellableCoroutine. */
    private fun StringBuilder.appendSuspendMethodProxy(fn: KneFunction, cls: KneClass, prefix: String) {
        val handleName = "${fn.name.uppercase()}_HANDLE"
        val params = fn.params.joinToString(", ") { "${it.name}: ${it.type.jvmTypeName}" }
        val retType = fn.returnType.jvmTypeName
        val overrideMod = if (fn.isOverride) "override " else ""
        val openMod = if (!fn.isOverride && (cls.isOpen || cls.isAbstract)) "open " else ""

        appendLine("    ${overrideMod}${openMod}suspend fun ${fn.name}($params): $retType = suspendCancellableCoroutine { _cont ->")
        appendLine("        _suspendInFlight.incrementAndGet()")
        // Use the object's shared callback arena — stubs live as long as the proxy object
        appendLine("        val _contStub = KneRuntime.createSuspendContStub({ _hasValue, _value ->")
        appendLine("            try {")
        appendSuspendResultDecode("                ", fn.returnType)
        appendLine("            } finally { _suspendInFlight.decrementAndGet() }")
        appendLine("        }, _callbackArena)")

        appendLine("        val _excStub = KneRuntime.createSuspendExcStub({ _msgHandle ->")
        appendLine("            try {")
        appendLine("                if (_msgHandle == 0L) _cont.cancel()")
        appendLine("                else _cont.resumeWithException(KotlinNativeException(KneRuntime.readStringFromRef(_msgHandle)))")
        appendLine("            } finally { _suspendInFlight.decrementAndGet() }")
        appendLine("        }, _callbackArena)")

        // Invoke native bridge
        appendLine("        Arena.ofConfined().use { _callArena ->")

        // Allocate string/DC/collection params
        appendStringInvokeArgsAlloc("            ", fn.params)
        appendCollectionParamAlloc("            ", fn.params)

        appendLine("            val _cancelOut = _callArena.allocate(JAVA_LONG)")

        // Build invoke args
        val invokeArgs = buildList {
            add("handle")
            fn.params.forEach { p -> addAll(buildExpandedInvokeArgs(p)) }
            add("_contStub"); add("_excStub"); add("_cancelOut")
        }.joinToString(", ")

        appendLine("            $handleName.invoke($invokeArgs)")
        appendLine("            val _jobHandle = _cancelOut.get(JAVA_LONG, 0L) as Long")
        appendLine("            _cont.invokeOnCancellation { KneRuntime.CANCEL_JOB_HANDLE.invoke(_jobHandle) }")
        appendLine("        }")
        appendLine("    }")
        appendLine()
    }

    /** Generate the result decoding inside a suspend continuation callback. */
    private fun StringBuilder.appendSuspendResultDecode(indent: String, type: KneType) {
        when (type) {
            KneType.UNIT -> appendLine("${indent}_cont.resume(Unit)")
            KneType.INT -> appendLine("${indent}_cont.resume(_value.toInt())")
            KneType.LONG -> appendLine("${indent}_cont.resume(_value)")
            KneType.SHORT -> appendLine("${indent}_cont.resume(_value.toShort())")
            KneType.BYTE -> appendLine("${indent}_cont.resume(_value.toByte())")
            KneType.FLOAT -> appendLine("${indent}_cont.resume(Float.fromBits(_value.toInt()))")
            KneType.DOUBLE -> appendLine("${indent}_cont.resume(Double.fromBits(_value))")
            KneType.BOOLEAN -> appendLine("${indent}_cont.resume(_value != 0L)")
            KneType.STRING -> appendLine("${indent}_cont.resume(KneRuntime.readStringFromRef(_value))")
            KneType.BYTE_ARRAY -> appendLine("${indent}_cont.resume(KneRuntime.readByteArrayFromRef(_value))")
            is KneType.OBJECT -> appendLine("${indent}_cont.resume(${type.simpleName}.fromNativeHandle(_value))")
            is KneType.ENUM -> appendLine("${indent}_cont.resume(${type.simpleName}.entries[_value.toInt()])")
            is KneType.DATA_CLASS -> {
                appendLine("${indent}Arena.ofConfined().use { _dcArena ->")
                val flatFields = flattenDcFields(type, "out")
                flatFields.forEach { (name, ftype) ->
                    when (ftype) {
                        KneType.STRING, KneType.BYTE_ARRAY ->
                            appendLine("${indent}    val $name = _dcArena.allocate($STRING_BUF_SIZE.toLong())")
                        else ->
                            appendLine("${indent}    val $name = _dcArena.allocate(${ftype.ffmLayout})")
                    }
                }
                val readArgs = buildList {
                    add("_value")
                    flatFields.forEach { (name, ftype) ->
                        when (ftype) {
                            KneType.STRING, KneType.BYTE_ARRAY -> { add(name); add("$STRING_BUF_SIZE") }
                            else -> add(name)
                        }
                    }
                }.joinToString(", ")
                val dn = type.simpleName.uppercase()
                appendLine("${indent}    SUSPENDDC_${dn}_READ_HANDLE.invoke($readArgs)")
                if (dcHasCollectionFields(type)) appendDcCollectionFieldReads("${indent}    ", type, "out")
                appendLine("${indent}    _cont.resume(${buildDcCtorFromOutParams(type, "out")})")
                appendLine("${indent}}")
            }
            is KneType.LIST -> {
                appendSuspendListDecode(indent, type.elementType, "List")
            }
            is KneType.SET -> {
                appendSuspendListDecode(indent, type.elementType, "Set")
            }
            is KneType.MAP -> {
                appendSuspendMapDecode(indent, type)
            }
            is KneType.NULLABLE -> {
                appendLine("${indent}if (_hasValue == 0) _cont.resume(null)")
                appendLine("${indent}else {")
                appendSuspendResultDecode("$indent    ", type.inner)
                appendLine("${indent}}")
            }
            else -> appendLine("${indent}_cont.resume(_value.toInt())") // fallback
        }
    }

    /** Map a collection element type to its suspend reader bridge key (must match NativeBridgeGenerator.suspendCollElemKey). */
    private fun suspendCollElemKey(type: KneType): String = when (type) {
        KneType.INT -> "Int"
        KneType.LONG -> "Long"
        KneType.DOUBLE -> "Double"
        KneType.FLOAT -> "Float"
        KneType.SHORT -> "Short"
        KneType.BYTE -> "Byte"
        KneType.BOOLEAN -> "Boolean"
        KneType.STRING -> "String"
        KneType.BYTE_ARRAY -> "ByteArray"
        is KneType.ENUM -> "Enum"
        is KneType.OBJECT -> "ObjHandle"
        is KneType.LIST, is KneType.SET, is KneType.MAP -> "NestedColl"
        else -> "Int"
    }

    /** Decode a List/Set from a StableRef handle inside a suspend continuation callback. */
    private fun StringBuilder.appendSuspendListDecode(indent: String, elemType: KneType, collType: String) {
        if (elemType is KneType.DATA_CLASS) {
            // Use existing list_DC_size/get/dispose bridges
            val dn = elemType.simpleName.uppercase()
            appendLine("${indent}val _listHandle = _value")
            appendLine("${indent}try {")
            appendLine("${indent}    val _size = LIST_${dn}_SIZE_HANDLE.invoke(_listHandle) as Int")
            appendLine("${indent}    val _list = ArrayList<${elemType.simpleName}>(_size)")
            appendLine("${indent}    Arena.ofConfined().use { dcArena ->")
            val flatFields = flattenDcFields(elemType, "out")
            flatFields.forEach { (name, type) ->
                when (type) {
                    KneType.STRING, KneType.BYTE_ARRAY ->
                        appendLine("${indent}        val $name = dcArena.allocate($STRING_BUF_SIZE.toLong())")
                    else ->
                        appendLine("${indent}        val $name = dcArena.allocate(${type.ffmLayout})")
                }
            }
            appendLine("${indent}        for (_i in 0 until _size) {")
            val getArgs = buildList {
                add("_listHandle"); add("_i")
                flatFields.forEach { (name, type) ->
                    when (type) { KneType.STRING, KneType.BYTE_ARRAY -> { add(name); add("$STRING_BUF_SIZE") }; else -> add(name) }
                }
            }.joinToString(", ")
            appendLine("${indent}            LIST_${dn}_GET_HANDLE.invoke($getArgs)")
            appendLine("${indent}            _list.add(${buildDcCtorFromOutParams(elemType, "out")})")
            appendLine("${indent}        }")
            appendLine("${indent}    }")
            if (collType == "Set") appendLine("${indent}    _cont.resume(_list.toSet())")
            else appendLine("${indent}    _cont.resume(_list)")
            appendLine("${indent}} finally {")
            appendLine("${indent}    LIST_${dn}_DISPOSE_HANDLE.invoke(_listHandle)")
            appendLine("${indent}}")
        } else {
            val key = suspendCollElemKey(elemType)
            appendLine("${indent}Arena.ofConfined().use { _collArena ->")
            if (elemType == KneType.STRING) {
                appendLine("${indent}    val _outBuf = _collArena.allocate($STRING_BUF_SIZE.toLong())")
                appendLine("${indent}    val _count = SUSPEND_READCOLL_${key.uppercase()}_HANDLE.invoke(_value, _outBuf, $STRING_BUF_SIZE) as Int")
                appendLine("${indent}    val _list = mutableListOf<String>()")
                appendLine("${indent}    var _off = 0L")
                appendLine("${indent}    repeat(_count) { _list.add(_outBuf.getString(_off)); _off += _list.last().toByteArray(Charsets.UTF_8).size + 1 }")
            } else {
                val layout = KneType.collectionElementLayout(elemType)
                appendLine("${indent}    val _outBuf = _collArena.allocate($layout, $MAX_COLLECTION_SIZE.toLong())")
                appendLine("${indent}    val _count = SUSPEND_READCOLL_${key.uppercase()}_HANDLE.invoke(_value, _outBuf, $MAX_COLLECTION_SIZE) as Int")
                // Decode elements
                when (elemType) {
                    KneType.BOOLEAN ->
                        appendLine("${indent}    val _list = List(_count) { _outBuf.getAtIndex(JAVA_INT, it.toLong()) != 0 }")
                    is KneType.ENUM ->
                        appendLine("${indent}    val _list = List(_count) { ${elemType.simpleName}.entries[_outBuf.getAtIndex(JAVA_INT, it.toLong())] }")
                    is KneType.OBJECT ->
                        appendLine("${indent}    val _list = List(_count) { ${elemType.simpleName}.fromNativeHandle(_outBuf.getAtIndex(JAVA_LONG, it.toLong()) as Long) }")
                    KneType.BYTE_ARRAY ->
                        appendLine("${indent}    val _list = List(_count) { KneRuntime.readByteArrayFromRef(_outBuf.getAtIndex(JAVA_LONG, it.toLong()) as Long) }")
                    is KneType.LIST, is KneType.SET, is KneType.MAP -> {
                        appendLine("${indent}    val _list = List(_count) { _idx ->")
                        appendLine("${indent}        val _innerHandle = _outBuf.getAtIndex(JAVA_LONG, _idx.toLong()) as Long")
                        appendNestedCollectionRead("${indent}        ", elemType)
                        appendLine("${indent}    }")
                    }
                    else ->
                        appendLine("${indent}    val _list = List(_count) { _outBuf.getAtIndex($layout, it.toLong()) as ${elemType.jvmTypeName} }")
                }
            }
            if (collType == "Set") appendLine("${indent}    _cont.resume(_list.toSet())")
            else appendLine("${indent}    _cont.resume(_list)")
            appendLine("${indent}}")
        }
    }

    /** Decode a Map from a StableRef handle inside a suspend continuation callback. */
    private fun StringBuilder.appendSuspendMapDecode(indent: String, type: KneType.MAP) {
        val kk = suspendCollElemKey(type.keyType)
        val vk = suspendCollElemKey(type.valueType)
        val isKeyString = type.keyType == KneType.STRING
        val isValString = type.valueType == KneType.STRING

        appendLine("${indent}Arena.ofConfined().use { _mapArena ->")
        // Allocate key buffer
        if (isKeyString) {
            appendLine("${indent}    val _keysBuf = _mapArena.allocate($STRING_BUF_SIZE.toLong())")
        } else {
            val kLayout = KneType.collectionElementLayout(type.keyType)
            appendLine("${indent}    val _keysBuf = _mapArena.allocate($kLayout, $MAX_COLLECTION_SIZE.toLong())")
        }
        // Allocate value buffer
        if (isValString) {
            appendLine("${indent}    val _valsBuf = _mapArena.allocate($STRING_BUF_SIZE.toLong())")
        } else {
            val vLayout = KneType.collectionElementLayout(type.valueType)
            appendLine("${indent}    val _valsBuf = _mapArena.allocate($vLayout, $MAX_COLLECTION_SIZE.toLong())")
        }

        // Invoke reader bridge
        val keySizeArg = if (isKeyString) "$STRING_BUF_SIZE" else "$MAX_COLLECTION_SIZE"
        val valSizeArg = if (isValString) "$STRING_BUF_SIZE" else "$MAX_COLLECTION_SIZE"
        appendLine("${indent}    val _count = SUSPEND_READMAP_${kk.uppercase()}_${vk.uppercase()}_HANDLE.invoke(_value, _keysBuf, $keySizeArg, _valsBuf, $valSizeArg) as Int")

        // Decode keys
        if (isKeyString) {
            appendLine("${indent}    val _keys = mutableListOf<String>()")
            appendLine("${indent}    var _kOff = 0L")
            appendLine("${indent}    repeat(_count) { _keys.add(_keysBuf.getString(_kOff)); _kOff += _keys.last().toByteArray(Charsets.UTF_8).size + 1 }")
        } else {
            val kLayout = KneType.collectionElementLayout(type.keyType)
            appendLine("${indent}    val _keys = List(_count) { _keysBuf.getAtIndex($kLayout, it.toLong()) as ${type.keyType.jvmTypeName} }")
        }

        // Decode values
        if (isValString) {
            appendLine("${indent}    val _vals = mutableListOf<String>()")
            appendLine("${indent}    var _vOff = 0L")
            appendLine("${indent}    repeat(_count) { _vals.add(_valsBuf.getString(_vOff)); _vOff += _vals.last().toByteArray(Charsets.UTF_8).size + 1 }")
        } else {
            val vLayout = KneType.collectionElementLayout(type.valueType)
            appendLine("${indent}    val _vals = List(_count) { _valsBuf.getAtIndex($vLayout, it.toLong()) as ${type.valueType.jvmTypeName} }")
        }

        appendLine("${indent}    _cont.resume(_keys.zip(_vals).toMap())")
        appendLine("${indent}}")
    }

    private fun StringBuilder.appendMethodProxy(fn: KneFunction, cls: KneClass, prefix: String) {
        val handleName = "${fn.name.uppercase()}_HANDLE"
        val params = fn.params.joinToString(", ") { "${it.name}: ${it.type.jvmTypeName}" }
        val overrideMod = if (fn.isOverride) "override " else ""
        val openMod = if (!fn.isOverride && (cls.isOpen || cls.isAbstract)) "open " else ""

        appendLine("    ${overrideMod}${openMod}fun ${fn.name}($params): ${fn.returnType.jvmTypeName} {")

        // Allocate callback stubs in persistent arena (survives async calls)
        appendCallbackStubAlloc("        ", fn.params, "_callbackArena")

        val returnDc = extractDataClass(fn.returnType)
        val returnsNullableDc = fn.returnType is KneType.NULLABLE && fn.returnType.inner is KneType.DATA_CLASS
        val hasAnyDcParams = fn.params.any { extractDataClass(it.type) != null }
        val returnsCollection = fn.returnType.isCollection()
        val needsConfinedArena = needsConfinedArena(fn.params, fn.returnType) || returnDc != null ||
            hasAnyDcParams && fn.params.any { dc -> val d = extractDataClass(dc.type); d != null && d.fields.any { f -> f.type == KneType.STRING } }

        if (needsConfinedArena || returnDc != null || returnsCollection) {
            appendLine("        Arena.ofConfined().use { arena ->")
            appendStringInvokeArgsAlloc("            ", fn.params)
            appendCollectionParamAlloc("            ", fn.params)
            if (returnDc != null) {
                appendDataClassReturnProxy("            ", fn, handleName, returnsNullableDc)
            } else if (returnsCollection) {
                appendCollectionReturnProxy("            ", fn, handleName)
            } else {
                val invokeArgs = buildClassInvokeArgsExpanded(fn)
                appendCallAndReturn("            ", fn.returnType, handleName, invokeArgs)
            }
            appendLine("        }")
        } else {
            val invokeArgs = buildClassInvokeArgsExpandedDirect(fn)
            appendCallAndReturn("        ", fn.returnType, handleName, invokeArgs)
        }

        appendLine("    }")
        appendLine()
    }

    /** Flatten data class into out-param (name, type) pairs (recursive for nested). */
    private fun flattenDcFields(dc: KneType.DATA_CLASS, prefix: String): List<Pair<String, KneType>> =
        dc.fields.flatMap { f ->
            val name = "${prefix}_${f.name}"
            if (f.type is KneType.DATA_CLASS) flattenDcFields(f.type, name)
            else listOf(Pair(name, f.type))
        }

    /** Generate the return-via-out-params pattern for DATA_CLASS return types. */
    private fun StringBuilder.appendDataClassReturnProxy(indent: String, fn: KneFunction, handleName: String, nullable: Boolean = false) {
        val dc = extractDataClass(fn.returnType)!!
        val flatFields = flattenDcFields(dc, "out")

        // Allocate out-params for each flat field
        flatFields.forEach { (name, type) ->
            when (type) {
                KneType.STRING -> appendLine("${indent}val $name = arena.allocate($STRING_BUF_SIZE.toLong())")
                KneType.BYTE_ARRAY -> appendLine("${indent}val $name = arena.allocate(JAVA_LONG)") // StableRef handle
                else -> appendLine("${indent}val $name = arena.allocate(${type.ffmLayout})")
            }
        }

        // Build invoke args: handle + expanded params + out-params
        val paramArgs = buildList {
            add("handle")
            fn.params.forEach { p -> addAll(buildExpandedInvokeArgs(p)) }
            flatFields.forEach { (name, type) ->
                when (type) {
                    KneType.STRING -> { add(name); add("$STRING_BUF_SIZE") }
                    KneType.BYTE_ARRAY -> add(name) // StableRef handle, no size needed
                    else -> add(name)
                }
            }
        }.joinToString(", ")

        if (nullable) {
            appendLine("${indent}val _isPresent = $handleName.invoke($paramArgs) as Int")
            appendLine("${indent}KneRuntime.checkError()")
            appendLine("${indent}if (_isPresent == 0) return null")
        } else {
            appendLine("${indent}$handleName.invoke($paramArgs)")
            appendLine("${indent}KneRuntime.checkError()")
        }

        // Read collection fields from StableRef handles, then reconstruct the data class
        if (dcHasCollectionFields(dc)) {
            appendDcCollectionFieldReads(indent, dc, "out")
        }
        appendLine("${indent}return ${buildDcCtorFromOutParams(dc, "out")}")
    }

    /** Generate the return-via-out-params pattern for DATA_CLASS property types. */
    private fun StringBuilder.appendDataClassReturnProxyForProperty(indent: String, prop: KneProperty, handleName: String, nullable: Boolean = false) {
        val dc = extractDataClass(prop.type)!!
        val flatFields = flattenDcFields(dc, "out")

        // Allocate out-params for each flat field
        flatFields.forEach { (name, type) ->
            when (type) {
                KneType.STRING -> appendLine("${indent}val $name = arena.allocate($STRING_BUF_SIZE.toLong())")
                KneType.BYTE_ARRAY -> appendLine("${indent}val $name = arena.allocate(JAVA_LONG)") // StableRef handle
                else -> appendLine("${indent}val $name = arena.allocate(${type.ffmLayout})")
            }
        }

        // Build invoke args: handle + out-params (no method params for properties)
        val paramArgs = buildList {
            add("handle")
            flatFields.forEach { (name, type) ->
                when (type) {
                    KneType.STRING -> { add(name); add("$STRING_BUF_SIZE") }
                    KneType.BYTE_ARRAY -> add(name) // StableRef handle, no size needed
                    else -> add(name)
                }
            }
        }.joinToString(", ")

        if (nullable) {
            appendLine("${indent}val _isPresent = $handleName.invoke($paramArgs) as Int")
            appendLine("${indent}KneRuntime.checkError()")
            appendLine("${indent}if (_isPresent == 0) return null")
        } else {
            appendLine("${indent}$handleName.invoke($paramArgs)")
            appendLine("${indent}KneRuntime.checkError()")
        }

        // Read collection fields from StableRef handles, then reconstruct the data class
        if (dcHasCollectionFields(dc)) {
            appendDcCollectionFieldReads(indent, dc, "out")
        }
        appendLine("${indent}return ${buildDcCtorFromOutParams(dc, "out")}")
    }

    /** Build a constructor call that reads from out-params (recursive for nested data classes). */
    /** Check if a DC (or nested DCs) has any collection fields. */
    private fun dcHasCollectionFields(dc: KneType.DATA_CLASS): Boolean =
        dc.fields.any { f ->
            f.type == KneType.BYTE_ARRAY ||
            f.type is KneType.LIST || f.type is KneType.SET || f.type is KneType.MAP ||
                (f.type is KneType.DATA_CLASS && dcHasCollectionFields(f.type))
        }

    /** Emit local variables for reading collection/ByteArray fields from StableRef handles before constructing the DC. */
    private fun StringBuilder.appendDcCollectionFieldReads(indent: String, dc: KneType.DATA_CLASS, prefix: String) {
        dc.fields.forEach { f ->
            val name = "${prefix}_${f.name}"
            when (f.type) {
                KneType.BYTE_ARRAY -> {
                    appendLine("${indent}val ${name}_baVal = KneRuntime.readByteArrayFromRef($name.get(JAVA_LONG, 0) as Long)")
                }
                is KneType.LIST -> {
                    val elemType = f.type.elementType
                    val handle = "${name}_collHandle"
                    appendLine("${indent}val $handle = $name.get(JAVA_LONG, 0) as Long")
                    if (elemType is KneType.DATA_CLASS) {
                        val dn = elemType.simpleName.uppercase()
                        appendLine("${indent}val ${name}_collVal = run {")
                        appendLine("${indent}    val _size = LIST_${dn}_SIZE_HANDLE.invoke($handle) as Int")
                        appendLine("${indent}    val _list = ArrayList<${elemType.simpleName}>(_size)")
                        appendLine("${indent}    Arena.ofConfined().use { _dcArena ->")
                        val flatFields = flattenDcFields(elemType, "out")
                        flatFields.forEach { (fn, ft) ->
                            when (ft) {
                                KneType.STRING, KneType.BYTE_ARRAY -> appendLine("${indent}        val $fn = _dcArena.allocate($STRING_BUF_SIZE.toLong())")
                                else -> appendLine("${indent}        val $fn = _dcArena.allocate(${ft.ffmLayout})")
                            }
                        }
                        appendLine("${indent}        for (_i in 0 until _size) {")
                        val getArgs = buildList {
                            add(handle); add("_i")
                            flatFields.forEach { (fn, ft) -> when (ft) { KneType.STRING, KneType.BYTE_ARRAY -> { add(fn); add("$STRING_BUF_SIZE") }; else -> add(fn) } }
                        }.joinToString(", ")
                        appendLine("${indent}            LIST_${dn}_GET_HANDLE.invoke($getArgs)")
                        if (dcHasCollectionFields(elemType)) {
                            appendDcCollectionFieldReads("${indent}            ", elemType, "out")
                        }
                        appendLine("${indent}            _list.add(${buildDcCtorFromOutParams(elemType, "out")})")
                        appendLine("${indent}        }")
                        appendLine("${indent}    }")
                        appendLine("${indent}    LIST_${dn}_DISPOSE_HANDLE.invoke($handle)")
                        appendLine("${indent}    _list as List<${elemType.simpleName}>")
                        appendLine("${indent}}")
                    } else {
                        appendCollFieldPrimitiveListRead(indent, name, elemType, handle)
                    }
                }
                is KneType.SET -> {
                    val elemType = f.type.elementType
                    val handle = "${name}_collHandle"
                    appendLine("${indent}val $handle = $name.get(JAVA_LONG, 0) as Long")
                    appendCollFieldPrimitiveListRead(indent, name, elemType, handle, isSet = true)
                }
                is KneType.MAP -> {
                    val kk = suspendCollElemKey(f.type.keyType)
                    val vk = suspendCollElemKey(f.type.valueType)
                    val handle = "${name}_collHandle"
                    appendLine("${indent}val $handle = $name.get(JAVA_LONG, 0) as Long")
                    appendLine("${indent}val ${name}_collVal = run {")
                    appendLine("${indent}    Arena.ofConfined().use { _mapArena ->")
                    val isKeyString = f.type.keyType == KneType.STRING
                    val isValString = f.type.valueType == KneType.STRING
                    if (isKeyString) appendLine("${indent}        val _keysBuf = _mapArena.allocate($STRING_BUF_SIZE.toLong())")
                    else appendLine("${indent}        val _keysBuf = _mapArena.allocate(${KneType.collectionElementLayout(f.type.keyType)}, $MAX_COLLECTION_SIZE.toLong())")
                    if (isValString) appendLine("${indent}        val _valsBuf = _mapArena.allocate($STRING_BUF_SIZE.toLong())")
                    else appendLine("${indent}        val _valsBuf = _mapArena.allocate(${KneType.collectionElementLayout(f.type.valueType)}, $MAX_COLLECTION_SIZE.toLong())")
                    val keySizeArg = if (isKeyString) "$STRING_BUF_SIZE" else "$MAX_COLLECTION_SIZE"
                    val valSizeArg = if (isValString) "$STRING_BUF_SIZE" else "$MAX_COLLECTION_SIZE"
                    appendLine("${indent}        val _count = SUSPEND_READMAP_${kk.uppercase()}_${vk.uppercase()}_HANDLE.invoke($handle, _keysBuf, $keySizeArg, _valsBuf, $valSizeArg) as Int")
                    if (isKeyString) {
                        appendLine("${indent}        val _keys = mutableListOf<String>()")
                        appendLine("${indent}        var _kOff = 0L")
                        appendLine("${indent}        repeat(_count) { _keys.add(_keysBuf.getString(_kOff)); _kOff += _keys.last().toByteArray(Charsets.UTF_8).size + 1 }")
                    } else {
                        appendLine("${indent}        val _keys = List(_count) { _keysBuf.getAtIndex(${KneType.collectionElementLayout(f.type.keyType)}, it.toLong()) as ${f.type.keyType.jvmTypeName} }")
                    }
                    if (isValString) {
                        appendLine("${indent}        val _vals = mutableListOf<String>()")
                        appendLine("${indent}        var _vOff = 0L")
                        appendLine("${indent}        repeat(_count) { _vals.add(_valsBuf.getString(_vOff)); _vOff += _vals.last().toByteArray(Charsets.UTF_8).size + 1 }")
                    } else {
                        appendLine("${indent}        val _vals = List(_count) { _valsBuf.getAtIndex(${KneType.collectionElementLayout(f.type.valueType)}, it.toLong()) as ${f.type.valueType.jvmTypeName} }")
                    }
                    appendLine("${indent}        _keys.zip(_vals).toMap()")
                    appendLine("${indent}    }")
                    appendLine("${indent}}")
                }
                is KneType.DATA_CLASS -> {
                    if (dcHasCollectionFields(f.type)) {
                        appendDcCollectionFieldReads(indent, f.type, name)
                    }
                }
                else -> {}
            }
        }
    }

    /** Read a primitive/string/enum/object list from StableRef handle into a local variable. */
    private fun StringBuilder.appendCollFieldPrimitiveListRead(indent: String, name: String, elemType: KneType, handle: String, isSet: Boolean = false) {
        val key = suspendCollElemKey(elemType)
        appendLine("${indent}val ${name}_collVal = run {")
        appendLine("${indent}    Arena.ofConfined().use { _collArena ->")
        if (elemType == KneType.STRING) {
            appendLine("${indent}        val _outBuf = _collArena.allocate($STRING_BUF_SIZE.toLong())")
            appendLine("${indent}        val _count = SUSPEND_READCOLL_${key.uppercase()}_HANDLE.invoke($handle, _outBuf, $STRING_BUF_SIZE) as Int")
            appendLine("${indent}        val _list = mutableListOf<String>()")
            appendLine("${indent}        var _off = 0L")
            appendLine("${indent}        repeat(_count) { _list.add(_outBuf.getString(_off)); _off += _list.last().toByteArray(Charsets.UTF_8).size + 1 }")
            if (isSet) appendLine("${indent}        _list.toSet()")
            else appendLine("${indent}        _list as List<String>")
        } else {
            val layout = KneType.collectionElementLayout(elemType)
            appendLine("${indent}        val _outBuf = _collArena.allocate($layout, $MAX_COLLECTION_SIZE.toLong())")
            appendLine("${indent}        val _count = SUSPEND_READCOLL_${key.uppercase()}_HANDLE.invoke($handle, _outBuf, $MAX_COLLECTION_SIZE) as Int")
            when (elemType) {
                KneType.BOOLEAN -> appendLine("${indent}        val _list = List(_count) { _outBuf.getAtIndex(JAVA_INT, it.toLong()) != 0 }")
                is KneType.ENUM -> appendLine("${indent}        val _list = List(_count) { ${elemType.simpleName}.entries[_outBuf.getAtIndex(JAVA_INT, it.toLong())] }")
                is KneType.OBJECT -> appendLine("${indent}        val _list = List(_count) { ${elemType.simpleName}.fromNativeHandle(_outBuf.getAtIndex(JAVA_LONG, it.toLong()) as Long) }")
                else -> appendLine("${indent}        val _list = List(_count) { _outBuf.getAtIndex($layout, it.toLong()) as ${elemType.jvmTypeName} }")
            }
            if (isSet) appendLine("${indent}        _list.toSet()")
            else appendLine("${indent}        _list")
        }
        appendLine("${indent}    }")
        appendLine("${indent}}")
    }

    private fun buildDcCtorFromOutParams(dc: KneType.DATA_CLASS, prefix: String): String {
        val args = dc.fields.joinToString(", ") { f ->
            val name = "${prefix}_${f.name}"
            when (f.type) {
                KneType.STRING -> "${f.name} = $name.getString(0)"
                KneType.BYTE_ARRAY -> "${f.name} = ${name}_baVal"
                KneType.BOOLEAN -> "${f.name} = $name.get(JAVA_INT, 0) != 0"
                is KneType.ENUM -> "${f.name} = ${f.type.simpleName}.entries[$name.get(JAVA_INT, 0)]"
                is KneType.OBJECT -> "${f.name} = ${f.type.simpleName}.fromNativeHandle($name.get(JAVA_LONG, 0) as Long)"
                is KneType.DATA_CLASS -> "${f.name} = ${buildDcCtorFromOutParams(f.type, name)}"
                is KneType.LIST, is KneType.SET, is KneType.MAP -> "${f.name} = ${name}_collVal"
                else -> "${f.name} = $name.get(${f.type.ffmLayout}, 0) as ${f.type.jvmTypeName}"
            }
        }
        return "${dc.simpleName}($args)"
    }

    /** Build invoke args with DATA_CLASS params expanded into individual fields (without output buffer args). */
    private fun buildClassInvokeArgsExpanded(fn: KneFunction): String {
        val args = buildList {
            add("handle")
            fn.params.forEach { p -> addAll(buildExpandedInvokeArgs(p)) }
        }
        return args.joinToString(", ")
    }

    private fun buildClassInvokeArgsExpandedDirect(fn: KneFunction): String {
        val args = buildList {
            add("handle")
            fn.params.forEach { p -> addAll(buildExpandedInvokeArgs(p)) }
        }
        return args.joinToString(", ")
    }

    /** Expand a single param into invoke args. DATA_CLASS becomes N args (recursive), ByteArray/collections add size. */
    private fun buildExpandedInvokeArgs(p: KneParam): List<String> {
        if (p.type == KneType.BYTE_ARRAY) return listOf("${p.name}Seg", "${p.name}.size")
        val isNullableColl = p.type is KneType.NULLABLE && (p.type as KneType.NULLABLE).inner.let { it is KneType.LIST || it is KneType.SET || it is KneType.MAP }
        if (p.type is KneType.LIST) {
            return if ((p.type as KneType.LIST).elementType is KneType.DATA_CLASS) listOf("${p.name}Handle")
            else listOf("${p.name}Seg", "${p.name}.size")
        }
        if (p.type is KneType.SET) {
            return if ((p.type as KneType.SET).elementType is KneType.DATA_CLASS) listOf("${p.name}Handle")
            else listOf("${p.name}Seg", "${p.name}.size")
        }
        if (p.type is KneType.MAP) return listOf("${p.name}_keysSeg", "${p.name}_valuesSeg", "${p.name}.size")
        if (isNullableColl) {
            val inner = (p.type as KneType.NULLABLE).inner
            return when (inner) {
                is KneType.LIST -> if (inner.elementType is KneType.DATA_CLASS) listOf("${p.name}Handle")
                    else listOf("${p.name}Seg", "if (${p.name} == null) -1 else ${p.name}.size")
                is KneType.SET -> if (inner.elementType is KneType.DATA_CLASS) listOf("${p.name}Handle")
                    else listOf("${p.name}Seg", "if (${p.name} == null) -1 else ${p.name}.size")
                is KneType.MAP -> listOf("${p.name}_keysSeg", "${p.name}_valuesSeg", "if (${p.name} == null) -1 else ${p.name}.size")
                else -> listOf(buildJvmInvokeArg(p.name, p.type))
            }
        }
        val dc = extractDataClass(p.type)
        if (dc == null) return listOf(buildJvmInvokeArg(p.name, p.type))
        val isNullable = p.type is KneType.NULLABLE
        val objExpr = p.name
        val flatArgs = buildFlatInvokeArgs(dc, objExpr, p.name, isNullable)
        return if (isNullable) listOf("if ($objExpr == null) 1 else 0") + flatArgs else flatArgs
    }

    private fun buildFlatInvokeArgs(dc: KneType.DATA_CLASS, objExpr: String, prefix: String, nullable: Boolean): List<String> =
        dc.fields.flatMap { f ->
            val access = if (nullable) "$objExpr?.${f.name}" else "$objExpr.${f.name}"
            val paramName = "${prefix}_${f.name}"
            when (f.type) {
                KneType.STRING -> listOf("${paramName}Seg")
                KneType.BYTE_ARRAY -> listOf("${paramName}Seg", "$access?.size ?: 0")
                KneType.BOOLEAN -> listOf("if ($access == true) 1 else 0")
                is KneType.ENUM -> listOf("$access?.ordinal ?: 0")
                is KneType.OBJECT -> listOf("$access?.handle ?: 0L")
                is KneType.DATA_CLASS -> buildFlatInvokeArgs(f.type, access ?: "null", paramName, nullable)
                is KneType.LIST, is KneType.SET, is KneType.MAP -> listOf("${paramName}CollHandle")
                else -> listOf("$access ?: 0")
            }
        }

    private fun StringBuilder.appendPropertyProxy(prop: KneProperty, cls: KneClass) {
        val getHandleName = "GET_${prop.name.uppercase()}_HANDLE"
        val isCollProp = prop.type.isCollection()
        val isDcProp = extractDataClass(prop.type) != null
        val overrideMod = if (prop.isOverride) "override " else ""
        val openMod = if (!prop.isOverride && (cls.isOpen || cls.isAbstract)) "open " else ""
        val propMod = "$overrideMod$openMod"
        if (prop.mutable) {
            appendLine("    ${propMod}var ${prop.name}: ${prop.type.jvmTypeName}")
        } else {
            appendLine("    ${propMod}val ${prop.name}: ${prop.type.jvmTypeName}")
        }
        appendLine("        get() {")
        if (isCollProp) {
            // Collection property getter: read StableRef handle, deserialize, dispose
            val inner = prop.type.unwrapCollection()
            appendLine("            val _handle = $getHandleName.invoke(handle) as Long")
            appendLine("            KneRuntime.checkError()")
            when (inner) {
                is KneType.LIST -> {
                    if (inner.elementType is KneType.DATA_CLASS) {
                        val dn = inner.elementType.simpleName.uppercase()
                        appendLine("            val _size = LIST_${dn}_SIZE_HANDLE.invoke(_handle) as Int")
                        appendLine("            val _list = ArrayList<${inner.elementType.simpleName}>(_size)")
                        appendLine("            Arena.ofConfined().use { dcArena ->")
                        val flatFields = flattenDcFields(inner.elementType, "out")
                        flatFields.forEach { (name, type) ->
                            when (type) {
                                KneType.STRING, KneType.BYTE_ARRAY -> appendLine("                val $name = dcArena.allocate($STRING_BUF_SIZE.toLong())")
                                else -> appendLine("                val $name = dcArena.allocate(${type.ffmLayout})")
                            }
                        }
                        appendLine("                for (_i in 0 until _size) {")
                        val getArgs = buildList {
                            add("_handle"); add("_i")
                            flatFields.forEach { (name, type) -> when (type) { KneType.STRING, KneType.BYTE_ARRAY -> { add(name); add("$STRING_BUF_SIZE") }; else -> add(name) } }
                        }.joinToString(", ")
                        appendLine("                    LIST_${dn}_GET_HANDLE.invoke($getArgs)")
                        if (dcHasCollectionFields(inner.elementType)) appendDcCollectionFieldReads("                    ", inner.elementType, "out")
                        appendLine("                    _list.add(${buildDcCtorFromOutParams(inner.elementType, "out")})")
                        appendLine("                }")
                        appendLine("            }")
                        appendLine("            LIST_${dn}_DISPOSE_HANDLE.invoke(_handle)")
                        appendLine("            return _list")
                    } else {
                        val key = suspendCollElemKey(inner.elementType)
                        appendLine("            Arena.ofConfined().use { _collArena ->")
                        if (inner.elementType == KneType.STRING) {
                            appendLine("                val _outBuf = _collArena.allocate($STRING_BUF_SIZE.toLong())")
                            appendLine("                val _count = SUSPEND_READCOLL_${key.uppercase()}_HANDLE.invoke(_handle, _outBuf, $STRING_BUF_SIZE) as Int")
                            appendLine("                val _list = mutableListOf<String>()")
                            appendLine("                var _off = 0L")
                            appendLine("                repeat(_count) { _list.add(_outBuf.getString(_off)); _off += _list.last().toByteArray(Charsets.UTF_8).size + 1 }")
                            appendLine("                return _list")
                        } else {
                            val layout = KneType.collectionElementLayout(inner.elementType)
                            appendLine("                val _outBuf = _collArena.allocate($layout, $MAX_COLLECTION_SIZE.toLong())")
                            appendLine("                val _count = SUSPEND_READCOLL_${key.uppercase()}_HANDLE.invoke(_handle, _outBuf, $MAX_COLLECTION_SIZE) as Int")
                            when (inner.elementType) {
                                KneType.BOOLEAN -> appendLine("                return List(_count) { _outBuf.getAtIndex(JAVA_INT, it.toLong()) != 0 }")
                                is KneType.ENUM -> appendLine("                return List(_count) { ${inner.elementType.simpleName}.entries[_outBuf.getAtIndex(JAVA_INT, it.toLong())] }")
                                is KneType.OBJECT -> appendLine("                return List(_count) { ${inner.elementType.simpleName}.fromNativeHandle(_outBuf.getAtIndex(JAVA_LONG, it.toLong()) as Long) }")
                                else -> appendLine("                return List(_count) { _outBuf.getAtIndex($layout, it.toLong()) as ${inner.elementType.jvmTypeName} }")
                            }
                        }
                        appendLine("            }")
                    }
                }
                is KneType.SET -> {
                    val key = suspendCollElemKey(inner.elementType)
                    appendLine("            Arena.ofConfined().use { _collArena ->")
                    val layout = KneType.collectionElementLayout(inner.elementType)
                    appendLine("                val _outBuf = _collArena.allocate($layout, $MAX_COLLECTION_SIZE.toLong())")
                    appendLine("                val _count = SUSPEND_READCOLL_${key.uppercase()}_HANDLE.invoke(_handle, _outBuf, $MAX_COLLECTION_SIZE) as Int")
                    when (inner.elementType) {
                        KneType.BOOLEAN -> appendLine("                return List(_count) { _outBuf.getAtIndex(JAVA_INT, it.toLong()) != 0 }.toSet()")
                        is KneType.ENUM -> appendLine("                return List(_count) { ${inner.elementType.simpleName}.entries[_outBuf.getAtIndex(JAVA_INT, it.toLong())] }.toSet()")
                        else -> appendLine("                return List(_count) { _outBuf.getAtIndex($layout, it.toLong()) as ${inner.elementType.jvmTypeName} }.toSet()")
                    }
                    appendLine("            }")
                }
                is KneType.MAP -> {
                    val kk = suspendCollElemKey(inner.keyType)
                    val vk = suspendCollElemKey(inner.valueType)
                    appendLine("            Arena.ofConfined().use { _mapArena ->")
                    val isKeyString = inner.keyType == KneType.STRING
                    val isValString = inner.valueType == KneType.STRING
                    if (isKeyString) appendLine("                val _keysBuf = _mapArena.allocate($STRING_BUF_SIZE.toLong())")
                    else appendLine("                val _keysBuf = _mapArena.allocate(${KneType.collectionElementLayout(inner.keyType)}, $MAX_COLLECTION_SIZE.toLong())")
                    if (isValString) appendLine("                val _valsBuf = _mapArena.allocate($STRING_BUF_SIZE.toLong())")
                    else appendLine("                val _valsBuf = _mapArena.allocate(${KneType.collectionElementLayout(inner.valueType)}, $MAX_COLLECTION_SIZE.toLong())")
                    val keySizeArg = if (isKeyString) "$STRING_BUF_SIZE" else "$MAX_COLLECTION_SIZE"
                    val valSizeArg = if (isValString) "$STRING_BUF_SIZE" else "$MAX_COLLECTION_SIZE"
                    appendLine("                val _count = SUSPEND_READMAP_${kk.uppercase()}_${vk.uppercase()}_HANDLE.invoke(_handle, _keysBuf, $keySizeArg, _valsBuf, $valSizeArg) as Int")
                    if (isKeyString) {
                        appendLine("                val _keys = mutableListOf<String>(); var _kOff = 0L")
                        appendLine("                repeat(_count) { _keys.add(_keysBuf.getString(_kOff)); _kOff += _keys.last().toByteArray(Charsets.UTF_8).size + 1 }")
                    } else {
                        appendLine("                val _keys = List(_count) { _keysBuf.getAtIndex(${KneType.collectionElementLayout(inner.keyType)}, it.toLong()) as ${inner.keyType.jvmTypeName} }")
                    }
                    if (isValString) {
                        appendLine("                val _vals = mutableListOf<String>(); var _vOff = 0L")
                        appendLine("                repeat(_count) { _vals.add(_valsBuf.getString(_vOff)); _vOff += _vals.last().toByteArray(Charsets.UTF_8).size + 1 }")
                    } else {
                        appendLine("                val _vals = List(_count) { _valsBuf.getAtIndex(${KneType.collectionElementLayout(inner.valueType)}, it.toLong()) as ${inner.valueType.jvmTypeName} }")
                    }
                    appendLine("                return _keys.zip(_vals).toMap()")
                    appendLine("            }")
                }
                else -> appendCallAndReturn("            ", prop.type, getHandleName, "handle")
            }
        } else if (isDcProp) {
            // Data class property getter: use out-params pattern
            val returnsNullableDc = prop.type is KneType.NULLABLE && prop.type.inner is KneType.DATA_CLASS
            appendLine("            Arena.ofConfined().use { arena ->")
            appendDataClassReturnProxyForProperty("                ", prop, getHandleName, returnsNullableDc)
            appendLine("            }")
        } else {
            val needsArena = prop.type.returnsViaBuffer()
            if (needsArena) {
                appendLine("            Arena.ofConfined().use { arena ->")
                appendStringReadWithRetry("                ", getHandleName, "handle")
                if (prop.type is KneType.NULLABLE) {
                    appendLine("                return if (_len < 0) null else _buf.getString(0)")
                } else {
                    appendLine("                return _buf.getString(0)")
                }
                appendLine("            }")
            } else {
                appendCallAndReturn("            ", prop.type, getHandleName, "handle")
            }
        }
        appendLine("        }")

        if (prop.mutable) {
            val setHandleName = "SET_${prop.name.uppercase()}_HANDLE"
            appendLine("        set(value) {")
            if (isCollProp) {
                // Collection property setter: serialize collection to StableRef, pass handle
                val inner = prop.type.unwrapCollection()
                appendLine("            Arena.ofConfined().use { arena ->")
                when (inner) {
                    is KneType.LIST, is KneType.SET -> {
                        val elemType = when (inner) { is KneType.LIST -> inner.elementType; is KneType.SET -> inner.elementType; else -> KneType.INT }
                        val srcExpr = if (inner is KneType.SET) "value.toList()" else "value"
                        val key = suspendCollElemKey(elemType)
                        appendLine("                val _src = $srcExpr")
                        appendLine("                val _wrapHandle = run {")
                        appendCollFieldWrapPrimitive("                    ", "_src", elemType, key)
                        appendLine("                }")
                        appendLine("                $setHandleName.invoke(handle, _wrapHandle)")
                    }
                    is KneType.MAP -> {
                        val kk = suspendCollElemKey(inner.keyType)
                        val vk = suspendCollElemKey(inner.valueType)
                        appendLine("                val _wrapHandle = run {")
                        appendCollFieldWrapMap("                    ", "value", inner, kk, vk)
                        appendLine("                }")
                        appendLine("                $setHandleName.invoke(handle, _wrapHandle)")
                    }
                    else -> {}
                }
                appendLine("            }")
            } else {
                appendSetterInvoke("            ", setHandleName, prop.type, "handle")
            }
            appendLine("        }")
        }
        appendLine()
    }

    // ── Companion method/property proxies ────────────────────────────────────

    private fun StringBuilder.appendCompanionMethodProxy(fn: KneFunction) {
        val handleName = "COMPANION_${fn.name.uppercase()}_HANDLE"
        val params = fn.params.joinToString(", ") { "${it.name}: ${it.type.jvmTypeName}" }

        appendLine()
        appendLine("        fun ${fn.name}($params): ${fn.returnType.jvmTypeName} {")

        appendCallbackStubAlloc("            ", fn.params, "_companionCallbackArena")

        val arenaNeeded = needsConfinedArena(fn.params, fn.returnType)
        if (arenaNeeded) {
            appendLine("            Arena.ofConfined().use { arena ->")
            appendStringInvokeArgsAlloc("                ", fn.params)
            val invokeArgs = buildTopLevelInvokeArgs(fn)
            appendCallAndReturn("                ", fn.returnType, handleName, invokeArgs)
            appendLine("            }")
        } else {
            val invokeArgs = fn.params.joinToString(", ") { p -> buildJvmInvokeArg(p.name, p.type) }
            appendCallAndReturn("            ", fn.returnType, handleName, invokeArgs)
        }

        appendLine("        }")
    }

    private fun StringBuilder.appendCompanionPropertyProxy(prop: KneProperty) {
        val getHandleName = "COMPANION_GET_${prop.name.uppercase()}_HANDLE"
        appendLine()
        if (prop.mutable) {
            appendLine("        var ${prop.name}: ${prop.type.jvmTypeName}")
        } else {
            appendLine("        val ${prop.name}: ${prop.type.jvmTypeName}")
        }
        appendLine("            get() {")
        val needsArena = prop.type.returnsViaBuffer()
        if (needsArena) {
            appendLine("                Arena.ofConfined().use { arena ->")
            appendStringReadWithRetry("                    ", getHandleName, "")
            if (prop.type is KneType.NULLABLE) {
                appendLine("                    return if (_len < 0) null else _buf.getString(0)")
            } else {
                appendLine("                    return _buf.getString(0)")
            }
            appendLine("                }")
        } else {
            appendCallAndReturn("                ", prop.type, getHandleName, "")
        }
        appendLine("            }")

        if (prop.mutable) {
            val setHandleName = "COMPANION_SET_${prop.name.uppercase()}_HANDLE"
            appendLine("            set(value) {")
            appendSetterInvoke("                ", setHandleName, prop.type, null)
            appendLine("            }")
        }
    }

    // ── Data class file ───────────────────────────────────────────────────────

    private fun generateDataClassFile(dc: KneDataClass, pkg: String): String = buildString {
        appendLine("// Auto-generated by kotlin-native-export plugin. Do not modify.")
        appendLine("package $pkg")
        appendLine()
        val fields = dc.fields.joinToString(", ") { "val ${it.name}: ${it.type.jvmTypeName}" }
        appendLine("data class ${dc.simpleName}($fields)")
    }

    // ── Enum proxy ───────────────────────────────────────────────────────────

    private fun generateEnumProxy(enum: KneEnum, module: KneModule, pkg: String): String = buildString {
        appendLine("// Auto-generated by kotlin-native-export plugin. Do not modify.")
        appendLine("package $pkg")
        appendLine()
        val header = if (enum.constructorParams.isEmpty()) {
            "enum class ${enum.simpleName} {"
        } else {
            val params = enum.constructorParams.joinToString(", ") { "val ${it.name}: ${it.type.jvmTypeName}" }
            "enum class ${enum.simpleName}($params) {"
        }
        appendLine(header)
        enum.entries.forEachIndexed { idx, entry ->
            val separator = if (idx < enum.entries.size - 1) "," else ";"
            val call = if (entry.constructorArgs.isEmpty()) {
                entry.name
            } else {
                "${entry.name}(${entry.constructorArgs.joinToString(", ")})"
            }
            appendLine("    $call$separator")
        }
        appendLine("}")
    }

    // ── Top-level function object ────────────────────────────────────────────

    private fun generateFunctionObject(
        fns: List<KneFunction>,
        objectName: String,
        module: KneModule,
        pkg: String,
    ): String = buildString {
        val p = module.libName

        appendLine("// Auto-generated by kotlin-native-export plugin. Do not modify.")
        appendLine("package $pkg")
        appendLine()
        appendLine("import java.lang.foreign.Arena")
        appendLine("import java.lang.foreign.FunctionDescriptor")
        appendLine("import java.lang.foreign.MemorySegment")
        appendLine("import java.lang.foreign.ValueLayout.*")
        appendLine("import java.lang.invoke.MethodHandle")
        appendLine()

        val objectHasCallbacks = fns.any { fn -> fn.params.any { it.type is KneType.FUNCTION } }

        appendLine("object $objectName {")
        if (objectHasCallbacks) {
            appendLine("    private val _callbackArena: Arena = Arena.ofShared()")
        }
        appendLine()

        fns.forEach { fn ->
            val handleName = "${fn.name.uppercase()}_HANDLE"
            val descriptor = buildTopLevelDescriptor(fn)
            appendLine("    private val $handleName: MethodHandle by lazy {")
            appendLine("        KneRuntime.handle(\"${p}_${fn.name}\", $descriptor)")
            appendLine("    }")
        }
        appendLine()

        fns.forEach { fn ->
            val handleName = "${fn.name.uppercase()}_HANDLE"
            val params = fn.params.joinToString(", ") { "${it.name}: ${it.type.jvmTypeName}" }
            appendLine("    fun ${fn.name}($params): ${fn.returnType.jvmTypeName} {")

            appendCallbackStubAlloc("        ", fn.params, "_callbackArena")

            val arenaNeeded = needsConfinedArena(fn.params, fn.returnType)
            if (arenaNeeded) {
                appendLine("        Arena.ofConfined().use { arena ->")
                appendStringInvokeArgsAlloc("            ", fn.params)
                val invokeArgs = buildTopLevelInvokeArgs(fn)
                appendCallAndReturn("            ", fn.returnType, handleName, invokeArgs)
                appendLine("        }")
            } else {
                val invokeArgs = fn.params.joinToString(", ") { fp ->
                    buildJvmInvokeArg(fp.name, fp.type)
                }
                appendCallAndReturn("        ", fn.returnType, handleName, invokeArgs)
            }

            appendLine("    }")
            appendLine()
        }

        appendLine("}")
    }

    // ── Descriptor builders ──────────────────────────────────────────────────

    private fun buildMethodDescriptor(fn: KneFunction): String {
        val returnDc = extractDataClass(fn.returnType)
        val returnsNullableDc = fn.returnType is KneType.NULLABLE && fn.returnType.inner is KneType.DATA_CLASS
        val paramLayouts = buildList {
            add("JAVA_LONG") // handle
            fn.params.forEach { p ->
                val dc = extractDataClass(p.type)
                if (dc != null) {
                    if (p.type is KneType.NULLABLE) add("JAVA_INT") // isNull flag
                    // Param: String fields are just ADDRESS (null-terminated), no size needed
                    flattenDcFields(dc, "").forEach { (_, type) ->
                        when {
                            type == KneType.BYTE_ARRAY -> { add("ADDRESS"); add("JAVA_INT") }
                            type is KneType.LIST || type is KneType.SET || type is KneType.MAP -> add("JAVA_LONG")
                            else -> add(type.ffmLayout)
                        }
                    }
                } else if (p.type == KneType.BYTE_ARRAY) {
                    add("ADDRESS"); add("JAVA_INT")
                } else if (p.type.isCollection()) {
                    val inner = p.type.unwrapCollection()
                    when (inner) {
                        is KneType.LIST -> if (inner.elementType is KneType.DATA_CLASS) add("JAVA_LONG") else { add("ADDRESS"); add("JAVA_INT") }
                        is KneType.SET -> if (inner.elementType is KneType.DATA_CLASS) add("JAVA_LONG") else { add("ADDRESS"); add("JAVA_INT") }
                        is KneType.MAP -> { add("ADDRESS"); add("ADDRESS"); add("JAVA_INT") }
                        else -> {}
                    }
                } else {
                    add(p.type.ffmLayout)
                }
            }
            // Suspend functions: add continuation + exception + cancelOut params, return void early
            if (fn.isSuspend) {
                add("JAVA_LONG")  // contPtr
                add("JAVA_LONG")  // excPtr
                add("ADDRESS")    // cancelOut
            }
            // Flow functions: add onNext + onError + onComplete + cancelOut params
            if (fn.returnType is KneType.FLOW) {
                add("JAVA_LONG")  // nextPtr
                add("JAVA_LONG")  // errorPtr
                add("JAVA_LONG")  // completePtr
                add("ADDRESS")    // cancelOut
            }
            val skipReturnParams = fn.isSuspend || fn.returnType is KneType.FLOW
            if (!skipReturnParams && fn.returnType.returnsViaBuffer()) {
                add("ADDRESS"); add("JAVA_INT")
            }
            if (!skipReturnParams && returnDc != null) {
                flattenDcFields(returnDc, "").forEach { (_, type) ->
                    when (type) {
                        KneType.STRING -> { add("ADDRESS"); add("JAVA_INT") }
                        KneType.BYTE_ARRAY -> add("ADDRESS") // StableRef handle in CPointer<LongVar>
                        else -> add("ADDRESS")
                    }
                }
            }
            // Collection return out-params (unwrap nullable) — skip for suspend
            if (!skipReturnParams && fn.returnType.isCollection()) {
                val collInner = fn.returnType.unwrapCollection()
                val collElem = when (collInner) { is KneType.LIST -> collInner.elementType; is KneType.SET -> collInner.elementType; else -> null }
                if (collElem is KneType.DATA_CLASS) {
                    // DC list: no out-params, returns Long handle (handled in effectiveReturn below)
                } else when (collInner) {
                    is KneType.LIST, is KneType.SET -> {
                        add("ADDRESS"); add("JAVA_INT") // outBuf + outLen/outBufLen
                    }
                    is KneType.MAP -> {
                        add("ADDRESS") // outKeys
                        if (collInner.keyType == KneType.STRING) add("JAVA_INT")
                        add("ADDRESS") // outValues
                        if (collInner.valueType == KneType.STRING) add("JAVA_INT")
                        add("JAVA_INT") // outLen
                    }
                    else -> {}
                }
            }
        }
        if (fn.isSuspend) return buildDescriptor(KneType.UNIT, paramLayouts)
        if (fn.returnType is KneType.FLOW) return buildDescriptor(KneType.UNIT, paramLayouts)

        val isDcColl = fn.returnType.isCollection() && run {
            val ci = fn.returnType.unwrapCollection()
            val ce = when (ci) { is KneType.LIST -> ci.elementType; is KneType.SET -> ci.elementType; else -> null }
            ce is KneType.DATA_CLASS
        }

        val effectiveReturn = when {
            returnDc != null && returnsNullableDc -> KneType.INT // 0=null, 1=present
            returnDc != null -> KneType.UNIT
            isDcColl -> KneType.LONG // opaque handle
            fn.returnType.isCollection() -> KneType.INT // element count
            else -> fn.returnType
        }
        return buildDescriptor(effectiveReturn, paramLayouts)
    }

    private fun buildGetterDescriptor(prop: KneProperty): String {
        val isCollProp = prop.type.isCollection()
        val returnDc = extractDataClass(prop.type)
        val returnsNullableDc = prop.type is KneType.NULLABLE && prop.type.inner is KneType.DATA_CLASS
        val paramLayouts = buildList {
            add("JAVA_LONG")
            if (!isCollProp && prop.type.returnsViaBuffer()) {
                add("ADDRESS"); add("JAVA_INT")
            }
            // Data class return: add out-param layouts for each flattened field
            if (returnDc != null) {
                flattenDcFields(returnDc, "").forEach { (_, type) ->
                    when (type) {
                        KneType.STRING -> { add("ADDRESS"); add("JAVA_INT") }
                        KneType.BYTE_ARRAY -> add("ADDRESS") // StableRef handle in CPointer<LongVar>
                        else -> add("ADDRESS")
                    }
                }
            }
        }
        // Collection getters return JAVA_LONG (StableRef handle)
        val effectiveReturn = when {
            returnDc != null && returnsNullableDc -> KneType.INT // 0=null, 1=present
            returnDc != null -> KneType.UNIT
            isCollProp -> KneType.LONG
            else -> prop.type
        }
        return buildDescriptor(effectiveReturn, paramLayouts)
    }

    private fun buildCompanionGetterDescriptor(prop: KneProperty): String {
        val paramLayouts = buildList {
            if (prop.type.returnsViaBuffer()) {
                add("ADDRESS"); add("JAVA_INT")
            }
        }
        return buildDescriptor(prop.type, paramLayouts)
    }

    private fun buildTopLevelDescriptor(fn: KneFunction): String {
        val paramLayouts = buildList {
            fn.params.forEach { p -> add(p.type.ffmLayout) }
            if (fn.returnType.returnsViaBuffer()) {
                add("ADDRESS"); add("JAVA_INT")
            }
        }
        return buildDescriptor(fn.returnType, paramLayouts)
    }

    private fun buildDescriptor(returnType: KneType, paramLayouts: List<String>): String {
        val params = paramLayouts.filter { it.isNotEmpty() }.joinToString(", ")
        return if (returnType == KneType.UNIT || returnType.returnsViaBuffer()) {
            val retLayout = if (returnType.returnsViaBuffer()) "JAVA_INT" else ""
            if (retLayout.isEmpty()) "FunctionDescriptor.ofVoid($params)"
            else "FunctionDescriptor.of($retLayout${if (params.isNotEmpty()) ", $params" else ""})"
        } else {
            "FunctionDescriptor.of(${returnType.ffmLayout}${if (params.isNotEmpty()) ", $params" else ""})"
        }
    }

    private fun buildLayouts(types: List<KneType>): String =
        types.filter { it.ffmLayout.isNotEmpty() }.joinToString("") { ", ${it.ffmLayout}" }

    /** Build a FunctionDescriptor for a constructor, expanding DC/ByteArray/collection params. */
    private fun buildCtorDescriptor(params: List<KneParam>): String {
        val layouts = buildList {
            params.forEach { p ->
                val dc = extractDataClass(p.type)
                if (dc != null) {
                    if (p.type is KneType.NULLABLE) add("JAVA_INT")
                    flattenDcFields(dc, "").forEach { (_, type) ->
                        when (type) { KneType.BYTE_ARRAY -> { add("ADDRESS"); add("JAVA_INT") }; else -> add(type.ffmLayout) }
                    }
                } else if (p.type == KneType.BYTE_ARRAY) { add("ADDRESS"); add("JAVA_INT") }
                else if (p.type.isCollection()) {
                    val inner = p.type.unwrapCollection()
                    when (inner) {
                        is KneType.LIST -> if (inner.elementType is KneType.DATA_CLASS) add("JAVA_LONG") else { add("ADDRESS"); add("JAVA_INT") }
                        is KneType.SET -> if (inner.elementType is KneType.DATA_CLASS) add("JAVA_LONG") else { add("ADDRESS"); add("JAVA_INT") }
                        is KneType.MAP -> { add("ADDRESS"); add("ADDRESS"); add("JAVA_INT") }
                        else -> add(p.type.ffmLayout)
                    }
                } else add(p.type.ffmLayout)
            }
        }
        return buildDescriptor(KneType.LONG, layouts) // constructor returns Long handle
    }

    // ── Invoke arg builders ──────────────────────────────────────────────────

    private fun buildJvmInvokeArg(name: String, type: KneType): String = when (type) {
        KneType.STRING -> "${name}Seg"
        KneType.BYTE_ARRAY -> "${name}Seg"
        KneType.BOOLEAN -> "if ($name) 1 else 0"
        is KneType.OBJECT -> "$name.handle"
        is KneType.ENUM -> "$name.ordinal"
        is KneType.NULLABLE -> buildNullableJvmInvokeArg(name, type)
        is KneType.FUNCTION -> "${name}Stub"
        is KneType.LIST -> "${name}Seg"
        is KneType.SET -> "${name}Seg"
        is KneType.MAP -> "${name}Seg" // shouldn't be reached; MAP expands to keys+values
        else -> name
    }

    private fun buildNullableJvmInvokeArg(name: String, type: KneType.NULLABLE): String = when (type.inner) {
        KneType.STRING -> "${name}Seg"
        KneType.BOOLEAN -> "if ($name == null) -1 else if ($name) 1 else 0"
        KneType.INT -> "$name?.toLong() ?: Long.MIN_VALUE"
        KneType.LONG -> "$name ?: Long.MIN_VALUE"
        KneType.SHORT -> "$name?.toInt() ?: Int.MIN_VALUE"
        KneType.BYTE -> "$name?.toInt() ?: Int.MIN_VALUE"
        KneType.FLOAT -> "if ($name != null) $name.toRawBits().toLong() else Long.MIN_VALUE"
        KneType.DOUBLE -> "if ($name != null) $name.toRawBits() else Long.MIN_VALUE"
        is KneType.OBJECT -> "$name?.handle ?: 0L"
        is KneType.ENUM -> "$name?.ordinal ?: -1"
        is KneType.FUNCTION -> "${name}Stub"
        else -> name
    }

    private fun buildCtorInvokeArgs(params: List<KneParam>): String {
        if (params.isEmpty()) return ""
        return params.joinToString(", ") { p -> buildJvmInvokeArg(p.name, p.type) }
    }

    private fun buildClassInvokeArgs(fn: KneFunction): String {
        val args = buildList {
            add("handle")
            fn.params.forEach { p -> add(buildJvmInvokeArg(p.name, p.type)) }
        }
        return args.joinToString(", ")
    }

    private fun buildClassInvokeArgsDirect(fn: KneFunction): String {
        val args = buildList {
            add("handle")
            fn.params.forEach { p -> add(buildJvmInvokeArg(p.name, p.type)) }
        }
        return args.joinToString(", ")
    }

    private fun buildTopLevelInvokeArgs(fn: KneFunction): String {
        val args = buildList {
            fn.params.forEach { p -> add(buildJvmInvokeArg(p.name, p.type)) }
        }
        return args.joinToString(", ")
    }

    // ── Code emission helpers ────────────────────────────────────────────────

    private fun StringBuilder.appendStringInvokeArgsAlloc(indent: String, params: List<KneParam>) {
        params.filter { it.type == KneType.STRING }.forEach { p ->
            appendLine("${indent}val ${p.name}Seg = arena.allocateFrom(${p.name})")
        }
        params.filter { it.type == KneType.BYTE_ARRAY }.forEach { p ->
            appendLine("${indent}val ${p.name}Seg = arena.allocate(${p.name}.size.toLong())")
            appendLine("${indent}MemorySegment.copy(${p.name}, 0, ${p.name}Seg, JAVA_BYTE, 0, ${p.name}.size)")
        }
        params.filter { it.type is KneType.NULLABLE && (it.type as KneType.NULLABLE).inner == KneType.STRING }.forEach { p ->
            appendLine("${indent}val ${p.name}Seg = if (${p.name} != null) arena.allocateFrom(${p.name}) else MemorySegment.NULL")
        }
        // Allocate String fields from data class params (including nullable)
        params.forEach { p ->
            val dc = extractDataClass(p.type) ?: return@forEach
            val isNullable = p.type is KneType.NULLABLE
            dc.fields.filter { it.type == KneType.STRING }.forEach { f ->
                if (isNullable) {
                    appendLine("${indent}val ${p.name}_${f.name}Seg = if (${p.name} != null) arena.allocateFrom(${p.name}.${f.name}) else MemorySegment.NULL")
                } else {
                    appendLine("${indent}val ${p.name}_${f.name}Seg = arena.allocateFrom(${p.name}.${f.name})")
                }
            }
            dc.fields.filter { it.type == KneType.BYTE_ARRAY }.forEach { f ->
                if (isNullable) {
                    appendLine("${indent}val ${p.name}_${f.name}Seg = if (${p.name} != null) { val _ba = ${p.name}.${f.name}; val _s = arena.allocate(_ba.size.toLong()); MemorySegment.copy(_ba, 0, _s, JAVA_BYTE, 0, _ba.size); _s } else MemorySegment.NULL")
                } else {
                    appendLine("${indent}val ${p.name}_${f.name}Seg = run { val _ba = ${p.name}.${f.name}; val _s = arena.allocate(_ba.size.toLong()); MemorySegment.copy(_ba, 0, _s, JAVA_BYTE, 0, _ba.size); _s }")
                }
            }
            // Wrap collection fields from DC params into StableRef handles
            appendDcCollectionFieldWraps(indent, dc, p.name, isNullable)
        }
    }

    /** Emit wrap bridge calls for collection fields in DC params. */
    private fun StringBuilder.appendDcCollectionFieldWraps(indent: String, dc: KneType.DATA_CLASS, objExpr: String, nullable: Boolean) {
        dc.fields.forEach { f ->
            val paramName = "${objExpr}_${f.name}"
            val access = if (nullable) "$objExpr?.${f.name}" else "$objExpr.${f.name}"
            when (f.type) {
                is KneType.LIST -> {
                    val elemType = f.type.elementType
                    if (elemType is KneType.DATA_CLASS) {
                        // Use listparam_create/add bridges (already exist)
                        val dn = elemType.simpleName.uppercase()
                        appendLine("${indent}val ${paramName}CollHandle = run {")
                        appendLine("${indent}    val _src = $access")
                        appendLine("${indent}    val _h = LISTPARAM_${dn}_CREATE_HANDLE.invoke(_src.size) as Long")
                        appendLine("${indent}    for (_elem in _src) {")
                        val fieldsWithPaths = buildDcFieldsWithAccessPaths(elemType, "_elem")
                        fieldsWithPaths.filter { it.type == KneType.STRING }.forEach { ff ->
                            appendLine("${indent}        val ${ff.segName} = arena.allocateFrom(${ff.accessExpr})")
                        }
                        val addArgs = buildList {
                            add("_h")
                            fieldsWithPaths.forEach { ff ->
                                when (ff.type) {
                                    KneType.STRING -> add(ff.segName)
                                    KneType.BOOLEAN -> add("if (${ff.accessExpr}) 1 else 0")
                                    is KneType.ENUM -> add("${ff.accessExpr}.ordinal")
                                    is KneType.OBJECT -> add("${ff.accessExpr}.handle")
                                    else -> add(ff.accessExpr)
                                }
                            }
                        }.joinToString(", ")
                        appendLine("${indent}        LISTPARAM_${dn}_ADD_HANDLE.invoke($addArgs)")
                        appendLine("${indent}    }")
                        appendLine("${indent}    _h")
                        appendLine("${indent}}")
                    } else {
                        val key = suspendCollElemKey(elemType)
                        appendLine("${indent}val ${paramName}CollHandle = run {")
                        appendLine("${indent}    val _src = $access")
                        appendCollFieldWrapPrimitive("${indent}    ", "_src", elemType, key)
                        appendLine("${indent}}")
                    }
                }
                is KneType.SET -> {
                    val elemType = f.type.elementType
                    val key = suspendCollElemKey(elemType)
                    appendLine("${indent}val ${paramName}CollHandle = run {")
                    appendLine("${indent}    val _src = ($access).toList()")
                    appendCollFieldWrapPrimitive("${indent}    ", "_src", elemType, key)
                    appendLine("${indent}}")
                }
                is KneType.MAP -> {
                    val kk = suspendCollElemKey(f.type.keyType)
                    val vk = suspendCollElemKey(f.type.valueType)
                    appendLine("${indent}val ${paramName}CollHandle = run {")
                    appendLine("${indent}    val _src = $access")
                    appendCollFieldWrapMap("${indent}    ", "_src", f.type, kk, vk)
                    appendLine("${indent}}")
                }
                is KneType.DATA_CLASS -> {
                    if (dcHasCollectionFields(f.type)) {
                        appendDcCollectionFieldWraps(indent, f.type, access, nullable)
                    }
                }
                else -> {}
            }
        }
    }

    /** Emit code to wrap a primitive list into a StableRef via wrap bridge. */
    private fun StringBuilder.appendCollFieldWrapPrimitive(indent: String, srcExpr: String, elemType: KneType, key: String) {
        when (elemType) {
            KneType.STRING -> {
                appendLine("${indent}val _totalBytes = $srcExpr.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 }")
                appendLine("${indent}val _buf = arena.allocate(_totalBytes.toLong().coerceAtLeast(1))")
                appendLine("${indent}var _off = 0L")
                appendLine("${indent}for (_s in $srcExpr) { _buf.setString(_off, _s); _off += _s.toByteArray(Charsets.UTF_8).size + 1 }")
                appendLine("${indent}WRAP_COLL_${key.uppercase()}_HANDLE.invoke(_buf, $srcExpr.size) as Long")
            }
            KneType.BOOLEAN -> {
                appendLine("${indent}val _buf = arena.allocate(JAVA_INT, $srcExpr.size.toLong())")
                appendLine("${indent}$srcExpr.forEachIndexed { i, v -> _buf.setAtIndex(JAVA_INT, i.toLong(), if (v) 1 else 0) }")
                appendLine("${indent}WRAP_COLL_${key.uppercase()}_HANDLE.invoke(_buf, $srcExpr.size) as Long")
            }
            is KneType.ENUM -> {
                appendLine("${indent}val _buf = arena.allocate(JAVA_INT, $srcExpr.size.toLong())")
                appendLine("${indent}$srcExpr.forEachIndexed { i, v -> _buf.setAtIndex(JAVA_INT, i.toLong(), v.ordinal) }")
                appendLine("${indent}WRAP_COLL_${key.uppercase()}_HANDLE.invoke(_buf, $srcExpr.size) as Long")
            }
            else -> {
                val layout = KneType.collectionElementLayout(elemType)
                appendLine("${indent}val _buf = arena.allocate($layout, $srcExpr.size.toLong())")
                appendLine("${indent}$srcExpr.forEachIndexed { i, v -> _buf.setAtIndex($layout, i.toLong(), v) }")
                appendLine("${indent}WRAP_COLL_${key.uppercase()}_HANDLE.invoke(_buf, $srcExpr.size) as Long")
            }
        }
    }

    /** Emit code to wrap a map into a StableRef via wrap bridge. */
    private fun StringBuilder.appendCollFieldWrapMap(indent: String, srcExpr: String, type: KneType.MAP, kk: String, vk: String) {
        val isKeyString = type.keyType == KneType.STRING
        val isValString = type.valueType == KneType.STRING

        appendLine("${indent}val _keys = $srcExpr.keys.toList()")
        appendLine("${indent}val _vals = $srcExpr.values.toList()")

        // Serialize keys
        if (isKeyString) {
            appendLine("${indent}val _kBytes = _keys.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 }")
            appendLine("${indent}val _kBuf = arena.allocate(_kBytes.toLong().coerceAtLeast(1))")
            appendLine("${indent}var _kOff = 0L")
            appendLine("${indent}for (_s in _keys) { _kBuf.setString(_kOff, _s); _kOff += _s.toByteArray(Charsets.UTF_8).size + 1 }")
        } else {
            val kLayout = KneType.collectionElementLayout(type.keyType)
            appendLine("${indent}val _kBuf = arena.allocate($kLayout, _keys.size.toLong())")
            appendLine("${indent}_keys.forEachIndexed { i, v -> _kBuf.setAtIndex($kLayout, i.toLong(), v) }")
        }

        // Serialize values
        if (isValString) {
            appendLine("${indent}val _vBytes = _vals.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 }")
            appendLine("${indent}val _vBuf = arena.allocate(_vBytes.toLong().coerceAtLeast(1))")
            appendLine("${indent}var _vOff = 0L")
            appendLine("${indent}for (_s in _vals) { _vBuf.setString(_vOff, _s); _vOff += _s.toByteArray(Charsets.UTF_8).size + 1 }")
        } else {
            val vLayout = KneType.collectionElementLayout(type.valueType)
            appendLine("${indent}val _vBuf = arena.allocate($vLayout, _vals.size.toLong())")
            appendLine("${indent}_vals.forEachIndexed { i, v -> _vBuf.setAtIndex($vLayout, i.toLong(), v) }")
        }

        val kCountArg = if (isKeyString) "_keys.size" else "_keys.size"
        val vCountArg = if (isValString) "_vals.size" else "_vals.size"
        appendLine("${indent}WRAP_MAP_${kk.uppercase()}_${vk.uppercase()}_HANDLE.invoke(_kBuf, $kCountArg, _vBuf, $vCountArg) as Long")
    }

    /** Emit callback stub allocation using the persistent arena. */
    private fun StringBuilder.appendCallbackStubAlloc(indent: String, params: List<KneParam>, arenaExpr: String) {
        params.filter { it.type is KneType.FUNCTION }.forEach { p ->
            val fnType = p.type as KneType.FUNCTION
            val id = callbackId(fnType)
            appendLine("${indent}val ${p.name}Stub = KneRuntime.createUpcallStub_$id(${p.name}, $arenaExpr)")
        }
        // Nullable callback params: create stub only if non-null, pass 0L for null
        params.filter { it.type is KneType.NULLABLE && (it.type as KneType.NULLABLE).inner is KneType.FUNCTION }.forEach { p ->
            val fnType = (p.type as KneType.NULLABLE).inner as KneType.FUNCTION
            val id = callbackId(fnType)
            appendLine("${indent}val ${p.name}Stub = if (${p.name} != null) KneRuntime.createUpcallStub_$id(${p.name}!!, $arenaExpr) else 0L")
        }
    }

    // ── Collection marshaling ────────────────────────────────────────────────

    /** Allocate MemorySegments for collection parameters (including nullable). */
    private fun StringBuilder.appendCollectionParamAlloc(indent: String, params: List<KneParam>) {
        params.forEach { p ->
            val isNullable = p.type is KneType.NULLABLE
            val inner = p.type.unwrapCollection()
            when (inner) {
                is KneType.LIST -> {
                    if (inner.elementType is KneType.DATA_CLASS) {
                        appendListDcParamAlloc(indent, p.name, inner.elementType as KneType.DATA_CLASS, isNullable)
                    } else if (isNullable) {
                        appendLine("${indent}val ${p.name}Unwrapped = ${p.name} ?: emptyList()")
                        appendListParamAlloc(indent, p.name, inner.elementType, srcExpr = "${p.name}Unwrapped")
                    } else {
                        appendListParamAlloc(indent, p.name, inner.elementType)
                    }
                }
                is KneType.SET -> {
                    if (inner.elementType is KneType.DATA_CLASS) {
                        if (isNullable) {
                            appendLine("${indent}val ${p.name}AsList = ${p.name}?.toList()")
                        } else {
                            appendLine("${indent}val ${p.name}AsList = ${p.name}.toList()")
                        }
                        appendListDcParamAlloc(indent, p.name, inner.elementType as KneType.DATA_CLASS, isNullable, srcExpr = "${p.name}AsList")
                    } else {
                        if (isNullable) {
                            appendLine("${indent}val ${p.name}AsList = ${p.name}?.toList() ?: emptyList()")
                        } else {
                            appendLine("${indent}val ${p.name}AsList = ${p.name}.toList()")
                        }
                        appendListParamAlloc(indent, p.name, inner.elementType, srcExpr = "${p.name}AsList")
                    }
                }
                is KneType.MAP -> {
                    if (isNullable) {
                        appendLine("${indent}val ${p.name}Unwrapped = ${p.name} ?: emptyMap()")
                        appendMapParamAlloc(indent, p.name, inner, srcExpr = "${p.name}Unwrapped")
                    } else {
                        appendMapParamAlloc(indent, p.name, inner)
                    }
                }
                else -> {}
            }
        }
    }

    private fun StringBuilder.appendListParamAlloc(indent: String, name: String, elemType: KneType, srcExpr: String = name) {
        when (elemType) {
            KneType.STRING -> {
                // Pack strings as null-terminated sequence in a single buffer
                appendLine("${indent}val ${name}TotalBytes = $srcExpr.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 }")
                appendLine("${indent}val ${name}Seg = arena.allocate(${name}TotalBytes.toLong().coerceAtLeast(1))")
                appendLine("${indent}var ${name}Off = 0L")
                appendLine("${indent}for (_s in $srcExpr) { ${name}Seg.setString(${name}Off, _s); ${name}Off += _s.toByteArray(Charsets.UTF_8).size + 1 }")
            }
            KneType.BOOLEAN -> {
                appendLine("${indent}val ${name}Seg = arena.allocate(JAVA_INT, $srcExpr.size.toLong())")
                appendLine("${indent}$srcExpr.forEachIndexed { i, v -> ${name}Seg.setAtIndex(JAVA_INT, i.toLong(), if (v) 1 else 0) }")
            }
            is KneType.ENUM -> {
                appendLine("${indent}val ${name}Seg = arena.allocate(JAVA_INT, $srcExpr.size.toLong())")
                appendLine("${indent}$srcExpr.forEachIndexed { i, v -> ${name}Seg.setAtIndex(JAVA_INT, i.toLong(), v.ordinal) }")
            }
            is KneType.OBJECT -> {
                appendLine("${indent}val ${name}Seg = arena.allocate(JAVA_LONG, $srcExpr.size.toLong())")
                appendLine("${indent}$srcExpr.forEachIndexed { i, v -> ${name}Seg.setAtIndex(JAVA_LONG, i.toLong(), v.handle) }")
            }
            KneType.BYTE_ARRAY -> {
                // ByteArray elements: wrap each as StableRef handle via wrap bridge
                appendLine("${indent}val ${name}Seg = arena.allocate(JAVA_LONG, $srcExpr.size.toLong())")
                appendLine("${indent}$srcExpr.forEachIndexed { i, v ->")
                appendLine("${indent}    val _baSeg = arena.allocate(v.size.toLong())")
                appendLine("${indent}    MemorySegment.copy(v, 0, _baSeg, JAVA_BYTE, 0, v.size)")
                appendLine("${indent}    ${name}Seg.setAtIndex(JAVA_LONG, i.toLong(), WRAP_COLL_BYTEARRAY_HANDLE.invoke(_baSeg, v.size) as Long)")
                appendLine("${indent}}")
            }
            is KneType.LIST, is KneType.SET, is KneType.MAP -> {
                // Nested collection: wrap each inner collection as StableRef handle via wrap bridge
                val innerKey = suspendCollElemKey(when (elemType) {
                    is KneType.LIST -> elemType.elementType
                    is KneType.SET -> elemType.elementType
                    else -> KneType.INT
                })
                appendLine("${indent}val ${name}Seg = arena.allocate(JAVA_LONG, $srcExpr.size.toLong())")
                appendLine("${indent}$srcExpr.forEachIndexed { _i, _inner ->")
                appendLine("${indent}    val _innerHandle = run {")
                if (elemType is KneType.MAP) {
                    val kk = suspendCollElemKey(elemType.keyType)
                    val vk = suspendCollElemKey(elemType.valueType)
                    appendCollFieldWrapMap("${indent}        ", "_inner", elemType, kk, vk)
                } else {
                    val innerElem = when (elemType) { is KneType.LIST -> elemType.elementType; is KneType.SET -> elemType.elementType; else -> KneType.INT }
                    val src = if (elemType is KneType.SET) "_inner.toList()" else "_inner"
                    appendCollFieldWrapPrimitive("${indent}        ", src, innerElem, innerKey)
                }
                appendLine("${indent}    }")
                appendLine("${indent}    ${name}Seg.setAtIndex(JAVA_LONG, _i.toLong(), _innerHandle)")
                appendLine("${indent}}")
            }
            else -> {
                val layout = KneType.collectionElementLayout(elemType)
                appendLine("${indent}val ${name}Seg = arena.allocate($layout, $srcExpr.size.toLong())")
                appendLine("${indent}$srcExpr.forEachIndexed { i, v -> ${name}Seg.setAtIndex($layout, i.toLong(), v) }")
            }
        }
    }

    /** Allocate a native list handle for List<DC> param by creating + adding elements one by one. */
    private fun StringBuilder.appendListDcParamAlloc(indent: String, name: String, dc: KneType.DATA_CLASS, nullable: Boolean, srcExpr: String = name) {
        val dn = dc.simpleName.uppercase()
        val src = if (nullable) "${name}Unwrapped" else srcExpr
        if (nullable) {
            appendLine("${indent}val ${name}Unwrapped = $srcExpr ?: emptyList()")
        }
        appendLine("${indent}val ${name}Handle = if (${if (nullable) "$srcExpr == null" else "false"}) 0L else {")
        appendLine("${indent}    val _h = LISTPARAM_${dn}_CREATE_HANDLE.invoke($src.size) as Long")
        appendLine("${indent}    for (_elem in $src) {")
        // Collect flat fields with their accessor paths
        val fieldsWithPaths = buildDcFieldsWithAccessPaths(dc, "_elem")
        // Allocate String segments
        fieldsWithPaths.filter { it.type == KneType.STRING }.forEach { f ->
            appendLine("${indent}        val ${f.segName} = arena.allocateFrom(${f.accessExpr})")
        }
        // Build ADD invoke args
        val addArgs = buildList {
            add("_h")
            fieldsWithPaths.forEach { f ->
                when (f.type) {
                    KneType.STRING -> add(f.segName)
                    KneType.BOOLEAN -> add("if (${f.accessExpr}) 1 else 0")
                    is KneType.ENUM -> add("${f.accessExpr}.ordinal")
                    is KneType.OBJECT -> add("${f.accessExpr}.handle")
                    else -> add(f.accessExpr)
                }
            }
        }.joinToString(", ")
        appendLine("${indent}        LISTPARAM_${dn}_ADD_HANDLE.invoke($addArgs)")
        appendLine("${indent}    }")
        appendLine("${indent}    _h")
        appendLine("${indent}}")
    }

    private data class DcFieldAccess(val accessExpr: String, val segName: String, val type: KneType)

    /** Recursively build flat field access paths for a DC, mapping each leaf field to its accessor chain. */
    private fun buildDcFieldsWithAccessPaths(dc: KneType.DATA_CLASS, objExpr: String): List<DcFieldAccess> =
        dc.fields.flatMap { f ->
            val access = "$objExpr.${f.name}"
            if (f.type is KneType.DATA_CLASS) {
                buildDcFieldsWithAccessPaths(f.type, access)
            } else {
                val safeName = access.replace(".", "_").removePrefix("_elem_")
                listOf(DcFieldAccess(access, "_${safeName}Seg", f.type))
            }
        }

    private fun StringBuilder.appendMapParamAlloc(indent: String, name: String, type: KneType.MAP, srcExpr: String = name) {
        appendLine("${indent}val ${name}KeysList = ${srcExpr}.keys.toList()")
        appendLine("${indent}val ${name}ValuesList = ${srcExpr}.values.toList()")
        appendListParamAlloc(indent, "${name}_keys", type.keyType, srcExpr = "${name}KeysList")
        appendListParamAlloc(indent, "${name}_values", type.valueType, srcExpr = "${name}ValuesList")
    }

    /** Generate the return-proxy for collection types (handles nullable: -1 = null). */
    private fun StringBuilder.appendCollectionReturnProxy(indent: String, fn: KneFunction, handleName: String) {
        val isNullable = fn.returnType is KneType.NULLABLE
        val inner = fn.returnType.unwrapCollection()
        when (inner) {
            is KneType.LIST -> appendListReturnProxy(indent, fn, handleName, inner.elementType, "List", isNullable)
            is KneType.SET -> appendListReturnProxy(indent, fn, handleName, inner.elementType, "Set", isNullable)
            is KneType.MAP -> appendMapReturnProxy(indent, fn, handleName, inner, isNullable)
            else -> {}
        }
    }

    private fun StringBuilder.appendListReturnProxy(indent: String, fn: KneFunction, handleName: String, elemType: KneType, collType: String, nullable: Boolean = false) {
        // List<DataClass> — opaque handle pattern
        if (elemType is KneType.DATA_CLASS) {
            val invokeArgs = buildClassInvokeArgsExpanded(fn)
            appendLine("${indent}val _listHandle = $handleName.invoke($invokeArgs) as Long")
            appendLine("${indent}KneRuntime.checkError()")
            if (nullable) appendLine("${indent}if (_listHandle == 0L) return null")
            appendLine("${indent}try {")
            appendLine("${indent}    val _size = LIST_${elemType.simpleName.uppercase()}_SIZE_HANDLE.invoke(_listHandle) as Int")
            appendLine("${indent}    val _list = ArrayList<${elemType.simpleName}>(_size)")
            // Allocate out-params for one DC element
            val flatFields = flattenDcFields(elemType, "out")
            appendLine("${indent}    Arena.ofConfined().use { dcArena ->")
            flatFields.forEach { (name, type) ->
                when (type) {
                    KneType.STRING, KneType.BYTE_ARRAY -> appendLine("${indent}        val $name = dcArena.allocate($STRING_BUF_SIZE.toLong())")
                    else -> appendLine("${indent}        val $name = dcArena.allocate(${type.ffmLayout})")
                }
            }
            appendLine("${indent}        for (_i in 0 until _size) {")
            // Build invoke args for get: handle, index, outParams
            val getArgs = buildList {
                add("_listHandle"); add("_i")
                flatFields.forEach { (name, type) ->
                    when (type) { KneType.STRING, KneType.BYTE_ARRAY -> { add(name); add("$STRING_BUF_SIZE") }; else -> add(name) }
                }
            }.joinToString(", ")
            appendLine("${indent}            LIST_${elemType.simpleName.uppercase()}_GET_HANDLE.invoke($getArgs)")
            if (dcHasCollectionFields(elemType)) appendDcCollectionFieldReads("${indent}            ", elemType, "out")
            appendLine("${indent}            _list.add(${buildDcCtorFromOutParams(elemType, "out")})")
            appendLine("${indent}        }")
            appendLine("${indent}    }")
            if (collType == "Set") appendLine("${indent}    return _list.toSet()")
            else appendLine("${indent}    return _list")
            appendLine("${indent}} finally {")
            appendLine("${indent}    LIST_${elemType.simpleName.uppercase()}_DISPOSE_HANDLE.invoke(_listHandle)")
            appendLine("${indent}}")
            return
        }
        when (elemType) {
            KneType.STRING -> {
                appendLine("${indent}val _outBuf = arena.allocate($STRING_BUF_SIZE.toLong())")
                val invokeArgs = buildClassInvokeArgsExpanded(fn) + ", _outBuf, $STRING_BUF_SIZE"
                appendLine("${indent}val _count = $handleName.invoke($invokeArgs) as Int")
                appendLine("${indent}KneRuntime.checkError()")
                if (nullable) appendLine("${indent}if (_count < 0) return null")
                appendLine("${indent}val _list = mutableListOf<String>()")
                appendLine("${indent}var _off = 0L")
                appendLine("${indent}repeat(_count) { _list.add(_outBuf.getString(_off)); _off += _list.last().toByteArray(Charsets.UTF_8).size + 1 }")
                if (collType == "Set") appendLine("${indent}return _list.toSet()")
                else appendLine("${indent}return _list")
            }
            else -> {
                val layout = KneType.collectionElementLayout(elemType)
                appendLine("${indent}val _outBuf = arena.allocate($layout, $MAX_COLLECTION_SIZE.toLong())")
                val invokeArgs = buildClassInvokeArgsExpanded(fn) + ", _outBuf, $MAX_COLLECTION_SIZE"
                appendLine("${indent}val _count = $handleName.invoke($invokeArgs) as Int")
                appendLine("${indent}KneRuntime.checkError()")
                if (nullable) appendLine("${indent}if (_count < 0) return null")
                appendCollectionElementRead(indent, elemType, "_count", collType)
            }
        }
    }

    private fun StringBuilder.appendCollectionElementRead(indent: String, elemType: KneType, countExpr: String, collType: String) {
        when (elemType) {
            KneType.BOOLEAN -> {
                appendLine("${indent}val _list = List($countExpr) { _outBuf.getAtIndex(JAVA_INT, it.toLong()) != 0 }")
            }
            is KneType.ENUM -> {
                appendLine("${indent}val _list = List($countExpr) { ${elemType.simpleName}.entries[_outBuf.getAtIndex(JAVA_INT, it.toLong())] }")
            }
            is KneType.OBJECT -> {
                appendLine("${indent}val _list = List($countExpr) { ${elemType.simpleName}.fromNativeHandle(_outBuf.getAtIndex(JAVA_LONG, it.toLong()) as Long) }")
            }
            KneType.BYTE_ARRAY -> {
                // ByteArray elements: each is a StableRef handle
                appendLine("${indent}val _list = List($countExpr) { _idx ->")
                appendLine("${indent}    KneRuntime.readByteArrayFromRef(_outBuf.getAtIndex(JAVA_LONG, _idx.toLong()) as Long)")
                appendLine("${indent}}")
            }
            is KneType.LIST, is KneType.SET, is KneType.MAP -> {
                // Nested collection: each element is a StableRef handle — read inner collection from each handle
                appendLine("${indent}val _list = List($countExpr) { _idx ->")
                appendLine("${indent}    val _innerHandle = _outBuf.getAtIndex(JAVA_LONG, _idx.toLong()) as Long")
                appendNestedCollectionRead("${indent}    ", elemType)
                appendLine("${indent}}")
            }
            else -> {
                val layout = KneType.collectionElementLayout(elemType)
                appendLine("${indent}val _list = List($countExpr) { _outBuf.getAtIndex($layout, it.toLong()) as ${elemType.jvmTypeName} }")
            }
        }
        if (collType == "Set") appendLine("${indent}return _list.toSet()")
        else appendLine("${indent}return _list")
    }

    /** Read a nested collection from its StableRef handle (for nested collections in List elements). */
    private fun StringBuilder.appendNestedCollectionRead(indent: String, elemType: KneType) {
        when (elemType) {
            is KneType.LIST, is KneType.SET -> {
                val innerElem = when (elemType) { is KneType.LIST -> elemType.elementType; is KneType.SET -> elemType.elementType; else -> KneType.INT }
                val key = suspendCollElemKey(innerElem)
                appendLine("${indent}Arena.ofConfined().use { _innerArena ->")
                if (innerElem == KneType.STRING) {
                    appendLine("${indent}    val _iBuf = _innerArena.allocate($STRING_BUF_SIZE.toLong())")
                    appendLine("${indent}    val _iCount = SUSPEND_READCOLL_${key.uppercase()}_HANDLE.invoke(_innerHandle, _iBuf, $STRING_BUF_SIZE) as Int")
                    appendLine("${indent}    val _inner = mutableListOf<String>()")
                    appendLine("${indent}    var _iOff = 0L")
                    appendLine("${indent}    repeat(_iCount) { _inner.add(_iBuf.getString(_iOff)); _iOff += _inner.last().toByteArray(Charsets.UTF_8).size + 1 }")
                } else {
                    val layout = KneType.collectionElementLayout(innerElem)
                    appendLine("${indent}    val _iBuf = _innerArena.allocate($layout, $MAX_COLLECTION_SIZE.toLong())")
                    appendLine("${indent}    val _iCount = SUSPEND_READCOLL_${key.uppercase()}_HANDLE.invoke(_innerHandle, _iBuf, $MAX_COLLECTION_SIZE) as Int")
                    when (innerElem) {
                        KneType.BOOLEAN -> appendLine("${indent}    val _inner = List(_iCount) { _iBuf.getAtIndex(JAVA_INT, it.toLong()) != 0 }")
                        is KneType.ENUM -> appendLine("${indent}    val _inner = List(_iCount) { ${innerElem.simpleName}.entries[_iBuf.getAtIndex(JAVA_INT, it.toLong())] }")
                        is KneType.OBJECT -> appendLine("${indent}    val _inner = List(_iCount) { ${innerElem.simpleName}.fromNativeHandle(_iBuf.getAtIndex(JAVA_LONG, it.toLong()) as Long) }")
                        else -> appendLine("${indent}    val _inner = List(_iCount) { _iBuf.getAtIndex($layout, it.toLong()) as ${innerElem.jvmTypeName} }")
                    }
                }
                if (elemType is KneType.SET) appendLine("${indent}    _inner.toSet()")
                else appendLine("${indent}    _inner as ${elemType.jvmTypeName}")
                appendLine("${indent}}")
            }
            is KneType.MAP -> {
                val kk = suspendCollElemKey(elemType.keyType)
                val vk = suspendCollElemKey(elemType.valueType)
                val isKeyString = elemType.keyType == KneType.STRING
                val isValString = elemType.valueType == KneType.STRING
                appendLine("${indent}Arena.ofConfined().use { _innerArena ->")
                if (isKeyString) appendLine("${indent}    val _kBuf = _innerArena.allocate($STRING_BUF_SIZE.toLong())")
                else appendLine("${indent}    val _kBuf = _innerArena.allocate(${KneType.collectionElementLayout(elemType.keyType)}, $MAX_COLLECTION_SIZE.toLong())")
                if (isValString) appendLine("${indent}    val _vBuf = _innerArena.allocate($STRING_BUF_SIZE.toLong())")
                else appendLine("${indent}    val _vBuf = _innerArena.allocate(${KneType.collectionElementLayout(elemType.valueType)}, $MAX_COLLECTION_SIZE.toLong())")
                val ksArg = if (isKeyString) "$STRING_BUF_SIZE" else "$MAX_COLLECTION_SIZE"
                val vsArg = if (isValString) "$STRING_BUF_SIZE" else "$MAX_COLLECTION_SIZE"
                appendLine("${indent}    val _iCount = SUSPEND_READMAP_${kk.uppercase()}_${vk.uppercase()}_HANDLE.invoke(_innerHandle, _kBuf, $ksArg, _vBuf, $vsArg) as Int")
                if (isKeyString) {
                    appendLine("${indent}    val _ks = mutableListOf<String>(); var _kOff = 0L")
                    appendLine("${indent}    repeat(_iCount) { _ks.add(_kBuf.getString(_kOff)); _kOff += _ks.last().toByteArray(Charsets.UTF_8).size + 1 }")
                } else {
                    appendLine("${indent}    val _ks = List(_iCount) { _kBuf.getAtIndex(${KneType.collectionElementLayout(elemType.keyType)}, it.toLong()) as ${elemType.keyType.jvmTypeName} }")
                }
                if (isValString) {
                    appendLine("${indent}    val _vs = mutableListOf<String>(); var _vOff = 0L")
                    appendLine("${indent}    repeat(_iCount) { _vs.add(_vBuf.getString(_vOff)); _vOff += _vs.last().toByteArray(Charsets.UTF_8).size + 1 }")
                } else {
                    appendLine("${indent}    val _vs = List(_iCount) { _vBuf.getAtIndex(${KneType.collectionElementLayout(elemType.valueType)}, it.toLong()) as ${elemType.valueType.jvmTypeName} }")
                }
                appendLine("${indent}    _ks.zip(_vs).toMap()")
                appendLine("${indent}}")
            }
            else -> appendLine("${indent}_innerHandle") // fallback
        }
    }

    private fun StringBuilder.appendMapReturnProxy(indent: String, fn: KneFunction, handleName: String, type: KneType.MAP, nullable: Boolean = false) {
        val kLayout = KneType.collectionElementLayout(type.keyType)
        val vLayout = KneType.collectionElementLayout(type.valueType)
        val isKeyString = type.keyType == KneType.STRING
        val isValString = type.valueType == KneType.STRING
        if (isKeyString) appendLine("${indent}val _keysBuf = arena.allocate($STRING_BUF_SIZE.toLong())")
        else appendLine("${indent}val _keysBuf = arena.allocate($kLayout, $MAX_COLLECTION_SIZE.toLong())")
        if (isValString) appendLine("${indent}val _valuesBuf = arena.allocate($STRING_BUF_SIZE.toLong())")
        else appendLine("${indent}val _valuesBuf = arena.allocate($vLayout, $MAX_COLLECTION_SIZE.toLong())")

        val invokeArgs = buildList {
            add("handle")
            fn.params.forEach { p -> addAll(buildExpandedInvokeArgs(p)) }
            add("_keysBuf")
            if (isKeyString) add("$STRING_BUF_SIZE")
            add("_valuesBuf")
            if (isValString) add("$STRING_BUF_SIZE")
            add("$MAX_COLLECTION_SIZE")
        }.joinToString(", ")

        appendLine("${indent}val _count = $handleName.invoke($invokeArgs) as Int")
        appendLine("${indent}KneRuntime.checkError()")
        if (nullable) appendLine("${indent}if (_count < 0) return null")
        appendLine("${indent}val _map = mutableMapOf<${type.keyType.jvmTypeName}, ${type.valueType.jvmTypeName}>()")
        // Read keys
        if (isKeyString) {
            appendLine("${indent}val _keys = mutableListOf<String>()")
            appendLine("${indent}var _kOff = 0L")
            appendLine("${indent}repeat(_count) { _keys.add(_keysBuf.getString(_kOff)); _kOff += _keys.last().toByteArray(Charsets.UTF_8).size + 1 }")
        } else {
            appendMapElementRead(indent, "_keys", type.keyType, "_count", "_keysBuf")
        }
        // Read values
        if (isValString) {
            appendLine("${indent}val _values = mutableListOf<String>()")
            appendLine("${indent}var _vOff = 0L")
            appendLine("${indent}repeat(_count) { _values.add(_valuesBuf.getString(_vOff)); _vOff += _values.last().toByteArray(Charsets.UTF_8).size + 1 }")
        } else {
            appendMapElementRead(indent, "_values", type.valueType, "_count", "_valuesBuf")
        }
        appendLine("${indent}repeat(_count) { _map[_keys[it]] = _values[it] }")
        appendLine("${indent}return _map")
    }

    private fun StringBuilder.appendMapElementRead(indent: String, varName: String, elemType: KneType, countExpr: String, bufExpr: String) {
        val layout = KneType.collectionElementLayout(elemType)
        when (elemType) {
            KneType.BOOLEAN -> appendLine("${indent}val $varName = List($countExpr) { $bufExpr.getAtIndex(JAVA_INT, it.toLong()) != 0 }")
            is KneType.ENUM -> appendLine("${indent}val $varName = List($countExpr) { ${elemType.simpleName}.entries[$bufExpr.getAtIndex(JAVA_INT, it.toLong())] }")
            else -> appendLine("${indent}val $varName = List($countExpr) { $bufExpr.getAtIndex($layout, it.toLong()) as ${elemType.jvmTypeName} }")
        }
    }

    /**
     * Emits a handle invocation with checkError() after the call.
     * For value-returning functions, stores result in a local var, checks error, then returns.
     */
    private fun StringBuilder.appendCallAndReturn(
        indent: String,
        returnType: KneType,
        handleName: String,
        invokeArgs: String,
    ) {
        when (returnType) {
            KneType.UNIT -> {
                appendLine("${indent}$handleName.invoke($invokeArgs)")
                appendLine("${indent}KneRuntime.checkError()")
            }
            KneType.STRING -> {
                appendStringReadWithRetry(indent, handleName, invokeArgs)
                appendLine("${indent}return _buf.getString(0)")
            }
            KneType.BYTE_ARRAY -> {
                appendStringReadWithRetry(indent, handleName, invokeArgs)
                appendLine("${indent}return _buf.asSlice(0, _len.toLong()).toArray(JAVA_BYTE)")
            }
            KneType.BOOLEAN -> {
                appendLine("${indent}val _r = $handleName.invoke($invokeArgs) as Int")
                appendLine("${indent}KneRuntime.checkError()")
                appendLine("${indent}return _r != 0")
            }
            KneType.INT -> {
                appendLine("${indent}val _r = $handleName.invoke($invokeArgs) as Int")
                appendLine("${indent}KneRuntime.checkError()")
                appendLine("${indent}return _r")
            }
            KneType.LONG -> {
                appendLine("${indent}val _r = $handleName.invoke($invokeArgs) as Long")
                appendLine("${indent}KneRuntime.checkError()")
                appendLine("${indent}return _r")
            }
            KneType.DOUBLE -> {
                appendLine("${indent}val _r = $handleName.invoke($invokeArgs) as Double")
                appendLine("${indent}KneRuntime.checkError()")
                appendLine("${indent}return _r")
            }
            KneType.FLOAT -> {
                appendLine("${indent}val _r = $handleName.invoke($invokeArgs) as Float")
                appendLine("${indent}KneRuntime.checkError()")
                appendLine("${indent}return _r")
            }
            KneType.BYTE -> {
                appendLine("${indent}val _r = $handleName.invoke($invokeArgs) as Byte")
                appendLine("${indent}KneRuntime.checkError()")
                appendLine("${indent}return _r")
            }
            KneType.SHORT -> {
                appendLine("${indent}val _r = $handleName.invoke($invokeArgs) as Short")
                appendLine("${indent}KneRuntime.checkError()")
                appendLine("${indent}return _r")
            }
            is KneType.OBJECT, is KneType.INTERFACE -> {
                val simpleName = when (returnType) {
                    is KneType.OBJECT -> returnType.simpleName
                    is KneType.INTERFACE -> returnType.simpleName
                    else -> error("unreachable")
                }
                appendLine("${indent}val resultHandle = $handleName.invoke($invokeArgs) as Long")
                appendLine("${indent}KneRuntime.checkError()")
                appendLine("${indent}return $simpleName.fromNativeHandle(resultHandle)")
            }
            is KneType.ENUM -> {
                appendLine("${indent}val _r = $handleName.invoke($invokeArgs) as Int")
                appendLine("${indent}KneRuntime.checkError()")
                appendLine("${indent}return ${returnType.simpleName}.entries[_r]")
            }
            is KneType.NULLABLE -> appendNullableCallAndReturn(indent, returnType, handleName, invokeArgs)
            is KneType.FUNCTION -> {
                val fnId = fnInvokeId(returnType)
                appendLine("${indent}val _fnHandle = $handleName.invoke($invokeArgs) as Long")
                appendLine("${indent}KneRuntime.checkError()")
                // Wrap in a lambda that invokes the native function via the invoke bridge
                val lambdaParams = returnType.paramTypes.mapIndexed { i, _ -> "_p$i" }.joinToString(", ")
                val invokeHandleName = "INVOKE_FN_${fnId.uppercase()}_HANDLE"
                val invokeCallArgs = buildList {
                    add("_fnHandle")
                    returnType.paramTypes.forEachIndexed { i, t ->
                        when (t) {
                            KneType.STRING -> add("Arena.ofAuto().allocateFrom(_p$i)")
                            KneType.BYTE_ARRAY -> { add("run { val _a = Arena.ofAuto(); val _s = _a.allocate(_p$i.size.toLong()); MemorySegment.copy(_p$i, 0, _s, JAVA_BYTE, 0, _p$i.size); _s }"); add("_p$i.size") }
                            KneType.BOOLEAN -> add("if (_p$i) 1 else 0")
                            is KneType.ENUM -> add("_p$i.ordinal")
                            is KneType.OBJECT -> add("_p$i.handle")
                            else -> add("_p$i")
                        }
                    }
                }.joinToString(", ")
                val retConvert = when (returnType.returnType) {
                    KneType.UNIT -> ""
                    KneType.BOOLEAN -> " != 0"
                    KneType.STRING -> ".let { KneRuntime.readStringFromRef(it as Long) }"
                    KneType.BYTE_ARRAY -> ".let { KneRuntime.readByteArrayFromRef(it as Long) }"
                    is KneType.ENUM -> ".let { ${returnType.returnType.simpleName}.entries[it as Int] }"
                    is KneType.OBJECT -> ".let { ${returnType.returnType.simpleName}.fromNativeHandle(it as Long) }"
                    else -> ""
                }
                if (returnType.returnType == KneType.UNIT) {
                    appendLine("${indent}return { $lambdaParams -> $invokeHandleName.invoke($invokeCallArgs); Unit }")
                } else {
                    val castExpr = when (returnType.returnType) {
                        KneType.INT -> " as Int"
                        KneType.LONG -> " as Long"
                        KneType.DOUBLE -> " as Double"
                        KneType.FLOAT -> " as Float"
                        KneType.SHORT -> " as Short"
                        KneType.BYTE -> " as Byte"
                        KneType.BOOLEAN, is KneType.ENUM -> " as Int"
                        KneType.STRING, KneType.BYTE_ARRAY, is KneType.OBJECT -> " as Long"
                        else -> ""
                    }
                    appendLine("${indent}return { $lambdaParams -> ($invokeHandleName.invoke($invokeCallArgs)$castExpr)$retConvert }")
                }
            }
            is KneType.DATA_CLASS -> {
                // DATA_CLASS returns are handled separately in appendMethodProxy
                appendLine("${indent}$handleName.invoke($invokeArgs)")
                appendLine("${indent}KneRuntime.checkError()")
            }
            is KneType.LIST, is KneType.SET, is KneType.MAP -> {
                // Collection returns are handled separately in appendCollectionReturnProxy
                appendLine("${indent}$handleName.invoke($invokeArgs)")
                appendLine("${indent}KneRuntime.checkError()")
            }
            is KneType.FLOW -> {
                // Flow returns are handled separately in appendFlowMethodProxy
                appendLine("${indent}$handleName.invoke($invokeArgs)")
                appendLine("${indent}KneRuntime.checkError()")
            }
        }
    }

    /**
     * Emits the retry-read pattern for string output buffers (JetBrains-style):
     * allocate initial buffer, call handle, if truncated retry with exact size.
     * After this call, generated locals `_buf` and `_len` are in scope.
     */
    private fun StringBuilder.appendStringReadWithRetry(
        indent: String,
        handleName: String,
        invokeArgs: String,
    ) {
        val bufArgs = if (invokeArgs.isEmpty()) "_buf, _bufSize" else "$invokeArgs, _buf, _bufSize"
        appendLine("${indent}var _bufSize = $STRING_BUF_SIZE")
        appendLine("${indent}var _buf = arena.allocate(_bufSize.toLong())")
        appendLine("${indent}val _len = $handleName.invoke($bufArgs) as Int")
        appendLine("${indent}KneRuntime.checkError()")
        appendLine("${indent}if (_len >= _bufSize) {")
        appendLine("${indent}    _bufSize = _len + 1")
        appendLine("${indent}    _buf = arena.allocate(_bufSize.toLong())")
        appendLine("${indent}    $handleName.invoke($bufArgs)")
        appendLine("${indent}    KneRuntime.checkError()")
        appendLine("${indent}}")
    }

    private fun StringBuilder.appendNullableCallAndReturn(
        indent: String,
        type: KneType.NULLABLE,
        handleName: String,
        invokeArgs: String,
    ) {
        when (type.inner) {
            KneType.STRING -> {
                appendStringReadWithRetry(indent, handleName, invokeArgs)
                appendLine("${indent}return if (_len < 0) null else _buf.getString(0)")
            }
            KneType.BOOLEAN -> {
                appendLine("${indent}val raw = $handleName.invoke($invokeArgs) as Int")
                appendLine("${indent}KneRuntime.checkError()")
                appendLine("${indent}return if (raw < 0) null else raw != 0")
            }
            KneType.INT -> {
                appendLine("${indent}val raw = $handleName.invoke($invokeArgs) as Long")
                appendLine("${indent}KneRuntime.checkError()")
                appendLine("${indent}return if (raw == Long.MIN_VALUE) null else raw.toInt()")
            }
            KneType.LONG -> {
                appendLine("${indent}val raw = $handleName.invoke($invokeArgs) as Long")
                appendLine("${indent}KneRuntime.checkError()")
                appendLine("${indent}return if (raw == Long.MIN_VALUE) null else raw")
            }
            KneType.SHORT -> {
                appendLine("${indent}val raw = $handleName.invoke($invokeArgs) as Int")
                appendLine("${indent}KneRuntime.checkError()")
                appendLine("${indent}return if (raw == Int.MIN_VALUE) null else raw.toShort()")
            }
            KneType.BYTE -> {
                appendLine("${indent}val raw = $handleName.invoke($invokeArgs) as Int")
                appendLine("${indent}KneRuntime.checkError()")
                appendLine("${indent}return if (raw == Int.MIN_VALUE) null else raw.toByte()")
            }
            KneType.FLOAT -> {
                appendLine("${indent}val raw = $handleName.invoke($invokeArgs) as Long")
                appendLine("${indent}KneRuntime.checkError()")
                appendLine("${indent}return if (raw == Long.MIN_VALUE) null else Float.fromBits(raw.toInt())")
            }
            KneType.DOUBLE -> {
                appendLine("${indent}val raw = $handleName.invoke($invokeArgs) as Long")
                appendLine("${indent}KneRuntime.checkError()")
                appendLine("${indent}return if (raw == Long.MIN_VALUE) null else Double.fromBits(raw)")
            }
            is KneType.OBJECT -> {
                appendLine("${indent}val resultHandle = $handleName.invoke($invokeArgs) as Long")
                appendLine("${indent}KneRuntime.checkError()")
                appendLine("${indent}return if (resultHandle == 0L) null else ${type.inner.simpleName}.fromNativeHandle(resultHandle)")
            }
            is KneType.ENUM -> {
                appendLine("${indent}val raw = $handleName.invoke($invokeArgs) as Int")
                appendLine("${indent}KneRuntime.checkError()")
                appendLine("${indent}return if (raw < 0) null else ${type.inner.simpleName}.entries[raw]")
            }
            KneType.UNIT -> {
                appendLine("${indent}$handleName.invoke($invokeArgs)")
                appendLine("${indent}KneRuntime.checkError()")
            }
            else -> {
                appendLine("${indent}val _r = $handleName.invoke($invokeArgs)")
                appendLine("${indent}KneRuntime.checkError()")
                appendLine("${indent}return _r")
            }
        }
    }

    private fun StringBuilder.appendSetterInvoke(indent: String, handleName: String, type: KneType, handleArg: String?) {
        val prefix = if (handleArg != null) "$handleArg, " else ""
        when (type) {
            KneType.STRING -> {
                appendLine("${indent}Arena.ofConfined().use { arena ->")
                appendLine("${indent}    val valueSeg = arena.allocateFrom(value)")
                appendLine("${indent}    $handleName.invoke(${prefix}valueSeg)")
                appendLine("${indent}    KneRuntime.checkError()")
                appendLine("${indent}}")
            }
            KneType.BOOLEAN -> {
                appendLine("${indent}$handleName.invoke(${prefix}if (value) 1 else 0)")
                appendLine("${indent}KneRuntime.checkError()")
            }
            is KneType.OBJECT -> {
                appendLine("${indent}$handleName.invoke(${prefix}value.handle)")
                appendLine("${indent}KneRuntime.checkError()")
            }
            is KneType.ENUM -> {
                appendLine("${indent}$handleName.invoke(${prefix}value.ordinal)")
                appendLine("${indent}KneRuntime.checkError()")
            }
            is KneType.NULLABLE -> appendNullableSetterInvoke(indent, handleName, type, handleArg)
            else -> {
                appendLine("${indent}$handleName.invoke(${prefix}value)")
                appendLine("${indent}KneRuntime.checkError()")
            }
        }
    }

    private fun StringBuilder.appendNullableSetterInvoke(indent: String, handleName: String, type: KneType.NULLABLE, handleArg: String?) {
        val prefix = if (handleArg != null) "$handleArg, " else ""
        when (type.inner) {
            KneType.STRING -> {
                appendLine("${indent}Arena.ofConfined().use { arena ->")
                appendLine("${indent}    val valueSeg = if (value != null) arena.allocateFrom(value) else MemorySegment.NULL")
                appendLine("${indent}    $handleName.invoke(${prefix}valueSeg)")
                appendLine("${indent}    KneRuntime.checkError()")
                appendLine("${indent}}")
            }
            else -> {
                val valueExpr = when (type.inner) {
                    KneType.BOOLEAN -> "if (value == null) -1 else if (value) 1 else 0"
                    KneType.INT -> "value?.toLong() ?: Long.MIN_VALUE"
                    KneType.LONG -> "value ?: Long.MIN_VALUE"
                    KneType.SHORT -> "value?.toInt() ?: Int.MIN_VALUE"
                    KneType.BYTE -> "value?.toInt() ?: Int.MIN_VALUE"
                    KneType.FLOAT -> "if (value != null) value.toRawBits().toLong() else Long.MIN_VALUE"
                    KneType.DOUBLE -> "if (value != null) value.toRawBits() else Long.MIN_VALUE"
                    is KneType.OBJECT -> "value?.handle ?: 0L"
                    is KneType.ENUM -> "value?.ordinal ?: -1"
                    else -> "value"
                }
                appendLine("${indent}$handleName.invoke(${prefix}$valueExpr)")
                appendLine("${indent}KneRuntime.checkError()")
            }
        }
    }
}
