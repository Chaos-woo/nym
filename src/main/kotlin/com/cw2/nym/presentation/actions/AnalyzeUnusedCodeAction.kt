package com.cw2.nym.presentation.actions

import com.cw2.nym.core.logging.NymLogger
import com.cw2.nym.core.result.Result
import com.cw2.nym.integrations.psi.UnusedCodeScanner
import com.cw2.nym.presentation.notifications.NymNotifier
import com.cw2.nym.presentation.messages.NymBundle
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project

/**
 * 扫描未使用的文件/类/方法/属性，并生成报告。
 *
 * 设计说明（为什么）：
 * - 用户需要快速识别未使用的代码以便清理；该动作在后台完成扫描，避免阻塞 UI。
 * - 报告写入 build/nym-unused-report.txt，方便查看与版本控制外排除。
 */
internal class AnalyzeUnusedCodeAction : AnAction(NymBundle.message("action.analyzeUnused.text")), DumbAware {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        NymLogger.info("UNUSED_SCAN", "start")
        UnusedCodeScanner.scanInBackground(project) { res ->
            when (res) {
                is Result.Success -> {
                    val report = res.data
                    val out = UnusedCodeScanner.writeReportToFile(project, report)
                    val outPath = out?.absolutePath ?: NymBundle.message("action.analyzeUnused.notWritten")
                    val msg = NymBundle.message(
                        "action.analyzeUnused.success",
                        report.unused.size,
                        report.scannedFiles,
                        report.scannedSymbols,
                        outPath
                    )
                    NymNotifier.info(msg)
                    NymLogger.info("UNUSED_SCAN", "done", mapOf(
                        "unused" to report.unused.size,
                        "files" to report.scannedFiles,
                        "symbols" to report.scannedSymbols,
                        "out" to (out?.absolutePath ?: "")
                    ))
                }
                is Result.Error -> {
                    val errMsg = res.error.message ?: NymBundle.message("common.unknownError")
                    NymNotifier.error(NymBundle.message("action.analyzeUnused.failed", errMsg))
                    NymLogger.logError("UNUSED_SCAN", res.error)
                }
            }
        }
    }
}
