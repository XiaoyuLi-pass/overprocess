
package com.github.xyzboom.codesmith.kotlin

import com.github.xyzboom.codesmith.CompileResult
import com.github.xyzboom.codesmith.ICompiler
import com.github.xyzboom.codesmith.ir.IrProgram
import com.github.xyzboom.codesmith.printer.IrProgramPrinter
import org.jetbrains.kotlin.test.TestJdkKind
import org.jetbrains.kotlin.test.services.KotlinTestInfo
import java.io.File

interface IKotlinCompiler : ICompiler {

    val jdk: TestJdkKind

    fun testProgram(fileContent: String): CompileResult {

        val tempFile = File.createTempFile("code-smith", ".kt")

        tempFile.writeText("// JDK_KIND: ${jdk.name}\n$fileContent")

        return try {

            val result = runTest(tempFile.absolutePath)

            println("Compiler: ${this::class.simpleName}")
            println("Result:")
            println(result)
            println("==============================")

            result

        } catch (e: Throwable) {

//            println("========== COMPILER CRASH ==========")
//            println("Compiler: ${this::class.simpleName}")
//            println("Program:")
//            println(fileContent)
//            e.printStackTrace()

            CompileResult(
                version = this::class.simpleName!!,
                majorResult = "CRASH\n${e.stackTraceToString()}",
                javaResult = null
            )
        }
    }

    /** Kotlin官方runner，必须保持Unit */
    fun runTest(filePath: String): CompileResult

    fun initTestInfo(testInfo: KotlinTestInfo)

    override fun compile(program: IrProgram): CompileResult {
        val fileContent = IrProgramPrinter().printToSingle(program)
        return testProgram(fileContent)
    }

    /** 统一转换结果 */
    fun toCompileResult(exception: Throwable?): CompileResult {

        return if (exception == null) {

            CompileResult(
                version = this::class.simpleName!!,
                majorResult = null,
                javaResult = null
            )

        } else {

            CompileResult(
                version = this::class.simpleName!!,
                majorResult = "CRASH\n${exception.stackTraceToString()}",
                javaResult = null
            )
        }
    }
}