package com.cw2.nym.platform.task

import com.cw2.nym.core.logging.NymLogger
import com.cw2.nym.data.settings.NymSettings
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

/**
 * 后台 AI 任务执行器（第三阶段：4.1-23）
 *
 * 设计说明（中文）：
 * - 提供最小封装：在带进度的后台任务中执行传入的函数，支持取消与超时检查。
 * - 避免阻塞 EDT；UI 更新由调用方在 onSuccess/onError 中自行确保在 EDT 执行。
 * - 超时阈值读取自 NymSettings（requestTimeoutMs），仅作软性检查（按需中断）。
 */
object AITaskManager {

    /**
     * 在后台执行任务，显示进度，可取消。
     * @param project 所在项目（可空，用于窗口归属）
     * @param title 任务标题（显示于进度窗口）
     * @param cancellable 是否可取消
     * @param task 实际执行体，接受 ProgressIndicator
     * @param onSuccess 成功回调
     * @param onError 失败回调
     */
    fun <T> execute(
        project: Project?,
        title: String,
        cancellable: Boolean = true,
        task: (indicator: ProgressIndicator) -> T,
        onSuccess: (T) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val timeoutMs = NymSettings.getInstance().requestTimeoutMs
        val start = System.currentTimeMillis()
        val backgroundTask = object : Task.Backgroundable(project, title, cancellable) {
            private var result: T? = null
            private var error: Throwable? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.isIndeterminate = true
                    indicator.text = title
                    // 执行主逻辑：调用方可在内部更新 indicator.text2 以提示细节
                    result = task(indicator)
                } catch (t: Throwable) {
                    error = t
                }
            }

            override fun onSuccess() {
                error?.let { onError(it) } ?: result?.let { onSuccess(it) }
            }

            override fun onCancel() {
                // 中文：用户主动取消
                NymLogger.info("AITaskManager", "AI task cancelled by user: $title")
            }

            override fun onFinished() {
                // 中文：软性超时提示（不强制终止线程）
                val cost = System.currentTimeMillis() - start
                if (cost > timeoutMs) {
                    NymLogger.warn("AITaskManager", "AI task exceeded timeout: cost=${cost}ms > ${timeoutMs}ms, title=$title")
                }
            }
        }
        ProgressManager.getInstance().run(backgroundTask)
    }
}
