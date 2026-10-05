package dev.nucleusframework.nna.plugin.analysis

import dev.nucleusframework.nna.plugin.ir.KneParam
import dev.nucleusframework.nna.plugin.ir.KneType
import java.io.Serializable

/**
 * PSI-free view of a type reference. Lets type resolution run on cached per-file data,
 * so unchanged source files never need to be re-parsed.
 */
sealed interface TypeSyntax : Serializable {
    data class User(val name: String, val args: List<TypeSyntax?>) : TypeSyntax
    data class Function(val params: List<TypeSyntax?>, val returnType: TypeSyntax?) : TypeSyntax
    data class Nullable(val inner: TypeSyntax?) : TypeSyntax
    data object Unsupported : TypeSyntax
}

enum class DeclKind { ENUM, DATA_CLASS, INTERFACE, CLASS }

/** A primary-constructor parameter of a data class, kept unresolved until the global symbol table exists. */
data class DataParamSyntax(val name: String?, val isProperty: Boolean, val type: TypeSyntax?) : Serializable

/** A type declaration discovered by the prescan phase. [dataParams] is null when there is no primary constructor. */
data class PrescanDecl(
    val name: String,
    val fqName: String,
    val kind: DeclKind,
    val dataParams: List<DataParamSyntax>? = null,
) : Serializable

/** Resolved data class: FQ name + resolved fields. */
typealias ResolvedDataClass = Pair<String, List<KneParam>>

/**
 * Everything the symbol table knows about a simple name. Per-file parse results record the
 * [SymbolInfo] of every name they looked up; a cached result stays valid as long as those
 * snapshots still match the current table.
 */
data class SymbolInfo(
    val enumFq: String?,
    val dataClass: ResolvedDataClass?,
    val interfaceFq: String?,
    val classFq: String?,
    val isCommonDataClass: Boolean,
) : Serializable

/** Name-based lookups used to resolve [TypeSyntax] into [KneType]. */
interface TypeScope {
    fun enumFq(name: String): String?
    fun dataClass(name: String): ResolvedDataClass?
    fun interfaceFq(name: String): String?
    fun classFq(name: String): String?
}

/** Global symbol table built from the prescan results of all source files, in merge order. */
class SymbolTable private constructor(
    private val enums: Map<String, String>,
    val dataClasses: Map<String, ResolvedDataClass>,
    private val interfaces: Map<String, String>,
    private val classes: Map<String, String>,
    val commonDataClassNames: Set<String>,
) {

    fun info(name: String): SymbolInfo? {
        val info = SymbolInfo(
            enumFq = enums[name],
            dataClass = dataClasses[name],
            interfaceFq = interfaces[name],
            classFq = classes[name],
            isCommonDataClass = name in commonDataClassNames,
        )
        return info.takeUnless { it == UNKNOWN }
    }

    companion object {
        private val UNKNOWN = SymbolInfo(null, null, null, null, false)

        /** Builds the table from `(prescan, isCommon)` pairs, one per source file, in merge order. */
        fun build(files: List<Pair<List<PrescanDecl>, Boolean>>): SymbolTable {
            val enums = LinkedHashMap<String, String>()
            val interfaces = LinkedHashMap<String, String>()
            val classes = LinkedHashMap<String, String>()
            val rawDataClasses = LinkedHashMap<String, PrescanDecl>()
            val commonDataClassNames = LinkedHashSet<String>()

            for ((decls, isCommon) in files) {
                val dataClassesBefore = rawDataClasses.keys.toSet()
                for (decl in decls) {
                    when (decl.kind) {
                        DeclKind.ENUM -> enums[decl.name] = decl.fqName
                        DeclKind.DATA_CLASS -> rawDataClasses[decl.name] = decl
                        DeclKind.INTERFACE -> interfaces[decl.name] = decl.fqName
                        DeclKind.CLASS -> classes[decl.name] = decl.fqName
                    }
                }
                if (isCommon) commonDataClassNames += rawDataClasses.keys - dataClassesBefore
            }

            // Resolve data class fields iteratively (a data class may nest another one)
            val dataClasses = LinkedHashMap<String, ResolvedDataClass>()
            val scope = object : TypeScope {
                override fun enumFq(name: String) = enums[name]
                override fun dataClass(name: String) = dataClasses[name]
                override fun interfaceFq(name: String): String? = null
                override fun classFq(name: String) = classes[name]
            }
            var changed = true
            while (changed) {
                changed = false
                for ((name, decl) in rawDataClasses) {
                    if (name in dataClasses) continue
                    val fields = resolveDataClassFields(decl.dataParams, scope) ?: continue
                    dataClasses[name] = decl.fqName to fields
                    changed = true
                }
            }
            // Unresolvable data classes are exported as regular classes
            for ((name, decl) in rawDataClasses) {
                if (name !in dataClasses) classes[name] = decl.fqName
            }

            return SymbolTable(enums, dataClasses, interfaces, classes, commonDataClassNames)
        }

        private fun resolveDataClassFields(params: List<DataParamSyntax>?, scope: TypeScope): List<KneParam>? {
            params ?: return null
            val fields = mutableListOf<KneParam>()
            for (param in params) {
                if (!param.isProperty) continue
                val name = param.name ?: return null
                val type = TypeResolver.resolve(param.type, scope) ?: return null
                fields.add(KneParam(name, type))
            }
            return fields.ifEmpty { null }
        }
    }
}

/** [TypeScope] over a [SymbolTable] that records every name it is asked about. */
class RecordingScope(private val table: SymbolTable) : TypeScope {

    private val recorded = LinkedHashMap<String, SymbolInfo?>()

    /** Snapshot of every looked-up name and what it resolved to. */
    val lookups: Map<String, SymbolInfo?> get() = recorded

    fun info(name: String): SymbolInfo? =
        if (name in recorded) recorded[name] else table.info(name).also { recorded[name] = it }

    override fun enumFq(name: String) = info(name)?.enumFq
    override fun dataClass(name: String) = info(name)?.dataClass
    override fun interfaceFq(name: String) = info(name)?.interfaceFq
    override fun classFq(name: String) = info(name)?.classFq
    fun isCommonDataClass(name: String) = info(name)?.isCommonDataClass == true
}

object TypeResolver {

    private val CALLBACK_PRIMITIVES = setOf(
        KneType.INT, KneType.LONG, KneType.DOUBLE, KneType.FLOAT,
        KneType.BOOLEAN, KneType.BYTE, KneType.SHORT, KneType.STRING,
    )

    fun resolve(type: TypeSyntax?, scope: TypeScope): KneType? {
        if (type is TypeSyntax.Nullable) {
            val inner = resolveElement(type.inner, scope) ?: return null
            return if (inner == KneType.UNIT || inner is KneType.NULLABLE) inner else KneType.NULLABLE(inner)
        }
        return resolveElement(type, scope)
    }

    private fun resolveElement(type: TypeSyntax?, scope: TypeScope): KneType? = when (type) {
        is TypeSyntax.Function -> resolveFunction(type, scope)
        is TypeSyntax.User -> resolveUser(type, scope)
        else -> null
    }

    private fun resolveFunction(type: TypeSyntax.Function, scope: TypeScope): KneType? {
        val paramTypes = type.params.mapNotNull { resolve(it, scope) }
        val returnType = resolve(type.returnType, scope) ?: KneType.UNIT
        fun ok(t: KneType) = t in CALLBACK_PRIMITIVES || t == KneType.BYTE_ARRAY || t is KneType.DATA_CLASS ||
            t is KneType.ENUM || t is KneType.OBJECT || t is KneType.INTERFACE ||
            t is KneType.LIST || t is KneType.SET || t is KneType.MAP
        if (paramTypes.any { !ok(it) } || !(ok(returnType) || returnType == KneType.UNIT)) return null
        return KneType.FUNCTION(paramTypes, returnType)
    }

    private fun resolveUser(type: TypeSyntax.User, scope: TypeScope): KneType? {
        val name = type.name
        val typeArgs = type.args.mapNotNull { resolve(it, scope) }
        return when (name) {
            "Int" -> KneType.INT; "Long" -> KneType.LONG; "Double" -> KneType.DOUBLE; "Float" -> KneType.FLOAT
            "Boolean" -> KneType.BOOLEAN; "Byte" -> KneType.BYTE; "Short" -> KneType.SHORT
            "String" -> KneType.STRING; "ByteArray" -> KneType.BYTE_ARRAY; "Unit" -> KneType.UNIT
            "List", "MutableList" -> if (typeArgs.size == 1) KneType.LIST(typeArgs[0]) else null
            "Set", "MutableSet" -> if (typeArgs.size == 1) KneType.SET(typeArgs[0]) else null
            "Map", "MutableMap" -> if (typeArgs.size == 2) KneType.MAP(typeArgs[0], typeArgs[1]) else null
            "Flow" -> if (typeArgs.size == 1) KneType.FLOW(typeArgs[0]) else null
            else -> scope.enumFq(name)?.let { KneType.ENUM(it, name) }
                ?: scope.dataClass(name)?.let { KneType.DATA_CLASS(it.first, name, it.second) }
                ?: scope.interfaceFq(name)?.let { KneType.INTERFACE(it, name) }
                ?: scope.classFq(name)?.let { KneType.OBJECT(it, name) }
        }
    }
}
