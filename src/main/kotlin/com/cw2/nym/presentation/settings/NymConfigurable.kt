package com.cw2.nym.presentation.settings

import com.cw2.nym.data.settings.NymSettings
import com.cw2.nym.data.settings.NymSecureStorage
import com.cw2.nym.presentation.messages.NymBundle
import com.cw2.nym.presentation.notifications.NymNotifier
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.JBColor
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JSlider
import javax.swing.JTextField
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.SwingConstants

/**
 * 设置页面（最简实现）
 *
 * 说明：
 * - 遵循即时校验与数据绑定的基本模式
 * - 并未引入复杂的表单框架，控制在最小变更
 * - 本次补充 AI 服务配置区（提供商/端点/API Key/温度）与“连接测试”按钮
 */
class NymConfigurable : Configurable {

    private val panel = JPanel(BorderLayout())
    private val form = JPanel(GridBagLayout())

    // 功能开关区域
    private val enableNaming = JCheckBox(NymBundle.message("settings.enable.naming"))
    private val enableComment = JCheckBox(NymBundle.message("settings.enable.comment"))
    private val cacheEnabled = JCheckBox(NymBundle.message("settings.cache.enabled"))
    private val autoTrigger = JCheckBox(NymBundle.message("settings.auto.trigger"))
    private val depthLabel = JLabel(NymBundle.message("settings.context.depth"))
    private val depthSlider = JSlider(1, 3, 2)

    // AI 服务配置区域（第三阶段新增）
    private val providerLabel = JLabel(NymBundle.message("settings.ai.provider"))
    private val providerCombo = JComboBox(arrayOf("OpenAI", "Custom"))
    private val endpointLabel = JLabel(NymBundle.message("settings.ai.endpoint"))
    private val endpointField = JTextField(24)
    private val endpointHelpLabel = JLabel(NymBundle.message("settings.ai.endpoint.hint"))
    private val modelLabel = JLabel(NymBundle.message("settings.ai.model"))
    private val modelField = JTextField(24)
    private val apiKeyLabel = JLabel(NymBundle.message("settings.ai.apikey"))
    private val apiKeyField = JPasswordField(24)
    // 显示/隐藏密钥切换按钮（避免在 EDT 泄露真实值，仅切换回显）
    private val toggleSecretButton = JButton(NymBundle.message("settings.ai.apikey.show"))
    // 清除已保存密钥按钮
    private val clearSecretButton = JButton(NymBundle.message("settings.ai.apikey.clear"))
    private val tempLabel = JLabel(NymBundle.message("settings.ai.temperature"))
    private val tempSlider = JSlider(0, 100, 70)
    private val testButton = JButton(NymBundle.message("settings.ai.test"))
    private val testResultLabel = JLabel("")

    // 性能优化区域（第三阶段：2.2-12）
    // 说明（中文）：
    // - 使用 JSpinner + SpinnerNumberModel 提供有界数值输入，避免非法值；步进值体现使用建议
    // - 仅做基本 UI 绑定，不在此处引入实际的限流/超时联动，避免超出本次改动范围
    private val perfSectionLabel = JLabel(NymBundle.message("settings.perf.section"))
    private val timeoutLabel = JLabel(NymBundle.message("settings.perf.timeout"))
    private val timeoutSpinner = JSpinner(SpinnerNumberModel(30000, 1000, 120000, 1000))
    private val concurrentLabel = JLabel(NymBundle.message("settings.perf.maxConcurrent"))
    private val concurrentSpinner = JSpinner(SpinnerNumberModel(6, 1, 32, 1))
    private val cacheMaxLabel = JLabel(NymBundle.message("settings.perf.cacheMaxEntries"))
    private val cacheMaxSpinner = JSpinner(SpinnerNumberModel(500, 0, 100000, 50))
    private val memoryThresholdLabel = JLabel(NymBundle.message("settings.perf.memoryThreshold"))
    private val memoryThresholdSpinner = JSpinner(SpinnerNumberModel(200, 100, 1024, 50))

    // 偏好设置区域（第三阶段：2.2-13）
    // 说明（中文）：
    // - 简单的下拉选择，便于与生成策略解耦；后续可以在 Provider 端读取使用。
    private val prefSectionLabel = JLabel(NymBundle.message("settings.pref.section"))
    private val langPrefLabel = JLabel(NymBundle.message("settings.pref.language"))
    private val langPrefCombo = JComboBox(arrayOf("AUTO", "EN", "ZH"))
    private val uiLangLabel = JLabel(NymBundle.message("settings.pref.uiLanguage"))
    private val uiLangCombo = JComboBox(arrayOf("AUTO", "EN", "ZH"))
    private val namingStyleLabel = JLabel(NymBundle.message("settings.pref.namingStyle"))
    private val namingStyleCombo = JComboBox(arrayOf("CAMEL_CASE", "SNAKE_CASE"))
    private val commentFormatLabel = JLabel(NymBundle.message("settings.pref.commentFormat"))
    private val commentFormatCombo = JComboBox(arrayOf("LINE", "JAVADOC", "JSDOC"))

    private val settings: NymSettings = NymSettings.getInstance()

    // 密钥显示状态（仅影响回显，不改变真实存储）
    private var secretVisible: Boolean = false
    private var defaultEchoChar: Char = '\u2022'
    
    // 缓存的 API Key 值，避免在 isModified() 和 reset() 中重复调用安全存储（EDT 违规）
    private var cachedApiKey: String = ""

    init {
        // 表单布局（左标签右控件），保持最小可用
        val c = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.WEST
        }
        // 功能开关
        form.add(enableNaming, c)
        c.gridy++
        form.add(enableComment, c)
        c.gridy++
        form.add(cacheEnabled, c)
        c.gridy++
        form.add(autoTrigger, c)
        c.gridy++
        form.add(depthLabel, c)
        c.gridx = 1
        form.add(depthSlider, c)

        // 分隔：回到首列，添加 AI 配置
        c.gridx = 0
        c.gridy++
        form.add(providerLabel, c)
        c.gridx = 1
        form.add(providerCombo, c)

        c.gridx = 0
        c.gridy++
        form.add(endpointLabel, c)
        c.gridx = 1
        form.add(endpointField, c)
        c.gridx = 2
        form.add(endpointHelpLabel, c)

        // 模型名称（仅 Custom 模式启用）
        c.gridx = 0
        c.gridy++
        form.add(modelLabel, c)
        c.gridx = 1
        form.add(modelField, c)

        c.gridx = 0
        c.gridy++
        form.add(apiKeyLabel, c)
        c.gridx = 1
        form.add(apiKeyField, c)
        // 在同一行右侧附加显示/隐藏与清除按钮，尽量不破坏现有布局
        c.gridx = 2
        form.add(toggleSecretButton, c)
        c.gridx = 3
        form.add(clearSecretButton, c)

        c.gridx = 0
        c.gridy++
        form.add(tempLabel, c)
        c.gridx = 1
        form.add(tempSlider, c)

        // 测试按钮占满一行
        c.gridx = 0
        c.gridy++
        form.add(testButton, c)
        
        // 测试结果标签
        c.gridy++
        form.add(testResultLabel, c)

        // ===== 分隔线：性能优化设置区域 =====
        c.gridx = 0
        c.gridy++
        form.add(perfSectionLabel, c)

        // 请求超时（毫秒）
        c.gridy++
        form.add(timeoutLabel, c)
        c.gridx = 1
        form.add(timeoutSpinner, c)

        // 最大并发请求数
        c.gridx = 0
        c.gridy++
        form.add(concurrentLabel, c)
        c.gridx = 1
        form.add(concurrentSpinner, c)

        // 缓存最大条目数
        c.gridx = 0
        c.gridy++
        form.add(cacheMaxLabel, c)
        c.gridx = 1
        form.add(cacheMaxSpinner, c)

        // 内存使用阈值（MB）
        c.gridx = 0
        c.gridy++
        form.add(memoryThresholdLabel, c)
        c.gridx = 1
        form.add(memoryThresholdSpinner, c)

        // ===== 分隔线：偏好设置区域 =====
        c.gridx = 0
        c.gridy++
        form.add(prefSectionLabel, c)

        // 语言偏好（生成内容）
        c.gridy++
        form.add(langPrefLabel, c)
        c.gridx = 1
        form.add(langPrefCombo, c)

        // 界面语言
        c.gridx = 0
        c.gridy++
        form.add(uiLangLabel, c)
        c.gridx = 1
        form.add(uiLangCombo, c)

        // 命名风格
        c.gridx = 0
        c.gridy++
        form.add(namingStyleLabel, c)
        c.gridx = 1
        form.add(namingStyleCombo, c)

        // 注释格式
        c.gridx = 0
        c.gridy++
        form.add(commentFormatLabel, c)
        c.gridx = 1
        form.add(commentFormatCombo, c)

        // 记录默认的回显字符，用于显示/隐藏切换
        defaultEchoChar = apiKeyField.echoChar

        // 显示/隐藏 API Key 回显（中文说明：仅改变 JPasswordField 回显，不改变存储安全性）
        toggleSecretButton.addActionListener {
            secretVisible = !secretVisible
            apiKeyField.echoChar = if (secretVisible) 0.toChar() else defaultEchoChar
            toggleSecretButton.text = if (secretVisible) NymBundle.message("settings.ai.apikey.hide") else NymBundle.message("settings.ai.apikey.show")
        }

        // 清除已保存的 API Key（安全存储与内存字段同时清理）
        clearSecretButton.addActionListener {
            apiKeyField.text = ""
            NymSecureStorage.clearApiKey()
            // 同时清空旧的明文字段，便于迁移
            settings.apiKey = ""
            NymNotifier.info(NymBundle.message("notification.success"))
        }

        // Provider 切换时更新可编辑状态
        providerCombo.addActionListener {
            val isCustom = providerCombo.selectedItem?.toString() == "Custom"
            modelLabel.isEnabled = isCustom
            modelField.isEnabled = isCustom
            endpointHelpLabel.isVisible = isCustom
        }

        // 测试连接：在后台线程执行实际网络请求，避免阻塞 EDT
        testButton.addActionListener {
            testButton.isEnabled = false
            testResultLabel.text = NymBundle.message("settings.ai.test.connecting")
            testResultLabel.foreground = JBColor.CYAN
            val provider = providerCombo.selectedItem?.toString()?.trim().orEmpty()
            val endpoint = endpointField.text.trim()
            val inlineKey = String(apiKeyField.password).trim()
            val model = modelField.text.trim().ifEmpty { settings.model }
            // 在EDT读取温度，避免后台线程访问Swing组件
            val temperature = tempSlider.value / 100.0

            ApplicationManager.getApplication().executeOnPooledThread {
                // 在后台线程中获取存储的 API Key，避免 EDT 违规
                val storedKey = NymSecureStorage.getApiKey()
                val anyKey = inlineKey.ifBlank { storedKey }
                var errorMessage: String? = null
                val success = try {
                    if (provider == "OpenAI") {
                        val cfg = com.cw2.nym.ai.provider.openai.OpenAIConfig(
                            apiUrl = endpoint.ifBlank { "https://api.openai.com/v1" },
                            apiKey = anyKey,
                            model = model.ifBlank { "gpt-4o-mini" },
                            temperature = temperature,
                            timeoutMs = settings.requestTimeoutMs.toLong()
                        )
                        val client = com.cw2.nym.ai.provider.openai.OpenAIHttpClient(cfg)
                        val req = com.cw2.nym.ai.provider.openai.OpenAIRequest(
                            model = cfg.model,
                            messages = listOf(com.cw2.nym.ai.provider.openai.OpenAIMessage("user", "ping")),
                            maxTokens = 1
                        )
                        val result = client.sendRequestSync(req)
                        if (!result.isSuccess) {
                            errorMessage = result.errorOrNull()?.message ?: "未知错误"
                        }
                        result.isSuccess
                    } else {
                        val cfg = com.cw2.nym.ai.provider.custom.CustomAPIConfig(
                            providerName = "Custom",
                            apiUrl = endpoint,
                            apiKey = anyKey,
                            model = model.ifBlank { "gpt-4o-mini" },
                            temperature = temperature,
                            timeoutMs = settings.requestTimeoutMs.toLong()
                        )
                        val client = com.cw2.nym.ai.provider.custom.CustomAPIHttpClient(cfg)
                        val req = com.cw2.nym.ai.provider.openai.OpenAIRequest(
                            model = cfg.model,
                            messages = listOf(com.cw2.nym.ai.provider.openai.OpenAIMessage("user", "ping")),
                            maxTokens = 10
                        )
                        val result = client.sendRequestSync(req)
                        if (!result.isSuccess) {
                            errorMessage = result.errorOrNull()?.message ?: "未知错误"
                        }
                        result.isSuccess
                    }
                } catch (t: Throwable) {
                    errorMessage = t.message ?: "网络连接异常"
                    false
                }

                ApplicationManager.getApplication().invokeLater({
                    if (success) {
                        testResultLabel.text = NymBundle.message("settings.ai.test.success")
                        testResultLabel.foreground = java.awt.Color.GREEN.darker()
                    } else {
                        val failMessage = if (errorMessage != null) {
                            "${NymBundle.message("settings.ai.test.fail")}: $errorMessage"
                        } else {
                            NymBundle.message("settings.ai.test.fail")
                        }
                        testResultLabel.text = failMessage
                        testResultLabel.foreground = JBColor.RED
                    }
                    testButton.isEnabled = true
                }, ModalityState.any())
            }
        }

        // 初始化可见性状态
        run {
            val isCustom = providerCombo.selectedItem?.toString() == "Custom"
            modelLabel.isEnabled = isCustom
            modelField.isEnabled = isCustom
            endpointHelpLabel.isVisible = isCustom
        }

        panel.add(form, BorderLayout.NORTH)
        
        // 在后台线程初始化缓存的 API Key，避免 EDT 违规
        ApplicationManager.getApplication().executeOnPooledThread {
            cachedApiKey = NymSecureStorage.getApiKey()
        }
    }

    override fun getDisplayName(): String = NymBundle.message("settings.title")

    override fun createComponent(): JComponent = panel

    override fun isModified(): Boolean {
        return enableNaming.isSelected != settings.enableNaming ||
            enableComment.isSelected != settings.enableComment ||
            cacheEnabled.isSelected != settings.cacheEnabled ||
            autoTrigger.isSelected != settings.autoTrigger ||
            depthSlider.value != settings.contextDepth ||
            providerCombo.selectedItem?.toString() != settings.aiProvider ||
            endpointField.text != settings.apiEndpoint ||
            modelField.text != settings.model ||
            // 比对输入框与缓存的安全存储值，避免泄露明文到配置和 EDT 违规
            String(apiKeyField.password) != cachedApiKey ||
            tempSlider.value != settings.modelTemperature ||
            (timeoutSpinner.value as Number).toInt() != settings.requestTimeoutMs ||
            (concurrentSpinner.value as Number).toInt() != settings.maxConcurrentRequests ||
            (cacheMaxSpinner.value as Number).toInt() != settings.cacheMaxEntries ||
            (memoryThresholdSpinner.value as Number).toInt() != settings.memoryUsageThresholdMb ||
            langPrefCombo.selectedItem?.toString() != settings.languagePreference ||
            uiLangCombo.selectedItem?.toString() != settings.uiLanguagePreference ||
            namingStyleCombo.selectedItem?.toString() != settings.namingStyle ||
            commentFormatCombo.selectedItem?.toString() != settings.commentFormat
    }

    override fun apply() {
        // 保存到持久化组件（中文说明：后续将迁移敏感字段到安全存储）
        settings.enableNaming = enableNaming.isSelected
        settings.enableComment = enableComment.isSelected
        settings.cacheEnabled = cacheEnabled.isSelected
        settings.autoTrigger = autoTrigger.isSelected
        settings.contextDepth = depthSlider.value

        settings.aiProvider = providerCombo.selectedItem?.toString() ?: settings.aiProvider
        settings.apiEndpoint = endpointField.text.trim()
        settings.model = modelField.text.trim()
        // 将密钥写入 IDE 安全存储，避免明文持久化 - 异步操作避免阻塞EDT
        val newKey = String(apiKeyField.password).trim()
        ApplicationManager.getApplication().executeOnPooledThread {
            NymSecureStorage.setApiKey(newKey)
        }
        // 同时更新缓存值，保持一致性
        cachedApiKey = newKey
        // 清空旧的明文字段，保留向后兼容字段但不再写入
        settings.apiKey = ""
        settings.modelTemperature = tempSlider.value

        // 高级性能设置（仅保存数值，实际联动由相关组件读取使用）
        settings.requestTimeoutMs = (timeoutSpinner.value as Number).toInt()
        settings.maxConcurrentRequests = (concurrentSpinner.value as Number).toInt()
        settings.cacheMaxEntries = (cacheMaxSpinner.value as Number).toInt()
        settings.memoryUsageThresholdMb = (memoryThresholdSpinner.value as Number).toInt()

        // 偏好设置
        settings.languagePreference = langPrefCombo.selectedItem?.toString() ?: settings.languagePreference
        settings.uiLanguagePreference = uiLangCombo.selectedItem?.toString() ?: settings.uiLanguagePreference
        settings.namingStyle = namingStyleCombo.selectedItem?.toString() ?: settings.namingStyle
        settings.commentFormat = commentFormatCombo.selectedItem?.toString() ?: settings.commentFormat
    }

    override fun reset() {
        // 从持久化组件恢复
        enableNaming.isSelected = settings.enableNaming
        enableComment.isSelected = settings.enableComment
        cacheEnabled.isSelected = settings.cacheEnabled
        autoTrigger.isSelected = settings.autoTrigger
        depthSlider.value = settings.contextDepth

        providerCombo.selectedItem = settings.aiProvider
        endpointField.text = settings.apiEndpoint
        modelField.text = settings.model
        // 优先从缓存加载密钥到输入框（仅供编辑，不代表持久化），避免 EDT 违规
        apiKeyField.text = cachedApiKey
        // 重置显示状态为隐藏
        secretVisible = false
        toggleSecretButton.text = NymBundle.message("settings.ai.apikey.show")
        apiKeyField.echoChar = defaultEchoChar
        tempSlider.value = settings.modelTemperature

        // 高级性能设置
        timeoutSpinner.value = settings.requestTimeoutMs
        concurrentSpinner.value = settings.maxConcurrentRequests
        cacheMaxSpinner.value = settings.cacheMaxEntries
        memoryThresholdSpinner.value = settings.memoryUsageThresholdMb

        // 偏好设置
        langPrefCombo.selectedItem = settings.languagePreference
        uiLangCombo.selectedItem = settings.uiLanguagePreference
        namingStyleCombo.selectedItem = settings.namingStyle
        commentFormatCombo.selectedItem = settings.commentFormat
    }
}
