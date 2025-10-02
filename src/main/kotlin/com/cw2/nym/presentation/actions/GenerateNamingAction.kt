package com.cw2.nym.presentation.actions

import com.cw2.nym.ai.model.CodeContext
import com.cw2.nym.ai.model.MethodContext
import com.cw2.nym.ai.model.VariableContext
import com.cw2.nym.ai.model.ClassContext
import com.cw2.nym.ai.model.ProgrammingLanguage
import com.cw2.nym.ai.model.SurroundingContext
import com.cw2.nym.ai.model.TypeInfo
import com.cw2.nym.ai.model.ParameterInfo
import com.cw2.nym.ai.provider.openai.OpenAIProvider
import com.cw2.nym.ai.provider.openai.OpenAIConfig
import com.cw2.nym.ai.provider.custom.CustomAPIProvider
import com.cw2.nym.ai.provider.custom.CustomAPIConfig
import com.cw2.nym.core.logging.NymLogger
import com.cw2.nym.data.settings.NymSettings
import com.cw2.nym.data.settings.NymSecureStorage
import com.cw2.nym.integrations.psi.UniversalCodeAnalyzer
import com.cw2.nym.presentation.messages.NymBundle
import com.cw2.nym.presentation.notifications.NymNotifier
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiVariable
import com.intellij.psi.PsiField
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiClass
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtClass
import kotlinx.coroutines.runBlocking

/**
 * 生成命名建议
 *
 * 实现策略：
 * - 在后台任务中分析当前光标处 PSI 元素
 * - 根据方法/变量分别构建适当的 CodeContext
 * - 调用配置的 AI Provider 生成真实的命名建议
 * - 展示AI生成的多个命名选项供用户选择
 */
internal class GenerateNamingAction : BaseAction() {

    override fun perform(project: Project, editor: Editor, e: AnActionEvent) {
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: run {
            NymNotifier.warn(NymBundle.message("action.naming.noPsiFile"))
            return
        }
        // 优先使用光标位置的 PSI 元素；若不可用再回退到事件上下文中的 PSI 元素（右键位置）
        val element = elementAtCaret(editor, psiFile) ?: e.getData(CommonDataKeys.PSI_ELEMENT)
        if (element == null) {
            NymNotifier.warn(NymBundle.message("action.naming.noElement"))
            return
        }

        val title = NymBundle.message("action.generateNaming.text")
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = NymBundle.message("progress.analyzingContext")

                try {
                    // 创建AI Provider实例
                    val provider = createAIProvider()
                    if (provider == null) {
                        NymNotifier.warn(NymBundle.message("settings.api.notConfigured"))
                        return
                    }

                    // 构建代码上下文
                    val codeContext = buildCodeContext(project, element, indicator)
                    if (codeContext == null || indicator.isCanceled) return

                    indicator.text = NymBundle.message("progress.generatingNaming")

                    // 调用AI生成命名建议
                    val result = runBlocking {
                        provider.generateNaming(codeContext)
                    }

                    if (indicator.isCanceled) return

                    // 处理结果
                    val message = if (result.isSuccess) {
                        val suggestions = result.getOrNull() ?: emptyList()
                        if (suggestions.isNotEmpty()) {
                            val suggestionList = suggestions.take(5).joinToString(" / ") { it.name }
                            NymBundle.message("action.naming.suggestions", suggestionList)
                        } else {
                            NymBundle.message("action.naming.noSuggestions")
                        }
                    } else {
                        val error = result.errorOrNull()
                        val errMsg = error?.message ?: NymBundle.message("common.unknownError")
                        NymBundle.message("action.common.failed", errMsg)
                    }

                    NymNotifier.info(message)

                } catch (t: Throwable) {
                    NymLogger.logError("GenerateNamingAction", 
                        com.cw2.nym.core.exception.NymError.APIError.ServerError("命名生成异常: ${t.message}"),
                        mapOf("exception" to (t.message ?: "unknown")))
                    run {
                        val errMsg = t.message ?: NymBundle.message("common.unknownError")
                        NymNotifier.warn(NymBundle.message("action.common.failed", errMsg))
                    }
                }
            }
        })
    }

    private fun elementAtCaret(editor: Editor, psiFile: PsiFile): PsiElement? {
        val offset = editor.caretModel.offset
        return ReadAction.compute<PsiElement?, Throwable> {
            psiFile.findElementAt(offset)
        }
    }

    /**
     * 根据设置创建AI Provider实例
     */
    private fun createAIProvider(): com.cw2.nym.ai.provider.AIProvider? {
        val settings = NymSettings.getInstance()
        val secureKey = NymSecureStorage.getApiKey()
        val resolvedKey = if (secureKey.isNotBlank()) secureKey else settings.apiKey.ifBlank { System.getenv("OPENAI_API_KEY") ?: "" }
        
        if (resolvedKey.isBlank()) return null

        return when (settings.aiProvider) {
            "Custom" -> {
                if (settings.apiEndpoint.isBlank()) return null
                CustomAPIProvider(
                    CustomAPIConfig(
                        providerName = "Custom API",
                        apiUrl = settings.apiEndpoint,
                        apiKey = resolvedKey,
                        model = settings.model,
                        temperature = settings.modelTemperature / 100.0,
                        timeoutMs = settings.requestTimeoutMs.toLong(),
                        maxTokens = 150
                    )
                )
            }
            else -> {
                OpenAIProvider(
                    OpenAIConfig(
                        apiKey = resolvedKey,
                        model = settings.model,
                        temperature = settings.modelTemperature / 100.0,
                        timeoutMs = settings.requestTimeoutMs.toLong(),
                        maxTokens = 150
                    )
                )
            }
        }
    }

    /**
     * 构建代码上下文
     */
    private fun buildCodeContext(project: Project, element: PsiElement, indicator: ProgressIndicator): CodeContext? {
        return try {
            ReadAction.compute<CodeContext?, Throwable> {
                val analyzer = UniversalCodeAnalyzer(project)
                val language = analyzer.detectLanguage(element)
                val projectInfo = analyzer.getProjectInfo()
                val surroundingContext = analyzer.extractSurroundingContext(element).getOrNull() ?: 
                    SurroundingContext(
                        precedingCode = emptyList(),
                        followingCode = emptyList(),
                        imports = emptyList(),
                        packageDeclaration = null,
                        fileComments = emptyList(),
                        siblingElements = emptyList(),
                        namingPatterns = null,
                        codeStyleAnalysis = null
                    )

                when {
                    PsiTreeUtil.getParentOfType(element, KtFunction::class.java) != null -> {
                        val fn = PsiTreeUtil.getParentOfType(element, KtFunction::class.java)!!
                        val analyzeResult = analyzer.analyzeMethod(fn)
                        if (analyzeResult.isSuccess) {
                            analyzeResult.getOrNull() as? MethodContext
                        } else {
                            // 创建基础的MethodContext（Kotlin）
                            MethodContext(
                                language = language,
                                projectInfo = projectInfo,
                                surroundingContext = surroundingContext,
                                methodName = fn.name,
                                parameters = emptyList(),
                                returnType = TypeInfo("Unit"),
                                modifiers = emptyList(),
                                annotations = emptyList(),
                                exceptions = emptyList(),
                                methodBody = fn.bodyExpression?.text,
                                isConstructor = false,
                                isAbstract = false,
                                containingClass = null
                            )
                        }
                    }
                    PsiTreeUtil.getParentOfType(element, KtProperty::class.java) != null -> {
                        val prop = PsiTreeUtil.getParentOfType(element, KtProperty::class.java)!!
                        val analyzeResult = analyzer.analyzeVariable(prop)
                        if (analyzeResult.isSuccess) {
                            analyzeResult.getOrNull() as? VariableContext
                        } else {
                            // 创建基础的VariableContext（Kotlin）
                            VariableContext(
                                language = language,
                                projectInfo = projectInfo,
                                surroundingContext = surroundingContext,
                                variableName = prop.name,
                                variableType = TypeInfo("Any"),
                                modifiers = emptyList(),
                                annotations = emptyList(),
                                initializer = prop.initializer?.text,
                                scope = com.cw2.nym.ai.model.VariableScope.LOCAL,
                                isConstant = prop.isVar.not(),
                                isStatic = false,
                                usagePattern = null,
                                containingClass = null,
                                containingMethod = null
                            )
                        }
                    }
                    PsiTreeUtil.getParentOfType(element, PsiMethod::class.java) != null -> {
                        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)!!
                        val analyzeResult = analyzer.analyzeMethod(method)
                        if (analyzeResult.isSuccess) {
                            analyzeResult.getOrNull() as? MethodContext
                        } else {
                            // 创建基础的MethodContext（Java）
                            MethodContext(
                                language = language,
                                projectInfo = projectInfo,
                                surroundingContext = surroundingContext,
                                methodName = method.name,
                                parameters = emptyList(),
                                returnType = TypeInfo(method.returnType?.presentableText ?: "void"),
                                modifiers = emptyList(),
                                annotations = emptyList(),
                                exceptions = emptyList(),
                                methodBody = method.body?.text,
                                isConstructor = method.isConstructor,
                                isAbstract = method.hasModifierProperty(PsiModifier.ABSTRACT),
                                containingClass = null
                            )
                        }
                    }
                    PsiTreeUtil.getParentOfType(element, PsiField::class.java) != null -> {
                        val field = PsiTreeUtil.getParentOfType(element, PsiField::class.java)!!
                        val analyzeResult = analyzer.analyzeVariable(field)
                        if (analyzeResult.isSuccess) {
                            analyzeResult.getOrNull() as? VariableContext
                        } else {
                            // 创建基础的VariableContext（Java 字段）
                            VariableContext(
                                language = language,
                                projectInfo = projectInfo,
                                surroundingContext = surroundingContext,
                                variableName = field.name,
                                variableType = TypeInfo(field.type.presentableText),
                                modifiers = emptyList(),
                                annotations = emptyList(),
                                initializer = field.initializer?.text,
                                scope = if (field.hasModifierProperty(PsiModifier.STATIC)) com.cw2.nym.ai.model.VariableScope.STATIC_FIELD else com.cw2.nym.ai.model.VariableScope.FIELD,
                                isConstant = field.hasModifierProperty(PsiModifier.FINAL),
                                isStatic = field.hasModifierProperty(PsiModifier.STATIC),
                                usagePattern = null,
                                containingClass = null,
                                containingMethod = null
                            )
                        }
                    }
                    PsiTreeUtil.getParentOfType(element, PsiVariable::class.java) != null -> {
                        val v = PsiTreeUtil.getParentOfType(element, PsiVariable::class.java)!!
                        val analyzeResult = analyzer.analyzeVariable(v)
                        if (analyzeResult.isSuccess) {
                            analyzeResult.getOrNull() as? VariableContext
                        } else {
                            // 创建基础的VariableContext（Java 局部变量/参数）
                            VariableContext(
                                language = language,
                                projectInfo = projectInfo,
                                surroundingContext = surroundingContext,
                                variableName = v.name,
                                variableType = TypeInfo(v.type.presentableText),
                                modifiers = emptyList(),
                                annotations = emptyList(),
                                initializer = v.initializer?.text,
                                scope = com.cw2.nym.ai.model.VariableScope.LOCAL,
                                isConstant = false,
                                isStatic = false,
                                usagePattern = null,
                                containingClass = null,
                                containingMethod = null
                            )
                        }
                    }
                    PsiTreeUtil.getParentOfType(element, KtClass::class.java) != null -> {
                        val cls = PsiTreeUtil.getParentOfType(element, KtClass::class.java)!!
                        val analyzeResult = analyzer.analyzeClass(cls)
                        if (analyzeResult.isSuccess) {
                            analyzeResult.getOrNull() as? ClassContext
                        } else {
                            ClassContext(
                                language = language,
                                projectInfo = projectInfo,
                                surroundingContext = surroundingContext,
                                className = cls.name,
                                superClass = null,
                                interfaces = emptyList(),
                                modifiers = emptyList(),
                                annotations = emptyList(),
                                fields = emptyList(),
                                methods = emptyList(),
                                innerClasses = emptyList(),
                                isInterface = false,
                                isAbstract = false,
                                isEnum = false,
                                packageName = surroundingContext.packageDeclaration ?: ""
                            )
                        }
                    }
                    PsiTreeUtil.getParentOfType(element, PsiClass::class.java) != null -> {
                        val cls = PsiTreeUtil.getParentOfType(element, PsiClass::class.java)!!
                        val analyzeResult = analyzer.analyzeClass(cls)
                        if (analyzeResult.isSuccess) {
                            analyzeResult.getOrNull() as? ClassContext
                        } else {
                            ClassContext(
                                language = language,
                                projectInfo = projectInfo,
                                surroundingContext = surroundingContext,
                                className = cls.name,
                                superClass = null,
                                interfaces = emptyList(),
                                modifiers = emptyList(),
                                annotations = emptyList(),
                                fields = emptyList(),
                                methods = emptyList(),
                                innerClasses = emptyList(),
                                isInterface = cls.isInterface,
                                isAbstract = cls.hasModifierProperty(PsiModifier.ABSTRACT),
                                isEnum = cls.isEnum,
                                packageName = surroundingContext.packageDeclaration ?: ""
                            )
                        }
                    }
                    else -> null
                }
            }
        } catch (t: Throwable) {
            NymLogger.logError("buildCodeContext", 
                com.cw2.nym.core.exception.NymError.ParseError.InvalidConfiguration("构建代码上下文失败: ${t.message}"),
                mapOf("exception" to (t.message ?: "unknown")))
            null
        }
    }
}
