package com.cw2.nym.core.exception

/**
 * Nym 插件异常定义体系 - Exception definition system for Nym plugin
 * 
 * 使用密封类层次结构来定义所有可能的错误类型，提供类型安全的错误处理
 * Uses sealed class hierarchy to define all possible error types, providing type-safe error handling
 */
sealed class NymError(
    open val message: String,
    open val cause: Throwable? = null
) {
    
    /**
     * 网络相关错误 - Network related errors
     */
    sealed class NetworkError(
        override val message: String,
        override val cause: Throwable? = null
    ) : NymError(message, cause) {
        
        /**
         * 连接超时错误 - Connection timeout error
         */
        data class ConnectionTimeout(
            override val message: String = "网络连接超时",
            override val cause: Throwable? = null
        ) : NetworkError(message, cause)
        
        /**
         * 读取超时错误 - Read timeout error
         */
        data class ReadTimeout(
            override val message: String = "网络读取超时",
            override val cause: Throwable? = null
        ) : NetworkError(message, cause)
        
        /**
         * 网络不可达错误 - Network unreachable error
         */
        data class NetworkUnreachable(
            override val message: String = "网络不可达",
            override val cause: Throwable? = null
        ) : NetworkError(message, cause)
        
        /**
         * 通用网络错误 - Generic network error
         */
        data class Generic(
            override val message: String = "网络请求失败",
            override val cause: Throwable? = null
        ) : NetworkError(message, cause)
    }
    
    /**
     * 认证相关错误 - Authentication related errors
     */
    sealed class AuthenticationError(
        override val message: String,
        override val cause: Throwable? = null
    ) : NymError(message, cause) {
        
        /**
         * API 密钥无效 - Invalid API key
         */
        data class InvalidApiKey(
            override val message: String = "API 密钥无效或已过期",
            override val cause: Throwable? = null
        ) : AuthenticationError(message, cause)
        
        /**
         * API 密钥未配置 - API key not configured
         */
        data class ApiKeyNotConfigured(
            override val message: String = "API 密钥未配置",
            override val cause: Throwable? = null
        ) : AuthenticationError(message, cause)
        
        /**
         * 权限不足 - Insufficient permissions
         */
        data class InsufficientPermissions(
            override val message: String = "API 权限不足",
            override val cause: Throwable? = null
        ) : AuthenticationError(message, cause)
    }
    
    /**
     * 限流相关错误 - Rate limiting related errors
     */
    sealed class RateLimitError(
        override val message: String,
        override val cause: Throwable? = null,
        open val retryAfter: Long? = null // 建议重试间隔，单位：毫秒
    ) : NymError(message, cause) {
        
        /**
         * 请求频率过高 - Request rate too high
         */
        data class TooManyRequests(
            override val message: String = "请求频率过高，请稍后重试",
            override val cause: Throwable? = null,
            override val retryAfter: Long? = null
        ) : RateLimitError(message, cause, retryAfter)
        
        /**
         * 配额已耗尽 - Quota exhausted
         */
        data class QuotaExhausted(
            override val message: String = "API 配额已耗尽",
            override val cause: Throwable? = null,
            override val retryAfter: Long? = null
        ) : RateLimitError(message, cause, retryAfter)
    }
    
    /**
     * API 相关错误 - API related errors
     */
    sealed class APIError(
        override val message: String,
        override val cause: Throwable? = null,
        open val httpCode: Int? = null
    ) : NymError(message, cause) {
        
        /**
         * 服务器内部错误 - Server internal error
         */
        data class ServerError(
            override val message: String = "服务器内部错误",
            override val cause: Throwable? = null,
            override val httpCode: Int = 500
        ) : APIError(message, cause, httpCode)
        
        /**
         * 请求格式错误 - Bad request format
         */
        data class BadRequest(
            override val message: String = "请求格式错误",
            override val cause: Throwable? = null,
            override val httpCode: Int = 400
        ) : APIError(message, cause, httpCode)
        
        /**
         * 服务不可用 - Service unavailable
         */
        data class ServiceUnavailable(
            override val message: String = "AI 服务暂时不可用",
            override val cause: Throwable? = null,
            override val httpCode: Int = 503
        ) : APIError(message, cause, httpCode)
        
        /**
         * 模型不支持 - Model not supported
         */
        data class ModelNotSupported(
            override val message: String = "指定的 AI 模型不支持",
            override val cause: Throwable? = null,
            override val httpCode: Int? = null
        ) : APIError(message, cause, httpCode)
    }
    
    /**
     * 解析相关错误 - Parsing related errors
     */
    sealed class ParseError(
        override val message: String,
        override val cause: Throwable? = null
    ) : NymError(message, cause) {
        
        /**
         * JSON 解析错误 - JSON parsing error
         */
        data class JsonParse(
            override val message: String = "JSON 数据解析失败",
            override val cause: Throwable? = null
        ) : ParseError(message, cause)
        
        /**
         * 响应格式错误 - Response format error
         */
        data class InvalidResponse(
            override val message: String = "AI 服务响应格式不正确",
            override val cause: Throwable? = null
        ) : ParseError(message, cause)
        
        /**
         * 配置格式错误 - Configuration format error
         */
        data class InvalidConfiguration(
            override val message: String = "配置格式不正确",
            override val cause: Throwable? = null
        ) : ParseError(message, cause)
    }
    
    /**
     * 平台相关错误 - Platform related errors
     */
    sealed class PlatformError(
        override val message: String,
        override val cause: Throwable? = null
    ) : NymError(message, cause) {
        
        /**
         * IDE 索引不可用 - IDE index unavailable
         */
        data class IndexNotReady(
            override val message: String = "IDE 索引尚未准备就绪",
            override val cause: Throwable? = null
        ) : PlatformError(message, cause)
        
        /**
         * 项目未打开 - Project not open
         */
        data class ProjectNotOpen(
            override val message: String = "项目未打开或已关闭",
            override val cause: Throwable? = null
        ) : PlatformError(message, cause)
        
        /**
         * 编辑器不可用 - Editor unavailable
         */
        data class EditorUnavailable(
            override val message: String = "编辑器不可用",
            override val cause: Throwable? = null
        ) : PlatformError(message, cause)
    }
    
    /**
     * 超时相关错误 - Timeout related errors
     */
    sealed class TimeoutError(
        override val message: String,
        override val cause: Throwable? = null,
        open val timeoutMs: Long? = null,
        open val actualDurationMs: Long? = null
    ) : NymError(message, cause) {
        
        /**
         * 操作超时 - Operation timeout
         */
        data class OperationTimeout(
            override val message: String = "操作执行超时",
            override val cause: Throwable? = null,
            override val timeoutMs: Long? = null,
            override val actualDurationMs: Long? = null
        ) : TimeoutError(message, cause, timeoutMs, actualDurationMs)
        
        /**
         * 连接建立超时 - Connection establishment timeout
         */
        data class ConnectionEstablishmentTimeout(
            override val message: String = "连接建立超时",
            override val cause: Throwable? = null,
            override val timeoutMs: Long? = null,
            override val actualDurationMs: Long? = null
        ) : TimeoutError(message, cause, timeoutMs, actualDurationMs)
        
        /**
         * 数据读取超时 - Data read timeout
         */
        data class DataReadTimeout(
            override val message: String = "数据读取超时",
            override val cause: Throwable? = null,
            override val timeoutMs: Long? = null,
            override val actualDurationMs: Long? = null
        ) : TimeoutError(message, cause, timeoutMs, actualDurationMs)
        
        /**
         * 整体请求超时 - Overall request timeout
         */
        data class RequestTimeout(
            override val message: String = "请求整体超时",
            override val cause: Throwable? = null,
            override val timeoutMs: Long? = null,
            override val actualDurationMs: Long? = null
        ) : TimeoutError(message, cause, timeoutMs, actualDurationMs)
    }
    
    /**
     * 操作取消错误 - Operation cancelled error
     */
    data class OperationCancelled(
        override val message: String = "操作已取消",
        override val cause: Throwable? = null
    ) : NymError(message, cause)
    
    /**
     * 未知错误 - Unknown error
     */
    data class Unknown(
        override val message: String = "未知错误",
        override val cause: Throwable? = null
    ) : NymError(message, cause)
    
    /**
     * 获取友好的用户显示消息 - Get user-friendly display message
     */
    fun getUserMessage(): String = when (this) {
        is NetworkError.ConnectionTimeout -> "网络连接超时，请检查网络连接后重试"
        is NetworkError.ReadTimeout -> "服务响应超时，请稍后重试"
        is NetworkError.NetworkUnreachable -> "无法连接到 AI 服务，请检查网络设置"
        is AuthenticationError.InvalidApiKey -> "API 密钥无效，请检查密钥配置"
        is AuthenticationError.ApiKeyNotConfigured -> "请先在设置中配置 API 密钥"
        is RateLimitError.TooManyRequests -> "请求过于频繁，请稍后重试"
        is RateLimitError.QuotaExhausted -> "API 配额已用完，请检查账户余额"
        is APIError.ServerError -> "AI 服务出现问题，请稍后重试"
        is APIError.ServiceUnavailable -> "AI 服务暂时不可用，请稍后重试"
        is PlatformError.IndexNotReady -> "IDE 正在建立索引，请稍等片刻"
        is TimeoutError.OperationTimeout -> "操作执行超时，请稍后重试"
        is TimeoutError.ConnectionEstablishmentTimeout -> "连接建立超时，请检查网络连接"
        is TimeoutError.DataReadTimeout -> "数据读取超时，请稍后重试"
        is TimeoutError.RequestTimeout -> "请求处理超时，请稍后重试"
        is OperationCancelled -> "操作已取消"
        else -> message
    }
    
    /**
     * 获取英文错误消息，避免日志乱码 - Get English error message to avoid log encoding issues
     */
    fun getEnglishMessage(): String = when (this) {
        is NetworkError.ConnectionTimeout -> "Network connection timeout"
        is NetworkError.ReadTimeout -> "Service response timeout"
        is NetworkError.NetworkUnreachable -> "Unable to connect to AI service"
        is NetworkError.Generic -> "Network request failed"
        is AuthenticationError.InvalidApiKey -> "Invalid API key"
        is AuthenticationError.ApiKeyNotConfigured -> "API key not configured"
        is AuthenticationError.InsufficientPermissions -> "Insufficient API permissions"
        is RateLimitError.TooManyRequests -> "Too many requests, please retry later"
        is RateLimitError.QuotaExhausted -> "API quota exhausted"
        is APIError.ServerError -> "AI service internal error"
        is APIError.BadRequest -> "Invalid request format"
        is APIError.ServiceUnavailable -> "AI service temporarily unavailable"
        is APIError.ModelNotSupported -> "AI model not supported"
        is ParseError.JsonParse -> "JSON parsing failed"
        is ParseError.InvalidResponse -> "Invalid AI service response"
        is ParseError.InvalidConfiguration -> "Invalid configuration"
        is PlatformError.IndexNotReady -> "IDE index not ready"
        is PlatformError.ProjectNotOpen -> "Project not open"
        is PlatformError.EditorUnavailable -> "Editor unavailable"
        is TimeoutError.OperationTimeout -> "Operation timeout"
        is TimeoutError.ConnectionEstablishmentTimeout -> "Connection establishment timeout"
        is TimeoutError.DataReadTimeout -> "Data read timeout"
        is TimeoutError.RequestTimeout -> "Request timeout"
        is OperationCancelled -> "Operation cancelled"
        is Unknown -> "Unknown error"
        else -> message
    }
}