package com.cw2.nym.platform.lifecycle

import com.cw2.nym.core.logging.NymLogger
import com.cw2.nym.integrations.editor.NymTypedActionHandler
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.editor.actionSystem.TypedAction
import com.intellij.openapi.editor.actionSystem.TypedActionHandler
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

/*
 * 启动活动：在项目启动后安装自定义键入处理器
 *
 * 中文说明：
 * - 为了兼容新版平台扩展点变动，这里通过运行时替换的方式包裹原有 TypedActionHandler。
 * - 不依赖已标记废弃或将移除的扩展点，降低兼容风险。
 */
internal class NymStartupActivity : StartupActivity {
    override fun runActivity(project: Project) {
        try {
            val actionManager: EditorActionManager = EditorActionManager.getInstance()
            val typedAction: TypedAction = actionManager.typedAction
            val original: TypedActionHandler? = typedAction.handler

            // 包裹原处理器，形成链式调用
            typedAction.setupHandler(NymTypedActionHandler(original))
            NymLogger.info("STARTUP", "NymTypedActionHandler installed")
        } catch (e: Throwable) {
            // 启动失败不应影响 IDE 使用，记录日志即可
            NymLogger.warn("STARTUP", "Failed to install typed handler", error = e)
        }
    }
}
