package com.cw2.nym.ai.model

import kotlinx.serialization.Serializable

/**
 * 代码上下文基础类
 * 
 * 定义了所有代码元素分析的基础信息结构，包含语言类型、项目信息和周围环境等通用属性。
 */
@Serializable
sealed class CodeContext {
    /**
     * 编程语言类型
     */
    abstract val language: ProgrammingLanguage
    
    /**
     * 项目信息
     */
    abstract val projectInfo: ProjectInfo
    
    /**
     * 周围上下文环境
     */
    abstract val surroundingContext: SurroundingContext
    
    /**
     * 用户意图描述（用于特殊符号生成场景）
     */
    abstract val userIntent: String?
    
    /**
     * 代码元素类型
     */
    abstract val elementType: CodeElementType
}

/**
 * 方法上下文信息
 * 
 * 包含方法的完整签名信息、参数详情、返回值类型等，用于生成精确的方法命名建议和注释。
 */
@Serializable
data class MethodContext(
    override val language: ProgrammingLanguage,
    override val projectInfo: ProjectInfo,
    override val surroundingContext: SurroundingContext,
    override val userIntent: String? = null,
    
    /**
     * 方法名称（当前名称或待生成）
     */
    val methodName: String? = null,
    
    /**
     * 方法参数列表
     */
    val parameters: List<ParameterInfo>,
    
    /**
     * 返回值类型信息
     */
    val returnType: TypeInfo,
    
    /**
     * 方法修饰符（public、private、static等）
     */
    val modifiers: List<String>,
    
    /**
     * 方法注解信息
     */
    val annotations: List<AnnotationInfo>,
    
    /**
     * 抛出的异常类型
     */
    val exceptions: List<TypeInfo>,
    
    /**
     * 方法体代码片段（用于理解方法逻辑）
     */
    val methodBody: String? = null,
    
    /**
     * 是否为构造方法
     */
    val isConstructor: Boolean = false,
    
    /**
     * 是否为抽象方法
     */
    val isAbstract: Boolean = false,
    
    /**
     * 所属类的信息
     */
    val containingClass: ClassInfo? = null
) : CodeContext() {
    override val elementType = CodeElementType.METHOD
}

/**
 * 类上下文信息
 * 
 * 包含类的继承关系、实现接口、成员信息等，用于生成合适的类命名建议和类级别注释。
 */
@Serializable
data class ClassContext(
    override val language: ProgrammingLanguage,
    override val projectInfo: ProjectInfo,
    override val surroundingContext: SurroundingContext,
    override val userIntent: String? = null,
    
    /**
     * 类名称（当前名称或待生成）
     */
    val className: String? = null,
    
    /**
     * 父类信息
     */
    val superClass: TypeInfo? = null,
    
    /**
     * 实现的接口列表
     */
    val interfaces: List<TypeInfo>,
    
    /**
     * 类修饰符
     */
    val modifiers: List<String>,
    
    /**
     * 类注解信息
     */
    val annotations: List<AnnotationInfo>,
    
    /**
     * 类成员字段
     */
    val fields: List<FieldInfo>,
    
    /**
     * 类方法列表
     */
    val methods: List<MethodInfo>,
    
    /**
     * 内部类列表
     */
    val innerClasses: List<ClassInfo>,
    
    /**
     * 是否为接口
     */
    val isInterface: Boolean = false,
    
    /**
     * 是否为抽象类
     */
    val isAbstract: Boolean = false,
    
    /**
     * 是否为枚举类
     */
    val isEnum: Boolean = false,
    
    /**
     * 包名
     */
    val packageName: String
) : CodeContext() {
    override val elementType = CodeElementType.CLASS
}

/**
 * 变量上下文信息
 * 
 * 包含变量的类型、作用域、初始化信息等，用于生成准确的变量命名建议。
 */
@Serializable
data class VariableContext(
    override val language: ProgrammingLanguage,
    override val projectInfo: ProjectInfo,
    override val surroundingContext: SurroundingContext,
    override val userIntent: String? = null,
    
    /**
     * 变量名称（当前名称或待生成）
     */
    val variableName: String? = null,
    
    /**
     * 变量类型信息
     */
    val variableType: TypeInfo,
    
    /**
     * 变量修饰符
     */
    val modifiers: List<String>,
    
    /**
     * 变量注解
     */
    val annotations: List<AnnotationInfo>,
    
    /**
     * 初始化表达式
     */
    val initializer: String? = null,
    
    /**
     * 变量作用域类型
     */
    val scope: VariableScope,
    
    /**
     * 是否为常量
     */
    val isConstant: Boolean = false,
    
    /**
     * 是否为静态变量
     */
    val isStatic: Boolean = false,
    
    /**
     * 使用模式分析（如何被使用）
     */
    val usagePattern: UsagePattern? = null,
    
    /**
     * 所属类信息（如果是类成员变量）
     */
    val containingClass: ClassInfo? = null,
    
    /**
     * 所属方法信息（如果是局部变量或参数）
     */
    val containingMethod: MethodInfo? = null
) : CodeContext() {
    override val elementType = CodeElementType.VARIABLE
}

/**
 * 周围上下文环境信息
 * 
 * 描述代码元素周围的环境信息，包括相邻代码、导入语句、项目命名规范等。
 */
@Serializable
data class SurroundingContext(
    /**
     * 前置代码片段（当前元素前n行代码）
     */
    val precedingCode: List<String>,
    
    /**
     * 后续代码片段（当前元素后n行代码）
     */
    val followingCode: List<String>,
    
    /**
     * 导入语句列表
     */
    val imports: List<String>,
    
    /**
     * 包声明
     */
    val packageDeclaration: String? = null,
    
    /**
     * 文件级注释
     */
    val fileComments: List<String>,
    
    /**
     * 相邻的同类型元素（如同一类中的其他方法）
     */
    val siblingElements: List<String>,
    
    /**
     * 项目命名模式分析
     */
    val namingPatterns: NamingPatternAnalysis? = null,
    
    /**
     * 代码风格分析
     */
    val codeStyleAnalysis: CodeStyleAnalysis? = null
)

/**
 * 编程语言类型枚举
 */
@Serializable
enum class ProgrammingLanguage {
    JAVA, KOTLIN, PYTHON, JAVASCRIPT, TYPESCRIPT, C_SHARP, CPP, GO, RUST, SWIFT, OTHER
}

/**
 * 代码元素类型
 */
@Serializable
enum class CodeElementType {
    METHOD, CLASS, VARIABLE, PARAMETER, FIELD, PACKAGE, MODULE, INTERFACE, ENUM, ANNOTATION
}

/**
 * 变量作用域类型
 */
@Serializable
enum class VariableScope {
    LOCAL, PARAMETER, FIELD, STATIC_FIELD, GLOBAL
}

/**
 * 项目信息
 */
@Serializable
data class ProjectInfo(
    val projectName: String,
    val projectType: String? = null,
    val framework: String? = null,
    val buildTool: String? = null,
    val javaVersion: String? = null,
    val kotlinVersion: String? = null
)

/**
 * 参数信息
 */
@Serializable
data class ParameterInfo(
    val name: String,
    val type: TypeInfo,
    val annotations: List<AnnotationInfo> = emptyList(),
    val hasDefaultValue: Boolean = false,
    val defaultValue: String? = null
)

/**
 * 类型信息
 */
@Serializable
data class TypeInfo(
    val typeName: String,
    val fullQualifiedName: String? = null,
    val genericTypes: List<TypeInfo> = emptyList(),
    val isNullable: Boolean = false,
    val isArray: Boolean = false,
    val arrayDimension: Int = 0,
    val isPrimitive: Boolean = false
)

/**
 * 注解信息
 */
@Serializable
data class AnnotationInfo(
    val name: String,
    val fullQualifiedName: String? = null,
    val parameters: Map<String, String> = emptyMap()
)

/**
 * 字段信息
 */
@Serializable
data class FieldInfo(
    val name: String,
    val type: TypeInfo,
    val modifiers: List<String> = emptyList(),
    val annotations: List<AnnotationInfo> = emptyList(),
    val initializer: String? = null
)

/**
 * 方法信息（简化版，用于类上下文）
 */
@Serializable
data class MethodInfo(
    val name: String,
    val returnType: TypeInfo,
    val parameters: List<ParameterInfo> = emptyList(),
    val modifiers: List<String> = emptyList(),
    val annotations: List<AnnotationInfo> = emptyList()
)

/**
 * 类信息（简化版，用于其他上下文）
 */
@Serializable
data class ClassInfo(
    val name: String,
    val fullQualifiedName: String? = null,
    val packageName: String? = null,
    val modifiers: List<String> = emptyList(),
    val isInterface: Boolean = false,
    val isAbstract: Boolean = false,
    val isEnum: Boolean = false
)

/**
 * 变量使用模式分析
 */
@Serializable
data class UsagePattern(
    val readCount: Int = 0,
    val writeCount: Int = 0,
    val usageContexts: List<String> = emptyList(),
    val isLoopVariable: Boolean = false,
    val isParameterPassed: Boolean = false,
    val isReturnValue: Boolean = false
)

/**
 * 命名模式分析
 */
@Serializable
data class NamingPatternAnalysis(
    val conventionType: NamingConvention,
    val commonPrefixes: List<String> = emptyList(),
    val commonSuffixes: List<String> = emptyList(),
    val averageLength: Int = 0,
    val domainTerms: List<String> = emptyList()
)

/**
 * 命名约定类型
 */
@Serializable
enum class NamingConvention {
    CAMEL_CASE, PASCAL_CASE, SNAKE_CASE, KEBAB_CASE, UPPER_SNAKE_CASE, MIXED
}

/**
 * 代码风格分析
 */
@Serializable
data class CodeStyleAnalysis(
    val indentationType: String = "spaces",
    val indentationSize: Int = 4,
    val bracketStyle: String = "same_line",
    val commentStyle: String = "javadoc",
    val lineLength: Int = 120,
    val useBraces: Boolean = true
)