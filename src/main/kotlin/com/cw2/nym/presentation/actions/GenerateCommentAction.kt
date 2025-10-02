package com.cw2.nym.presentation.actions

import com.cw2.nym.ai.model.CodeContext
import com.cw2.nym.ai.model.MethodContext
import com.cw2.nym.ai.model.ClassContext
import com.cw2.nym.ai.model.ProgrammingLanguage
import com.cw2.nym.ai.model.SurroundingContext
import com.cw2.nym.ai.model.TypeInfo
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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiClass
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtPsiFactory
import kotlinx.coroutines.runBlocking

/**
 * 生成注释（KDoc/JavaDoc）
 *
 * 实现策略：
 * - 在后台任务中分析当前光标处的代码元素
 * - 构建适当的 CodeContext 传递给 AI Provider
 * - 调用配置的 AI Provider 生成真实的注释内容
 * - 使用写命令将AI生成的注释插入到代码中
 */
internal class GenerateCommentAction : BaseAction() {

    override fun perform(project: Project, editor: Editor, e: AnActionEvent) {
        val psiFile: PsiFile = e.getData(CommonDataKeys.PSI_FILE) ?: run {
            NymNotifier.warn(NymBundle.message("action.comment.noPsiFile"))
            return
        }
        val offset = editor.caretModel.offset
        val methodElementAndLang = ReadAction.compute<Pair<PsiElement, ProgrammingLanguage>?, Throwable> {
            val element = psiFile.findElementAt(offset)
            if (element != null) {
                val kt = PsiTreeUtil.getParentOfType(element, KtFunction::class.java)
                if (kt != null) return@compute Pair(kt, ProgrammingLanguage.KOTLIN)
                val jm = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
                if (jm != null) return@compute Pair(jm, ProgrammingLanguage.JAVA)
                val kc = PsiTreeUtil.getParentOfType(element, KtClass::class.java)
                if (kc != null) return@compute Pair(kc, ProgrammingLanguage.KOTLIN)
                val jc = PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
                if (jc != null) return@compute Pair(jc, ProgrammingLanguage.JAVA)
            }
            null
        }
        if (methodElementAndLang == null) {
            NymNotifier.warn(NymBundle.message("action.comment.notSupportedHere"))
            return
        }
        val (methodElement, detectedLang) = methodElementAndLang

        val title = NymBundle.message("action.generateComment.text")
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = NymBundle.message("progress.checkingCommentStatus")

                try {
                    // 检查是否已存在注释
                    val hasExistingDoc = ReadAction.compute<Boolean, Throwable> {
                        when (val el = methodElement) {
                            is KtFunction -> el.docComment != null
                            is PsiMethod -> el.docComment != null
                            is KtClass -> el.docComment != null
                            is PsiClass -> el.docComment != null
                            else -> false
                        }
                    }
                    if (hasExistingDoc) {
                        NymNotifier.info(NymBundle.message("action.comment.alreadyExists"))
                        return
                    }

                    if (indicator.isCanceled) return

                    // 创建AI Provider实例
                    val provider = createAIProvider()
                    if (provider == null) {
                        NymNotifier.warn(NymBundle.message("settings.api.notConfigured"))
                        return
                    }

                    indicator.text = NymBundle.message("progress.analyzingTargetContext")

                    // 构建代码上下文
                    val codeContext = buildCodeContext(project, methodElement, indicator)
                    if (codeContext == null || indicator.isCanceled) return

                    indicator.text = NymBundle.message("progress.generatingComment")

                    // 调用AI生成注释
                    val result = runBlocking {
                        provider.generateComment(codeContext)
                    }

                    if (indicator.isCanceled) return

                    // 处理结果并插入注释
                    if (result.isSuccess) {
                        val commentSuggestion = result.getOrNull()
                        val commentContent = commentSuggestion?.content ?: NymBundle.message("action.comment.generatedPlaceholder")
                        
                        // 写命令：插入AI生成的注释（KDoc/JavaDoc）
                        WriteCommandAction.runWriteCommandAction(project, title, null, Runnable {
                            when (val el = methodElement) {
                                is KtFunction -> {
                                    val psiFactory = KtPsiFactory(project)
                                    val doc = psiFactory.createComment("/**\n * $commentContent\n */")
                                    (el as KtDeclaration).addBefore(doc, el.firstChild)
                                }
                                is PsiMethod -> {
                                    val factory = JavaPsiFacade.getElementFactory(project)
                                    val doc = factory.createDocCommentFromText("/**\n * $commentContent\n */")
                                    el.addBefore(doc, el.firstChild)
                                    CodeStyleManager.getInstance(project).reformat(el)
                                }
                                is KtClass -> {
                                    val psiFactory = KtPsiFactory(project)
                                    val doc = psiFactory.createComment("/**\n * $commentContent\n */")
                                    (el as KtDeclaration).addBefore(doc, el.firstChild)
                                }
                                is PsiClass -> {
                                    val factory = JavaPsiFacade.getElementFactory(project)
                                    val doc = factory.createDocCommentFromText("/**\n * $commentContent\n */")
                                    el.addBefore(doc, el.firstChild)
                                    CodeStyleManager.getInstance(project).reformat(el)
                                }
                                else -> {
                                    // 未知类型：不进行插入
                                }
                            }
                            NymNotifier.info(NymBundle.message("action.comment.generatedOk"))
                        })
                    } else {
                        val error = result.errorOrNull()
                        run {
                            val errMsg = error?.message ?: NymBundle.message("common.unknownError")
                            NymNotifier.warn(NymBundle.message("action.common.failed", errMsg))
                        }
                    }

                } catch (t: Throwable) {
                    NymLogger.logError("GenerateCommentAction", 
                        com.cw2.nym.core.exception.NymError.APIError.ServerError("注释生成异常: ${t.message}"),
                        mapOf("exception" to (t.message ?: "unknown")))
                    run {
                        val errMsg = t.message ?: NymBundle.message("common.unknownError")
                        NymNotifier.warn(NymBundle.message("action.common.failed", errMsg))
                    }
                }
            }
        })
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
                        maxTokens = 300
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
                        maxTokens = 300
                    )
                )
            }
        }
    }

    /**
     * 构建代码上下文（专为注释生成优化）
     */
    private fun buildCodeContext(project: Project, methodElement: PsiElement, indicator: ProgressIndicator): CodeContext? {
        return try {
            ReadAction.compute<CodeContext?, Throwable> {
                val analyzer = UniversalCodeAnalyzer(project)
                val language = analyzer.detectLanguage(methodElement)
                val projectInfo = analyzer.getProjectInfo()
                val surroundingContext = analyzer.extractSurroundingContext(methodElement).getOrNull() ?: 
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

                when (methodElement) {
                    is KtFunction -> {
                        val analyzeResult = analyzer.analyzeMethod(methodElement)
                        if (analyzeResult.isSuccess) {
                            analyzeResult.getOrNull() as? MethodContext
                        } else {
                            MethodContext(
                                language = language,
                                projectInfo = projectInfo,
                                surroundingContext = surroundingContext,
                                methodName = methodElement.name,
                                parameters = emptyList(),
                                returnType = TypeInfo("Unit"),
                                modifiers = emptyList(),
                                annotations = emptyList(),
                                exceptions = emptyList(),
                                methodBody = methodElement.bodyExpression?.text,
                                isConstructor = false,
                                isAbstract = false,
                                containingClass = null
                            )
                        }
                    }
                    is PsiMethod -> {
                        val analyzeResult = analyzer.analyzeMethod(methodElement)
                        if (analyzeResult.isSuccess) {
                            analyzeResult.getOrNull() as? MethodContext
                        } else {
                            MethodContext(
                                language = language,
                                projectInfo = projectInfo,
                                surroundingContext = surroundingContext,
                                methodName = methodElement.name,
                                parameters = emptyList(),
                                returnType = TypeInfo(methodElement.returnType?.presentableText ?: "void"),
                                modifiers = emptyList(),
                                annotations = emptyList(),
                                exceptions = emptyList(),
                                methodBody = methodElement.body?.text,
                                isConstructor = methodElement.isConstructor,
                                isAbstract = methodElement.hasModifierProperty(PsiModifier.ABSTRACT),
                                containingClass = null
                            )
                        }
                    }
                    is KtClass -> {
                        val analyzeResult = analyzer.analyzeClass(methodElement)
                        if (analyzeResult.isSuccess) {
                            analyzeResult.getOrNull() as? ClassContext
                        } else {
                            ClassContext(
                                language = language,
                                projectInfo = projectInfo,
                                surroundingContext = surroundingContext,
                                className = methodElement.name,
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
                    is PsiClass -> {
                        val analyzeResult = analyzer.analyzeClass(methodElement)
                        if (analyzeResult.isSuccess) {
                            analyzeResult.getOrNull() as? ClassContext
                        } else {
                            ClassContext(
                                language = language,
                                projectInfo = projectInfo,
                                surroundingContext = surroundingContext,
                                className = methodElement.name,
                                superClass = null,
                                interfaces = emptyList(),
                                modifiers = emptyList(),
                                annotations = emptyList(),
                                fields = emptyList(),
                                methods = emptyList(),
                                innerClasses = emptyList(),
                                isInterface = methodElement.isInterface,
                                isAbstract = methodElement.hasModifierProperty(PsiModifier.ABSTRACT),
                                isEnum = methodElement.isEnum,
                                packageName = surroundingContext.packageDeclaration ?: ""
                            )
                        }
                    }
                    else -> {
                        MethodContext(
                            language = language,
                            projectInfo = projectInfo,
                            surroundingContext = surroundingContext,
                            methodName = null,
                            parameters = emptyList(),
                            returnType = TypeInfo("void"),
                            modifiers = emptyList(),
                            annotations = emptyList(),
                            exceptions = emptyList(),
                            methodBody = null,
                            isConstructor = false,
                            isAbstract = false,
                            containingClass = null
                        )
                    }
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
