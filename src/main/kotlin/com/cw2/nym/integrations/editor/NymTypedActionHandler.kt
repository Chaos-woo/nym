package com.cw2.nym.integrations.editor

import com.cw2.nym.ai.provider.AIProvider
import com.cw2.nym.ai.provider.custom.CustomAPIConfig
import com.cw2.nym.ai.provider.custom.CustomAPIProvider
import com.cw2.nym.ai.provider.openai.OpenAIConfig
import com.cw2.nym.ai.provider.openai.OpenAIProvider
import com.cw2.nym.core.logging.NymLogger
import com.cw2.nym.data.settings.NymSecureStorage
import com.cw2.nym.data.settings.NymSettings
import com.cw2.nym.platform.task.AITaskManager
import com.cw2.nym.presentation.notifications.NymNotifier
import com.cw2.nym.presentation.messages.NymBundle
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.TypedActionHandler
import com.intellij.openapi.util.TextRange
import kotlin.math.max
import kotlinx.coroutines.runBlocking

/*
 * 键入处理器（基于 TypedActionHandler）
 *
 * 中文说明：
 * - 通过 StartupActivity 在 IDE 启动后替换原有 TypedActionHandler，从而实现按键拦截。
 * - 新约定：当检测到形如 "[$<用户自定义提示>]" 的模式且键入 ']' 完成闭合时，
 *   触发 AI 调用，使用用户输入的提示作为唯一 prompt，不添加任何硬编码前后缀，
 *   并将整个特殊符号（包括方括号）替换为 AI 返回的内容。
 * - 保持非目标输入走默认逻辑，避免影响 IDE 体验。
 */
internal class NymTypedActionHandler(
    private val delegate: TypedActionHandler?
) : TypedActionHandler {

    override fun execute(editor: Editor, charTyped: Char, dataContext: DataContext) {
        // 仅当设置开启且键入 ']' 时尝试匹配
        if (!NymSettings.getInstance().autoTrigger || charTyped != ']') {
            delegate?.execute(editor, charTyped, dataContext)
            return
        }

        val project = editor.project
        if (project == null) {
            delegate?.execute(editor, charTyped, dataContext)
            return
        }

        val document = editor.document
        val caretOffset = editor.caretModel.offset

        // 为避免全量扫描，仅在最近窗口中回溯查找 "[$"
        val windowSize = 512
        val windowStart = max(0, caretOffset - windowSize)
        val recent = document.getText(TextRange(windowStart, caretOffset))
        val localIdx = recent.lastIndexOf("[$")
        if (localIdx < 0) {
            delegate?.execute(editor, charTyped, dataContext)
            return
        }

        val startOffset = windowStart + localIdx // 指向 '[' 的位置
        val promptStart = startOffset + 2 // 紧随 "[$" 之后
        val promptEnd = caretOffset - 1 // ']' 之前的最后一个字符
        if (promptEnd < promptStart) {
            delegate?.execute(editor, charTyped, dataContext)
            return
        }

        val prompt = document.getText(TextRange(promptStart, promptEnd)).trim()
        if (prompt.isEmpty()) {
            // 空提示不触发 AI，交给默认处理
            delegate?.execute(editor, charTyped, dataContext)
            return
        }

        val provider = createAIProvider()
        if (provider == null) {
            NymNotifier.warn(NymBundle.message("typed.handler.notConfigured"))
            return
        }

        // 捕获当前需要替换的范围：[startOffset, caretOffset)
        val replaceStart = startOffset
        val replaceEnd = caretOffset

        AITaskManager.execute(
            project,
            title = NymBundle.message("typed.handler.title"),
            cancellable = true,
            task = {
                runBlocking {
                    provider.generateCustom(prompt, null)
                }
            },
            onSuccess = { result ->
                if (result.isSuccess) {
                    val text = result.getOrNull() ?: ""
                    // 为确保在平台完全加载、索引可用且在 EDT 执行写操作，这里延迟到 EDT 并在 Smart 模式下执行
                    val app = com.intellij.openapi.application.ApplicationManager.getApplication()
                    app.invokeLater({
                        val proj = project
                        if (proj == null || proj.isDisposed) return@invokeLater
                        com.intellij.openapi.project.DumbService.getInstance(proj).runWhenSmart {
                            WriteCommandAction.runWriteCommandAction(proj) {
                                try {
                                    document.replaceString(replaceStart, replaceEnd, text)
                                    com.intellij.psi.PsiDocumentManager.getInstance(proj).commitDocument(document)
                                    editor.caretModel.moveToOffset(replaceStart + text.length)
                                } catch (t: Throwable) {
                                    NymLogger.warn("TypedReplace", "Failed to replace text after AI result", error = t)
                                }
                            }
                        }
                    }, com.intellij.openapi.application.ModalityState.defaultModalityState())
                } else {
                    val err = result.errorOrNull()
                    val errMsg = err?.message ?: NymBundle.message("common.unknownError")
                    NymNotifier.warn(NymBundle.message("action.common.failed", errMsg))
                }
            },
            onError = { t ->
                run {
                    val errMsg = t.message ?: NymBundle.message("common.unknownError")
                    NymNotifier.warn(NymBundle.message("action.common.failed", errMsg))
                }
            }
        )
    }

    private fun createAIProvider(): AIProvider? {
        val settings = NymSettings.getInstance()
        val secureKey = NymSecureStorage.getApiKey()
        val resolvedKey = secureKey.ifBlank { settings.apiKey.ifBlank { System.getenv("OPENAI_API_KEY") ?: "" } }
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
                        maxTokens = 500
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
                        maxTokens = 500
                    )
                )
            }
        }
    }
}
