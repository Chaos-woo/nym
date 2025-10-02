package com.cw2.nym.data.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * 插件设置持久化组件
 *
 * 设计原则：
 * - 默认值安全：即使未初始化也有合理默认值
 * - 敏感信息后续使用安全存储（本次不涉及 API Key）
 */
@Service(Service.Level.APP)
@State(name = "NymSettings", storages = [Storage("nym_settings.xml")])
class NymSettings : PersistentStateComponent<NymSettings> {

    // 功能开关（默认启用）
    var enableNaming: Boolean = true
    var enableComment: Boolean = true

    // 分析深度（简化为 1-3）
    var contextDepth: Int = 2

    // 缓存开关
    var cacheEnabled: Boolean = true

    // 自动触发（预留）
    var autoTrigger: Boolean = false

    // ===== AI 服务配置（第三阶段新增） =====
    // 说明：
    // - 为了最小变更，先使用明文字段存储；后续阶段将迁移到 IDE 安全存储（见安全与隐私章节）。
    // - provider 取值建议：OpenAI / Custom（与现有 provider 实现对应）。
    var aiProvider: String = "OpenAI"
    var apiEndpoint: String = ""
    var apiKey: String = ""
    // 自定义/兼容 OpenAI 的模型名称（Custom 模式下可编辑），例如：gpt-4o-mini、gpt-4、qwen2.5
    var model: String = "gpt-4o-mini"
    // 模型温度（0-100，对应 0.0-1.0），便于 UI slider 绑定
    var modelTemperature: Int = 70

    // ===== 高级性能配置（第三阶段：2.2-12） =====
    // 为什么要引入这些字段：
    // - 统一在 PersistentState 中保存可调性能参数，便于 UI 绑定与后续行为调整（并发/超时/缓存/内存阈值）
    // - 采用保守的默认值，避免给 IDE 带来额外压力
    // - 数值单位采用毫秒/条目数/MB，UI 中进行约束与提示
    var requestTimeoutMs: Int = 60000 // 单请求超时，默认 60s（AI服务响应可能较慢）
    var maxConcurrentRequests: Int = 6 // 全局最大并发请求数，默认 6（兼顾响应与稳定）
    var cacheMaxEntries: Int = 500 // 缓存最大条目数（具体策略由缓存层决定）
    var memoryUsageThresholdMb: Int = 200 // 内存使用阈值，用于后续自适应降级

    // ===== 偏好设置（第三阶段：2.2-13） =====
    // 说明（中文）：
    // - languagePreference: 生成内容语言偏好（AUTO/EN/ZH），用于控制 AI 生成注释/说明等文本的语言。
    // - uiLanguagePreference: 界面文案语言（AUTO/EN/ZH），用于控制 Nym 的 UI 文案语言；AUTO 根据 IDE 环境，否则回退 EN。
    // - namingStyle: 命名风格（CAMEL_CASE/SNAKE_CASE），作为生成与校验的偏好输入。
    // - commentFormat: 注释格式（LINE/JAVADOC/JSDOC），用于控制模板输出样式。
    var languagePreference: String = "AUTO"
    var uiLanguagePreference: String = "AUTO"
    var namingStyle: String = "CAMEL_CASE"
    var commentFormat: String = "JAVADOC"

    // 设置结构版本号（用于未来迁移判断）
    var settingsVersion: Int = 3

    override fun getState(): NymSettings = this

    override fun loadState(state: NymSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        /**
         * 获取单例实例
         */
        @JvmStatic
        fun getInstance(): NymSettings = com.intellij.openapi.application.ApplicationManager
            .getApplication()
            .getService(NymSettings::class.java)
    }
}
