package com.cw2.nym.core.logging

import com.intellij.openapi.diagnostic.Logger
import com.cw2.nym.core.exception.NymError

/**
 * Nym 插件统一日志系统 - Unified logging system for Nym plugin
 * 
 * 基于 IntelliJ Logger 构建，提供结构化日志输出和敏感数据脱敏功能
 * Built on IntelliJ Logger, provides structured logging and sensitive data masking
 */
object NymLogger {
    
    private val logger: Logger = Logger.getInstance("Nym")
    
    // 敏感数据模式 - Sensitive data patterns
    private val sensitivePatterns = listOf(
        Regex("""(?i)(api[_-]?key|apikey|token|secret|password|pwd)\s*[:=]\s*["\']?([^"\'\s,}]+)["\']?"""),
        Regex("""sk-[a-zA-Z0-9]{20,}"""), // OpenAI API keys
        Regex("""Bearer\s+[a-zA-Z0-9_\-\.]+"""), // Bearer tokens
        Regex("""[a-zA-Z0-9+/]{32,}={0,2}""") // Base64 encoded strings (potentially sensitive)
    )
    
    /**
     * 脱敏处理敏感数据 - Mask sensitive data
     */
    private fun maskSensitiveData(message: String): String {
        var maskedMessage = message
        sensitivePatterns.forEach { pattern ->
            maskedMessage = maskedMessage.replace(pattern) { matchResult ->
                when (matchResult.groups.size) {
                    3 -> "${matchResult.groups[1]?.value}=${maskString(matchResult.groups[2]?.value ?: "")}"
                    2 -> maskString(matchResult.groups[1]?.value ?: "")
                    else -> maskString(matchResult.value)
                }
            }
        }
        return maskedMessage
    }
    
    /**
     * 遮蔽字符串 - Mask string
     */
    private fun maskString(value: String): String {
        return when {
            value.length <= 6 -> "*".repeat(value.length)
            value.length <= 12 -> "${value.take(2)}${"*".repeat(value.length - 4)}${value.takeLast(2)}"
            else -> "${value.take(4)}${"*".repeat(value.length - 8)}${value.takeLast(4)}"
        }
    }
    
    /**
     * 格式化结构化日志消息 - Format structured log message
     */
    private fun formatMessage(
        operation: String,
        message: String,
        context: Map<String, Any?> = emptyMap(),
        error: Throwable? = null
    ): String {
        val contextStr = if (context.isNotEmpty()) {
            context.entries.joinToString(", ", " [", "]") { (key, value) ->
                "$key=$value"
            }
        } else ""
        
        val errorStr = error?.let { " | Error: ${it.message}" } ?: ""
        
        return maskSensitiveData("[$operation] $message$contextStr$errorStr")
    }
    
    /**
     * 调试级别日志 - Debug level logging
     */
    fun debug(operation: String, message: String, context: Map<String, Any?> = emptyMap()) {
        if (logger.isDebugEnabled) {
            logger.debug(formatMessage(operation, message, context))
        }
    }
    
    /**
     * 信息级别日志 - Info level logging
     */
    fun info(operation: String, message: String, context: Map<String, Any?> = emptyMap()) {
        logger.info(formatMessage(operation, message, context))
    }
    
    /**
     * 警告级别日志 - Warning level logging
     */
    fun warn(operation: String, message: String, context: Map<String, Any?> = emptyMap(), error: Throwable? = null) {
        logger.warn(formatMessage(operation, message, context, error), error)
    }
    
    /**
     * 错误级别日志 - Error level logging
     */
    fun error(operation: String, message: String, context: Map<String, Any?> = emptyMap(), error: Throwable? = null) {
        logger.error(formatMessage(operation, message, context, error), error)
    }
    
    /**
     * 记录 NymError - Log NymError
     */
    fun logError(operation: String, nymError: NymError, context: Map<String, Any?> = emptyMap()) {
        val message = "Operation failed: ${nymError.getEnglishMessage()}"
        val errorContext = context + mapOf(
            "errorType" to nymError::class.simpleName,
            "errorMessage" to nymError.getEnglishMessage()
        )
        
        when (nymError) {
            is NymError.NetworkError,
            is NymError.APIError,
            is NymError.AuthenticationError,
            is NymError.RateLimitError -> {
                // 网络和 API 相关错误通常是外部因素，记录为警告
                warn(operation, message, errorContext, nymError.cause)
            }
            is NymError.ParseError,
            is NymError.PlatformError,
            is NymError.Unknown -> {
                // 解析、平台和未知错误可能是代码问题，记录为错误
                error(operation, message, errorContext, nymError.cause)
            }
            is NymError.OperationCancelled -> {
                // 操作取消是正常情况，记录为调试信息
                debug(operation, message, errorContext)
            }

            else -> {}
        }
    }
    
    /**
     * 记录性能指标 - Log performance metrics
     */
    fun logPerformance(operation: String, durationMs: Long, context: Map<String, Any?> = emptyMap()) {
        val message = "操作完成"
        val perfContext = context + mapOf(
            "duration" to "${durationMs}ms",
            "performance" to when {
                durationMs < 100 -> "快速"
                durationMs < 1000 -> "正常"
                durationMs < 5000 -> "较慢"
                else -> "缓慢"
            }
        )
        
        when {
            durationMs < 1000 -> debug(operation, message, perfContext)
            durationMs < 5000 -> info(operation, message, perfContext)
            else -> warn(operation, "操作耗时较长", perfContext)
        }
    }
    
    /**
     * 记录 AI 服务调用 - Log AI service calls
     */
    fun logAICall(
        provider: String,
        model: String,
        operation: String,
        success: Boolean,
        durationMs: Long,
        tokenCount: Int? = null,
        error: NymError? = null
    ) {
        val context = mutableMapOf<String, Any?>(
            "provider" to provider,
            "model" to model,
            "duration" to "${durationMs}ms",
            "success" to success
        )
        
        tokenCount?.let { context["tokens"] = it }
        
        if (success) {
            info("AI_CALL", "AI 服务调用成功", context)
            logPerformance("AI_CALL", durationMs, context)
            // 记录 Token 使用量到指标采集器（中文说明：便于工具窗口统计展示）
            tokenCount?.let {
                try {
                    com.cw2.nym.core.metrics.MetricsCollector.recordTokens(it)
                } catch (_: Throwable) {
                    // 指标统计失败不影响主流程
                }
            }
        } else {
            error?.let { logError("AI_CALL", it, context) }
        }
    }
    
    /**
     * 记录用户操作 - Log user operations
     */
    fun logUserAction(action: String, context: Map<String, Any?> = emptyMap()) {
        info("USER_ACTION", "用户执行操作: $action", context)
    }
    
    /**
     * 记录配置变更 - Log configuration changes
     */
    fun logConfigChange(setting: String, oldValue: String?, newValue: String?) {
        val context = mapOf(
            "setting" to setting,
            "oldValue" to (oldValue ?: "null"),
            "newValue" to (newValue ?: "null")
        )
        info("CONFIG_CHANGE", "配置项已更新", context)
    }
    
    /**
     * 记录缓存操作 - Log cache operations
     */
    fun logCacheOperation(operation: String, key: String, hit: Boolean? = null, size: Int? = null) {
        val context = mutableMapOf<String, Any?>(
            "key" to maskSensitiveData(key),
            "operation" to operation
        )
        
        hit?.let { context["hit"] = it }
        size?.let { context["size"] = it }
        
        debug("CACHE", "缓存操作", context)
    }
    
    /**
     * 批量日志记录器 - Batch logger for related operations
     */
    class BatchLogger(private val operation: String) {
        private val startTime = System.currentTimeMillis()
        private val context = mutableMapOf<String, Any?>()
        
        fun addContext(key: String, value: Any?): BatchLogger {
            context[key] = value
            return this
        }
        
        fun debug(message: String) {
            NymLogger.debug(operation, message, context)
        }
        
        fun info(message: String) {
            NymLogger.info(operation, message, context)
        }
        
        fun warn(message: String, error: Throwable? = null) {
            NymLogger.warn(operation, message, context, error)
        }
        
        fun error(message: String, error: Throwable? = null) {
            NymLogger.error(operation, message, context, error)
        }
        
        fun logError(nymError: NymError) {
            NymLogger.logError(operation, nymError, context)
        }
        
        fun finish(message: String = "操作完成") {
            val duration = System.currentTimeMillis() - startTime
            logPerformance(operation, duration, context)
            info(message)
        }
    }
    
    /**
     * 创建批量日志记录器 - Create batch logger
     */
    fun batch(operation: String): BatchLogger = BatchLogger(operation)
}

/**
 * 扩展函数：为任意类添加日志功能 - Extension function: add logging capability to any class
 */
inline fun <reified T> T.logger(): NymLogger = NymLogger

/**
 * 扩展函数：记录代码块执行时间 - Extension function: log code block execution time
 */
inline fun <T> logTime(operation: String, context: Map<String, Any?> = emptyMap(), block: () -> T): T {
    val startTime = System.currentTimeMillis()
    return try {
        val result = block()
        val duration = System.currentTimeMillis() - startTime
        NymLogger.logPerformance(operation, duration, context)
        result
    } catch (e: Exception) {
        val duration = System.currentTimeMillis() - startTime
        NymLogger.error(operation, "执行失败", context + mapOf("duration" to "${duration}ms"), e)
        throw e
    }
}