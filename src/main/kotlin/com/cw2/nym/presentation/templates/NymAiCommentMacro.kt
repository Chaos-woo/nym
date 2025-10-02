package com.cw2.nym.presentation.templates

import com.cw2.nym.ai.model.CodeContext
import com.cw2.nym.core.logging.NymLogger
import com.cw2.nym.data.settings.NymSettings
import com.cw2.nym.integrations.psi.UniversalCodeAnalyzer
import com.intellij.codeInsight.template.Expression
import com.intellij.codeInsight.template.ExpressionContext
import com.intellij.codeInsight.template.Macro
import com.intellij.codeInsight.template.Result
import com.intellij.codeInsight.template.TextResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import java.util.UUID

/**
 * Nym 自定义 Live Template 宏：基于当前光标上下文异步生成注释
 *
 * 设计说明（中文）：
 * - Live Template 展开通常发生在 EDT（事件派发线程），因此严禁直接做网络/IO。
 * - 该宏采用“占位符 + 后台生成 + 写命令替换”的策略，保证 UI 无阻塞。
 * - 为了最小改动：
 *   1) 立即返回一个唯一占位符文本（用户可见，避免空白）。
 *   2) 后台线程收集 PSI 上下文，调用既有 AI Provider 管线生成注释文本。
 *   3) 使用 WriteCommandAction 在文档中定位占位符并替换为最终结果。
 * - 当生成失败时，保留占位符并记录日志，避免破坏用户编辑流。
 */
class NymAiCommentMacro : Macro() {
    override fun getName(): String = "nymAiComment"

    override fun getPresentableName(): String = "nymAiComment()"

    override fun calculateResult(params: Array<Expression>, context: ExpressionContext): Result? {
        val project = context.project ?: return TextResult("Nym: 生成注释…")
        val editor = context.editor ?: return TextResult("Nym: 生成注释…")
        val document = editor.document

        // 生成一个独特占位符，后续用它来进行文本替换
        val placeholder = "/* Nym_Generating_Comment_${UUID.randomUUID().toString().substring(0, 8)} */"

        // 在后台线程执行 AI 生成逻辑
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                generateAndReplaceAsync(project, editor, document, context, placeholder)
            } catch (pc: ProcessCanceledException) {
                // 用户取消或 IDE 关闭等情况：按平台要求重新抛出，以便上层及时终止
                NymLogger.debug("NymAiCommentMacro", "生成过程被取消", mapOf("reason" to "ProcessCanceledException"))
                throw pc
            } catch (t: Throwable) {
                // 失败时仅记录日志，不阻塞 UI
                NymLogger.error("NymAiCommentMacro", "异步注释生成失败", mapOf("exception" to (t.message ?: "unknown")), t)
            }
        }

        // 立即返回占位符，让模板先行展开
        return TextResult(placeholder)
    }

    private fun generateAndReplaceAsync(
        project: Project,
        editor: Editor,
        document: Document,
        context: ExpressionContext,
        placeholder: String
    ) {
        // 读取 PSI 元素要在 ReadAction 内进行（此处只做最小读取）
        val psiAtOffset: PsiElement? = try {
            com.intellij.openapi.application.ReadAction.compute<PsiElement?, RuntimeException> {
                context.psiElementAtStartOffset
            }
        } catch (t: Throwable) {
            null
        }

        val analyzer = UniversalCodeAnalyzer(project)
        val codeElement = psiAtOffset
        if (codeElement == null) {
            NymLogger.warn("NymAiCommentMacro", "找不到 PSI 元素，使用简单上下文")
        }

        // 根据 PSI 元素推断语言与上下文；若失败，回退到 OTHER 与最小上下文
        val language = try { analyzer.detectLanguage(codeElement ?: return) } catch (_: Throwable) { com.cw2.nym.ai.model.ProgrammingLanguage.OTHER }
        val projectInfo = analyzer.getProjectInfo()
        val surrounding = if (codeElement != null) {
            analyzer.extractSurroundingContext(codeElement).getOrNull()
        } else null
        val surroundingContext = surrounding ?: com.cw2.nym.ai.model.SurroundingContext(
            precedingCode = emptyList(),
            followingCode = emptyList(),
            imports = emptyList(),
            packageDeclaration = null,
            fileComments = emptyList(),
            siblingElements = emptyList(),
            namingPatterns = null,
            codeStyleAnalysis = null
        )

        // 这里构造一个最小可用的 CodeContext（方法/类/变量难以可靠判定，使用通用 MethodContext 近似）
        // 为什么：为最小改动与演示效果，选择一个信息较丰富的上下文类型，后续可基于 PSI 元素精细化。
        val codeContext: CodeContext = com.cw2.nym.ai.model.MethodContext(
            language = language,
            projectInfo = projectInfo,
            surroundingContext = surroundingContext,
            methodName = null,
            parameters = emptyList(),
            returnType = com.cw2.nym.ai.model.TypeInfo("Unit"),
            modifiers = emptyList(),
            annotations = emptyList(),
            exceptions = emptyList(),
            methodBody = null,
            isConstructor = false,
            isAbstract = false,
            containingClass = null
        )

        // 从设置中读取用户偏好，作为后续提示增强（当前未深入介入提示模板，以中文注释记录设计）
        val settings = NymSettings.getInstance()
        val userLangPref = settings.languagePreference
        val userCommentFormat = settings.commentFormat
        val userNamingStyle = settings.namingStyle

        // 通过应用服务选择 Provider（最小实现：直接构造 OpenAIProvider 或交由未来的 Provider 工厂）
        // 优先走安全存储，向后兼容读取旧字段与环境变量
        val secureKey = com.cw2.nym.data.settings.NymSecureStorage.getApiKey()
        val resolvedKey = if (secureKey.isNotBlank()) secureKey else settings.apiKey.ifBlank { System.getenv("OPENAI_API_KEY") ?: "" }
        val provider = com.cw2.nym.ai.provider.openai.OpenAIProvider(
            com.cw2.nym.ai.provider.openai.OpenAIConfig(
                apiKey = resolvedKey,
                model = settings.model,
                // 温度从整型百分比转换为 0.0-1.0
                temperature = settings.modelTemperature / 100.0,
                timeoutMs = settings.requestTimeoutMs.toLong(),
                maxTokens = 200
            )
        )

        // 调用 AI 生成注释内容
        val result = try {
            // 使用运行阻塞的方式是为了简化：后台线程内调用挂起函数
            kotlinx.coroutines.runBlocking {
                provider.generateComment(codeContext)
            }
        } catch (t: Throwable) {
            NymLogger.error("NymAiCommentMacro", "调用 AI 失败", mapOf("exception" to (t.message ?: "unknown")), t)
            null
        }

        val finalText = when {
            result == null -> null
            result.isSuccess -> {
                val text = result.getOrNull()?.content ?: "生成失败"
                // 简单根据用户偏好调整格式（示例）：
                when (userCommentFormat) {
                    CommentFormat.JAVADOC.name -> "/**\n * $text\n */"
                    CommentFormat.JSDOC.name -> "/**\n * $text\n */"
                    else -> "// $text"
                }
            }
            else -> null
        }

        if (finalText == null) return

        // 在写命令中把占位符替换为最终内容
        replacePlaceholder(project, editor, document, placeholder, finalText)
    }

    private fun replacePlaceholder(project: Project, editor: Editor, document: Document, placeholder: String, finalText: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            val text = document.text
            val idx = text.indexOf(placeholder)
            if (idx >= 0) {
                document.replaceString(idx, idx + placeholder.length, finalText)
                PsiDocumentManager.getInstance(project).commitDocument(document)
            } else {
                // 找不到占位符（用户已编辑或撤销），将结果插入到光标处，尽量不打扰用户
                val caret = editor.caretModel.currentCaret
                val offset = caret.offset
                document.insertString(offset, "$finalText\n")
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
        }
    }
}

/**
 * 用户注释格式偏好（与设置页保持一致）
 */
enum class CommentFormat { LINE, JAVADOC, JSDOC }
