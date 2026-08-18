package dev.nucleusframework.nna.plugin.analysis

import dev.nucleusframework.nna.plugin.ir.KneClass
import dev.nucleusframework.nna.plugin.ir.KneConstructor
import dev.nucleusframework.nna.plugin.ir.KneDataClass
import dev.nucleusframework.nna.plugin.ir.KneEnum
import dev.nucleusframework.nna.plugin.ir.KneEnumEntry
import dev.nucleusframework.nna.plugin.ir.KneFunction
import dev.nucleusframework.nna.plugin.ir.KneInterface
import dev.nucleusframework.nna.plugin.ir.KneModule
import dev.nucleusframework.nna.plugin.ir.KneParam
import dev.nucleusframework.nna.plugin.ir.KneProperty
import dev.nucleusframework.nna.plugin.ir.KneType
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironmentMode
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreProjectEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFunctionType
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.KtTypeElement
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType
import java.io.File

/**
 * AST-based parser using Kotlin PSI from kotlin-compiler-embeddable.
 * Runs inside an isolated classloader (Gradle Worker API) to avoid conflicts
 * with Gradle's own Kotlin runtime.
 */
class PsiSourceParser {

    fun parse(files: Collection<File>, libName: String, commonFiles: Collection<File>): KneModule {
        val disposable = Disposer.newDisposable("KnePsiParser")
        try {
            // IntelliJ PathManager requires idea.home.path
            val tmpHome = java.nio.file.Files.createTempDirectory("kne-psi").toFile()
            tmpHome.resolve("product-info.json").writeText("""{"buildNumber":"999.SNAPSHOT"}""")
            System.setProperty("idea.home.path", tmpHome.absolutePath)

            val appEnv = KotlinCoreApplicationEnvironment.create(disposable, KotlinCoreApplicationEnvironmentMode.Production)
            appEnv.registerFileType(KotlinFileType.INSTANCE, "kt")
            appEnv.registerParserDefinition(org.jetbrains.kotlin.parsing.KotlinParserDefinition())
            val projEnv = KotlinCoreProjectEnvironment(disposable, appEnv)
            val psiFactory = KtPsiFactory(projEnv.project)

            val ktFiles = files.filter { it.extension == "kt" }
            val commonKtFiles = commonFiles.filter { it.extension == "kt" }

            // Phase 1: prescan type names
            val knownClasses = mutableMapOf<String, String>()
            val knownEnums = mutableMapOf<String, String>()
            val knownInterfaces = mutableMapOf<String, String>()
            val rawDataClasses = mutableMapOf<String, Triple<String, KtClass, String>>()
            val commonDataClassNames = mutableSetOf<String>()
            val commonClassNames = mutableSetOf<String>()
            val commonInterfaceNames = mutableSetOf<String>()
            // Track modifiers for classes found during prescan
            val sealedClassNames = mutableSetOf<String>()
            val openClassNames = mutableSetOf<String>()
            val abstractClassNames = mutableSetOf<String>()

            fun prescanDeclarations(declarations: List<KtDeclaration>, pkg: String, parentFq: String? = null) {
                for (decl in declarations) {
                    if (decl !is KtClass) continue
                    val name = decl.name ?: continue
                    val fq = if (parentFq != null) "$parentFq.$name"
                        else if (pkg.isNotEmpty()) "$pkg.$name" else name
                    when {
                        decl.isEnum() -> knownEnums[name] = fq
                        decl.isData() -> rawDataClasses[name] = Triple(fq, decl, pkg)
                        decl.isInterface() -> knownInterfaces[name] = fq
                        else -> {
                            knownClasses[name] = fq
                            if (decl.hasModifier(KtTokens.SEALED_KEYWORD)) sealedClassNames.add(name)
                            if (decl.hasModifier(KtTokens.OPEN_KEYWORD)) openClassNames.add(name)
                            if (decl.hasModifier(KtTokens.ABSTRACT_KEYWORD)) abstractClassNames.add(name)
                            prescanDeclarations(decl.declarations.toList(), pkg, fq)
                        }
                    }
                }
            }

            val commonFileSet = commonKtFiles.toSet()
            for (file in commonKtFiles + ktFiles) {
                val ktFile = psiFactory.createFile(file.name, file.readText())
                val pkg = ktFile.packageFqName.asString()
                val classKeysBefore = knownClasses.keys.toSet()
                val ifaceKeysBefore = knownInterfaces.keys.toSet()
                val dcKeysBefore = rawDataClasses.keys.toSet()
                prescanDeclarations(ktFile.declarations.toList(), pkg)
                if (file in commonFileSet) {
                    (rawDataClasses.keys - dcKeysBefore).forEach { commonDataClassNames.add(it) }
                    (knownInterfaces.keys - ifaceKeysBefore).forEach { commonInterfaceNames.add(it) }
                    (knownClasses.keys - classKeysBefore).forEach { commonClassNames.add(it) }
                }
            }

            // Phase 2: resolve data class fields iteratively
            val knownDataClasses = mutableMapOf<String, Pair<String, List<KneParam>>>()
            var changed = true
            while (changed) {
                changed = false
                for ((name, info) in rawDataClasses) {
                    if (name in knownDataClasses) continue
                    val fields = resolveDataClassFields(info.second, knownEnums, knownClasses, knownDataClasses)
                    if (fields != null) { knownDataClasses[name] = Pair(info.first, fields); changed = true }
                }
            }
            for ((name, info) in rawDataClasses) {
                if (name !in knownDataClasses) knownClasses[name] = info.first
            }

            val typeMaps = TypeMaps(knownClasses, knownEnums, knownDataClasses, knownInterfaces)

            // Phase 3: parse all files (deduplicate by fqName)
            val classMap = mutableMapOf<String, KneClass>()
            val dataClassMap = mutableMapOf<String, KneDataClass>()
            val enumMap = mutableMapOf<String, KneEnum>()
            val functionMap = mutableMapOf<String, KneFunction>()
            val interfaceMap = mutableMapOf<String, KneInterface>()
            val packages = mutableSetOf<String>()

            for (file in commonKtFiles + ktFiles) {
                val isCommonFile = file in commonFileSet
                val ktFile = psiFactory.createFile(file.name, file.readText())
                val pkg = ktFile.packageFqName.asString()
                if (pkg.isNotEmpty()) packages.add(pkg)

                fun processDeclarations(declarations: List<KtDeclaration>, parentSimpleName: String?, isTopLevel: Boolean) {
                    for (decl in declarations) {
                        if (decl.isPrivateOrInternal()) continue
                        when {
                            decl is KtClass && decl.isEnum() -> parseEnum(decl, pkg, typeMaps)?.let { enumMap.putIfAbsent(it.fqName, it) }
                            decl is KtClass && decl.isData() -> {
                                val name = decl.name ?: continue
                                val dcInfo = knownDataClasses[name] ?: continue
                                val fq = dcInfo.first
                                dataClassMap.putIfAbsent(fq, KneDataClass(name, fq, dcInfo.second, isCommon = name in commonDataClassNames))
                            }
                            decl is KtClass && decl.isInterface() -> {
                                val iface = parseInterface(decl, pkg, typeMaps, isCommon = isCommonFile) ?: continue
                                interfaceMap.putIfAbsent(iface.fqName, iface)
                            }
                            decl is KtClass -> {
                                val cls = parseClass(decl, pkg, typeMaps, isCommon = isCommonFile) ?: continue
                                val correctFq = knownClasses[decl.name ?: ""] ?: cls.fqName
                                val qualifiedCls = if (parentSimpleName != null) {
                                    cls.copy(simpleName = "${parentSimpleName}_${cls.simpleName}", fqName = correctFq)
                                } else cls.copy(fqName = correctFq)
                                classMap.putIfAbsent(qualifiedCls.fqName, qualifiedCls)
                                // Only recurse for nested classes, not into class body
                                processDeclarations(decl.declarations.toList(), qualifiedCls.simpleName, false)
                            }
                            // Top-level functions (including extension functions)
                            isTopLevel && decl is KtNamedFunction -> {
                                val fn = parseFunction(decl, typeMaps) ?: continue
                                // Use a unique key for extension functions to avoid collisions
                                val key = if (fn.isExtension && fn.receiverType != null) {
                                    val receiverName = when (val rt = fn.receiverType) {
                                        is KneType.OBJECT -> rt.simpleName
                                        is KneType.INTERFACE -> rt.simpleName
                                        else -> rt.jvmTypeName
                                    }
                                    "${receiverName}.${fn.name}"
                                } else fn.name
                                functionMap.putIfAbsent(key, fn)
                            }
                        }
                    }
                }
                processDeclarations(ktFile.declarations.toList(), null, true)
            }

            // Ensure common data classes are present even if only defined in commonMain
            for (name in commonDataClassNames) {
                val dcInfo = knownDataClasses[name] ?: continue
                dataClassMap.putIfAbsent(dcInfo.first, KneDataClass(name, dcInfo.first, dcInfo.second, isCommon = true))
            }

            tmpHome.deleteRecursively()

            return KneModule(libName, packages, classMap.values.toList(), dataClassMap.values.toList(), enumMap.values.toList(), functionMap.values.toList(), interfaceMap.values.toList())
        } finally {
            Disposer.dispose(disposable)
        }
    }

    private data class TypeMaps(
        val classes: Map<String, String>,
        val enums: Map<String, String>,
        val dataClasses: Map<String, Pair<String, List<KneParam>>> = emptyMap(),
        val interfaces: Map<String, String> = emptyMap(),
    )

    private fun resolveDataClassFields(
        ktClass: KtClass, knownEnums: Map<String, String>,
        knownClasses: Map<String, String>, knownDataClasses: Map<String, Pair<String, List<KneParam>>>,
    ): List<KneParam>? {
        val params = ktClass.primaryConstructor?.valueParameters ?: return null
        val fields = mutableListOf<KneParam>()
        for (param in params) {
            if (!param.hasValOrVar()) continue
            val name = param.name ?: return null
            val type = resolveType(param.typeReference, knownEnums, knownClasses, knownDataClasses) ?: return null
            fields.add(KneParam(name, type))
        }
        return if (fields.isNotEmpty()) fields else null
    }

    private fun parseClass(ktClass: KtClass, pkg: String, typeMaps: TypeMaps, isCommon: Boolean = false): KneClass? {
        val name = ktClass.name ?: return null
        val fq = if (pkg.isNotEmpty()) "$pkg.$name" else name
        val rawCtorParams = ktClass.primaryConstructor?.valueParameters ?: emptyList()
        val ctorParams = rawCtorParams.mapNotNull { param ->
            val pName = param.name ?: return@mapNotNull null
            val type = resolveTypeFromMaps(param.typeReference, typeMaps) ?: return@mapNotNull null
            KneParam(pName, type, hasDefault = param.hasDefaultValue())
        }
        // Constructor val/var params are properties — collect them for getter/setter generation
        val ctorProperties = rawCtorParams.filter { it.hasValOrVar() }.mapNotNull { param ->
            val pName = param.name ?: return@mapNotNull null
            val type = resolveTypeFromMaps(param.typeReference, typeMaps) ?: return@mapNotNull null
            KneProperty(pName, type, param.isMutable)
        }

        // Extract modifiers
        val isOpen = ktClass.hasModifier(KtTokens.OPEN_KEYWORD)
        val isAbstract = ktClass.hasModifier(KtTokens.ABSTRACT_KEYWORD)
        val isSealed = ktClass.hasModifier(KtTokens.SEALED_KEYWORD)

        // Extract superclass and implemented interfaces
        var superClass: String? = null
        val implementedInterfaces = mutableListOf<String>()
        for (entry in ktClass.superTypeListEntries) {
            val typeName = entry.typeReference?.text?.substringBefore("<")?.trim() ?: continue
            when {
                typeName in typeMaps.interfaces -> {
                    typeMaps.interfaces[typeName]?.let { implementedInterfaces.add(it) }
                }
                typeName in typeMaps.classes -> {
                    if (superClass == null) superClass = typeMaps.classes[typeName]
                }
            }
        }

        // Collect sealed subclasses (nested within sealed class, so FQ = parentFQ.SubName)
        val sealedSubclasses = if (isSealed) {
            ktClass.declarations.filterIsInstance<KtClass>().mapNotNull { sub ->
                val subName = sub.name ?: return@mapNotNull null
                "$fq.$subName"
            }
        } else emptyList()

        val methods = mutableListOf<KneFunction>()
        val properties = mutableListOf<KneProperty>()
        val companionMethods = mutableListOf<KneFunction>()
        val companionProperties = mutableListOf<KneProperty>()
        val ctorParamNames = ctorParams.map { it.name }.toSet()

        // Collect body method names first to detect manual getters that would clash with ctor properties
        val bodyMethodNames = ktClass.declarations
            .filterIsInstance<KtNamedFunction>()
            .filter { !it.isPrivateOrInternal() }
            .mapNotNull { it.name }
            .toSet()

        // Add constructor val/var properties, but skip those with explicit getter methods in the body
        ctorProperties.forEach { prop ->
            val getterName = "get${prop.name.replaceFirstChar { it.uppercaseChar() }}"
            if (getterName !in bodyMethodNames) {
                properties.add(prop)
            }
        }

        for (decl in ktClass.declarations) {
            if (decl.isPrivateOrInternal()) continue
            when (decl) {
                is KtNamedFunction -> {
                    if (decl.name?.startsWith("_") == true) continue
                    parseFunction(decl, typeMaps)?.let { methods.add(it) }
                }
                is KtProperty -> {
                    val propName = decl.name ?: continue
                    if (propName in ctorParamNames) continue
                    parseProperty(decl, typeMaps)?.let { properties.add(it) }
                }
                is KtObjectDeclaration -> if (decl.isCompanion()) {
                    for (cd in decl.declarations) {
                        if (cd.isPrivateOrInternal()) continue
                        when (cd) {
                            is KtNamedFunction -> parseFunction(cd, typeMaps)?.let { companionMethods.add(it) }
                            is KtProperty -> parseProperty(cd, typeMaps)?.let { companionProperties.add(it) }
                        }
                    }
                }
            }
        }
        return KneClass(
            name, fq, KneConstructor(ctorParams), methods, properties, companionMethods, companionProperties,
            isOpen = isOpen, isAbstract = isAbstract, isSealed = isSealed,
            superClass = superClass, interfaces = implementedInterfaces, sealedSubclasses = sealedSubclasses,
            isCommon = isCommon,
        )
    }

    private fun parseInterface(ktClass: KtClass, pkg: String, typeMaps: TypeMaps, isCommon: Boolean = false): KneInterface? {
        val name = ktClass.name ?: return null
        val fq = if (pkg.isNotEmpty()) "$pkg.$name" else name
        val methods = mutableListOf<KneFunction>()
        val properties = mutableListOf<KneProperty>()
        val superInterfaces = mutableListOf<String>()

        // Extract super interfaces
        for (entry in ktClass.superTypeListEntries) {
            val typeName = entry.typeReference?.text?.substringBefore("<")?.trim() ?: continue
            typeMaps.interfaces[typeName]?.let { superInterfaces.add(it) }
        }

        for (decl in ktClass.declarations) {
            if (decl.isPrivateOrInternal()) continue
            when (decl) {
                is KtNamedFunction -> {
                    if (decl.name?.startsWith("_") == true) continue
                    parseFunction(decl, typeMaps)?.let { methods.add(it) }
                }
                is KtProperty -> parseProperty(decl, typeMaps)?.let { properties.add(it) }
            }
        }
        return KneInterface(name, fq, methods, properties, superInterfaces, isCommon = isCommon)
    }

    private fun parseEnum(ktClass: KtClass, pkg: String, typeMaps: TypeMaps): KneEnum? {
        val name = ktClass.name ?: return null
        val fq = if (pkg.isNotEmpty()) "$pkg.$name" else name

        // `declarations` can omit enum entries depending on PSI (stubs vs. AST).
        // Raw body children always include KtEnumEntry nodes created by the parser.
        val psiEntries = ktClass.body?.children?.filterIsInstance<KtEnumEntry>().orEmpty().ifEmpty {
            ktClass.declarations.filterIsInstance<KtEnumEntry>()
        }
        val entries = parseEnumEntries(psiEntries)

        val rawCtorParams = ktClass.primaryConstructor?.valueParameters ?: emptyList()
        val ctorParams = rawCtorParams.map { param ->
            val pName = param.name ?: return KneEnum(name, fq, entries)
            val type = resolveTypeFromMaps(param.typeReference, typeMaps)
                ?: return KneEnum(name, fq, entries)
            KneParam(pName, type, hasDefault = param.hasDefaultValue())
        }

        return KneEnum(name, fq, entries, ctorParams)
    }

    private fun parseEnumEntries(psiEntries: List<KtEnumEntry>): List<KneEnumEntry> =
        psiEntries.mapNotNull { entry ->
            val entryName = entry.name ?: return@mapNotNull null
            val args = entry.superTypeListEntries
                .filterIsInstance<KtSuperTypeCallEntry>()
                .firstOrNull()
                ?.valueArgumentList
                ?.arguments
                ?.map { it.getArgumentExpression()?.text ?: return@mapNotNull null }
                ?: emptyList()
            KneEnumEntry(entryName, args)
        }

    private fun parseFunction(fn: KtNamedFunction, typeMaps: TypeMaps): KneFunction? {
        val name = fn.name ?: return null
        if (name == "init") return null
        val isSuspend = fn.hasModifier(KtTokens.SUSPEND_KEYWORD)
        val isOverride = fn.hasModifier(KtTokens.OVERRIDE_KEYWORD)
        val params = fn.valueParameters.mapNotNull { param ->
            val pName = param.name ?: return@mapNotNull null
            val type = resolveTypeFromMaps(param.typeReference, typeMaps) ?: return@mapNotNull null
            KneParam(pName, type)
        }
        val returnType = fn.typeReference?.let { resolveTypeFromMaps(it, typeMaps) } ?: KneType.UNIT

        // Extension function detection
        val receiverTypeRef = fn.receiverTypeReference
        val receiverType = if (receiverTypeRef != null) resolveTypeFromMaps(receiverTypeRef, typeMaps) else null
        val isExtension = receiverType != null

        return KneFunction(name, params, returnType, isSuspend = isSuspend,
            isExtension = isExtension, receiverType = receiverType, isOverride = isOverride)
    }

    private fun parseProperty(prop: KtProperty, typeMaps: TypeMaps): KneProperty? {
        val name = prop.name ?: return null
        val type = resolveTypeFromMaps(prop.typeReference, typeMaps) ?: return null
        val isOverride = prop.hasModifier(KtTokens.OVERRIDE_KEYWORD)
        return KneProperty(name, type, prop.isVar, isOverride = isOverride)
    }

    private fun resolveTypeFromMaps(typeRef: KtTypeReference?, typeMaps: TypeMaps): KneType? =
        resolveType(typeRef, typeMaps.enums, typeMaps.classes, typeMaps.dataClasses, typeMaps.interfaces)

    private fun resolveType(
        typeRef: KtTypeReference?, knownEnums: Map<String, String>,
        knownClasses: Map<String, String>, knownDataClasses: Map<String, Pair<String, List<KneParam>>>,
        knownInterfaces: Map<String, String> = emptyMap(),
    ): KneType? {
        val typeElem = typeRef?.typeElement ?: return null
        if (typeElem is KtNullableType) {
            val inner = resolveTypeElement(typeElem.innerType, knownEnums, knownClasses, knownDataClasses, knownInterfaces) ?: return null
            return if (inner == KneType.UNIT || inner is KneType.NULLABLE) inner else KneType.NULLABLE(inner)
        }
        return resolveTypeElement(typeElem, knownEnums, knownClasses, knownDataClasses, knownInterfaces)
    }

    private fun resolveTypeElement(
        typeElem: KtTypeElement?, knownEnums: Map<String, String>,
        knownClasses: Map<String, String>, knownDataClasses: Map<String, Pair<String, List<KneParam>>>,
        knownInterfaces: Map<String, String> = emptyMap(),
    ): KneType? {
        if (typeElem == null) return null
        if (typeElem is KtFunctionType) {
            val paramTypes = typeElem.parameters.mapNotNull { p ->
                resolveType(p.typeReference, knownEnums, knownClasses, knownDataClasses, knownInterfaces)
            }
            val returnType = resolveType(typeElem.returnTypeReference, knownEnums, knownClasses, knownDataClasses, knownInterfaces) ?: KneType.UNIT
            val supported = setOf(KneType.INT, KneType.LONG, KneType.DOUBLE, KneType.FLOAT, KneType.BOOLEAN, KneType.BYTE, KneType.SHORT, KneType.STRING)
            fun ok(t: KneType) = t in supported || t == KneType.BYTE_ARRAY || t is KneType.DATA_CLASS || t is KneType.ENUM || t is KneType.OBJECT || t is KneType.INTERFACE || t is KneType.LIST || t is KneType.SET || t is KneType.MAP
            fun okRet(t: KneType) = ok(t) || t == KneType.UNIT
            if (paramTypes.any { !ok(it) } || !okRet(returnType)) return null
            return KneType.FUNCTION(paramTypes, returnType)
        }
        if (typeElem is KtUserType) {
            val name = typeElem.referencedName ?: return null
            val typeArgs = typeElem.typeArguments.mapNotNull { arg ->
                resolveType(arg.typeReference, knownEnums, knownClasses, knownDataClasses, knownInterfaces)
            }
            return when (name) {
                "Int" -> KneType.INT; "Long" -> KneType.LONG; "Double" -> KneType.DOUBLE; "Float" -> KneType.FLOAT
                "Boolean" -> KneType.BOOLEAN; "Byte" -> KneType.BYTE; "Short" -> KneType.SHORT
                "String" -> KneType.STRING; "ByteArray" -> KneType.BYTE_ARRAY; "Unit" -> KneType.UNIT
                "List", "MutableList" -> if (typeArgs.size == 1) KneType.LIST(typeArgs[0]) else null
                "Set", "MutableSet" -> if (typeArgs.size == 1) KneType.SET(typeArgs[0]) else null
                "Map", "MutableMap" -> if (typeArgs.size == 2) KneType.MAP(typeArgs[0], typeArgs[1]) else null
                "Flow" -> if (typeArgs.size == 1) KneType.FLOW(typeArgs[0]) else null
                else -> knownEnums[name]?.let { KneType.ENUM(it, name) }
                    ?: knownDataClasses[name]?.let { KneType.DATA_CLASS(it.first, name, it.second) }
                    ?: knownInterfaces[name]?.let { KneType.INTERFACE(it, name) }
                    ?: knownClasses[name]?.let { KneType.OBJECT(it, name) }
            }
        }
        return null
    }

    private fun KtDeclaration.isPrivateOrInternal(): Boolean {
        val mods = modifierList ?: return false
        return mods.hasModifier(KtTokens.PRIVATE_KEYWORD) || mods.hasModifier(KtTokens.INTERNAL_KEYWORD) || mods.hasModifier(KtTokens.PROTECTED_KEYWORD)
    }
}
