package com.github.xyzboom.codesmith.kotlin

import com.github.xyzboom.codesmith.CompileResult
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.test.TestJdkKind
import org.jetbrains.kotlin.test.services.KotlinTestInfo
import java.io.File

class ForkedKotlinCompiler(
    override val jdk: TestJdkKind,
    private val kotlinCompilerClasspath: String,
    private val jacocoAgentPath: String? = null
) : IKotlinCompiler {

    private lateinit var testInfo: KotlinTestInfo

    override fun initTestInfo(testInfo: KotlinTestInfo) {
        this.testInfo = testInfo
    }

    override fun runTest(filePath: String): CompileResult {

        val jacocoDir = File("jacoco-output").apply { mkdirs() }
        val execFile = File(jacocoDir, "jacoco.exec")

        val outputDir = File("kotlinc-classes").apply { mkdirs() }

        val tempSrcDir = File("kotlincK2-src").apply {
            deleteRecursively()
            mkdirs()
        }

        val inputFile = File(filePath)

        splitTestFile(inputFile, tempSrcDir)

        val command = mutableListOf<String>()

        command += "java"

        jacocoAgentPath?.let {
            command += "-javaagent:$it=destfile=${execFile.absolutePath},append=true,includes=org.jetbrains.kotlin.*"
        }

        command += "-cp"
        command += kotlinCompilerClasspath

        // K2 compiler
        command += "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"


        tempSrcDir.walkTopDown()
            .filter { it.extension == "kt" || it.extension == "java" }
            .forEach { command += it.absolutePath }

        command += "-d"
        command += outputDir.absolutePath

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()

        val exitCode = process.waitFor()

        val crash =
            output.contains("Exception") ||
                    output.contains("AssertionError") ||
                    output.contains("Internal error")

        // ===== 生成 majorResult =====
        val majorResult = when {
            crash -> "CRASH\n$output"
            exitCode != 0 -> output
            else -> null
        }
        return CompileResult(
            version = "K2",
            majorResult = majorResult,
            javaResult = null
        )
    }
    private fun splitTestFile(input: File, outDir: File) {

        var currentFile: File? = null
        val builder = StringBuilder()

        input.forEachLine { line ->

            if (line.startsWith("// FILE:")) {

                currentFile?.writeText(builder.toString())
                builder.clear()

                val name = line.removePrefix("// FILE:").trim()

                currentFile = File(outDir, name)
                currentFile!!.parentFile.mkdirs()

            } else {
                builder.appendLine(line)
            }
        }

        currentFile?.writeText(builder.toString())
    }
}