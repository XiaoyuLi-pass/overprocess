package com.github.xyzboom.codesmith.kotlin

import com.github.xyzboom.codesmith.CompileResult
import org.jetbrains.kotlin.test.TestJdkKind
import org.jetbrains.kotlin.test.services.KotlinTestInfo
import java.io.File

/**
 * Forked K1 Kotlin Compiler，支持 Jacoco 插桩和三阶段混合编译
 * 版本：2.1.20 (K1)
 */
class ForkedK1Compiler(
    override val jdk: TestJdkKind,
    private val kotlinCompilerClasspath: String,
    private val jdkHome: String,                    // JDK 安装路径
    private val jacocoAgentPath: String? = null
) : IKotlinCompiler {

    private lateinit var testInfo: KotlinTestInfo
    private val isWindows = System.getProperty("os.name").startsWith("Windows")

    // 用于记录三阶段编译的完整日志
    private val compileLog = StringBuilder()

    override fun initTestInfo(testInfo: KotlinTestInfo) {
        this.testInfo = testInfo
    }

    override fun runTest(filePath: String): CompileResult {
        compileLog.clear()

        val jacocoDir = File("jacoco-output").apply { mkdirs() }
        val execFile = File(jacocoDir, "k1.exec")

        val outputDir = File("kotlincK1-classes").apply { mkdirs() }
        val stubDir = File(outputDir, "stubs").apply { mkdirs() }

        val tempSrcDir = File("kotlincK1-src").apply {
            deleteRecursively()
            mkdirs()
        }

        val inputFile = File(filePath)

        // 1. 拆分多文件测试用例
        val files = splitTestFile(inputFile, tempSrcDir)
        val kotlinFiles = files.filter { it.extension == "kt" }
        val javaFiles = files.filter { it.extension == "java" }

        // 打印源文件信息
        compileLog.appendLine("=".repeat(60))
        compileLog.appendLine("K1 三阶段编译")
        compileLog.appendLine("=".repeat(60))
        compileLog.appendLine("Kotlin 文件 (${kotlinFiles.size}): ${kotlinFiles.joinToString { it.name }}")
        compileLog.appendLine("Java 文件 (${javaFiles.size}): ${javaFiles.joinToString { it.name }}")
        compileLog.appendLine()

        // 2. 自动构建 javac 路径
        val javacPath = getJavacPath(jdkHome)

        // 3. 三阶段编译：全部文件 → Kotlin → Java → 全部文件 → Kotlin
        return compileWithThreePhases(kotlinFiles, javaFiles, outputDir, stubDir, execFile, javacPath)
    }

    /**
     * 从 JDK 路径获取 javac 路径
     */
    private fun getJavacPath(jdkHome: String): String {
        val javacName = if (isWindows) "javac.exe" else "javac"
        val javacFile = File(jdkHome, "bin/$javacName")
        require(javacFile.exists()) { "javac not found at ${javacFile.absolutePath}" }
        return javacFile.absolutePath
    }

    /**
     * 三阶段编译：全部文件 → Kotlin → Java → 全部文件 → Kotlin
     */
    private fun compileWithThreePhases(
        kotlinFiles: List<File>,
        javaFiles: List<File>,
        outputDir: File,
        stubDir: File,
        execFile: File,
        javacPath: String
    ): CompileResult {
        var majorError: String? = null
        var javaError: String? = null

        val allFiles = kotlinFiles + javaFiles

        // ========== 阶段一：所有文件传给 kotlinc 生成存根 ==========
        compileLog.appendLine("-".repeat(60))
        compileLog.appendLine("【阶段一】Kotlin 编译器生成存根 (Stubs)")
        compileLog.appendLine("-".repeat(60))
        compileLog.appendLine("输入文件: ${allFiles.joinToString { it.name }}")
        compileLog.appendLine("输出目录: ${stubDir.absolutePath}")
        compileLog.appendLine()

        if (allFiles.isNotEmpty()) {
            val stubResult = compileKotlinForStubs(allFiles, stubDir, execFile)
            compileLog.appendLine("退出码: ${if (stubResult.success) 0 else 1}")
            if (!stubResult.success) {
                compileLog.appendLine("输出:")
                compileLog.appendLine(stubResult.output.take(1000))
                compileLog.appendLine()
                majorError = stubResult.output
                return createResultWithLog("K1", majorError, javaError)
            } else {
                compileLog.appendLine("✅ 阶段一成功")
                if (stubResult.output.isNotBlank()) {
                    compileLog.appendLine("输出:")
                    compileLog.appendLine(stubResult.output.take(500))
                }
                compileLog.appendLine()
            }
        } else {
            compileLog.appendLine("⚠️ 无源文件，跳过阶段一")
            compileLog.appendLine()
        }

        // ========== 阶段二：编译 Java 文件 ==========
        if (javaFiles.isNotEmpty()) {
            compileLog.appendLine("-".repeat(60))
            compileLog.appendLine("【阶段二】Java 编译器编译 (javac)")
            compileLog.appendLine("-".repeat(60))
            compileLog.appendLine("输入文件: ${javaFiles.joinToString { it.name }}")
            compileLog.appendLine("输出目录: ${outputDir.absolutePath}")
            compileLog.appendLine("Classpath: ${stubDir.absolutePath}")
            compileLog.appendLine()

            val javaResult = compileJavaFiles(javaFiles, outputDir, execFile, javacPath, stubDir)
            compileLog.appendLine("退出码: ${if (javaResult.success) 0 else 1}")
            if (!javaResult.success) {
                compileLog.appendLine("输出:")
                compileLog.appendLine(javaResult.output.take(1000))
                compileLog.appendLine()
                javaError = javaResult.output
                compileLog.appendLine("⚠️ 阶段二失败，继续阶段三")
            } else {
                compileLog.appendLine("✅ 阶段二成功")
                if (javaResult.output.isNotBlank()) {
                    compileLog.appendLine("输出:")
                    compileLog.appendLine(javaResult.output.take(500))
                }
                compileLog.appendLine()
            }
        } else {
            compileLog.appendLine("-".repeat(60))
            compileLog.appendLine("【阶段二】跳过 (无 Java 文件)")
            compileLog.appendLine()
        }

        // ========== 阶段三：所有文件再次传给 kotlinc 完整编译 ==========
        if (allFiles.isNotEmpty()) {
            compileLog.appendLine("-".repeat(60))
            compileLog.appendLine("【阶段三】Kotlin 编译器完整编译")
            compileLog.appendLine("-".repeat(60))
            compileLog.appendLine("输入文件: ${allFiles.joinToString { it.name }}")
            compileLog.appendLine("输出目录: ${outputDir.absolutePath}")
            compileLog.appendLine()

            val finalResult = compileKotlinFinal(allFiles, outputDir, execFile)
            compileLog.appendLine("退出码: ${if (finalResult.success) 0 else 1}")
            if (!finalResult.success) {
                compileLog.appendLine("输出:")
                compileLog.appendLine(finalResult.output.take(2000))
                compileLog.appendLine()
                majorError = finalResult.output
            } else {
                compileLog.appendLine("✅ 阶段三成功")
                if (finalResult.output.isNotBlank()) {
                    compileLog.appendLine("输出:")
                    compileLog.appendLine(finalResult.output.take(500))
                }
                compileLog.appendLine()
            }
        } else {
            compileLog.appendLine("-".repeat(60))
            compileLog.appendLine("【阶段三】跳过 (无源文件)")
            compileLog.appendLine()
        }

        // ========== 编译完成 ==========
        compileLog.appendLine("=".repeat(60))
        if (majorError == null && javaError == null) {
            compileLog.appendLine("✅ 编译成功")
        } else {
            compileLog.appendLine("❌ 编译失败")
            if (majorError != null) compileLog.appendLine("  - Kotlin 编译器错误")
            if (javaError != null) compileLog.appendLine("  - Java 编译器错误")
        }
        compileLog.appendLine("=".repeat(60))

        return createResultWithLog("K1", majorError, javaError)
    }

    /**
     * 创建带完整日志的编译结果
     */
    private fun createResultWithLog(version: String, majorError: String?, javaError: String?): CompileResult {
        // 无论成功还是失败，都输出完整的三阶段日志
        val fullLog = buildString {
            append(compileLog.toString())
        }

        // 如果有错误，将完整日志附加到错误信息中
        val finalMajorResult = if (majorError != null) {
            buildString {
                appendLine("=".repeat(60))
                appendLine("三阶段编译日志")
                appendLine("=".repeat(60))
                append(fullLog)
                appendLine()
                appendLine("-".repeat(60))
                appendLine("Kotlin 编译器错误详情")
                appendLine("-".repeat(60))
                append(majorError)
            }
        } else {
            null
        }

        val finalJavaResult = if (javaError != null) {
            buildString {
                appendLine("-".repeat(60))
                appendLine("Java 编译器错误详情")
                appendLine("-".repeat(60))
                append(javaError)
            }
        } else {
            null
        }

        // 如果编译成功，也将完整日志输出到控制台（可选，通过 println 输出）
        if (majorError == null && javaError == null) {
            println("=".repeat(60))
            println("K1 编译成功 - 三阶段日志")
            println("=".repeat(60))
            println(fullLog)
        }

        return CompileResult(
            version = version,
            majorResult = finalMajorResult,
            javaResult = finalJavaResult
        )
    }

    /**
     * 阶段一：所有文件传给 kotlinc 生成存根
     */
    private fun compileKotlinForStubs(
        sourceFiles: List<File>,
        stubDir: File,
        execFile: File
    ): CompileExecutionResult {
        val command = buildKotlinCompilerCommand(
            sourceFiles = sourceFiles,
            outputDir = stubDir,
            execFile = execFile,
            extraArgs = listOf("-Xskip-metadata-version-check")
        )
        return executeCommand(command)
    }

    /**
     * 阶段二：编译 Java 文件
     */
    private fun compileJavaFiles(
        javaFiles: List<File>,
        outputDir: File,
        execFile: File,
        javacPath: String,
        stubDir: File
    ): CompileExecutionResult {
        val command = mutableListOf<String>()
        command += javacPath

        // JaCoCo Agent 附加到 javac（通过 -J 参数传递给底层 JVM）
        jacocoAgentPath?.let {
            command += "-J-javaagent:$it=destfile=${execFile.absolutePath},append=true"
        }

        // 设置 classpath（包含 Kotlin 生成的 stubs）
        val classpath = listOf(stubDir.absolutePath)
            .filter { File(it).exists() }
            .joinToString(File.pathSeparator)

        if (classpath.isNotEmpty()) {
            command += "-cp"
            command += classpath
        }

        command += "-d"
        command += outputDir.absolutePath

        // 添加所有 Java 源文件
        javaFiles.forEach { command += it.absolutePath }

        return executeCommand(command)
    }

    /**
     * 阶段三：所有文件再次传给 kotlinc 完整编译
     */
    private fun compileKotlinFinal(
        sourceFiles: List<File>,
        outputDir: File,
        execFile: File
    ): CompileExecutionResult {
        val command = buildKotlinCompilerCommand(
            sourceFiles = sourceFiles,
            outputDir = outputDir,
            execFile = execFile
        )
        return executeCommand(command)
    }

    /**
     * 构建 Kotlin 编译器命令
     */
    private fun buildKotlinCompilerCommand(
        sourceFiles: List<File>,
        outputDir: File,
        execFile: File,
        extraArgs: List<String> = emptyList()
    ): List<String> {
        val command = mutableListOf<String>()
        command += "java"

        // JaCoCo Agent
        jacocoAgentPath?.let {
            command += "-javaagent:$it=destfile=${execFile.absolutePath},append=true,includes=org.jetbrains.kotlin.*"
        }

        // JDK 路径
        command += "-Djdk.home=$jdkHome"

        command += "-cp"
        command += kotlinCompilerClasspath

        // K1 编译器使用 K2JVMCompiler（K1 的入口类）
        command += "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"

        // JDK 路径参数
        command += "-jdk-home"
        command += jdkHome

        // 输出目录
        command += "-d"
        command += outputDir.absolutePath

        // 设置语言版本为 1.9（K1 特性）
        command += "-language-version"
        command += "1.9"

        // 添加所有源文件
        sourceFiles.forEach { command += it.absolutePath }

        // 添加额外参数
        command += extraArgs

        return command
    }

    /**
     * 执行命令行命令
     */
    private fun executeCommand(command: List<String>): CompileExecutionResult {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        // 判断编译是否成功
        val hasError = exitCode != 0 ||
                output.contains("error:") ||
                output.contains("Exception") ||
                output.contains("ERROR") ||
                output.contains("failed")

        return CompileExecutionResult(
            output = output,
            success = !hasError
        )
    }

    /**
     * 拆分多文件测试用例
     */
    private fun splitTestFile(input: File, outDir: File): List<File> {
        val outputFiles = mutableListOf<File>()
        var currentFile: File? = null
        val builder = StringBuilder()

        input.forEachLine { line ->
            if (line.startsWith("// FILE:")) {
                currentFile?.let {
                    it.writeText(builder.toString().trim())
                    outputFiles.add(it)
                    builder.clear()
                }

                val fileName = line.removePrefix("// FILE:").trim()
                currentFile = File(outDir, fileName)
                currentFile.parentFile.mkdirs()
            } else {
                builder.appendLine(line)
            }
        }

        currentFile?.let {
            it.writeText(builder.toString().trim())
            outputFiles.add(it)
        }

        return outputFiles
    }

    /**
     * 编译执行结果数据类
     */
    private data class CompileExecutionResult(
        val output: String,
        val success: Boolean
    )
}