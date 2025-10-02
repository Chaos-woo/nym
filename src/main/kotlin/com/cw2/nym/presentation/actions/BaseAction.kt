package com.cw2.nym.presentation.actions

import com.cw2.nym.core.metrics.MetricsCollector
import com.cw2.nym.presentation.notifications.NymNotifier
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project

/**
 * 所有 Nym 动作的基类
 *
 * 设计动机（为什么）：
 * - 统一可用性检查（无 Project/Editor 时禁用）
 * - 复用通用的参数提取逻辑
 * - 降低各 Action 的样板代码
 * - 轻量埋点：记录一次动作调用的成功/失败与耗时，用于工具窗口统计
 */
internal abstract class BaseAction : AnAction(), DumbAware {

    final override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val enabled = project != null && editor != null
        e.presentation.isEnabledAndVisible = enabled
        // 在 Dumb 模式下也允许显示，但避免做索引相关操作（各子类在执行时需注意）
    }

    final override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        if (project == null || editor == null) {
            NymNotifier.warn(com.cw2.nym.presentation.messages.NymBundle.message("base.action.missingContext"))
            return
        }

        // 统一埋点计时（中文说明：不侵入各子类逻辑，只统计顶层调用耗时）
        val start = System.currentTimeMillis()
        var success = true
        try {
            perform(project, editor, e)
        } catch (t: Throwable) {
            success = false
            // 错误提示简化处理，避免暴露敏感信息
            NymNotifier.error(com.cw2.nym.presentation.messages.NymBundle.message("base.action.failed", t.message ?: com.cw2.nym.presentation.messages.NymBundle.message("common.unknownError")))
        } finally {
            val cost = System.currentTimeMillis() - start
            MetricsCollector.record(success = success, latencyMs = cost)
        }
    }

    /**
     * 子类实现具体处理逻辑
     */
    protected abstract fun perform(project: Project, editor: Editor, e: AnActionEvent)
}
