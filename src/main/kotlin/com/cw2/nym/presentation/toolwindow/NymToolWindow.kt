package com.cw2.nym.presentation.toolwindow

import com.cw2.nym.core.metrics.MetricsCollector
import com.cw2.nym.presentation.messages.NymBundle
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Nym 工具窗口主面板（小幅扩展：加入使用统计）
 *
 * 设计说明（中文）：
 * - 在原有欢迎文本基础上，增加一个“使用统计”区块，用简单文本展示核心数据。
 * - 保持实现轻量，不引入图表与复杂刷新机制；提供手动“刷新”按钮满足最小可用。
 * - 统计数据来源于 MetricsCollector，后续可在各 Action/Provider 调用处补充 record 调用。
 */
class NymToolWindow {
    fun getComponent(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(12)

        // 顶部欢迎文本
        panel.add(JBLabel(NymBundle.message("toolwindow.welcome")), BorderLayout.NORTH)

        // 中部：使用统计（今日/总计/成功率/平均耗时）
        val center = JPanel()
        center.layout = java.awt.GridLayout(0, 1, 0, 6)
        val section = JBLabel(NymBundle.message("toolwindow.stats.section"))
        val todayLabel = JBLabel()
        val totalLabel = JBLabel()
        val successLabel = JBLabel()
        val latencyLabel = JBLabel()
        // Token 使用统计
        val tokenSection = JBLabel(NymBundle.message("toolwindow.stats.tokens.section"))
        val tokensTodayLabel = JBLabel()
        val tokensWeekLabel = JBLabel()
        val tokensMonthLabel = JBLabel()
        val tokensTotalLabel = JBLabel()
        val refresh = JButton(NymBundle.message("toolwindow.stats.refresh"))

        fun updateStats() {
            val s = MetricsCollector.snapshot()
            todayLabel.text = NymBundle.message("toolwindow.stats.today") + ": " + s.today
            totalLabel.text = NymBundle.message("toolwindow.stats.total") + ": " + s.total
            val ratePercent = String.format("%.0f%%", s.successRate * 100.0)
            successLabel.text = NymBundle.message("toolwindow.stats.successRate") + ": " + ratePercent
            latencyLabel.text = NymBundle.message("toolwindow.stats.avgLatency") + ": " + s.averageLatencyMs

            tokensTodayLabel.text = NymBundle.message("toolwindow.stats.tokens.today") + ": " + s.tokensToday
            tokensWeekLabel.text = NymBundle.message("toolwindow.stats.tokens.week") + ": " + s.tokensWeek
            tokensMonthLabel.text = NymBundle.message("toolwindow.stats.tokens.month") + ": " + s.tokensMonth
            tokensTotalLabel.text = NymBundle.message("toolwindow.stats.tokens.total") + ": " + s.tokensTotal
        }

        refresh.addActionListener { updateStats() }

        center.add(section)
        center.add(todayLabel)
        center.add(totalLabel)
        center.add(successLabel)
        center.add(latencyLabel)
        // Token 区块
        center.add(tokenSection)
        center.add(tokensTodayLabel)
        center.add(tokensWeekLabel)
        center.add(tokensMonthLabel)
        center.add(tokensTotalLabel)
        center.add(refresh)

        panel.add(center, BorderLayout.CENTER)

        // 初始化一次
        updateStats()
        return panel
    }
}
