package com.example.calculator

class Calculator(initial: Int = 0) {

    private var accumulator: Int = initial

    // ── Int methods ─────────────────────────────────────────────────────────

    fun add(value: Int): Int {
        accumulator += value
        return accumulator
    }

    fun subtract(value: Int): Int {
        accumulator -= value
        return accumulator
    }

    fun multiply(value: Int): Int {
        accumulator *= value
        return accumulator
    }

    fun reset() {
        accumulator = 0
    }

    fun divide(divisor: Int): Int {
        require(divisor != 0) { "Division by zero" }
        accumulator /= divisor
        return accumulator
    }

    fun failAlways(): String {
        error("Intentional error for testing")
    }

    val current: Int
        get() = accumulator

    // ── All primitive types as params and returns ───────────────────────────

    fun addLong(value: Long): Long = (accumulator + value.toInt()).toLong()
    fun addDouble(value: Double): Double = accumulator.toDouble() + value
    fun addFloat(value: Float): Float = accumulator.toFloat() + value
    fun addShort(value: Short): Short = (accumulator + value).toShort()
    fun addByte(value: Byte): Byte = (accumulator + value).toByte()
    fun isPositive(): Boolean = accumulator > 0
    fun checkFlag(flag: Boolean): Boolean = flag && accumulator > 0

    // ── String methods ──────────────────────────────────────────────────────

    fun describe(): String = "Calculator(current=$accumulator)"
    fun echo(text: String): String = text
    fun concat(a: String, b: String): String = a + b

    // ── Mutable properties ──────────────────────────────────────────────────

    var label: String = ""
    var scale: Double = 1.0
    var enabled: Boolean = true

    // ── Collection properties ──────────────────────────────────────────────

    val recentScores: List<Int> get() = listOf(accumulator, accumulator * 2, accumulator * 3)

    var tags: List<String> = listOf("default")

    val info: Map<String, Int> get() = mapOf("current" to accumulator, "scale" to scale.toInt())

    // ── Enum support ────────────────────────────────────────────────────────

    var lastOperation: Operation = Operation.ADD

    fun applyOp(op: Operation, value: Int): Int {
        lastOperation = op
        return when (op) {
            Operation.ADD -> add(value)
            Operation.SUBTRACT -> subtract(value)
            Operation.MULTIPLY -> multiply(value)
        }
    }

    fun getLastOp(): Operation = lastOperation

    var lastStatus: Status = Status.SUCCESS

    fun getStatus(): Status = when {
        accumulator > 0 -> Status.SUCCESS
        accumulator < 0 -> Status.ERROR
        else -> Status.PENDING
    }

    fun getHttpStatus(): HttpStatus = if (accumulator >= 0) HttpStatus.OK else HttpStatus.NOT_FOUND

    // ── Nullable types ──────────────────────────────────────────────────────

    var nickname: String? = null

    fun divideOrNull(divisor: Int): Int? = if (divisor != 0) accumulator / divisor else null

    fun describeOrNull(): String? = if (accumulator > 0) "Positive($accumulator)" else null

    fun isPositiveOrNull(): Boolean? = if (accumulator == 0) null else accumulator > 0

    fun findOp(name: String?): Operation? = if (name != null) Operation.entries.find { it.name == name } else null

    fun toLongOrNull(): Long? = if (accumulator != 0) accumulator.toLong() else null

    fun toDoubleOrNull(): Double? = if (accumulator != 0) accumulator.toDouble() else null

    // ── ByteArray support ─────────────────────────────────────────────────

    fun toBytes(): ByteArray {
        val str = accumulator.toString()
        return str.encodeToByteArray()
    }

    fun sumBytes(data: ByteArray): Int {
        accumulator = data.sumOf { it.toInt() }
        return accumulator
    }

    fun reverseBytes(data: ByteArray): ByteArray = data.reversedArray()

    // ── Callback support ──────────────────────────────────────────────────

    fun onValueChanged(callback: (Int) -> Unit) {
        callback(accumulator)
    }

    fun transform(fn: (Int) -> Int): Int {
        accumulator = fn(accumulator)
        return accumulator
    }

    fun compute(a: Int, b: Int, op: (Int, Int) -> Int): Int {
        accumulator = op(a, b)
        return accumulator
    }

    fun checkWith(predicate: (Int) -> Boolean): Boolean {
        return predicate(accumulator)
    }

    // ── Data class support (nativeMain-only) ───────────────────────────────

    fun getPoint(): Point = Point(accumulator, accumulator * 2)

    fun addPoint(p: Point): Int {
        accumulator += p.x + p.y
        return accumulator
    }

    fun getNamedValue(): NamedValue = NamedValue(label.ifEmpty { "default" }, accumulator)

    fun setFromNamed(nv: NamedValue) {
        label = nv.name
        accumulator = nv.value
    }

    fun getTaggedPoint(): TaggedPoint = TaggedPoint(Point(accumulator, accumulator * 2), lastOperation)

    fun setFromTagged(tp: TaggedPoint) {
        accumulator = tp.point.x + tp.point.y
        lastOperation = tp.tag
    }

    fun getRect(): Rect = Rect(Point(0, 0), Point(accumulator, accumulator))

    fun snapshot(): CalculatorSnapshot = CalculatorSnapshot(this, label.ifEmpty { "snapshot" })

    fun restoreFrom(snap: CalculatorSnapshot): Int {
        accumulator = snap.calc.current
        label = snap.label
        return accumulator
    }

    fun getPointOrNull(): Point? = if (accumulator != 0) Point(accumulator, accumulator * 2) else null

    fun addPointOrNull(p: Point?): Int {
        if (p != null) accumulator += p.x + p.y
        return accumulator
    }

    fun getResultOrNull(): CalcResult? = if (accumulator != 0) CalcResult(accumulator, "Result: $accumulator") else null

    // ── Data class support (commonMain) ─────────────────────────────────

    fun getResult(): CalcResult = CalcResult(accumulator, "Result: $accumulator")

    fun applyResult(r: CalcResult): Int {
        accumulator = r.value
        label = r.description
        return accumulator
    }

    // ── Callback support ────────────────────────────────────────────────────

    fun withDescription(callback: (String) -> Unit) {
        callback("Calculator(current=$accumulator)")
    }

    fun formatWith(formatter: (Int) -> String): String {
        return formatter(accumulator)
    }

    fun transformLabel(fn: (String) -> String): String {
        label = fn(label)
        return label
    }

    fun onOperation(callback: (Operation) -> Unit) {
        callback(lastOperation)
    }

    fun chooseOp(chooser: (Int) -> Operation): Operation {
        lastOperation = chooser(accumulator)
        return lastOperation
    }

    fun withLong(fn: (Long) -> Long): Long = fn(accumulator.toLong())
    fun withDouble(fn: (Double) -> Double): Double = fn(accumulator.toDouble())
    fun withFloat(fn: (Float) -> Float): Float = fn(accumulator.toFloat())
    fun withShort(fn: (Short) -> Short): Short = fn(accumulator.toShort())
    fun withByte(fn: (Byte) -> Byte): Byte = fn(accumulator.toByte())

    // ── Lambda return type ────────────────────────────────────────────────

    fun getAdder(amount: Int): (Int) -> Int {
        return { x -> x + amount }
    }

    fun getFormatter(): (Int) -> String {
        return { x -> "value=$x (acc=$accumulator)" }
    }

    fun getNotifier(): (Int) -> Unit {
        return { x -> accumulator = x }
    }

    // ── ByteArray callback params ─────────────────────────────────────────

    fun onBytesReady(callback: (ByteArray) -> Unit) {
        callback(ByteArray(accumulator.coerceAtLeast(0)) { (it % 256).toByte() })
    }

    fun transformBytes(input: ByteArray, fn: (ByteArray) -> ByteArray): ByteArray {
        return fn(input)
    }

    // ── Nullable callback params ──────────────────────────────────────────

    fun onValueChangedOrNull(callback: ((Int) -> Unit)?): Boolean {
        if (callback != null) {
            callback(accumulator)
            return true
        }
        return false
    }

    fun transformOrDefault(fn: ((Int) -> Int)?, default: Int): Int {
        accumulator = if (fn != null) fn(accumulator) else default
        return accumulator
    }

    fun formatOrNull(formatter: ((Int) -> String)?): String {
        return formatter?.invoke(accumulator) ?: "null"
    }

    fun onPointComputed(callback: (Point) -> Unit) {
        callback(Point(accumulator, accumulator * 2))
    }

    fun onResultReady(callback: (CalcResult) -> Unit) {
        callback(CalcResult(accumulator, "Result: $accumulator"))
    }

    fun createPoint(factory: (Int) -> Point): Point {
        return factory(accumulator)
    }

    fun transformPoint(fn: (Point) -> Int): Int {
        accumulator = fn(Point(accumulator, accumulator * 2))
        return accumulator
    }

    fun findAndReport(keyword: String, callback: (String, Int) -> Unit) {
        val found = if (label.contains(keyword)) 1 else 0
        callback(label, found)
    }

    // ── List<DataClass> support ────────────────────────────────────────────────

    fun getPoints(): List<Point> = listOf(Point(accumulator, 0), Point(0, accumulator), Point(accumulator, accumulator))

    fun getNamedValues(): List<NamedValue> = listOf(
        NamedValue("first", accumulator),
        NamedValue("second", accumulator * 2),
    )

    fun getTaggedPoints(): List<TaggedPoint> = listOf(
        TaggedPoint(Point(accumulator, 0), lastOperation),
        TaggedPoint(Point(0, accumulator), Operation.ADD),
    )

    fun getPointsOrNull(): List<Point>? = if (accumulator != 0) listOf(Point(accumulator, accumulator)) else null

    fun getEmptyPoints(): List<Point> = emptyList()

    fun getSinglePoint(): List<Point> = listOf(Point(accumulator, accumulator * 2))

    // ── Suspend functions ─────────────────────────────────────────────────────

    suspend fun delayedAdd(a: Int, b: Int): Int {
        kotlinx.coroutines.delay(50)
        accumulator = a + b
        return accumulator
    }

    suspend fun delayedDescribe(): String {
        kotlinx.coroutines.delay(30)
        return describe()
    }

    suspend fun failAfterDelay(): String {
        kotlinx.coroutines.delay(30)
        error("intentional suspend error")
    }

    suspend fun longDelay(): Int {
        kotlinx.coroutines.delay(5000)
        return 42
    }

    suspend fun instantReturn(): Int = accumulator

    suspend fun delayedIsPositive(): Boolean {
        kotlinx.coroutines.delay(10)
        return accumulator > 0
    }

    suspend fun delayedGetOp(): Operation {
        kotlinx.coroutines.delay(10)
        return lastOperation
    }

    suspend fun delayedNullable(): String? {
        kotlinx.coroutines.delay(10)
        return if (accumulator > 0) "positive($accumulator)" else null
    }

    suspend fun delayedDouble(value: Double): Double {
        kotlinx.coroutines.delay(10)
        return value * 2.0
    }

    suspend fun delayedLong(value: Long): Long {
        kotlinx.coroutines.delay(10)
        return value + accumulator.toLong()
    }

    suspend fun delayedUnit() {
        kotlinx.coroutines.delay(10)
        accumulator += 1
    }

    suspend fun multiError(shouldFail: Boolean): Int {
        kotlinx.coroutines.delay(20)
        if (shouldFail) error("conditional error")
        return accumulator
    }

    suspend fun chainedDelay(steps: Int): Int {
        var result = accumulator
        repeat(steps) {
            kotlinx.coroutines.delay(5)
            result += 1
        }
        accumulator = result
        return result
    }

    suspend fun delayedGetCurrentOrNull(): Int? {
        kotlinx.coroutines.delay(10)
        return if (accumulator != 0) accumulator else null
    }

    suspend fun delayedByteArray(): ByteArray {
        kotlinx.coroutines.delay(10)
        return "acc=$accumulator".encodeToByteArray()
    }

    suspend fun delayedByteArrayNullable(): ByteArray? {
        kotlinx.coroutines.delay(10)
        return if (accumulator > 0) "pos=$accumulator".encodeToByteArray() else null
    }

    suspend fun delayedLargeByteArray(size: Int): ByteArray {
        kotlinx.coroutines.delay(10)
        return ByteArray(size) { (it % 256).toByte() }
    }

    // ── Suspend DataClass returns ──────────────────────────────────────────

    suspend fun delayedGetPoint(): Point {
        kotlinx.coroutines.delay(10)
        return Point(accumulator, accumulator * 2)
    }

    suspend fun delayedGetPointOrNull(): Point? {
        kotlinx.coroutines.delay(10)
        return if (accumulator != 0) Point(accumulator, accumulator * 2) else null
    }

    suspend fun delayedGetNamedValue(): NamedValue {
        kotlinx.coroutines.delay(10)
        return NamedValue(label.ifEmpty { "default" }, accumulator)
    }

    suspend fun delayedGetTaggedPoint(): TaggedPoint {
        kotlinx.coroutines.delay(10)
        return TaggedPoint(Point(accumulator, accumulator * 2), lastOperation)
    }

    suspend fun delayedGetRect(): Rect {
        kotlinx.coroutines.delay(10)
        return Rect(Point(0, 0), Point(accumulator, accumulator))
    }

    suspend fun delayedGetCalcResult(): CalcResult {
        kotlinx.coroutines.delay(10)
        return CalcResult(accumulator, "delayed: $accumulator")
    }

    // ── Suspend Collection returns ──────────────────────────────────────────

    suspend fun delayedGetScores(): List<Int> {
        kotlinx.coroutines.delay(10)
        return listOf(accumulator, accumulator * 2, accumulator * 3)
    }

    suspend fun delayedGetScoresOrNull(): List<Int>? {
        kotlinx.coroutines.delay(10)
        return if (accumulator != 0) listOf(accumulator, accumulator * 2) else null
    }

    suspend fun delayedGetLabels(): List<String> {
        kotlinx.coroutines.delay(10)
        return listOf(label.ifEmpty { "default" }, "item_$accumulator")
    }

    suspend fun delayedGetPoints(): List<Point> {
        kotlinx.coroutines.delay(10)
        return listOf(Point(accumulator, 0), Point(0, accumulator))
    }

    suspend fun delayedGetPointsOrNull(): List<Point>? {
        kotlinx.coroutines.delay(10)
        return if (accumulator != 0) listOf(Point(accumulator, accumulator)) else null
    }

    suspend fun delayedGetTaggedPoints(): List<TaggedPoint> {
        kotlinx.coroutines.delay(10)
        return listOf(
            TaggedPoint(Point(accumulator, 0), Operation.ADD),
            TaggedPoint(Point(0, accumulator), lastOperation),
        )
    }

    suspend fun delayedGetRects(): List<Rect> {
        kotlinx.coroutines.delay(10)
        return listOf(
            Rect(Point(0, 0), Point(accumulator, accumulator)),
            Rect(Point(accumulator, 0), Point(0, accumulator)),
        )
    }

    suspend fun delayedGetOperations(): List<Operation> {
        kotlinx.coroutines.delay(10)
        return Operation.entries.toList()
    }

    suspend fun delayedGetWeights(): List<Double> {
        kotlinx.coroutines.delay(10)
        return listOf(accumulator.toDouble(), accumulator * 1.5)
    }

    suspend fun delayedGetFlags(): List<Boolean> {
        kotlinx.coroutines.delay(10)
        return listOf(accumulator > 0, accumulator % 2 == 0)
    }

    suspend fun delayedGetUniqueScores(): Set<Int> {
        kotlinx.coroutines.delay(10)
        return setOf(accumulator, accumulator * 2, accumulator * 3)
    }

    suspend fun delayedGetUniqueLabels(): Set<String> {
        kotlinx.coroutines.delay(10)
        return setOf(label.ifEmpty { "default" }, "item_$accumulator")
    }

    suspend fun delayedGetUsedOps(): Set<Operation> {
        kotlinx.coroutines.delay(10)
        return setOf(lastOperation, Operation.ADD)
    }

    suspend fun delayedGetUsedOpsOrNull(): Set<Operation>? {
        kotlinx.coroutines.delay(10)
        return if (accumulator > 0) setOf(lastOperation) else null
    }

    suspend fun delayedGetMetadata(): Map<String, Int> {
        kotlinx.coroutines.delay(10)
        return mapOf("current" to accumulator, "scale" to scale.toInt())
    }

    suspend fun delayedGetMetadataOrNull(): Map<String, Int>? {
        kotlinx.coroutines.delay(10)
        return if (accumulator != 0) mapOf("val" to accumulator) else null
    }

    suspend fun delayedGetByteChunks(): List<ByteArray> {
        kotlinx.coroutines.delay(10)
        return listOf(byteArrayOf(1, 2, 3), ByteArray(accumulator.coerceAtLeast(0)) { (it % 256).toByte() })
    }

    suspend fun delayedGetMatrix(): List<List<Int>> {
        kotlinx.coroutines.delay(10)
        return listOf(listOf(accumulator, accumulator + 1), listOf(accumulator * 2))
    }

    suspend fun delayedGetSquares(): Map<Int, Int> {
        kotlinx.coroutines.delay(10)
        return mapOf(1 to 1, 2 to 4, accumulator to accumulator * accumulator)
    }

    // ── Flow support ─────────────────────────────────────────────────────────

    fun countUp(max: Int): kotlinx.coroutines.flow.Flow<Int> = kotlinx.coroutines.flow.flow {
        for (i in 1..max) { kotlinx.coroutines.delay(5); emit(i) }
    }

    fun tickStrings(count: Int): kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.flow {
        repeat(count) { kotlinx.coroutines.delay(3); emit("tick_$it") }
    }

    fun failingFlow(): kotlinx.coroutines.flow.Flow<Int> = kotlinx.coroutines.flow.flow {
        emit(1); emit(2); error("flow boom")
    }

    fun infiniteFlow(): kotlinx.coroutines.flow.Flow<Int> = kotlinx.coroutines.flow.flow {
        var i = 0; while (true) { kotlinx.coroutines.delay(5); emit(i++) }
    }

    fun emptyIntFlow(): kotlinx.coroutines.flow.Flow<Int> = kotlinx.coroutines.flow.emptyFlow()

    fun singleFlow(): kotlinx.coroutines.flow.Flow<Int> = kotlinx.coroutines.flow.flowOf(accumulator)

    fun enumFlow(): kotlinx.coroutines.flow.Flow<Operation> = kotlinx.coroutines.flow.flow {
        Operation.entries.forEach { emit(it) }
    }

    fun boolFlow(): kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.flow {
        emit(true); emit(false); emit(accumulator > 0)
    }

    // ── Flow<ByteArray> support ─────────────────────────────────────────────────

    fun byteChunks(count: Int, chunkSize: Int): kotlinx.coroutines.flow.Flow<ByteArray> = kotlinx.coroutines.flow.flow {
        repeat(count) { i ->
            kotlinx.coroutines.delay(3)
            emit(ByteArray(chunkSize) { ((i * chunkSize + it) % 256).toByte() })
        }
    }

    fun singleByteFlow(): kotlinx.coroutines.flow.Flow<ByteArray> = kotlinx.coroutines.flow.flow {
        emit(byteArrayOf(1, 2, 3))
    }

    // ── Flow<Collection> support ────────────────────────────────────────────────

    fun scoresFlow(count: Int): kotlinx.coroutines.flow.Flow<List<Int>> = kotlinx.coroutines.flow.flow {
        repeat(count) { i -> kotlinx.coroutines.delay(3); emit(listOf(i, i * 2, i * 3)) }
    }

    fun labelsFlow(): kotlinx.coroutines.flow.Flow<List<String>> = kotlinx.coroutines.flow.flow {
        emit(listOf("a", "b")); emit(listOf("c", "d", "e"))
    }

    fun metadataFlow(count: Int): kotlinx.coroutines.flow.Flow<Map<String, Int>> = kotlinx.coroutines.flow.flow {
        repeat(count) { i -> kotlinx.coroutines.delay(3); emit(mapOf("step" to i, "value" to accumulator + i)) }
    }

    // ── Flow<DataClass> support ────────────────────────────────────────────────

    fun pointFlow(count: Int): kotlinx.coroutines.flow.Flow<Point> = kotlinx.coroutines.flow.flow {
        repeat(count) { i -> kotlinx.coroutines.delay(3); emit(Point(i, i * 2)) }
    }

    fun namedValueFlow(): kotlinx.coroutines.flow.Flow<NamedValue> = kotlinx.coroutines.flow.flow {
        emit(NamedValue(label.ifEmpty { "default" }, accumulator))
        emit(NamedValue("second", accumulator * 2))
    }

    fun taggedPointFlow(): kotlinx.coroutines.flow.Flow<TaggedPoint> = kotlinx.coroutines.flow.flow {
        Operation.entries.forEach { op ->
            emit(TaggedPoint(Point(accumulator, accumulator * 2), op))
        }
    }

    fun emptyPointFlow(): kotlinx.coroutines.flow.Flow<Point> = kotlinx.coroutines.flow.emptyFlow()

    fun singlePointFlow(): kotlinx.coroutines.flow.Flow<Point> = kotlinx.coroutines.flow.flowOf(Point(accumulator, accumulator * 2))

    fun failingPointFlow(): kotlinx.coroutines.flow.Flow<Point> = kotlinx.coroutines.flow.flow {
        emit(Point(1, 2)); error("point flow boom")
    }

    fun calcResultFlow(count: Int): kotlinx.coroutines.flow.Flow<CalcResult> = kotlinx.coroutines.flow.flow {
        repeat(count) { i ->
            kotlinx.coroutines.delay(3)
            emit(CalcResult(accumulator + i, "step_$i"))
        }
    }

    fun rectFlow(): kotlinx.coroutines.flow.Flow<Rect> = kotlinx.coroutines.flow.flow {
        emit(Rect(Point(0, 0), Point(accumulator, accumulator)))
        emit(Rect(Point(1, 1), Point(accumulator + 1, accumulator + 1)))
    }

    // ── Object in callbacks ────────────────────────────────────────────────────

    fun onSelfReady(callback: (Calculator) -> Unit) {
        callback(this)
    }

    fun transformWith(other: Calculator, fn: (Calculator, Calculator) -> Int): Int {
        accumulator = fn(this, other)
        return accumulator
    }

    fun createVia(factory: (Int) -> Calculator): Calculator = factory(accumulator)

    // ── Collection support ────────────────────────────────────────────────────

    // List<Int>
    fun getScores(): List<Int> = listOf(accumulator, accumulator * 2, accumulator * 3)

    fun sumAll(values: List<Int>): Int {
        accumulator = values.sum()
        return accumulator
    }

    // List<String>
    fun getLabels(): List<String> = listOf(label.ifEmpty { "default" }, "item_$accumulator")

    fun joinLabels(labels: List<String>): String = labels.joinToString(", ")

    // List<Double>
    fun getWeights(): List<Double> = listOf(accumulator.toDouble(), accumulator * 1.5)

    // List<Boolean>
    fun getFlags(): List<Boolean> = listOf(accumulator > 0, accumulator % 2 == 0, label.isNotEmpty())

    // List<Enum>
    fun getOperations(): List<Operation> = Operation.entries.toList()

    fun countOps(ops: List<Operation>): Int = ops.size

    // Set<Int>
    fun getUniqueDigits(): Set<Int> {
        val digits = mutableSetOf<Int>()
        var n = if (accumulator < 0) -accumulator else accumulator
        if (n == 0) digits.add(0)
        while (n > 0) { digits.add(n % 10); n /= 10 }
        return digits
    }

    fun sumUnique(values: Set<Int>): Int {
        accumulator = values.sum()
        return accumulator
    }

    // Map<String, Int>
    fun getMetadata(): Map<String, Int> = mapOf("current" to accumulator, "scale" to scale.toInt())

    fun sumMap(data: Map<String, Int>): Int {
        accumulator = data.values.sum()
        return accumulator
    }

    // List<Long>
    fun getLongScores(): List<Long> = listOf(accumulator.toLong(), accumulator.toLong() * 100_000L)

    fun sumLongs(values: List<Long>): Long = values.sum()

    // List<Float>
    fun getFloatWeights(): List<Float> = listOf(accumulator.toFloat(), accumulator * 0.5f)

    // List<Short>
    fun getShortValues(): List<Short> = listOf(accumulator.toShort(), (accumulator * 2).toShort())

    // List<Byte>
    fun getByteValues(): List<Byte> = listOf(accumulator.toByte(), (accumulator + 1).toByte())

    // Set<String>
    fun getUniqueLabels(): Set<String> = setOf(label.ifEmpty { "default" }, "item_$accumulator", label.ifEmpty { "default" })

    fun joinUniqueStrings(values: Set<String>): String = values.sorted().joinToString(";")

    // Set<Enum>
    fun getUsedOps(): Set<Operation> = setOf(lastOperation, Operation.ADD)

    // Map<Int, String>
    fun getIndexedLabels(): Map<Int, String> = mapOf(0 to label.ifEmpty { "default" }, 1 to "item_$accumulator")

    // Map<Int, Int>
    fun getSquares(): Map<Int, Int> = mapOf(1 to 1, 2 to 4, 3 to 9, accumulator to accumulator * accumulator)

    fun sumMapValues(data: Map<Int, Int>): Int {
        accumulator = data.values.sum()
        return accumulator
    }

    // Map<String, String>
    fun getStringMap(): Map<String, String> = mapOf("name" to label.ifEmpty { "unnamed" }, "value" to accumulator.toString())

    fun concatMapEntries(data: Map<String, String>): String = data.entries.joinToString(", ") { "${it.key}=${it.value}" }

    // ── Collections in callbacks ────────────────────────────────────────────

    fun onScoresReady(callback: (List<Int>) -> Unit) {
        callback(listOf(accumulator, accumulator * 2, accumulator * 3))
    }

    fun onLabelsReady(callback: (List<String>) -> Unit) {
        callback(listOf(label.ifEmpty { "default" }, "item_$accumulator"))
    }

    fun onOpsReady(callback: (List<Operation>) -> Unit) {
        callback(Operation.entries.toList())
    }

    fun onFlagsReady(callback: (List<Boolean>) -> Unit) {
        callback(listOf(accumulator > 0, accumulator % 2 == 0))
    }

    // Map in callbacks
    fun onMetadataReady(callback: (Map<String, Int>) -> Unit) {
        callback(mapOf("current" to accumulator, "doubled" to accumulator * 2))
    }

    // Map in callbacks - extra
    fun onMapIntIntReady(callback: (Map<Int, Int>) -> Unit) {
        callback(mapOf(accumulator to accumulator * accumulator))
    }

    // Collection as callback return
    fun getTransformedScores(fn: (Int) -> List<Int>): List<Int> = fn(accumulator)

    fun getComputedLabels(fn: (Int) -> List<String>): List<String> = fn(accumulator)

    fun getComputedMap(fn: (Int) -> Map<String, Int>): Map<String, Int> = fn(accumulator)

    // Collection return callback - extra combinations
    fun getComputedOps(fn: (Int) -> List<Operation>): List<Operation> = fn(accumulator)

    fun getComputedBools(fn: (Int) -> List<Boolean>): List<Boolean> = fn(accumulator)

    fun getComputedLongs(fn: (Int) -> List<Long>): List<Long> = fn(accumulator)

    // Multi-param callback with collection
    fun computeWithScores(base: Int, callback: (List<Int>, String) -> Unit) {
        callback(listOf(base, base * 2, base * 3), "computed_$base")
    }

    // ── List<DataClass> as param ─────────────────────────────────────────

    fun sumPoints(points: List<Point>): Int {
        accumulator = points.sumOf { it.x + it.y }
        return accumulator
    }

    fun sumPointsOrNull(points: List<Point>?): Int {
        accumulator = points?.sumOf { it.x + it.y } ?: -1
        return accumulator
    }

    fun countTaggedPoints(items: List<TaggedPoint>): Int {
        return items.size
    }

    fun describeNamedValues(items: List<NamedValue>): String {
        return items.joinToString(", ") { "${it.name}=${it.value}" }
    }

    fun sumRects(rects: List<Rect>): Int {
        return rects.sumOf { it.topLeft.x + it.topLeft.y + it.bottomRight.x + it.bottomRight.y }
    }

    fun firstPointOrDefault(points: List<Point>): Point {
        return points.firstOrNull() ?: Point(0, 0)
    }

    fun describePersons(persons: List<Person>): String {
        return persons.joinToString("; ") { "${it.name}(${it.age}) @ ${it.address.street}, ${it.address.city}" }
    }

    fun oldestPersonAge(persons: List<Person>): Int {
        return persons.maxOfOrNull { it.age } ?: -1
    }

    // ── DataClass with collection fields ────────────────────────────────────

    fun getTaggedList(): TaggedList {
        return TaggedList(label.ifEmpty { "default" }, listOf(accumulator, accumulator * 2, accumulator * 3))
    }

    fun applyTaggedList(tl: TaggedList): Int {
        label = tl.label
        accumulator = tl.scores.sum()
        return accumulator
    }

    fun getMetadataHolder(): MetadataHolder {
        return MetadataHolder(label.ifEmpty { "calc" }, mapOf("current" to accumulator, "scale" to scale.toInt()))
    }

    fun applyMetadataHolder(mh: MetadataHolder): String {
        label = mh.name
        accumulator = mh.metadata.values.sum()
        return "$label:$accumulator"
    }

    fun getMultiCollDC(): MultiCollDC {
        return MultiCollDC(
            tags = listOf(label.ifEmpty { "default" }, "item_$accumulator"),
            flags = listOf(accumulator > 0, accumulator % 2 == 0),
            counts = listOf(accumulator, accumulator + 1, accumulator + 2),
        )
    }

    // ── ByteArray in collections ──────────────────────────────────────────

    fun getByteChunks(): List<ByteArray> {
        return listOf(
            byteArrayOf(1, 2, 3),
            byteArrayOf(4, 5, 6, 7),
            ByteArray(accumulator.coerceAtLeast(0)) { (it % 256).toByte() },
        )
    }

    fun countByteChunks(chunks: List<ByteArray>): Int {
        accumulator = chunks.sumOf { it.size }
        return accumulator
    }

    // ── ByteArray DC field ──────────────────────────────────────────────────

    fun getBinaryPayload(): BinaryPayload {
        return BinaryPayload("payload_$accumulator", ByteArray(accumulator.coerceAtLeast(0)) { (it % 256).toByte() })
    }

    fun applyBinaryPayload(bp: BinaryPayload): Int {
        label = bp.name
        accumulator = bp.data.size
        return accumulator
    }

    // ── Nested collections ──────────────────────────────────────────────────

    fun getMatrix(): List<List<Int>> {
        return listOf(
            listOf(accumulator, accumulator + 1),
            listOf(accumulator * 2, accumulator * 2 + 1),
        )
    }

    fun sumMatrix(matrix: List<List<Int>>): Int {
        accumulator = matrix.sumOf { row -> row.sum() }
        return accumulator
    }

    fun getTagGrid(): List<List<String>> {
        return listOf(
            listOf("a_$accumulator", "b_$accumulator"),
            listOf("c_$accumulator"),
        )
    }

    // ── Nullable collections ────────────────────────────────────────────────

    fun getScoresOrNull(): List<Int>? = if (accumulator != 0) listOf(accumulator, accumulator * 2) else null

    fun getLabelsOrNull(): List<String>? = if (label.isNotEmpty()) listOf(label, "extra") else null

    fun sumAllOrNull(values: List<Int>?): Int {
        if (values == null) return -1
        accumulator = values.sum()
        return accumulator
    }

    fun getOpsOrNull(): Set<Operation>? = if (accumulator > 0) setOf(lastOperation, Operation.ADD) else null

    fun getMetadataOrNull(): Map<String, Int>? = if (accumulator != 0) mapOf("val" to accumulator) else null

    // ── Extra collection edge-case methods ──────────────────────────────────

    fun getSingletonList(): List<Int> = listOf(accumulator)

    fun getEmptyList(): List<Int> = emptyList()

    fun getLargeList(size: Int): List<Int> = List(size) { it * accumulator }

    fun reverseList(values: List<Int>): List<Int> {
        val reversed = values.reversed()
        accumulator = reversed.firstOrNull() ?: 0
        return reversed
    }

    fun filterPositive(values: List<Int>): List<Int> = values.filter { it > 0 }

    fun getEmptyStringList(): List<String> = emptyList()

    fun repeatLabel(count: Int): List<String> = List(count) { "${label.ifEmpty { "item" }}_$it" }

    fun transformStrings(values: List<String>): List<String> = values.map { it.uppercase() }

    fun getSingletonMap(): Map<String, Int> = mapOf("only" to accumulator)

    fun getEmptyMap(): Map<String, Int> = emptyMap()

    fun mergeMapValues(a: Map<String, Int>, b: Map<String, Int>): Map<String, Int> = a + b

    fun getEmptySet(): Set<Int> = emptySet()

    fun intersectSets(a: Set<Int>, b: Set<Int>): Set<Int> = a.intersect(b)

    fun onLargeListReady(size: Int, callback: (List<Int>) -> Unit) {
        callback(List(size) { it })
    }

    fun onEmptyListReady(callback: (List<Int>) -> Unit) {
        callback(emptyList())
    }

    fun getScoresOrNullByLabel(): List<String>? = if (label.isEmpty()) null else listOf(label)

    fun getNullableSetByAccum(): Set<Int>? = if (accumulator < 0) null else setOf(accumulator, accumulator + 1)

    fun getNullableMapByLabel(): Map<String, String>? = if (label.isEmpty()) null else mapOf("label" to label)

    // ── Companion object ────────────────────────────────────────────────────

    companion object {
        val VERSION: String = "2.0"
        var instanceCount: Int = 0

        fun version(): String = VERSION
        fun create(initial: Int): Calculator {
            instanceCount++
            return Calculator(initial)
        }
    }
}
