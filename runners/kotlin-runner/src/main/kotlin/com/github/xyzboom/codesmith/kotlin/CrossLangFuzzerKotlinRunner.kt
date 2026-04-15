package com.github.xyzboom.codesmith.kotlin

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.double
import com.github.xyzboom.codesmith.CommonCompilerRunner
import com.github.xyzboom.codesmith.CompileResult
import com.github.xyzboom.codesmith.ICompiler
import com.github.xyzboom.codesmith.RunMode
import com.github.xyzboom.codesmith.data.DataRecorder
import com.github.xyzboom.codesmith.generator.IrDeclGenerator
import com.github.xyzboom.codesmith.ir.IrProgram
import com.github.xyzboom.codesmith.ir.Language
import com.github.xyzboom.codesmith.ir.deepCopy
import com.github.xyzboom.codesmith.minimize.MinimizeRunner2
import com.github.xyzboom.codesmith.mutator.IrMutator
import com.github.xyzboom.codesmith.printer.IrProgramPrinter
import com.github.xyzboom.codesmith.recordCompileResult
import com.github.xyzboom.codesmith.serde.gson
import com.github.xyzboom.codesmith.utils.mkdirsIfNotExists
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import org.jetbrains.kotlin.test.TestJdkKind
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives
import org.jetbrains.kotlin.test.services.KotlinTestInfo
import org.jetbrains.kotlin.test.util.KtTestUtil
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.measureTime

val testInfo = run {
    KtTestUtil.getJdk8Home()
    KtTestUtil.getJdk17Home()
    KotlinTestInfo("CrossLangFuzzerKotlinRunner", "main", emptySet())
}

class CrossLangFuzzerKotlinRunner : CommonCompilerRunner("kotlin") {
    companion object {
        private val logger = KotlinLogging.logger {}
        fun TestConfigurationBuilder.config() {
            defaultDirectives {
                +CodegenTestDirectives.IGNORE_DEXING
            }
        }
    }

    // ========== 命令行参数 ==========
    private val enableCoverageGuide: Boolean by option("--coverage-guide", "-cg")
        .flag(default = false)
        .help("Enable coverage-guided fuzzing")

    private val coverageCheckInterval: Int by option("--coverage-interval", "-ci")
        .int()
        .default(1)
        .help("Check coverage every N iterations")

    private val maxCorpusSize: Int by option("--corpus-size", "-cs")
        .int()
        .default(1000)
        .help("Maximum corpus size")

    private val seedSelectionProb: Double by option("--seed-prob", "-sp")
        .double()
        .default(0.8)
        .help("Probability of selecting from corpus")

    private val topSeedPercent: Double by option("--top-percent", "-tp")
        .double()
        .default(0.1)
        .help("Percentage of top seeds to select from")

    private val maxRunHours: Int by option("--max-hours", "-mh")
        .int()
        .default(0)
        .help("Maximum runtime in hours (0 = unlimited)")

    private val parallelThreads: Int by option("--parallel", "-p")
        .int()
        .default(1)
        .help("Number of parallel threads")

    private val k1CompilerPath: String by option("--k1-path")
        .default("E:/overprocess/CrossLangFuzzer/tool/kotlin-compiler-2.1.20-Beta1/kotlinc")
        .help("Path to K1 compiler")

    private val k2CompilerPath: String by option("--k2-path")
        .default("E:/overprocess/CrossLangFuzzer/tool/kotlin-compiler-2.2.21/kotlinc")
        .help("Path to K2 compiler")

    private val jacocoAgentPath: String by option("--jacoco-path")
        .default("E:/overprocess/CrossLangFuzzer/tool/jacoco-0.8.12/lib/jacocoagent.jar")
        .help("Path to JaCoCo agent JAR")

    // ========== 能量调度相关 ==========

    data class Seed(
        val program: IrProgram,
        var coverage: Int,
        val hash: String,
        var timesSelected: Int = 0,
        var energy: Double = 1.0,
        var lastCoverageGain: Int = 0,
        var totalNewPaths: Int = 0,      // 累计实际覆盖率增益
        var staleCount: Int = 0,
        var totalExecTime: Long = 0,
        var execCount: Int = 0
    )

    private val programHashes = mutableSetOf<String>()
    private val corpus = mutableListOf<Seed>()
    private val programStats = mutableMapOf<String, Seed>()
    private val seedEnergyMap = TreeMap<Double, Seed>()
    private var needUpdateEnergyMap = true
    private var currentSeedHash: String? = null
    private var bestCoverage = 0
    private var totalPathsDiscovered = 0

    // ========== 辅助方法 ==========

    private fun buildCompilerClasspath(kotlincHomePath: String): String {
        val kotlincHome = File(kotlincHomePath)
        require(kotlincHome.exists()) { "kotlinc path not found: ${kotlincHome.absolutePath}" }
        val libDir = File(kotlincHome, "lib")
        require(libDir.exists()) { "kotlinc lib not found: ${libDir.absolutePath}" }
        val jars = libDir.listFiles()
            ?.filter { it.extension == "jar" }
            ?: error("No jar files in ${libDir.absolutePath}")
        return jars.joinToString(File.pathSeparator) { it.absolutePath }
    }

    private val testers by lazy {
        listOf(
            ForkedK1Compiler(
                jdk = TestJdkKind.FULL_JDK,
                kotlinCompilerClasspath = buildCompilerClasspath(k1CompilerPath),
                jacocoAgentPath = jacocoAgentPath
            ),
            ForkedKotlinCompiler(
                jdk = TestJdkKind.FULL_JDK_17,
                kotlinCompilerClasspath = buildCompilerClasspath(k2CompilerPath),
                jacocoAgentPath = jacocoAgentPath
            )
        )
    }

    private val minimizeRunner by lazy { MinimizeRunner2(this) }
    private val recorder = DataRecorder()
    private var iteration = 0

    // ========== 程序哈希和大小 ==========

    private fun getProgramHash(program: IrProgram): String {
        val content = IrProgramPrinter(Language.KOTLIN).printToSingle(program)
        return content.hashCode().toString()
    }

    private fun getProgramSize(program: IrProgram): Int {
        val content = IrProgramPrinter(Language.KOTLIN).printToSingle(program)
        return content.length
    }

    // ========== 能量计算（修复版） ==========

    private fun calculateSeedEnergy(seed: Seed): Double {
        var energy = 1.0

        // 历史收益因子（累计发现的实际覆盖率增益）
        val historyFactor = when {
            seed.totalNewPaths > 2000 -> 1.5
            seed.totalNewPaths > 1000 -> 1.3
            seed.totalNewPaths > 500 -> 1.2
            seed.totalNewPaths > 100 -> 1.1
            seed.totalNewPaths > 0 -> 1.0 //
            else -> 1.0                 //
        }
        energy *= historyFactor

        // 新鲜度因子（最近发现新路径，能量高）
        val recencyFactor = when {
            seed.lastCoverageGain > 0 -> 1.5
            seed.staleCount == 0 -> 1.3
            seed.staleCount < 3 ->0.7
            else -> 0.1
        }
        energy *= recencyFactor

        // 大小因子（程序越小，能量越高）
        val programSize = getProgramSize(seed.program)
        val sizeFactor = when {
            programSize < 9000 -> 1.1
            else -> 0.9
        }
        energy *= sizeFactor

        // 探索因子（被选中次数少，能量高）
        val explorationFactor = when {
            seed.timesSelected < 2 -> 1.3
            seed.timesSelected < 5 -> 0.8
            seed.timesSelected < 10 -> 0.1
            else -> 0.1
        }
        energy *= explorationFactor
        val coverageFactor = when {
            bestCoverage-seed.coverage > 1000 ->0.8
            bestCoverage-seed.coverage > 500 ->0.9
            bestCoverage-seed.coverage > 0->1.0
            bestCoverage-seed.coverage <0 ->1.2
            else -> 1.0
        }
        energy *= coverageFactor
        return energy.coerceIn(0.5, 15.0)
    }

    private fun updateAllSeedEnergy() {
        corpus.forEach { seed ->
            val oldEnergy = seed.energy
            seed.energy = calculateSeedEnergy(seed)
            if (Math.abs(oldEnergy - seed.energy) > 0.01) {
                println("  📊 Energy updated: ${seed.hash.take(8)} $oldEnergy -> ${"%.2f".format(seed.energy)}")
            }
        }
        needUpdateEnergyMap = true
    }

    private fun updateEnergyMap() {
        seedEnergyMap.clear()
        var cumulative = 0.0
        corpus.sortedByDescending { it.energy }.forEach { seed ->
            cumulative += seed.energy
            seedEnergyMap[cumulative] = seed
        }
        needUpdateEnergyMap = false
        println("  📊 Energy map rebuilt, total energy: ${"%.2f".format(cumulative)}")
    }

    private fun updateSeedWithNewPaths(seedHash: String, actualGain: Int, execTime: Long) {
        val seed = programStats[seedHash] ?: return

        if (actualGain > 0) {
            // 实际增益用于累计
            seed.totalNewPaths += actualGain
            seed.staleCount = 0

            seed.lastCoverageGain = actualGain

            totalPathsDiscovered += actualGain

        } else {
            seed.lastCoverageGain = 0
            //无效调用
            seed.staleCount++
            if (seed.staleCount == 5) {
                println("⚠️ Seed 已经五次调用没有覆盖率增加了")
            }
        }

        seed.totalExecTime += execTime
        seed.execCount++
    }

    private fun addNewSeed(program: IrProgram, coverage: Int, hash: String) {
        // 检查是否重复
        if (programHashes.contains(hash)) {
            println("  ⚠️ Seed already exists, skipping")
            return
        }

        val newSeed = Seed(
            program = program,
            coverage = coverage,
            hash = hash,
            timesSelected = 0,
            energy = 1.0,
            lastCoverageGain = 0,
            totalNewPaths = 0,
            staleCount = 0,
            totalExecTime = 0,
            execCount = 0
        )
        newSeed.energy = calculateSeedEnergy(newSeed)
        corpus.add(newSeed)
        programHashes.add(hash)
        programStats[hash] = newSeed
        needUpdateEnergyMap = true

        println("  🌱 New seed: coverage=$coverage, energy=${"%.2f".format(newSeed.energy)}, size=${getProgramSize(program)}")
    }

    private fun printEnergyStats() {
        if (corpus.isEmpty()) return
        val topSeeds = corpus.sortedByDescending { it.energy }.take(5)
        println("\n📈 Top 5 seeds by energy:")
        println("  ┌────────────┬────────────┬────────────┬────────────┬────────────┬────────────┐")
        println("  │ 覆盖率     │ 能量       │ 选择次数   │ 累计增益   │ 连续无新   │ 状态       │")
        println("  ├────────────┼────────────┼────────────┼────────────┼────────────┼────────────┤")
        topSeeds.forEach { seed ->
            val status = when {
                seed.lastCoverageGain > 0 -> "活跃"
                seed.staleCount > 3 -> "陈旧"
                else -> "正常"
            }
            println("  │ ${seed.coverage.toString().padEnd(10)} │ " +
                    "${"%.2f".format(seed.energy).padEnd(10)} │ " +
                    "${seed.timesSelected.toString().padEnd(10)} │ " +
                    "${seed.totalNewPaths.toString().padEnd(10)} │ " +
                    "${seed.staleCount.toString().padEnd(10)} │ " +
                    "$status │")
        }
        println("  └────────────┴────────────┴────────────┴────────────┴────────────┴────────────┘")
    }


    // ========== 种子选择（带详细调试） ==========

    private fun chooseSeedByEnergy(): IrProgram {
        if (corpus.isEmpty()) error("Corpus empty")

        println("\n" + "═".repeat(60))
        println("🎲 种子选择开始")
        println("═".repeat(60))
        println("总种子数: ${corpus.size}, 最高覆盖率: $bestCoverage, 总路径数: $totalPathsDiscovered")

        // 打印所有种子的能量分布
        println("\n📊 种子能量列表:")
        println("┌────┬────────────┬────────────┬────────────┬────────────┬────────────┐")
        println("│ #  │ 覆盖率     │ 能量       │ 选择次数   │ 累计增益   │ 状态       │")
        println("├────┼────────────┼────────────┼────────────┼────────────┼────────────┤")

        val sortedSeeds = corpus.sortedByDescending { it.energy }
        sortedSeeds.forEachIndexed { index, seed ->
            val status = when {
                seed.lastCoverageGain > 0 -> "🔥"
                seed.staleCount > 3 -> "😴"
                else -> "✅"
            }
            println("│ ${(index + 1).toString().padEnd(2)} │ " +
                    "${seed.coverage.toString().padEnd(10)} │ " +
                    "${"%.2f".format(seed.energy).padEnd(10)} │ " +
                    "${seed.timesSelected.toString().padEnd(10)} │ " +
                    "${seed.totalNewPaths.toString().padEnd(10)} │ " +
                    "$status │")
        }
        println("└────┴────────────┴────────────┴────────────┴────────────┴────────────┘")

        // 重建能量映射（如果需要）
        if (needUpdateEnergyMap) {
            println("\n🔄 重建能量映射表...")
            updateEnergyMap()
        }

        // 打印能量区间分布
        println("\n🎲 能量轮盘区间:")
        println("┌──────────┬────────────┬────────────┬────────────┬────────────┐")
        println("│ 种子     │ 能量       │ 区间起点   │ 区间终点   │ 区间长度   │")
        println("├──────────┼────────────┼────────────┼────────────┼────────────┤")

        val entries = seedEnergyMap.entries.toList()
        var prevEnd = 0.0
        entries.forEachIndexed { index, entry ->
            val start = if (index == 0) 0.0 else entries[index - 1].key
            val end = entry.key
            val seed = entry.value
            val length = end - start
            println("│ ${seed.hash.take(6).padEnd(8)} │ " +
                    "${"%.2f".format(seed.energy).padEnd(10)} │ " +
                    "${"%.2f".format(start).padEnd(10)} │ " +
                    "${"%.2f".format(end).padEnd(10)} │ " +
                    "${"%.2f".format(length).padEnd(10)} │")
        }
        println("└──────────┴────────────┴────────────┴────────────┴────────────┘")

        // 执行选择
        val total = seedEnergyMap.lastKey()
        val target = Math.random() * total
        val entry = seedEnergyMap.ceilingEntry(target)
        val selected = entry?.value ?: corpus.random()

        // 计算选中的区间
        var cumulativeBefore = 0.0
        var selectedStart = 0.0
        for ((key, seed) in seedEnergyMap.entries) {
            if (seed == selected) {
                selectedStart = cumulativeBefore
                break
            }
            cumulativeBefore = key
        }
        val selectedEnd = cumulativeBefore + selected.energy

        println("\n🎯 选择结果:")
        println("  总能量: ${"%.2f".format(total)}")
        println("  随机数: ${"%.4f".format(target)} (0-$total)")
        println("  命中区间: [${"%.2f".format(selectedStart)}, ${"%.2f".format(selectedEnd)})")

        // 可视化
        val pos = (target / total * 40).toInt()
        val startPos = (selectedStart / total * 40).toInt()
        val endPos = (selectedEnd / total * 40).toInt()

        print("\n  能量分布: ")
        for (i in 0..40) {
            when {
                i == pos -> print("🔴")
                i in startPos..endPos -> print("█")
                else -> print("░")
            }
        }
        println()

        println("\n✅ 选中种子详情:")
        println("  ├─ Hash: ${selected.hash.take(16)}...")
        println("  ├─ 覆盖率: ${selected.coverage}")
        println("  ├─ 能量值: ${"%.2f".format(selected.energy)}")
        println("  ├─ 被选次数: ${selected.timesSelected + 1}")
        println("  ├─ 累计增益: ${selected.totalNewPaths}")
        println("  ├─ 连续无新: ${selected.staleCount}")
        println("  ├─ 程序大小: ${getProgramSize(selected.program)} 字符")
        println("  └─ 执行次数: ${selected.execCount}")
        println("═".repeat(60) + "\n")

        // 更新统计
        selected.timesSelected++
        currentSeedHash = selected.hash

        return selected.program.deepCopy()
    }

    private fun chooseSeed(): IrProgram {
        if (corpus.isEmpty()) error("Corpus empty")

        val random = Math.random()
        val shouldSelect = enableCoverageGuide && random < seedSelectionProb

        println("\n🎲 选择模式: enableCoverageGuide=$enableCoverageGuide, " +
                "seedSelectionProb=$seedSelectionProb, random=$random, " +
                "shouldSelect=$shouldSelect")

        return if (shouldSelect) {
            println("  → 从语料库选择")
            chooseSeedByEnergy()
        } else {
            println("  → 随机生成新程序")
            currentSeedHash = null
            IrDeclGenerator(runConfig.generatorConfig).genProgram()
        }
    }

    // ========== 编译和记录 ==========

    fun doDifferentialCompile(program: IrProgram): List<CompileResult> {
        val fileContent = IrProgramPrinter(Language.KOTLIN).printToSingle(program)
        return testers.map { it.testProgram(fileContent) }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun doOneRoundDifferentialAndRecord(program: IrProgram, throwException: Boolean, execTime: Long = 0) {

        iteration++
        println("\n${"=".repeat(60)}")
        println("🔄 iteration = $iteration")
        println("${"=".repeat(60)}")
        //遍历corpus打印所有元素
        for (seed in corpus) {
            println("\u001B[31m  ${seed.hash.take(8)}: coverage=${seed.coverage}, energy=${"%.2f".format(seed.energy)}，staleCount=${seed.staleCount}, execCount=${seed.execCount}，totalNewPaths=${seed.totalNewPaths}，timesSelected=${seed.timesSelected}，totalExecTime=${seed.totalExecTime}，lastCoverageGain=${seed.lastCoverageGain}，lastExecTime=${seed.totalExecTime}\u001B[0m")
        }
        val fileContent = IrProgramPrinter(Language.KOTLIN).printToSingle(program)
        val testResults = testers.map { it.testProgram(fileContent) }
        val resultSet = testResults.toSet()

        if (resultSet.size != 1) {
            val (minimize, minResult) = try {
                minimizeRunner.minimize(program, testResults, recorder.recordCompilers(testers))
            } catch (e: Throwable) {
                println("Minimize failed: ${e.message}")
                e.printStackTrace()
                null to null
            }

            val anySimilar = if (enableGED && minimize != null) false else false
            if (anySimilar) {
                recordCompileResult(Language.KOTLIN, program, testResults, minimize, minResult)
            } else {
                recordCompileResult(
                    Language.KOTLIN,
                    program,
                    testResults,
                    minimize,
                    minResult,
                    outDir = nonSimilarOutDir
                )
            }
            if (throwException) {
                println("❌ Find a compiler bug with -s, stop the runner")
                exitProcess(0)
            }
        }

        // Coverage 读取逻辑
        try {
            val coverage = getCoverageReader().getInstructionCoverage()
            val coverageK1 = getCoverageReaderK1().getInstructionCoverage()
            println("   coverage: $coverage")
            println("   coverageK1: $coverageK1")
            val hash = getProgramHash(program)
            val actualGain = coverage - bestCoverage


            // 更新当前种子的统计
            if (currentSeedHash != null && actualGain >= 0) {
                updateSeedWithNewPaths(currentSeedHash!!, actualGain, execTime)
            }
            //更新能量
            val seed = programStats[currentSeedHash] ?: return
            calculateSeedEnergy(seed)
            needUpdateEnergyMap = true
            // 覆盖率提升时的处理
            if (coverage > bestCoverage) {
                val gain = coverage - bestCoverage
                val oldBest = bestCoverage


                // ⭐ 关键修复：第一次获得有效覆盖率后，删除初始脏种子
                if (oldBest == 0 && coverage > 0 && corpus.size > 0) {
                    val dirtySeed = corpus.find { it.coverage == 0 }
                    if (dirtySeed != null) {
                        corpus.remove(dirtySeed)
                        programHashes.remove(dirtySeed.hash)
                        programStats.remove(dirtySeed.hash)
                        needUpdateEnergyMap = true
                        println("  🗑️ Removed initial dirty seed (coverage=0) after first valid coverage")
                    }
                }

                // 新种子添加
                if (!programHashes.contains(hash)) {
                    val copy = program.deepCopy()
                    addNewSeed(copy, coverage, hash)

                    // 语料库大小控制
                    if (corpus.size > maxCorpusSize) {
                        val toRemove = corpus.minByOrNull { it.energy }
                        toRemove?.let {
                            corpus.remove(it)
                            programHashes.remove(it.hash)
                            programStats.remove(it.hash)
                            println("  🗑️ Removed low-energy seed: ${it.hash.take(8)}")
                        }
                    }

                    saveInterestingProgram(copy, coverage)
                }  else {
                // 更新已有种子的覆盖率
                val existingSeed = programStats[hash]
                existingSeed?.let { seed ->
                    val oldEnergy = seed.energy
                    seed.coverage = coverage
                    // ⭐ 关键：重新计算能量
                    seed.energy = calculateSeedEnergy(seed)
                    needUpdateEnergyMap = true

                    println("  📈 Updated existing seed: coverage=$coverage, " +
                            "energy ${"%.2f".format(oldEnergy)} -> ${"%.2f".format(seed.energy)}")
                }


                println("🎉 New coverage record: $bestCoverage (+$gain)")
                println("📊 Corpus: ${corpus.size} seeds, Total paths: $totalPathsDiscovered")
            }
//                logCorpusStatus()

            // 定期输出能量统计
            if (iteration % 50 == 0) {
                printEnergyStats()
                updateAllSeedEnergy()
            }
                bestCoverage = coverage
            }

        } catch (e: Exception) {
            println("❌ Coverage read failed: ${e.message}")

        }
    }

    private fun doReduce(program: IrProgram): IrProgram? {
        val fileContent = IrProgramPrinter(Language.KOTLIN).printToSingle(program)
        val testResults = testers.map { it.testProgram(fileContent) }
        recorder.addProgram("ori", program)
        val reduced: IrProgram
        val reduceTime = measureTime {
            val (result, _) = try {
                minimizeRunner.minimize(program, testResults, recorder.recordCompilers(testers))
            } catch (_: Throwable) {
                return null
            }
            reduced = result
        }
        recorder.addProgram("reduced", reduced)
        recorder.mergeData("reduceTime", reduceTime, Duration::plus)
        return reduced
    }

    private suspend fun CoroutineScope.runOnInputIRFiles() {
        val inputIRFiles = inputIRFiles!!
        val jobs = mutableListOf<Job>()
        for (file in inputIRFiles) {
            val job = launch {
                val prog = file.reader().use { gson.fromJson(it, IrProgram::class.java) }
                val reducedFile = File(file.parentFile, file.name + ".reduced")
                val reducedCache = if (reducedFile.exists()) {
                    reducedFile.reader().use { gson.fromJson(it, IrProgram::class.java) }
                } else null
                when (runMode) {
                    RunMode.DifferentialTest -> doOneRoundDifferentialAndRecord(prog, false)
                    RunMode.NormalTest -> {}
                    RunMode.ReduceOnly -> {
                        if (reducedCache != null && useCache) {
                            recorder.addProgram("ori", prog)
                            recorder.addProgram("reduced", reducedCache)
                        } else {
                            val reduced = doReduce(prog)
                            if (useCache && reduced != null) {
                                reducedFile.writer().use { gson.toJson(reduced, it) }
                            }
                        }
                    }
                    RunMode.GenerateIROnly -> throw IllegalStateException("Using input IR file, cannot run GenerateIROnly mode.")
                }
                logger.info { gson.toJson(recorder.programCount) }
                logger.info { gson.toJson(recorder.programData) }
                logger.info { recorder.getData<Duration>("reduceTime").toString() }
                logger.info { "compile times: ${recorder.getCompileTimes()}" }
            }
            jobs.add(job)
        }
        jobs.joinAll()
        logger.info { gson.toJson(recorder.programCount) }
        logger.info { gson.toJson(recorder.programData) }
        logger.info { recorder.getData<Duration>("reduceTime").toString() }
        logger.info { "compile times: ${recorder.getCompileTimes()}" }
    }

    override val availableCompilers: Map<String, ICompiler> get() = TODO("Not yet implemented")
    override val defaultCompilers: Map<String, ICompiler> get() = TODO("Not yet implemented")

    @OptIn(ExperimentalCoroutinesApi::class, ExperimentalStdlibApi::class)
    override fun runnerMain() {
        println("=".repeat(60))
        println("⚙️ Configuration:")
        println("=".repeat(60))
        println("  Coverage-guided: ${if (enableCoverageGuide) "ON" else "OFF"}")
        if (enableCoverageGuide) {
            println("  Coverage interval: $coverageCheckInterval")
            println("  Max corpus size: $maxCorpusSize")
            println("  Seed selection probability: $seedSelectionProb")
            println("  Top seed percentage: $topSeedPercent")
        }
        println("  Max run hours: ${if (maxRunHours > 0) "$maxRunHours hours" else "unlimited"}")
        println("  Parallel threads: $parallelThreads")
        println("  Stop on error: ${if (stopOnErrors) "ON" else "OFF"}")
        println("=".repeat(60))

        logger.info { "start kotlin runner" }

        if (enableCoverageGuide) {
            println("\n🌱 初始化语料库...")
            val generator = IrDeclGenerator(runConfig.generatorConfig)
            val initialProg = generator.genProgram()
            val initialHash = getProgramHash(initialProg)
            addNewSeed(initialProg, 0, initialHash)
            updateAllSeedEnergy()
            println("  Initial corpus size = ${corpus.size}")
        }

        val beijingZone = ZoneId.of("Asia/Shanghai")
        val now = LocalDateTime.now(beijingZone)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        println("\n⏰ 当前北京时间: ${now.format(formatter)}")

        val endTime = if (maxRunHours > 0) {
            val end = now.plusHours(maxRunHours.toLong())
            println("⏰ 计划结束时间: ${end.format(formatter)}")
            end
        } else {
            println("⏰ 运行模式: 无限运行（直到手动停止或发现错误）")
            null
        }

        val i = AtomicInteger(0)
        val inputIRFiles = inputIRFiles

        if (inputIRFiles != null) {
            runBlocking(Dispatchers.IO.limitedParallelism(32)) { runOnInputIRFiles() }
            return
        }

        when (runMode) {
            RunMode.DifferentialTest -> {
                runBlocking(Dispatchers.IO.limitedParallelism(parallelThreads)) {
                    val jobs = mutableListOf<Job>()
                    repeat(parallelThreads) {
                        val job = launch {
                            val threadName = Thread.currentThread().name
                            while (isActive && (endTime == null || LocalDateTime.now(beijingZone) < endTime)) {
                                val generator = IrDeclGenerator(runConfig.generatorConfig)
                                val prog = if (enableCoverageGuide) chooseSeed() else generator.genProgram()

                                repeat(1) {
                                    if (!isActive || (endTime != null && LocalDateTime.now(beijingZone) >= endTime))
                                        return@launch
                                    val dur = measureTime {
                                        doOneRoundDifferentialAndRecord(prog, stopOnErrors)
                                    }
                                    println("$threadName ${i.incrementAndGet()}:${dur}\t\t")
                                    generator.shuffleLanguage(prog)
                                }

                                repeat(1) {
                                    if (!isActive || (endTime != null && LocalDateTime.now(beijingZone) >= endTime))
                                        return@launch
                                    val mutator = IrMutator(
                                        runConfig.mutatorConfig,
                                        generator = generator
                                    )
                                    val copiedProg = prog.deepCopy()
                                    if (mutator.mutate(copiedProg)) {
                                        repeat(1) {
                                            if (!isActive || (endTime != null && LocalDateTime.now(beijingZone) >= endTime))
                                                return@launch
                                            val dur = measureTime {
                                                doOneRoundDifferentialAndRecord(
                                                    copiedProg,
                                                    stopOnErrors
                                                )
                                            }
                                            println("$threadName ${i.incrementAndGet()}:${dur}\t\t")
                                            generator.shuffleLanguage(copiedProg)
                                        }
                                    }
                                }
                            }
                        }
                        jobs.add(job)
                    }

                    if (maxRunHours > 0) {
                        withTimeoutOrNull(maxRunHours * 3600 * 1000L) { jobs.joinAll() }
                        println("✅ 已达到${maxRunHours}小时运行时间，程序退出")
                    } else {
                        jobs.joinAll()
                    }
                }
            }
            RunMode.NormalTest -> println("NormalTest mode - not fully implemented")
            RunMode.GenerateIROnly -> {
                val endTimeNano = if (maxRunHours > 0) System.nanoTime() + maxRunHours * 3600 * 1_000_000_000L else Long.MAX_VALUE
                while (System.nanoTime() < endTimeNano) {
                    val generator = IrDeclGenerator(runConfig.generatorConfig)
                    val prog = generator.genProgram()
                    val outDir = File(generateIROnlyOutDir, System.nanoTime().toHexString()).mkdirsIfNotExists()
                    File(outDir, "main.json").writer().use { gson.toJson(prog, it) }
                }
                if (maxRunHours > 0) println("✅ 已达到${maxRunHours}小时运行时间，程序退出")
            }
            RunMode.ReduceOnly -> throw IllegalStateException("No input IR file, cannot run ReduceOnly mode.")
        }
    }


    // 在类中添加这个辅助方法
    private fun logCorpusStatus() {
        val logFile = File("corpus_status.txt")
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))

        logFile.appendText(
            """
        ========================================
        时间: $timestamp
        迭代次数: $iteration
        最佳覆盖率: $bestCoverage
        总路径数: $totalPathsDiscovered
        种子数量: ${corpus.size}
        
        种子详情:
        ┌────┬────────────┬────────────┬────────────┬────────────┬────────────┬────────────┬────────────────────────────────────────┐
        │ #  │ 覆盖率     │ 能量       │ 选择次数   │ 累计增益   │ 连续无新   │ 程序大小   │ 能量计算说明                          │
        ├────┼────────────┼────────────┼────────────┼────────────┼────────────┼────────────┼────────────────────────────────────────┤
        """.trimIndent()
        )
        logFile.appendText(
         "\n"
        )

        corpus.forEachIndexed { index, seed ->
            // 历史收益因子

            // 大小因子
            val size = getProgramSize(seed.program)


                // 理论能量
            val theoreticalEnergy = calculateSeedEnergy(seed)


            val calculation = "$theoreticalEnergy"
            // 修复：使用 padEnd 确保对齐，不要换行
            val line = String.format(
                "│ %-2d │ %-10d │ %-10.2f │ %-10d │ %-10d │ %-10d │ %-10d │ %-50s │\n",
                index + 1,
                seed.coverage,
                seed.energy,
                seed.timesSelected,
                seed.totalNewPaths,
                seed.staleCount,
                size,
                calculation
            )
            logFile.appendText(line)
        }

        logFile.appendText(
            """
        └────┴────────────┴────────────┴────────────┴────────────┴────────────┴────────────┴────────────────────────────────────────┘
        
        """.trimIndent()
        )

        println("📝 种子池状态已记录到 corpus_status.txt")

    }
}

// ========== Coverage 部分 ==========
private var coverageReader: JacocoCoverageReader? = null
private var coverageReaderK1: JacocoCoverageReader? = null
private val interestingDir = File("interesting-inputs").apply { mkdirs() }
private val startTime = kotlin.time.TimeSource.Monotonic.markNow()

private fun getCoverageReader(): JacocoCoverageReader {
    if (coverageReader == null) {
        coverageReader = JacocoCoverageReader(
            File("jacoco-output/jacoco.exec"),
            File("compiler-classes")
        )
    }
    return coverageReader!!
}

private fun getCoverageReaderK1(): JacocoCoverageReader {
    if (coverageReaderK1 == null) {
        coverageReaderK1 = JacocoCoverageReader(
            File("jacoco-output/k1.exec"),
            File("K1-classes/kotlin-compiler-2.1.20-Beta1")
        )
    }
    return coverageReaderK1!!
}

private fun saveInterestingProgram(program: IrProgram, coverage: Int) {
    val elapsedMin = startTime.elapsedNow().inWholeMilliseconds / 1000.0 / 60
    val formatted = "%.2f".format(elapsedMin)
    val outDir = File(interestingDir, "cov_${coverage}_t_${formatted}min")
    outDir.mkdirs()
    File(outDir, "program.json").writer().use { gson.toJson(program, it) }
    println("💾 Saved interesting program: coverage=$coverage at ${formatted}min")
}

fun main(args: Array<String>) {
    File("jacoco-output/jacoco.exec").takeIf { it.exists() }?.delete()?.let { println("Old coverage K2 file deleted") }
    File("jacoco-output/k1.exec").takeIf { it.exists() }?.delete()?.let { println("Old coverage K1 file deleted") }
    CrossLangFuzzerKotlinRunner().main(args)

}

