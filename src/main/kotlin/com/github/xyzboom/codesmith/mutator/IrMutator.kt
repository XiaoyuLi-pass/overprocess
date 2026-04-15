package com.github.xyzboom.codesmith.mutator

import com.github.xyzboom.codesmith.ir.Language
import com.github.xyzboom.codesmith.generator.IrDeclGenerator
import com.github.xyzboom.codesmith.ir.IrProgram
import com.github.xyzboom.codesmith.ir.declarations.IrClassDeclaration
import com.github.xyzboom.codesmith.ir.declarations.IrFunctionDeclaration
import com.github.xyzboom.codesmith.ir.declarations.builder.buildParameter
import com.github.xyzboom.codesmith.ir.types.IrNullableType
import com.github.xyzboom.codesmith.ir.types.IrParameterizedClassifier
import com.github.xyzboom.codesmith.ir.types.IrType
import com.github.xyzboom.codesmith.ir.types.areEqualTypes
import com.github.xyzboom.codesmith.ir.types.builder.buildNullableType
import com.github.xyzboom.codesmith.ir.types.builder.buildSimpleClassifier
import com.github.xyzboom.codesmith.ir.types.builtin.IrAny
import com.github.xyzboom.codesmith.ir.types.builtin.IrNothing
import com.github.xyzboom.codesmith.ir.types.builtin.IrUnit
import com.github.xyzboom.codesmith.ir.types.getTypeArguments
import com.github.xyzboom.codesmith.ir.types.putTypeArgument
import com.github.xyzboom.codesmith.ir.types.render
import com.github.xyzboom.codesmith.utils.rouletteSelection
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.random.Random
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.memberProperties

class IrMutator(
    private val config: MutatorConfig = MutatorConfig.default,
    private val generator: IrDeclGenerator,
) {
    private val logger = KotlinLogging.logger {}
    private val random: Random = generator.random

    @ConfigBy("mutateGenericArgumentInParentWeight")
    fun mutateGenericArgumentInParent(program: IrProgram): Boolean {
        program.randomTraverseClasses(random) { clazz ->
            val implements = clazz.implementedTypes
            for (impl in implements) {
                if (impl is IrParameterizedClassifier) {
                    val typeArguments = impl.getTypeArguments()
                    val entries = typeArguments.entries.filter { it.value.second !== IrAny }
                    if (entries.isEmpty()) continue
                    val (_, pair) = entries.random(random)
                    val (typeParam, typeArg) = pair
                    val replaceArg = generator.randomType(
                        program.classes, clazz.typeParameters, false
                    ) { type ->
                        type !is IrParameterizedClassifier && !areEqualTypes(type, typeArg)
                    } ?: continue
                    logger.trace {
                        "mutateGenericArgumentInParent at: ${clazz.name}, change: $typeArg into $replaceArg"
                    }
                    impl.putTypeArgument(typeParam, replaceArg)
                    return@mutateGenericArgumentInParent true
                }
            }
            false
        }
        return false
    }

    @ConfigBy("removeOverrideMemberFunctionWeight")
    fun removeOverrideMemberFunction(program: IrProgram): Boolean {
        program.randomTraverseMemberFunctions(random) { func, _ ->
            if (func.isOverride && !func.isOverrideStub) {
                func.isOverrideStub = true
                return@removeOverrideMemberFunction true
            }
            false
        }
        return false
    }

    @ConfigBy("mutateGenericArgumentInMemberFunctionParameterWeight")
    fun mutateGenericArgumentInMemberFunctionParameter(program: IrProgram): Boolean {
        program.randomTraverseMemberFunctions(random) { func, clazz ->
            val param = func.parameterList.parameters.firstOrNull { it.type is IrParameterizedClassifier }
            if (func.isOverride && !func.isOverrideStub && param != null) {
                val paramType = param.type as IrParameterizedClassifier
                val typeArguments = paramType.getTypeArguments()
                val entries = typeArguments.entries.filter { it.value.second !== IrAny }
                if (entries.isEmpty()) return@randomTraverseMemberFunctions false
                val (_, pair) = entries.random(random)
                val (typeParam, typeArg) = pair
                val replaceArg = generator.randomType(
                    program.classes, clazz.typeParameters + func.typeParameters, false
                ) { type ->
                    type !is IrParameterizedClassifier && !areEqualTypes(type, typeArg)
                } ?: return@randomTraverseMemberFunctions false
                paramType.putTypeArgument(typeParam, replaceArg)
                logger.trace {
                    "mutateGenericArgumentInMemberFunctionParameter at: " +
                            "${clazz.name}:${func.name}, " +
                            "change: $typeArg into $replaceArg, new param $param"
                }
                return@mutateGenericArgumentInMemberFunctionParameter true
            }
            false
        }
        return false
    }
    /**
     * 变异函数返回类型
     * 独立实现：随机选择一个非重写函数，将返回类型改为随机生成的类型
     */
    @ConfigBy("mutateFunctionReturnTypeWeight")
    fun mutateFunctionReturnType(program: IrProgram): Boolean {
        val allClasses = program.classes.toList()
        if (allClasses.isEmpty()) return false

        // 收集所有非重写函数
        val candidateFunctions = mutableListOf<Pair<IrFunctionDeclaration, IrClassDeclaration>>()
        for (clazz in allClasses) {
            for (func in clazz.functions) {
                if (!func.isOverride) {                candidateFunctions.add(func to clazz)
                }
            }
        }

        if (candidateFunctions.isEmpty()) return false

        val (targetFunc, targetClass) = candidateFunctions.random(random)

        // 收集可用的返回类型
        val availableTypes = mutableListOf<IrType>()

        // 添加内置类型
        availableTypes.addAll(listOf(IrAny, IrUnit, IrNothing))

        // 添加类的类型
        for (clazz in allClasses) {
            if (clazz.typeParameters.isEmpty()) {
                // 简单类型
                availableTypes.add(buildSimpleClassifier { classDecl = clazz })
            }
        }

        // 添加类型参数
        availableTypes.addAll(targetClass.typeParameters)
        availableTypes.addAll(targetFunc.typeParameters)

        if (availableTypes.isEmpty()) return false

        // 过滤掉当前返回类型
        val newTypes = availableTypes.filter { !areEqualTypes(it, targetFunc.returnType) }
        if (newTypes.isEmpty()) return false

        val oldType = targetFunc.returnType
        targetFunc.returnType = newTypes.random(random)

        logger.trace { "mutateFunctionReturnType: ${targetFunc.name}, ${oldType.render()} -> ${targetFunc.returnType.render()}" }
        return true
    }

    /**
     * 添加函数参数
     * 独立实现：随机选择一个非重写函数，在参数列表末尾添加新参数
     */
    @ConfigBy("addFunctionParameterWeight")
    fun addFunctionParameter(program: IrProgram): Boolean {
        val allClasses = program.classes.toList()
        if (allClasses.isEmpty()) return false

        // 收集所有非重写函数
        val candidates = mutableListOf<IrFunctionDeclaration>()
        for (clazz in allClasses) {
            for (func in clazz.functions) {
                if (!func.isOverride) {
                    candidates.add(func)
                }
            }
        }

        if (candidates.isEmpty()) return false

        val targetFunc = candidates.random(random)

        // 生成参数名
        val paramIndex = targetFunc.parameterList.parameters.size
        val paramName = "newParam$paramIndex"

        // 收集可用的参数类型
        val availableTypes = mutableListOf<IrType>()
        for (clazz in allClasses) {
            if (clazz.typeParameters.isEmpty()) {
                availableTypes.add(buildSimpleClassifier { classDecl = clazz })
            }
        }
        availableTypes.add(IrAny)

        if (availableTypes.isEmpty()) return false

        val paramType = availableTypes.random(random)

        // 创建新参数
        val newParam = buildParameter {
            name = paramName
            type = paramType
        }

        targetFunc.parameterList.parameters.add(newParam)
        logger.trace { "addFunctionParameter: ${targetFunc.name}, added param: $paramName : ${paramType.render()}" }
        return true
    }

    /**
     * 删除函数参数
     * 独立实现：随机选择一个有参数的函数，随机删除一个参数
     */
    @ConfigBy("removeFunctionParameterWeight")
    fun removeFunctionParameter(program: IrProgram): Boolean {
        val allClasses = program.classes.toList()
        if (allClasses.isEmpty()) return false

        // 收集有参数的函数
        val candidates = mutableListOf<IrFunctionDeclaration>()
        for (clazz in allClasses) {
            for (func in clazz.functions) {
                if (func.parameterList.parameters.isNotEmpty()) {
                    candidates.add(func)
                }
            }
        }

        if (candidates.isEmpty()) return false

        val targetFunc = candidates.random(random)
        val params = targetFunc.parameterList.parameters
        val removed = params.removeAt(random.nextInt(params.size))

        logger.trace { "removeFunctionParameter: ${targetFunc.name}, removed param: ${removed.name}" }
        return true
    }

    /**
     * 交换函数参数顺序
     * 独立实现：随机选择一个有至少2个参数的函数，交换两个参数的位置
     */
    @ConfigBy("swapFunctionParametersWeight")
    fun swapFunctionParameters(program: IrProgram): Boolean {
        val allClasses = program.classes.toList()
        if (allClasses.isEmpty()) return false

        // 收集有至少2个参数的函数
        val candidates = mutableListOf<IrFunctionDeclaration>()
        for (clazz in allClasses) {
            for (func in clazz.functions) {
                if (func.parameterList.parameters.size >= 2) {
                    candidates.add(func)
                }
            }
        }

        if (candidates.isEmpty()) return false

        val targetFunc = candidates.random(random)
        val params = targetFunc.parameterList.parameters

        var i = random.nextInt(params.size)
        var j = random.nextInt(params.size)
        while (i == j) {
            j = random.nextInt(params.size)
        }

        val temp = params[i]
        params[i] = params[j]
        params[j] = temp

        logger.trace { "swapFunctionParameters: ${targetFunc.name}, swapped param${i} and param${j}" }
        return true
    }
    /**
     * 变异函数修饰符（isFinal/isOverride）
     * 测试：Kotlin 的继承控制、开放/最终类语义
     */
    @ConfigBy("mutateFunctionModifierWeight")
    fun mutateFunctionModifier(program: IrProgram): Boolean {
        val allClasses = program.classes.toList()
        if (allClasses.isEmpty()) return false

        val shuffledClasses = allClasses.shuffled(random)
        for (clazz in shuffledClasses) {
            val functions = clazz.functions.filter { !it.isOverride }
            if (functions.isEmpty()) continue

            val targetFunc = functions.random(random)

            when (random.nextInt(2)) {
                0 -> {
                    // 切换 final 状态
                    targetFunc.isFinal = !targetFunc.isFinal
                    logger.trace { "mutateFunctionModifier: ${targetFunc.name} isFinal = ${targetFunc.isFinal}" }
                    return true
                }
                1 -> {
                    // 添加 override（需要父类有同名方法）
                    if (!targetFunc.isOverride && clazz.superType != null) {
                        targetFunc.isOverride = true
                        targetFunc.isOverrideStub = true
                        logger.trace { "mutateFunctionModifier: ${targetFunc.name} became override" }
                        return true
                    }
                }
            }
        }
        return false
    }

    @ConfigBy("mutateParameterNullabilityWeight")
    fun mutateParameterNullability(program: IrProgram): Boolean {
        program.randomTraverseMemberFunctions(random) { func, _ ->
            val param = func.parameterList.parameters.firstOrNull {
                if (random.nextBoolean()) {
                    it.type is IrNullableType
                } else {
                    it.type !is IrNullableType
                }
            } ?: return@randomTraverseMemberFunctions false
            val type = param.type
            if (type is IrNullableType) {
                param.type = type.innerType
            } else {
                param.type = buildNullableType {
                    this.innerType = type
                }
            }
            if (func.language == Language.JAVA) {
                func.printNullableAnnotations = true
            }
            return@mutateParameterNullability true
        }
        return false
    }

    @ConfigBy("mutateClassTypeParameterUpperBoundNullabilityWeight")
    fun mutateClassTypeParameterUpperBoundNullability(program: IrProgram): Boolean {
        program.randomTraverseClasses(random) { clazz ->
            if (clazz.typeParameters.isNotEmpty()) {
                val typeParameter = clazz.typeParameters.random(random)
                val upperbound = typeParameter.upperbound
                if (upperbound is IrNullableType) {
                    typeParameter.upperbound = upperbound.innerType
                } else {
                    typeParameter.upperbound = buildNullableType { innerType = upperbound }
                }
                return@mutateClassTypeParameterUpperBoundNullability true
            }
            return@randomTraverseClasses false
        }
        return false
    }

    @ConfigBy("mutateClassTypeParameterUpperBoundWeight")
    fun mutateClassTypeParameterUpperBound(program: IrProgram): Boolean {
        program.randomTraverseClasses(random) { clazz ->
            if (clazz.typeParameters.isNotEmpty()) {
                val typeParameter = clazz.typeParameters.random(random)
                val replace = generator.randomType(
                    program.classes, clazz.typeParameters, false
                ) { type ->
                    type !is IrParameterizedClassifier && !areEqualTypes(type, typeParameter)
                            && (generator.config.allowUnitInTypeArgument || type !== IrUnit)
                            && (generator.config.allowNothingInTypeArgument || type !== IrNothing)
                } ?: return@mutateClassTypeParameterUpperBound false
                val before = typeParameter.upperbound
                if (typeParameter.upperbound is IrNullableType) {
                    (typeParameter.upperbound as IrNullableType).innerType = replace
                } else {
                    typeParameter.upperbound = replace
                }
                logger.trace { "mutateClassTypeParameterUpperBound in class ${clazz.name}," +
                        " before: ${before.render()}, after: ${replace.render()}" }
                return@mutateClassTypeParameterUpperBound true
            }
            return@randomTraverseClasses false
        }
        return false
    }

    fun mutate(program: IrProgram): Boolean {
        println("=== MUTATE METHOD CALLED ===")

        val configByMethods = this::class.declaredMemberFunctions.filter {
            it.annotations.any { anno ->
                anno.annotationClass == ConfigBy::class
            }
        }

        println("Available mutators: ${configByMethods.map { it.name }}")

        val weights = configByMethods.map { method ->
            val anno = method.annotations.single { it.annotationClass == ConfigBy::class } as ConfigBy
            val configProperty = MutatorConfig::class.memberProperties.single { it.name == anno.name }
            val weight = configProperty.get(config) as Int
            println("  ${method.name}: weight=$weight")
            weight
        }

        if (weights.all { it == 0 }) {
            println("WARNING: All weights are zero!")
            return false
        }

        val mutatorMethod = rouletteSelection(configByMethods, weights, random)
        println("Selected: ${mutatorMethod.name}")  // ← 移到这里，在调用之前

        return try {
            mutatorMethod.call(this, program) as Boolean
        } catch (e: Exception) {
            println("ERROR: ${e.message}")
            false
        }
    }
}