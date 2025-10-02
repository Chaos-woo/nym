package com.cw2.nym.integrations.psi

import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.*
import com.cw2.nym.ai.model.*
import com.cw2.nym.core.result.Result
import com.cw2.nym.core.exception.NymError

/**
 * Kotlin代码分析器
 * 
 * 专门处理Kotlin代码元素的分析，包括函数、类、属性等。
 */
class KotlinCodeAnalyzer(private val project: Project) {
    
    // 说明：为了兼容 Kotlin K2 模式与遵循 PSI 线程约束，所有 PSI 读取均放入 ReadAction 中执行
    
    fun analyzeKotlinFunction(function: KtFunction): Result<MethodContext> {
        return try {
            ReadAction.compute<Result<MethodContext>, Throwable> {
                val parameters = function.valueParameters.map { param ->
                    ParameterInfo(
                        name = param.name ?: "",
                        type = TypeInfo(
                            typeName = param.typeReference?.text ?: "Any",
                            fullQualifiedName = param.typeReference?.text ?: "Any"
                        ),
                        annotations = param.annotationEntries.map {
                            AnnotationInfo(it.shortName?.asString() ?: "", it.typeReference?.text)
                        },
                        hasDefaultValue = param.hasDefaultValue(),
                        defaultValue = param.defaultValue?.text
                    )
                }

                val returnType = TypeInfo(
                    typeName = function.typeReference?.text ?: "Unit",
                    fullQualifiedName = function.typeReference?.text ?: "Unit"
                )

                val modifiers = mutableListOf<String>().apply {
                    if (function.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PUBLIC_KEYWORD)) add("public")
                    if (function.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PRIVATE_KEYWORD)) add("private")
                    if (function.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PROTECTED_KEYWORD)) add("protected")
                    if (function.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.INTERNAL_KEYWORD)) add("internal")
                    if (function.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.ABSTRACT_KEYWORD)) add("abstract")
                    if (function.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.FINAL_KEYWORD)) add("final")
                    if (function.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.OPEN_KEYWORD)) add("open")
                    if (function.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.OVERRIDE_KEYWORD)) add("override")
                    if (function.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.SUSPEND_KEYWORD)) add("suspend")
                    if (function.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.INLINE_KEYWORD)) add("inline")
                }

                val methodContext = MethodContext(
                    language = ProgrammingLanguage.KOTLIN,
                    projectInfo = ProjectInfo(project.name),
                    surroundingContext = SurroundingContext(
                        precedingCode = emptyList(),
                        followingCode = emptyList(),
                        imports = emptyList(),
                        fileComments = emptyList(),
                        siblingElements = emptyList()
                    ),
                    methodName = function.name,
                    parameters = parameters,
                    returnType = returnType,
                    modifiers = modifiers,
                    annotations = function.annotationEntries.map {
                        AnnotationInfo(it.shortName?.asString() ?: "", it.typeReference?.text)
                    },
                    exceptions = emptyList(), // Kotlin doesn't have checked exceptions
                    methodBody = function.bodyExpression?.text ?: function.bodyBlockExpression?.text,
                    isConstructor = function is org.jetbrains.kotlin.psi.KtConstructor<*>,
                    isAbstract = function.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.ABSTRACT_KEYWORD)
                )

                Result.success(methodContext)
            }
        } catch (e: Exception) {
            Result.error(NymError.Unknown("Kotlin函数分析失败: ${e.message}"))
        }
    }
    
    fun analyzeKotlinClass(clazz: KtClass): Result<ClassContext> {
        return try {
            ReadAction.compute<Result<ClassContext>, Throwable> {
                val superClass = clazz.superTypeListEntries
                    .filterIsInstance<org.jetbrains.kotlin.psi.KtSuperTypeCallEntry>()
                    .firstOrNull()?.let { superEntry ->
                        TypeInfo(
                            typeName = superEntry.typeReference?.text ?: "",
                            fullQualifiedName = superEntry.typeReference?.text ?: ""
                        )
                    }

                val interfaces = clazz.superTypeListEntries
                    .filterIsInstance<org.jetbrains.kotlin.psi.KtSuperTypeEntry>()
                    .map { interfaceEntry ->
                        TypeInfo(
                            typeName = interfaceEntry.typeReference?.text ?: "",
                            fullQualifiedName = interfaceEntry.typeReference?.text ?: ""
                        )
                    }

                val modifiers = mutableListOf<String>().apply {
                    if (clazz.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PUBLIC_KEYWORD)) add("public")
                    if (clazz.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PRIVATE_KEYWORD)) add("private")
                    if (clazz.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PROTECTED_KEYWORD)) add("protected")
                    if (clazz.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.INTERNAL_KEYWORD)) add("internal")
                    if (clazz.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.ABSTRACT_KEYWORD)) add("abstract")
                    if (clazz.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.FINAL_KEYWORD)) add("final")
                    if (clazz.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.OPEN_KEYWORD)) add("open")
                    if (clazz.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.DATA_KEYWORD)) add("data")
                    if (clazz.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.SEALED_KEYWORD)) add("sealed")
                    if (clazz.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.INLINE_KEYWORD)) add("inline")
                }

                val properties = clazz.getProperties().map { property ->
                    FieldInfo(
                        name = property.name ?: "",
                        type = TypeInfo(
                            typeName = property.typeReference?.text ?: "Any",
                            fullQualifiedName = property.typeReference?.text ?: "Any"
                        ),
                        modifiers = mutableListOf<String>().apply {
                            if (property.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PUBLIC_KEYWORD)) add("public")
                            if (property.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PRIVATE_KEYWORD)) add("private")
                            if (property.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PROTECTED_KEYWORD)) add("protected")
                            if (property.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.INTERNAL_KEYWORD)) add("internal")
                            if (property.isVar()) add("var") else add("val")
                        },
                        annotations = property.annotationEntries.map {
                            AnnotationInfo(it.shortName?.asString() ?: "", it.typeReference?.text)
                        },
                        initializer = property.initializer?.text
                    )
                }

                val methods = clazz.declarations.filterIsInstance<KtFunction>().map { function ->
                    MethodInfo(
                        name = function.name ?: "",
                        returnType = TypeInfo(
                            typeName = function.typeReference?.text ?: "Unit",
                            fullQualifiedName = function.typeReference?.text ?: "Unit"
                        ),
                        parameters = function.valueParameters.map { param ->
                            ParameterInfo(
                                name = param.name ?: "",
                                type = TypeInfo(
                                    typeName = param.typeReference?.text ?: "Any",
                                    fullQualifiedName = param.typeReference?.text ?: "Any"
                                )
                            )
                        },
                        modifiers = mutableListOf<String>().apply {
                            if (function.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PUBLIC_KEYWORD)) add("public")
                            if (function.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PRIVATE_KEYWORD)) add("private")
                            if (function.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PROTECTED_KEYWORD)) add("protected")
                            if (function.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.INTERNAL_KEYWORD)) add("internal")
                            if (function.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.OVERRIDE_KEYWORD)) add("override")
                            if (function.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.SUSPEND_KEYWORD)) add("suspend")
                        },
                        annotations = function.annotationEntries.map {
                            AnnotationInfo(it.shortName?.asString() ?: "", it.typeReference?.text)
                        }
                    )
                }

                val classContext = ClassContext(
                    language = ProgrammingLanguage.KOTLIN,
                    projectInfo = ProjectInfo(project.name),
                    surroundingContext = SurroundingContext(
                        precedingCode = emptyList(),
                        followingCode = emptyList(),
                        imports = emptyList(),
                        fileComments = emptyList(),
                        siblingElements = emptyList()
                    ),
                    className = clazz.name,
                    superClass = superClass,
                    interfaces = interfaces,
                    modifiers = modifiers,
                    annotations = clazz.annotationEntries.map {
                        AnnotationInfo(it.shortName?.asString() ?: "", it.typeReference?.text)
                    },
                    fields = properties,
                    methods = methods,
                    innerClasses = clazz.declarations.filterIsInstance<KtClass>().map { innerClass ->
                        ClassInfo(
                            name = innerClass.name ?: "",
                            fullQualifiedName = innerClass.containingKtFile.packageDirective?.fqName?.let { packageName ->
                                if (packageName.isRoot) innerClass.name else "${packageName.asString()}.${innerClass.name}"
                            } ?: innerClass.name,
                            isInterface = innerClass.isInterface(),
                            isAbstract = innerClass.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.ABSTRACT_KEYWORD),
                            isEnum = innerClass.isEnum()
                        )
                    },
                    isInterface = clazz.isInterface(),
                    isAbstract = clazz.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.ABSTRACT_KEYWORD),
                    isEnum = clazz.isEnum(),
                    packageName = clazz.containingKtFile.packageDirective?.fqName?.asString() ?: ""
                )

                Result.success(classContext)
            }
        } catch (e: Exception) {
            Result.error(NymError.Unknown("Kotlin类分析失败: ${e.message}"))
        }
    }
    
    fun analyzeKotlinProperty(property: KtProperty): Result<VariableContext> {
        return try {
            ReadAction.compute<Result<VariableContext>, Throwable> {
                val propertyType = TypeInfo(
                    typeName = property.typeReference?.text ?: "Any",
                    fullQualifiedName = property.typeReference?.text ?: "Any",
                    isNullable = property.typeReference?.text?.endsWith("?") == true
                )

                val modifiers = mutableListOf<String>().apply {
                    if (property.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PUBLIC_KEYWORD)) add("public")
                    if (property.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PRIVATE_KEYWORD)) add("private")
                    if (property.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PROTECTED_KEYWORD)) add("protected")
                    if (property.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.INTERNAL_KEYWORD)) add("internal")
                    if (property.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.CONST_KEYWORD)) add("const")
                    if (property.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.LATEINIT_KEYWORD)) add("lateinit")
                    if (property.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.OVERRIDE_KEYWORD)) add("override")
                    if (property.isVar()) add("var") else add("val")
                }

                val containingClass = property.parent?.parent as? KtClass

                val variableContext = VariableContext(
                    language = ProgrammingLanguage.KOTLIN,
                    projectInfo = ProjectInfo(project.name),
                    surroundingContext = SurroundingContext(
                        precedingCode = emptyList(),
                        followingCode = emptyList(),
                        imports = emptyList(),
                        fileComments = emptyList(),
                        siblingElements = emptyList()
                    ),
                    variableName = property.name,
                    variableType = propertyType,
                    modifiers = modifiers,
                    annotations = property.annotationEntries.map {
                        AnnotationInfo(it.shortName?.asString() ?: "", it.typeReference?.text)
                    },
                    initializer = property.initializer?.text,
                    scope = if (property.isTopLevel) VariableScope.GLOBAL else VariableScope.FIELD,
                    isConstant = property.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.CONST_KEYWORD) || !property.isVar(),
                    isStatic = property.isTopLevel,
                    containingClass = containingClass?.let { cls ->
                        ClassInfo(
                            name = cls.name ?: "",
                            fullQualifiedName = cls.containingKtFile.packageDirective?.fqName?.let { packageName ->
                                if (packageName.isRoot) cls.name else "${packageName.asString()}.${cls.name}"
                            } ?: cls.name,
                            isInterface = cls.isInterface(),
                            isAbstract = cls.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.ABSTRACT_KEYWORD)
                        )
                    }
                )

                Result.success(variableContext)
            }
        } catch (e: Exception) {
            Result.error(NymError.Unknown("Kotlin属性分析失败: ${e.message}"))
        }
    }
    
    fun analyzeKotlinParameter(parameter: KtParameter): Result<VariableContext> {
        return try {
            ReadAction.compute<Result<VariableContext>, Throwable> {
                val parameterType = TypeInfo(
                    typeName = parameter.typeReference?.text ?: "Any",
                    fullQualifiedName = parameter.typeReference?.text ?: "Any",
                    isNullable = parameter.typeReference?.text?.endsWith("?") == true
                )

                val modifiers = mutableListOf<String>().apply {
                    if (parameter.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.VARARG_KEYWORD)) add("vararg")
                    if (parameter.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.NOINLINE_KEYWORD)) add("noinline")
                    if (parameter.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.CROSSINLINE_KEYWORD)) add("crossinline")
                    if (parameter.isMutable) add("var") else add("val")
                }

                val variableContext = VariableContext(
                    language = ProgrammingLanguage.KOTLIN,
                    projectInfo = ProjectInfo(project.name),
                    surroundingContext = SurroundingContext(
                        precedingCode = emptyList(),
                        followingCode = emptyList(),
                        imports = emptyList(),
                        fileComments = emptyList(),
                        siblingElements = emptyList()
                    ),
                    variableName = parameter.name,
                    variableType = parameterType,
                    modifiers = modifiers,
                    annotations = parameter.annotationEntries.map {
                        AnnotationInfo(it.shortName?.asString() ?: "", it.typeReference?.text)
                    },
                    initializer = parameter.defaultValue?.text,
                    scope = VariableScope.PARAMETER,
                    isConstant = !parameter.isMutable,
                    isStatic = false,
                    containingMethod = PsiTreeUtil.getParentOfType(parameter, KtFunction::class.java)?.let { function ->
                        MethodInfo(
                            name = function.name ?: "",
                            returnType = TypeInfo(
                                typeName = function.typeReference?.text ?: "Unit",
                                fullQualifiedName = function.typeReference?.text ?: "Unit"
                            )
                        )
                    }
                )

                Result.success(variableContext)
            }
        } catch (e: Exception) {
            Result.error(NymError.Unknown("Kotlin参数分析失败: ${e.message}"))
        }
    }
}