package com.cw2.nym.integrations.psi

import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.cw2.nym.ai.model.*
import com.cw2.nym.core.result.Result
import com.cw2.nym.core.exception.NymError

/**
 * Java代码分析器
 * 
 * 专门处理Java代码元素的分析，包括方法、类、变量等。
 */
class JavaCodeAnalyzer(private val project: Project) {
    
    fun analyzeJavaMethod(method: PsiMethod): Result<MethodContext> {
        return try {
            val parameters = method.parameterList.parameters.map { param ->
                ParameterInfo(
                    name = param.name ?: "",
                    type = TypeInfo(
                        typeName = param.type.presentableText,
                        fullQualifiedName = param.type.canonicalText,
                        isPrimitive = param.type is PsiPrimitiveType
                    ),
                    annotations = param.annotations.map { 
                        AnnotationInfo(it.qualifiedName ?: "", it.qualifiedName) 
                    }
                )
            }
            
            val returnType = TypeInfo(
                typeName = method.returnType?.presentableText ?: "void",
                fullQualifiedName = method.returnType?.canonicalText ?: "void",
                isPrimitive = method.returnType is PsiPrimitiveType
            )
            
            val modifiers = method.modifierList?.let { modifierList ->
                mutableListOf<String>().apply {
                    if (modifierList.hasModifierProperty(PsiModifier.PUBLIC)) add("public")
                    if (modifierList.hasModifierProperty(PsiModifier.PRIVATE)) add("private")
                    if (modifierList.hasModifierProperty(PsiModifier.PROTECTED)) add("protected")
                    if (modifierList.hasModifierProperty(PsiModifier.STATIC)) add("static")
                    if (modifierList.hasModifierProperty(PsiModifier.FINAL)) add("final")
                    if (modifierList.hasModifierProperty(PsiModifier.ABSTRACT)) add("abstract")
                }
            } ?: emptyList()
            
            val methodContext = MethodContext(
                language = ProgrammingLanguage.JAVA,
                projectInfo = ProjectInfo(project.name),
                surroundingContext = SurroundingContext(
                    precedingCode = emptyList(),
                    followingCode = emptyList(),
                    imports = emptyList(),
                    fileComments = emptyList(),
                    siblingElements = emptyList()
                ),
                methodName = method.name,
                parameters = parameters,
                returnType = returnType,
                modifiers = modifiers,
                annotations = method.annotations.map { 
                    AnnotationInfo(it.qualifiedName ?: "", it.qualifiedName) 
                },
                exceptions = method.throwsList.referencedTypes.map { 
                    TypeInfo(it.presentableText, it.canonicalText) 
                },
                methodBody = method.body?.text,
                isConstructor = method.isConstructor,
                isAbstract = method.hasModifierProperty(PsiModifier.ABSTRACT)
            )
            
            Result.success(methodContext)
        } catch (e: Exception) {
            Result.error(NymError.Unknown("Java方法分析失败: ${e.message}"))
        }
    }
    
    fun analyzeJavaClass(clazz: PsiClass): Result<ClassContext> {
        return try {
            val superClass = clazz.superClass?.let { superCls ->
                TypeInfo(
                    typeName = superCls.name ?: "",
                    fullQualifiedName = superCls.qualifiedName
                )
            }
            
            val interfaces = clazz.implementsList?.referencedTypes?.map { interfaceType ->
                TypeInfo(
                    typeName = interfaceType.presentableText,
                    fullQualifiedName = interfaceType.canonicalText
                )
            } ?: emptyList()
            
            val modifiers = clazz.modifierList?.let { modifierList ->
                mutableListOf<String>().apply {
                    if (modifierList.hasModifierProperty(PsiModifier.PUBLIC)) add("public")
                    if (modifierList.hasModifierProperty(PsiModifier.PRIVATE)) add("private")
                    if (modifierList.hasModifierProperty(PsiModifier.PROTECTED)) add("protected")
                    if (modifierList.hasModifierProperty(PsiModifier.STATIC)) add("static")
                    if (modifierList.hasModifierProperty(PsiModifier.FINAL)) add("final")
                    if (modifierList.hasModifierProperty(PsiModifier.ABSTRACT)) add("abstract")
                }
            } ?: emptyList()
            
            val fields = clazz.fields.map { field ->
                FieldInfo(
                    name = field.name,
                    type = TypeInfo(
                        typeName = field.type.presentableText,
                        fullQualifiedName = field.type.canonicalText,
                        isPrimitive = field.type is PsiPrimitiveType
                    ),
                    modifiers = field.modifierList?.let { modList ->
                        mutableListOf<String>().apply {
                            if (modList.hasModifierProperty(PsiModifier.PUBLIC)) add("public")
                            if (modList.hasModifierProperty(PsiModifier.PRIVATE)) add("private")
                            if (modList.hasModifierProperty(PsiModifier.PROTECTED)) add("protected")
                            if (modList.hasModifierProperty(PsiModifier.STATIC)) add("static")
                            if (modList.hasModifierProperty(PsiModifier.FINAL)) add("final")
                        }
                    } ?: emptyList()
                )
            }
            
            val methods = clazz.methods.map { method ->
                MethodInfo(
                    name = method.name,
                    returnType = TypeInfo(
                        typeName = method.returnType?.presentableText ?: "void",
                        fullQualifiedName = method.returnType?.canonicalText ?: "void"
                    ),
                    parameters = method.parameterList.parameters.map { param ->
                        ParameterInfo(
                            name = param.name ?: "",
                            type = TypeInfo(
                                typeName = param.type.presentableText,
                                fullQualifiedName = param.type.canonicalText
                            )
                        )
                    }
                )
            }
            
            val classContext = ClassContext(
                language = ProgrammingLanguage.JAVA,
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
                annotations = clazz.annotations.map { 
                    AnnotationInfo(it.qualifiedName ?: "", it.qualifiedName) 
                },
                fields = fields,
                methods = methods,
                innerClasses = clazz.innerClasses.map { innerClass ->
                    ClassInfo(
                        name = innerClass.name ?: "",
                        fullQualifiedName = innerClass.qualifiedName,
                        isInterface = innerClass.isInterface,
                        isAbstract = innerClass.hasModifierProperty(PsiModifier.ABSTRACT)
                    )
                },
                isInterface = clazz.isInterface,
                isAbstract = clazz.hasModifierProperty(PsiModifier.ABSTRACT),
                isEnum = clazz.isEnum,
                packageName = (clazz.containingFile as? PsiJavaFile)?.packageName ?: ""
            )
            
            Result.success(classContext)
        } catch (e: Exception) {
            Result.error(NymError.Unknown("Java类分析失败: ${e.message}"))
        }
    }
    
    fun analyzeJavaVariable(variable: PsiVariable): Result<VariableContext> {
        return try {
            val variableType = TypeInfo(
                typeName = variable.type.presentableText,
                fullQualifiedName = variable.type.canonicalText,
                isPrimitive = variable.type is PsiPrimitiveType
            )
            
            val modifiers = variable.modifierList?.let { modifierList ->
                mutableListOf<String>().apply {
                    if (modifierList.hasModifierProperty(PsiModifier.FINAL)) add("final")
                    if (modifierList.hasModifierProperty(PsiModifier.STATIC)) add("static")
                    if (modifierList.hasModifierProperty(PsiModifier.VOLATILE)) add("volatile")
                    if (modifierList.hasModifierProperty(PsiModifier.TRANSIENT)) add("transient")
                }
            } ?: emptyList()
            
            val variableContext = VariableContext(
                language = ProgrammingLanguage.JAVA,
                projectInfo = ProjectInfo(project.name),
                surroundingContext = SurroundingContext(
                    precedingCode = emptyList(),
                    followingCode = emptyList(),
                    imports = emptyList(),
                    fileComments = emptyList(),
                    siblingElements = emptyList()
                ),
                variableName = variable.name,
                variableType = variableType,
                modifiers = modifiers,
                annotations = variable.annotations.map { 
                    AnnotationInfo(it.qualifiedName ?: "", it.qualifiedName) 
                },
                initializer = variable.initializer?.text,
                scope = when (variable) {
                    is PsiField -> VariableScope.FIELD
                    is PsiLocalVariable -> VariableScope.LOCAL
                    is PsiParameter -> VariableScope.PARAMETER
                    else -> VariableScope.LOCAL
                },
                isConstant = variable.modifierList?.hasModifierProperty(PsiModifier.FINAL) == true,
                isStatic = variable.modifierList?.hasModifierProperty(PsiModifier.STATIC) == true
            )
            
            Result.success(variableContext)
        } catch (e: Exception) {
            Result.error(NymError.Unknown("Java变量分析失败: ${e.message}"))
        }
    }
    
    fun analyzeJavaField(field: PsiField): Result<VariableContext> {
        return try {
            val fieldType = TypeInfo(
                typeName = field.type.presentableText,
                fullQualifiedName = field.type.canonicalText,
                isPrimitive = field.type is PsiPrimitiveType
            )
            
            val modifiers = field.modifierList?.let { modifierList ->
                mutableListOf<String>().apply {
                    if (modifierList.hasModifierProperty(PsiModifier.PUBLIC)) add("public")
                    if (modifierList.hasModifierProperty(PsiModifier.PRIVATE)) add("private")
                    if (modifierList.hasModifierProperty(PsiModifier.PROTECTED)) add("protected")
                    if (modifierList.hasModifierProperty(PsiModifier.STATIC)) add("static")
                    if (modifierList.hasModifierProperty(PsiModifier.FINAL)) add("final")
                    if (modifierList.hasModifierProperty(PsiModifier.VOLATILE)) add("volatile")
                    if (modifierList.hasModifierProperty(PsiModifier.TRANSIENT)) add("transient")
                }
            } ?: emptyList()
            
            val variableContext = VariableContext(
                language = ProgrammingLanguage.JAVA,
                projectInfo = ProjectInfo(project.name),
                surroundingContext = SurroundingContext(
                    precedingCode = emptyList(),
                    followingCode = emptyList(),
                    imports = emptyList(),
                    fileComments = emptyList(),
                    siblingElements = emptyList()
                ),
                variableName = field.name,
                variableType = fieldType,
                modifiers = modifiers,
                annotations = field.annotations.map { 
                    AnnotationInfo(it.qualifiedName ?: "", it.qualifiedName) 
                },
                initializer = field.initializer?.text,
                scope = if (field.hasModifierProperty(PsiModifier.STATIC)) VariableScope.STATIC_FIELD else VariableScope.FIELD,
                isConstant = field.hasModifierProperty(PsiModifier.FINAL),
                isStatic = field.hasModifierProperty(PsiModifier.STATIC),
                containingClass = field.containingClass?.let { containingClass ->
                    ClassInfo(
                        name = containingClass.name ?: "",
                        fullQualifiedName = containingClass.qualifiedName,
                        isInterface = containingClass.isInterface,
                        isAbstract = containingClass.hasModifierProperty(PsiModifier.ABSTRACT)
                    )
                }
            )
            
            Result.success(variableContext)
        } catch (e: Exception) {
            Result.error(NymError.Unknown("Java字段分析失败: ${e.message}"))
        }
    }
}