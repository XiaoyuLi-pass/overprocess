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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.measureTime

val testInfo = run {
    // make sure JDK HOME is set
    KtTestUtil.getJdk8Home()
    KtTestUtil.getJdk17Home()
    KotlinTestInfo("CrossLangFuzzerKotlinRunner", "main", emptySet())
}

class CrossLangFuzzerKotlinRunner : CommonCompilerRunner("kotlin") {
    companion object {
        private val logger = KotlinLogging.logger {}
        fun TestConfigurationBuilder.config() {
            defaultDirectives {
                +CodegenTestDirectives.IGNORE_DEXING // Avoids loading R8 from the classpath.
            }
        }
    }

    // ===== 新增命令行参数 =====
    private val enableCoverageGuide: Boolean by option("--coverage-guide", "-cg")
        .flag(default = false)
        .help("Enable coverage-guided fuzzing (default: false)")

    private val coverageCheckInterval: Int by option("--coverage-interval", "-ci")
        .int()
        .default(1)
        .help("Check coverage every N iterations (default: 1)")

    private val maxCorpusSize: Int by option("--corpus-size", "-cs")
        .int()
        .default(1000)
        .help("Maximum corpus size for coverage-guided fuzzing (default: 1000)")

    private val seedSelectionProb: Double by option("--seed-prob", "-sp")
        .double()
        .default(0.6)
        .help("Probability of selecting from top coverage seeds (default: 0.6)")

    private val topSeedPercent: Double by option("--top-percent", "-tp")
        .double()
        .default(0.1)
        .help("Percentage of top coverage seeds to select from (default: 0.1)")

    private val maxRunHours: Int by option("--max-hours", "-mh")
        .int()
        .default(0)
        .help("Maximum runtime in hours (0 = unlimited, default: 0)")

    private val parallelThreads: Int by option("--parallel", "-p")
        .int()
        .default(1)
        .help("Number of parallel threads (default: 1)")

    private val k1CompilerPath: String by option("--k1-path")
        .default("E:/overprocess/CrossLangFuzzer/tool/kotlin-compiler-2.1.20-Beta1/kotlinc")
        .help("Path to K1 compiler")

    private val k2CompilerPath: String by option("--k2-path")
        .default("E:/overprocess/CrossLangFuzzer/tool/kotlin-compiler-2.2.21/kotlinc")
        .help("Path to K2 compiler")

    private val jacocoAgentPath: String by option("--jacoco-path")
        .default("E:/overprocess/CrossLangFuzzer/tool/jacoco-0.8.12/lib/jacocoagent.jar")
        .help("Path to JaCoCo agent JAR")

    private fun buildCompilerClasspath(kotlincHomePath: String): String {
        val kotlincHome = File(kotlincHomePath)
        require(kotlincHome.exists()) {
            "kotlinc path not found: ${kotlincHome.absolutePath}"
        }
        val libDir = File(kotlincHome, "lib")
        require(libDir.exists()) {
            "kotlinc lib not found: ${libDir.absolutePath}"
        }
        val jars = libDir.listFiles()
            ?.filter { it.extension == "jar" }
            ?: error("No jar files in ${libDir.absolutePath}")
        return jars.joinToString(File.pathSeparator) { it.absolutePath }
    }

    // 使用命令行参数构建编译器路径
    private val testers by lazy {
        listOf<IKotlinCompiler>(
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

    fun doDifferentialCompile(program: IrProgram): List<CompileResult> {
        val fileContent = IrProgramPrinter(Language.KOTLIN).printToSingle(program)
        return testers.map { it.testProgram(fileContent) }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun doOneRoundDifferentialAndRecord(program: IrProgram, throwException: Boolean) {
        iteration++
        println("\u001B[31miteration = $iteration\u001B[0m")
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

            val anySimilar = if (enableGED && minimize != null) {
                false
            } else false
            if (anySimilar) {
                recordCompileResult(Language.KOTLIN, program, testResults, minimize, minResult)
            } else {
                recordCompileResult(
                    Language.KOTLIN, program, testResults, minimize, minResult, outDir = nonSimilarOutDir
                )
            }
            if (throwException) {
                println("Find a compiler bug with -s, stop the runner")
                exitProcess(0)
            }
        }

        // ===== Coverage 读取逻辑（仅在启用覆盖率引导时执行）=====

            try {
                val reader = getCoverageReader()
                val readerK1 = getCoverageReaderK1()
                val coverage = reader.getInstructionCoverage()
                val coverageK1 = readerK1.getInstructionCoverage()

                println("COVERAGEK2 = $coverage")
                println("COVERAGEK1 = $coverageK1")
                if (enableCoverageGuide && iteration % coverageCheckInterval == 0) {
                if (coverage > bestCoverage) {
                    bestCoverage = coverage
                    val copy = program.deepCopy()
                    corpus.add(Seed(copy, coverage))
                    if (corpus.size > maxCorpusSize) {
                        corpus.remove(corpus.minBy { it.coverage })
                    }
                    saveInterestingProgram(copy, coverage)
                    println("🔥 Coverage increased → $coverage")
                    println("🔥 Corpus size = ${corpus.size}")
                }
                }
            } catch (e: Exception) {
                println("Coverage read failed: ${e.message}")
            }

    }

    override val availableCompilers: Map<String, ICompiler>
        get() = TODO("Not yet implemented")
    override val defaultCompilers: Map<String, ICompiler>
        get() = TODO("Not yet implemented")

    private val recorder = DataRecorder()

    // ===== Coverage 控制 =====
    private var iteration = 0
    data class Seed(
        val program: IrProgram,
        val coverage: Int
    )
    private val corpus = mutableListOf<Seed>()

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
                    RunMode.DifferentialTest -> {
                        doOneRoundDifferentialAndRecord(prog, false)
                    }
                    RunMode.NormalTest -> {
                        // doOneRoundAndRecord(prog, false)
                    }
                    RunMode.ReduceOnly -> {
                        if (reducedCache != null && useCache) {
                            recorder.addProgram("ori", prog)
                            recorder.addProgram("reduced", reducedCache)
                        } else {
                            val reduced = doReduce(prog)
                            if (useCache && reduced != null) {
                                reducedFile.writer().use {
                                    gson.toJson(reduced, it)
                                }
                            }
                        }
                    }
                    RunMode.GenerateIROnly ->
                        throw IllegalStateException("Using input IR file, cannot run GenerateIROnly mode.")
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

    private fun chooseSeed(): IrProgram {
        if (corpus.isEmpty()) error("Corpus empty")

        return if (enableCoverageGuide && Math.random() < seedSelectionProb) {
            val sorted = corpus.sortedByDescending { it.coverage }
            val topSize = (sorted.size * topSeedPercent).toInt().coerceAtLeast(1)
            val top = sorted.take(topSize)
            top.random().program.deepCopy()
        } else {
            val generator = IrDeclGenerator(runConfig.generatorConfig)
            generator.genProgram()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class, ExperimentalStdlibApi::class)
    override fun runnerMain() {
        // 打印配置信息
        println("=".repeat(60))
        println("Configuration:")
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
        println("  K1 compiler path: $k1CompilerPath")
        println("  K2 compiler path: $k2CompilerPath")
        println("=".repeat(60))

        logger.info { "start kotlin runner" }

        // 初始化语料库（仅在启用覆盖率引导时）
        if (enableCoverageGuide) {
            val generator = IrDeclGenerator(runConfig.generatorConfig)
            val prog = generator.genProgram()
            corpus.add(Seed(prog, 0))
            println("Initial corpus size = ${corpus.size}")
        }

        // 打印当前系统时间
        val beijingZone = ZoneId.of("Asia/Shanghai")
        val now = LocalDateTime.now(beijingZone)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        val formatted = now.format(formatter)
        println("当前北京时间: $formatted")

        // 计算结束时间（0表示无限运行）
        val endTime = if (maxRunHours > 0) {
            val end = now.plusHours(maxRunHours.toLong())
            println("计划结束时间: ${end.format(formatter)}")
            end
        } else {
            println("运行模式: 无限运行（直到手动停止或发现错误）")
            null
        }

        val i = AtomicInteger(0)
        val inputIRFiles = inputIRFiles

        if (inputIRFiles != null) {
            runBlocking(Dispatchers.IO.limitedParallelism(32)) {
                runOnInputIRFiles()
            }
            return
        }

        when (runMode) {
            RunMode.DifferentialTest -> {
                runBlocking(Dispatchers.IO.limitedParallelism(parallelThreads)) {
                    val jobs = mutableListOf<Job>()
                    repeat(parallelThreads) {
                        val job = launch {
                            val threadName = Thread.currentThread().name
                            // 无限循环，除非设置了endTime或stopOnErrors触发退出
                            while (isActive && (endTime == null || LocalDateTime.now(beijingZone) < endTime)) {
                                val generator = IrDeclGenerator(runConfig.generatorConfig)
                                val prog = if (enableCoverageGuide) {
                                    chooseSeed()
                                } else {
                                    generator.genProgram()
                                }

                                repeat(runConfig.langShuffleTimesBeforeMutate) {
                                    if (!isActive || (endTime != null && LocalDateTime.now(beijingZone) >= endTime))
                                        return@launch
                                    val dur = measureTime {
                                        doOneRoundDifferentialAndRecord(prog, stopOnErrors)
                                    }
                                    println("$threadName ${i.incrementAndGet()}:${dur}\t\t")
                                    generator.shuffleLanguage(prog)
                                }

                                repeat(runConfig.mutateTimes) {
                                    if (!isActive || (endTime != null && LocalDateTime.now(beijingZone) >= endTime))
                                        return@launch
                                    val mutator = IrMutator(
                                        runConfig.mutatorConfig,
                                        generator = generator
                                    )
                                    val copiedProg = prog.deepCopy()
                                    if (mutator.mutate(copiedProg)) {
                                        repeat(runConfig.langShuffleTimesAfterMutate) {
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

                    // 如果设置了时间限制，使用超时
                    if (maxRunHours > 0) {
                        withTimeoutOrNull(maxRunHours * 3600 * 1000L) {
                            jobs.joinAll()
                        }
                        println("已达到${maxRunHours}小时运行时间，程序退出")
                    } else {
                        // 无限运行，等待直到被中断或发现错误
                        jobs.joinAll()
                    }
                }
            }

            RunMode.NormalTest -> {
                println("NormalTest mode - not fully implemented")
            }

            RunMode.GenerateIROnly -> {
                val endTimeNano = if (maxRunHours > 0) {
                    System.nanoTime() + maxRunHours * 3600 * 1_000_000_000L
                } else {
                    Long.MAX_VALUE
                }
                while (System.nanoTime() < endTimeNano) {
                    val generator = IrDeclGenerator(runConfig.generatorConfig)
                    val prog = generator.genProgram()
                    val outDir = File(generateIROnlyOutDir, System.nanoTime().toHexString())
                        .mkdirsIfNotExists()
                    File(outDir, "main.json").writer().use {
                        gson.toJson(prog, it)
                    }
                }
                if (maxRunHours > 0) {
                    println("已达到${maxRunHours}小时运行时间，程序退出")
                }
            }

            RunMode.ReduceOnly -> {
                throw IllegalStateException("No input IR file, cannot run ReduceOnly mode.")
            }
        }
    }
}

// ====== Coverage 部分（懒加载，仅在需要时初始化）=====
private var coverageReader: JacocoCoverageReader? = null
private var coverageReaderK1: JacocoCoverageReader? = null
private var bestCoverage = 0
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
    File(outDir, "program.json").writer().use {
        gson.toJson(program, it)
    }
    println("🔥 New coverage: $coverage  → saved")
}

fun main(args: Array<String>) {
    // 删除旧的覆盖率文件
    val execFile = File("jacoco-output/jacoco.exec")
    if (execFile.exists()) {
        execFile.delete()
        println("Old coverage K2 file deleted")
    }
    val execFileK1 = File("jacoco-output/k1.exec")
    if (execFileK1.exists()) {
        execFileK1.delete()
        println("Old coverage K1 file deleted")
    }

    CrossLangFuzzerKotlinRunner().main(args)
}