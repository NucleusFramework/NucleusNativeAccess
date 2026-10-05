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
import dev.nucleusframework.nna.plugin.ir.KneSourceDecl
import dev.nucleusframework.nna.plugin.ir.KneType
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironmentMode
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreProjectEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
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
 *
 * Parsing is incremental: every source file maps to a [CachedSourceFile] (prescan + IR fragment).
 * A file is re-parsed only when its content changed, or when a global symbol it looked up
 * (see [RecordingScope]) now resolves differently. The PSI environment is only created when
 * at least one file has to be parsed.
 */
class PsiSourceParser {

    /** One input file. Sources are merged in list order (common sources first). */
    data class Source(val file: File, val isCommon: Boolean)

    class Result(
        val module: KneModule,
        /** Source file → IR mapping, keyed by absolute path; feed it back as `previous` on the next run. */
        val files: Map<String, CachedSourceFile>,
        val parsedFiles: Int,
    )

    fun parse(files: Collection<File>, libName: String, commonFiles: Collection<File>): KneModule {
        val sources = commonFiles.filter { it.extension == "kt" }.map { Source(it, isCommon = true) } +
            files.filter { it.extension == "kt" }.map { Source(it, isCommon = false) }
        return parseIncremental(sources, libName, emptyMap()).module
    }

    fun parseIncremental(sources: List<Source>, libName: String, previous: Map<String, CachedSourceFile>): Result {
        PsiFiles().use { psi ->
            class Entry(val source: Source, val key: String, val text: String, val hash: String, val cached: CachedSourceFile?, val prescan: List<PrescanDecl>)

            // Phase 1: prescan type declarations (reused as long as the content is unchanged)
            val entries = sources.distinctBy { it.file.absoluteFile }.map { source ->
                val key = source.file.absolutePath
                val text = source.file.readText()
                val hash = sha256(text.toByteArray())
                val cached = previous[key]?.takeIf { it.contentHash == hash && it.isCommon == source.isCommon }
                Entry(source, key, text, hash, cached, cached?.prescan ?: prescan(psi.parse(source.file, text)))
            }

            // Phase 2: global symbol table (cheap, always rebuilt from the prescans)
            val table = SymbolTable.build(entries.map { it.prescan to it.source.isCommon })

            // Phase 3: IR fragment per file, reused while every symbol it looked up is unchanged
            var parsedFiles = 0
            val files = LinkedHashMap<String, CachedSourceFile>()
            for (entry in entries) {
                val cached = entry.cached?.takeIf { cached ->
                    cached.lookups.all { (name, info) -> table.info(name) == info }
                }
                files[entry.key] = cached ?: run {
                    parsedFiles++
                    val ktFile = psi.parse(entry.source.file, entry.text)
                    val scope = RecordingScope(table)
                    val fragment = parseFragment(ktFile, scope, entry.source.isCommon)
                    CachedSourceFile(entry.hash, entry.source.isCommon, entry.prescan, fragment, scope.lookups.toMap())
                }
            }

            return Result(merge(libName, files.values.map { it.fragment }, table), files, parsedFiles)
        }
    }

    /** Lazily-created PSI environment; parsed files are memoized for the duration of one run. */
    private class PsiFiles : AutoCloseable {
        private var disposable: Disposable? = null
        private var tmpHome: File? = null
        private val factory: KtPsiFactory by lazy {
            val root = Disposer.newDisposable("KnePsiParser").also { disposable = it }
            // IntelliJ PathManager requires idea.home.path
            val home = java.nio.file.Files.createTempDirectory("kne-psi").toFile().also { tmpHome = it }
            home.resolve("product-info.json").writeText("""{"buildNumber":"999.SNAPSHOT"}""")
            System.setProperty("idea.home.path", home.absolutePath)

            val appEnv = KotlinCoreApplicationEnvironment.create(root, KotlinCoreApplicationEnvironmentMode.Production)
            appEnv.registerFileType(KotlinFileType.INSTANCE, "kt")
            appEnv.registerParserDefinition(org.jetbrains.kotlin.parsing.KotlinParserDefinition())
            KtPsiFactory(KotlinCoreProjectEnvironment(root, appEnv).project)
        }
        private val parsed = HashMap<File, KtFile>()

        fun parse(file: File, text: String): KtFile = parsed.getOrPut(file) { factory.createFile(file.name, text) }

        override fun close() {
            tmpHome?.deleteRecursively()
            disposable?.let(Disposer::dispose)
        }
    }

    private fun prescan(ktFile: KtFile): List<PrescanDecl> {
        val decls = mutableListOf<PrescanDecl>()
        val pkg = ktFile.packageFqName.asString()

        fun visit(declarations: List<KtDeclaration>, parentFq: String?) {
            for (decl in declarations) {
                if (decl !is KtClass) continue
                val name = decl.name ?: continue
                val fq = if (parentFq != null) "$parentFq.$name"
                    else if (pkg.isNotEmpty()) "$pkg.$name" else name
                when {
                    decl.isEnum() -> decls += PrescanDecl(name, fq, DeclKind.ENUM)
                    decl.isData() -> decls += PrescanDecl(name, fq, DeclKind.DATA_CLASS, dataParams(decl))
                    decl.isInterface() -> decls += PrescanDecl(name, fq, DeclKind.INTERFACE)
                    else -> {
                        decls += PrescanDecl(name, fq, DeclKind.CLASS)
                        visit(decl.declarations.toList(), fq)
                    }
                }
            }
        }
        visit(ktFile.declarations.toList(), null)
        return decls
    }

    private fun dataParams(ktClass: KtClass): List<DataParamSyntax>? =
        ktClass.primaryConstructor?.valueParameters?.map { param ->
            DataParamSyntax(param.name, param.hasValOrVar(), param.typeReference.toSyntax())
        }

    private fun parseFragment(ktFile: KtFile, scope: RecordingScope, isCommonFile: Boolean): FileFragment {
        val pkg = ktFile.packageFqName.asString()
        val imports = ktFile.importDirectives.map { it.text }
        val classes = mutableListOf<KneClass>()
        val dataClasses = mutableListOf<KneDataClass>()
        val enums = mutableListOf<KneEnum>()
        val functions = mutableListOf<Pair<String, KneFunction>>()
        val interfaces = mutableListOf<KneInterface>()

        fun processDeclarations(declarations: List<KtDeclaration>, parentSimpleName: String?, isTopLevel: Boolean) {
            for (decl in declarations) {
                if (decl.isPrivateOrInternal()) continue
                when {
                    decl is KtClass && decl.isEnum() -> parseEnum(decl, pkg, scope)
                        ?.copy(source = sourceDecl(decl, imports))
                        ?.let { enums += it }
                    decl is KtClass && decl.isData() -> {
                        val name = decl.name ?: continue
                        val dcInfo = scope.dataClass(name) ?: continue
                        dataClasses += KneDataClass(
                            name, dcInfo.first, dcInfo.second,
                            isCommon = scope.isCommonDataClass(name),
                            source = sourceDecl(decl, imports),
                        )
                    }
                    decl is KtClass && decl.isInterface() -> {
                        interfaces += parseInterface(decl, pkg, scope, isCommon = isCommonFile) ?: continue
                    }
                    decl is KtClass -> {
                        val cls = parseClass(decl, pkg, scope, isCommon = isCommonFile) ?: continue
                        val correctFq = scope.classFq(decl.name ?: "") ?: cls.fqName
                        val qualifiedCls = if (parentSimpleName != null) {
                            cls.copy(simpleName = "${parentSimpleName}_${cls.simpleName}", fqName = correctFq)
                        } else cls.copy(fqName = correctFq)
                        classes += qualifiedCls
                        // Only recurse for nested classes, not into class body
                        processDeclarations(decl.declarations.toList(), qualifiedCls.simpleName, false)
                    }
                    // Top-level functions (including extension functions)
                    isTopLevel && decl is KtNamedFunction -> {
                        val fn = parseFunction(decl, scope) ?: continue
                        // Use a unique key for extension functions to avoid collisions
                        val key = if (fn.isExtension && fn.receiverType != null) {
                            val receiverName = when (val rt = fn.receiverType) {
                                is KneType.OBJECT -> rt.simpleName
                                is KneType.INTERFACE -> rt.simpleName
                                else -> rt.jvmTypeName
                            }
                            "${receiverName}.${fn.name}"
                        } else fn.name
                        functions += key to fn
                    }
                }
            }
        }
        processDeclarations(ktFile.declarations.toList(), null, true)
        return FileFragment(pkg, classes, dataClasses, enums, functions, interfaces)
    }

    /** Merges per-file fragments in source order; the first declaration of a given FQ name wins. */
    private fun merge(libName: String, fragments: List<FileFragment>, table: SymbolTable): KneModule {
        val classMap = LinkedHashMap<String, KneClass>()
        val dataClassMap = LinkedHashMap<String, KneDataClass>()
        val enumMap = LinkedHashMap<String, KneEnum>()
        val functionMap = LinkedHashMap<String, KneFunction>()
        val interfaceMap = LinkedHashMap<String, KneInterface>()
        val packages = LinkedHashSet<String>()

        for (fragment in fragments) {
            if (fragment.packageName.isNotEmpty()) packages += fragment.packageName
            fragment.classes.forEach { classMap.putIfAbsent(it.fqName, it) }
            fragment.dataClasses.forEach { dataClassMap.putIfAbsent(it.fqName, it) }
            fragment.enums.forEach { enumMap.putIfAbsent(it.fqName, it) }
            fragment.functions.forEach { (key, fn) -> functionMap.putIfAbsent(key, fn) }
            fragment.interfaces.forEach { interfaceMap.putIfAbsent(it.fqName, it) }
        }

        // Ensure common data classes are present even if only defined in commonMain
        for (name in table.commonDataClassNames) {
            val (fq, fields) = table.dataClasses[name] ?: continue
            dataClassMap.putIfAbsent(fq, KneDataClass(name, fq, fields, isCommon = true))
        }

        return KneModule(
            libName, packages, classMap.values.toList(), dataClassMap.values.toList(),
            enumMap.values.toList(), functionMap.values.toList(), interfaceMap.values.toList(),
        )
    }

    /** Captures the declaration text (incl. KDoc and annotations) re-indented to top level. */
    private fun sourceDecl(decl: KtClass, imports: List<String>): KneSourceDecl {
        val indent = decl.prevSibling?.text?.substringAfterLast('\n', missingDelimiterValue = "")?.takeIf { it.isBlank() }.orEmpty()
        val text = (indent + decl.text).trimIndent()
        return KneSourceDecl(text, imports)
    }

    private fun parseClass(ktClass: KtClass, pkg: String, scope: RecordingScope, isCommon: Boolean = false): KneClass? {
        val name = ktClass.name ?: return null
        val fq = if (pkg.isNotEmpty()) "$pkg.$name" else name
        val rawCtorParams = ktClass.primaryConstructor?.valueParameters ?: emptyList()
        val ctorParams = rawCtorParams.mapNotNull { param ->
            val pName = param.name ?: return@mapNotNull null
            val type = resolveType(param.typeReference, scope) ?: return@mapNotNull null
            KneParam(pName, type, hasDefault = param.hasDefaultValue())
        }
        // Constructor val/var params are properties — collect them for getter/setter generation
        val ctorProperties = rawCtorParams.filter { it.hasValOrVar() }.mapNotNull { param ->
            val pName = param.name ?: return@mapNotNull null
            val type = resolveType(param.typeReference, scope) ?: return@mapNotNull null
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
            val interfaceFq = scope.interfaceFq(typeName)
            if (interfaceFq != null) {
                implementedInterfaces.add(interfaceFq)
            } else if (superClass == null) {
                superClass = scope.classFq(typeName)
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
                    parseFunction(decl, scope)?.let { methods.add(it) }
                }
                is KtProperty -> {
                    val propName = decl.name ?: continue
                    if (propName in ctorParamNames) continue
                    parseProperty(decl, scope)?.let { properties.add(it) }
                }
                is KtObjectDeclaration -> if (decl.isCompanion()) {
                    for (cd in decl.declarations) {
                        if (cd.isPrivateOrInternal()) continue
                        when (cd) {
                            is KtNamedFunction -> parseFunction(cd, scope)?.let { companionMethods.add(it) }
                            is KtProperty -> parseProperty(cd, scope)?.let { companionProperties.add(it) }
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

    private fun parseInterface(ktClass: KtClass, pkg: String, scope: RecordingScope, isCommon: Boolean = false): KneInterface? {
        val name = ktClass.name ?: return null
        val fq = if (pkg.isNotEmpty()) "$pkg.$name" else name
        val methods = mutableListOf<KneFunction>()
        val properties = mutableListOf<KneProperty>()
        val superInterfaces = mutableListOf<String>()

        // Extract super interfaces
        for (entry in ktClass.superTypeListEntries) {
            val typeName = entry.typeReference?.text?.substringBefore("<")?.trim() ?: continue
            scope.interfaceFq(typeName)?.let { superInterfaces.add(it) }
        }

        for (decl in ktClass.declarations) {
            if (decl.isPrivateOrInternal()) continue
            when (decl) {
                is KtNamedFunction -> {
                    if (decl.name?.startsWith("_") == true) continue
                    parseFunction(decl, scope)?.let { methods.add(it) }
                }
                is KtProperty -> parseProperty(decl, scope)?.let { properties.add(it) }
            }
        }
        return KneInterface(name, fq, methods, properties, superInterfaces, isCommon = isCommon)
    }

    private fun parseEnum(ktClass: KtClass, pkg: String, scope: RecordingScope): KneEnum? {
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
            val type = resolveType(param.typeReference, scope)
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

    private fun parseFunction(fn: KtNamedFunction, scope: RecordingScope): KneFunction? {
        val name = fn.name ?: return null
        if (name == "init") return null
        val isSuspend = fn.hasModifier(KtTokens.SUSPEND_KEYWORD)
        val isOverride = fn.hasModifier(KtTokens.OVERRIDE_KEYWORD)
        val params = fn.valueParameters.mapNotNull { param ->
            val pName = param.name ?: return@mapNotNull null
            val type = resolveType(param.typeReference, scope) ?: return@mapNotNull null
            KneParam(pName, type)
        }
        val returnType = fn.typeReference?.let { resolveType(it, scope) } ?: KneType.UNIT

        // Extension function detection
        val receiverTypeRef = fn.receiverTypeReference
        val receiverType = if (receiverTypeRef != null) resolveType(receiverTypeRef, scope) else null
        val isExtension = receiverType != null

        return KneFunction(name, params, returnType, isSuspend = isSuspend,
            isExtension = isExtension, receiverType = receiverType, isOverride = isOverride)
    }

    private fun parseProperty(prop: KtProperty, scope: RecordingScope): KneProperty? {
        val name = prop.name ?: return null
        val type = resolveType(prop.typeReference, scope) ?: return null
        val isOverride = prop.hasModifier(KtTokens.OVERRIDE_KEYWORD)
        return KneProperty(name, type, prop.isVar, isOverride = isOverride)
    }

    private fun resolveType(typeRef: KtTypeReference?, scope: TypeScope): KneType? =
        TypeResolver.resolve(typeRef.toSyntax(), scope)

    private fun KtTypeReference?.toSyntax(): TypeSyntax? = this?.typeElement?.let(::syntaxOf)

    private fun syntaxOf(elem: KtTypeElement?): TypeSyntax? = when (elem) {
        null -> null
        is KtNullableType -> TypeSyntax.Nullable(syntaxOf(elem.innerType))
        is KtFunctionType -> TypeSyntax.Function(
            elem.parameters.map { it.typeReference.toSyntax() },
            elem.returnTypeReference.toSyntax(),
        )
        is KtUserType -> elem.referencedName
            ?.let { name -> TypeSyntax.User(name, elem.typeArguments.map { it.typeReference.toSyntax() }) }
            ?: TypeSyntax.Unsupported
        else -> TypeSyntax.Unsupported
    }

    private fun KtDeclaration.isPrivateOrInternal(): Boolean {
        val mods = modifierList ?: return false
        return mods.hasModifier(KtTokens.PRIVATE_KEYWORD) || mods.hasModifier(KtTokens.INTERNAL_KEYWORD) || mods.hasModifier(KtTokens.PROTECTED_KEYWORD)
    }
}
