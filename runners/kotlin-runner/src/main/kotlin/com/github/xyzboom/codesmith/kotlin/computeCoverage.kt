package com.github.xyzboom.codesmith.kotlin

import org.jacoco.core.analysis.Analyzer
import org.jacoco.core.analysis.CoverageBuilder
import org.jacoco.core.data.ExecutionDataReader
import org.jacoco.core.data.ExecutionDataStore
import org.jacoco.core.data.SessionInfoStore
import java.io.File
import java.io.FileInputStream

fun computeCoverage(execFile: File, classDir: File): Double {

    val executionData = ExecutionDataStore()
    val sessionInfos = SessionInfoStore()

    FileInputStream(execFile).use { input ->
        val reader = ExecutionDataReader(input)
        reader.setExecutionDataVisitor(executionData)
        reader.setSessionInfoVisitor(sessionInfos)

        while (reader.read()) {}
    }

    val coverageBuilder = CoverageBuilder()

    val analyzer = Analyzer(executionData, coverageBuilder)

    analyzer.analyzeAll(classDir)

    val bundle = coverageBuilder.getBundle("bundle")

    val total = bundle.instructionCounter.totalCount
    val covered = bundle.instructionCounter.coveredCount

    if (total == 0) return 0.0

    return covered * 100.0 / total
}