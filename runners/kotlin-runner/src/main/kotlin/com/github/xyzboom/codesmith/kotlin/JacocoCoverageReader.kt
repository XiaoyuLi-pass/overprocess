package com.github.xyzboom.codesmith.kotlin

import org.jacoco.core.analysis.Analyzer
import org.jacoco.core.analysis.CoverageBuilder
import org.jacoco.core.tools.ExecFileLoader
import java.io.File

class JacocoCoverageReader(
    private val execFile: File,
    private val classesDir: File
) {

    fun getInstructionCoverage(): Int {
        val loader = ExecFileLoader()
        loader.load(execFile)

        val coverageBuilder = CoverageBuilder()

        val analyzer = Analyzer(loader.executionDataStore, coverageBuilder)

        analyzer.analyzeAll(classesDir)

        val bundle = coverageBuilder.getBundle("kotlin-compiler")

        return bundle.instructionCounter.coveredCount
    }


}