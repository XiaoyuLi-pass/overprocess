package com.github.xyzboom.codesmith

//class CompileResult(
//    val version: String,
//    val majorResult: String?,
//    val javaResult: String?,
//    private val strictEquals: Boolean = false
//) {
//    val success: Boolean = majorResult == null && javaResult == null
//    override fun equals(other: Any?): Boolean {
//        if (this === other) return true
//        if (other !is CompileResult) return false
//        if (strictEquals) {
//            if (majorResult == null && other.majorResult != null) return false
//            if (majorResult != null && other.majorResult == null) return false
//
//            if (javaResult == null && other.javaResult != null) return false
//            if (javaResult != null && other.javaResult == null) return false
//        } else {
//            if (success != other.success) return false
//        }
//        return true
//    }
//
//    override fun hashCode(): Int {
//        if (strictEquals) {
//            var result = if (majorResult != null) 1 else 0
//            result = 31 * result + if (javaResult != null) 1 else 0
//            return result
//        } else {
//            return if (success) 1 else 0
//        }
//    }
//
//    operator fun component1(): String? {
//        return majorResult
//    }
//
//    operator fun component2(): String? {
//        return javaResult
//    }
//
//    override fun toString(): String {
//        return if (majorResult != null) {
//            "Major Error:\n$majorResult"
//        } else {
//            "Java Error:\n$javaResult"
//        }
//    }
//}








class CompileResult(
    val version: String,
    val majorResult: String?,
    val javaResult: String?,
    private val strictEquals: Boolean = false
) {

    val success: Boolean = majorResult == null && javaResult == null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CompileResult) return false

        return if (strictEquals) {
            (majorResult != null) == (other.majorResult != null) &&
                    (javaResult != null) == (other.javaResult != null)
        } else {
            success == other.success
        }
    }

    override fun hashCode(): Int {
        return if (strictEquals) {
            var result = if (majorResult != null) 1 else 0
            result = 31 * result + if (javaResult != null) 1 else 0
            result
        } else {
            if (success) 1 else 0
        }
    }

    operator fun component1(): String? = majorResult

    operator fun component2(): String? = javaResult

    override fun toString(): String {
        return when {
            success -> "Compile Success"
            majorResult != null -> "Major Error:\n$majorResult"
            javaResult != null -> "Java Error:\n$javaResult"
            else -> "Unknown Result"
        }
    }
}