package com.github.xyzboom.codesmith.mutator

import kotlin.Int

data class MutatorConfig(
    val mutateGenericArgumentInParentWeight: Int = 2,              // 1 -> 2 (Kotlin泛型使用更频繁)
    val removeOverrideMemberFunctionWeight: Int = 3,               // 1 -> 3 (override是显式的)
    val mutateGenericArgumentInMemberFunctionParameterWeight: Int = 4,  // 3 -> 4 (略微增加)
    val mutateParameterNullabilityWeight: Int = 10,                // 2 -> 10 (空安全是Kotlin核心特性)
    val mutateClassTypeParameterUpperBoundNullabilityWeight: Int = 3,   // 1 -> 3 (Kotlin类型投影)
    val mutateClassTypeParameterUpperBoundWeight: Int = 4,         // 3 -> 4 (略微增加)

// 函数相关
    val mutateFunctionReturnTypeWeight: Int = 6,                   // 10 -> 6 (Kotlin类型推断)
    val addFunctionParameterWeight: Int = 5,                       // 8 -> 5 (支持默认参数)
    val removeFunctionParameterWeight: Int = 4,                    // 7 -> 4 (命名参数特性)
    val swapFunctionParametersWeight: Int = 3,                     // 6 -> 3 (命名参数降低影响)
    val mutateFunctionModifierWeight: Int = 8,                     // 5 -> 8 (Kotlin修饰符更丰富)
) {
    companion object {
        @JvmStatic
        val default = MutatorConfig()

        @JvmStatic
        val allZero = MutatorConfig(
            mutateGenericArgumentInParentWeight = 0,
            removeOverrideMemberFunctionWeight = 0,
            mutateGenericArgumentInMemberFunctionParameterWeight = 0,
            mutateParameterNullabilityWeight = 0,
            mutateClassTypeParameterUpperBoundNullabilityWeight = 0,
            mutateClassTypeParameterUpperBoundWeight = 0
        )
    }
}