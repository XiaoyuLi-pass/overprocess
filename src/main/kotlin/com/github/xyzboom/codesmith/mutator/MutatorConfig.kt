package com.github.xyzboom.codesmith.mutator

import kotlin.Int

data class MutatorConfig(
    val mutateGenericArgumentInParentWeight: Int = 1,
    val removeOverrideMemberFunctionWeight: Int = 1,
    val mutateGenericArgumentInMemberFunctionParameterWeight: Int = 3,
    val mutateParameterNullabilityWeight: Int = 2,
    val mutateClassTypeParameterUpperBoundNullabilityWeight: Int = 1,
    val mutateClassTypeParameterUpperBoundWeight: Int = 3,
    // ========== 新增的变异函数权重 ==========
    // 函数相关
    val mutateFunctionReturnTypeWeight: Int = 10,
    val addFunctionParameterWeight: Int = 8,
    val removeFunctionParameterWeight: Int = 7,
    val swapFunctionParametersWeight: Int = 6,
    val mutateFunctionModifierWeight: Int = 5,
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