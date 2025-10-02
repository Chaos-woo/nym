package com.cw2.nym.ai.retry

import com.cw2.nym.core.result.Result
import com.cw2.nym.core.exception.NymError
import com.cw2.nym.core.logging.NymLogger
import kotlin.math.pow
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * 重试管理器
 * 
 * 实现了指数退避重试机制，支持自定义重试策略和条件判断。
 * 包含幂等性检查、最大重试次数限制和智能错误分类。
 */
class RetryManager(
    private val config: RetryConfig = RetryConfig()
) {
    
    /**
     * 执行带重试的操作
     * 
     * @param operation 要执行的操作
     * @param operationName 操作名称（用于日志）
     * @param isIdempotent 操作是否为幂等的
     * @param customRetryPolicy 自定义重试策略
     * @return 操作结果
     */
    suspend fun <T> executeWithRetry(
        operation: suspend () -> Result<T>,
        operationName: String,
        isIdempotent: Boolean = true,
        customRetryPolicy: RetryPolicy? = null
    ): Result<T> {
        val policy = customRetryPolicy ?: config.defaultPolicy
        var lastError: NymError? = null
        
        repeat(policy.maxRetries + 1) { attempt ->
            try {
                NymLogger.debug("executeWithRetry", 
                    "执行操作 '$operationName'，尝试次数: ${attempt + 1}/${policy.maxRetries + 1}")
                
                val result = operation()
                
                // 成功则直接返回
                if (result.errorOrNull() == null) {
                    if (attempt > 0) {
                        NymLogger.info("executeWithRetry", 
                            "操作 '$operationName' 在第 ${attempt + 1} 次尝试后成功")
                    }
                    return result
                }
                
                val error = result.errorOrNull()!!
                lastError = error
                
                // 检查是否应该重试
                if (!shouldRetry(error, attempt, policy, isIdempotent)) {
                    NymLogger.warn("executeWithRetry", 
                        "操作 '$operationName' 不满足重试条件，停止重试", 
                        context = mapOf("error" to error.message))
                    return result
                }
                
                // 如果还有重试机会，计算延迟时间
                if (attempt < policy.maxRetries) {
                    val delayMs = calculateDelay(error, attempt, policy)
                    
                    NymLogger.warn("executeWithRetry", 
                        "操作 '$operationName' 失败，${delayMs}ms 后进行第 ${attempt + 2} 次尝试",
                        context = mapOf("error" to error.message, "attempt" to attempt + 1))
                    
                    delay(delayMs)
                }
                
            } catch (e: Exception) {
                val error = NymError.Unknown("操作执行异常: ${e.message}")
                lastError = error
                
                NymLogger.logError("executeWithRetry", error, 
                    context = mapOf("operation" to operationName, "attempt" to attempt + 1, "exception" to e.message))
                
                if (attempt >= policy.maxRetries) {
                    return Result.error(error)
                }
            }
        }
        
        // 所有重试都失败了
        val finalError = lastError ?: NymError.Unknown("操作失败且无错误信息")
        NymLogger.logError("executeWithRetry", finalError, 
            context = mapOf("operation" to operationName, "totalAttempts" to policy.maxRetries + 1))
        
        return Result.error(finalError)
    }
    
    /**
     * 判断是否应该重试
     */
    private fun shouldRetry(
        error: NymError,
        attempt: Int,
        policy: RetryPolicy,
        isIdempotent: Boolean
    ): Boolean {
        // 已达到最大重试次数
        if (attempt >= policy.maxRetries) {
            return false
        }
        
        // 非幂等操作的特殊处理
        if (!isIdempotent && !policy.retryNonIdempotent) {
            return false
        }
        
        // 根据错误类型判断是否重试
        return when (error) {
            // 网络错误通常可以重试
            is NymError.NetworkError -> true
            
            // API 服务器错误可以重试
            is NymError.APIError.ServerError -> true
            is NymError.APIError.ServiceUnavailable -> true
            
            // 速率限制错误可以重试
            is NymError.RateLimitError -> true
            
            // 超时错误可以重试
            is NymError.NetworkError.ReadTimeout,
            is NymError.NetworkError.ConnectionTimeout -> true
            
            // 认证错误通常不应该重试
            is NymError.AuthenticationError -> policy.retryOnAuthError
            
            // 请求格式错误不应该重试
            is NymError.APIError.BadRequest -> false
            
            // 解析错误可能是临时的，可以重试
            is NymError.ParseError -> policy.retryOnParseError
            
            // 平台错误通常不重试
            is NymError.PlatformError -> false
            
            // 取消操作不重试
            is NymError.OperationCancelled -> false
            
            // 其他未知错误可以重试
            else -> policy.retryOnUnknownError
        }
    }
    
    /**
     * 计算重试延迟时间
     */
    private fun calculateDelay(error: NymError, attempt: Int, policy: RetryPolicy): Long {
        // 对于速率限制错误，优先使用服务器指定的重试时间
        if (error is NymError.RateLimitError && error.retryAfter != null) {
            val serverDelay = error.retryAfter!!
            val maxAllowedDelay = policy.maxDelayMs
            return minOf(serverDelay, maxAllowedDelay)
        }
        
        // 使用指数退避算法
        val baseDelay = when (error) {
            is NymError.RateLimitError -> policy.rateLimitBaseDelayMs
            is NymError.NetworkError -> policy.networkErrorBaseDelayMs
            is NymError.APIError.ServerError -> policy.serverErrorBaseDelayMs
            else -> policy.baseDelayMs
        }
        
        // 计算指数退避延迟 - 使用 2^attempt * baseDelay 的指数增长
        val exponentialDelay = minOf(
            baseDelay * (2.0.pow(attempt)).toLong(),
            policy.maxDelayMs
        )
        
        // 添加随机抖动以避免惊群效应
        val jitterRange = (exponentialDelay * policy.jitterFactor).toLong()
        val jitter = Random.nextLong(-jitterRange, jitterRange + 1)
        
        return maxOf(policy.minDelayMs, exponentialDelay + jitter)
    }
}

/**
 * 重试配置
 */
@Serializable
data class RetryConfig(
    /**
     * 默认重试策略
     */
    val defaultPolicy: RetryPolicy = RetryPolicy()
) {
    companion object {
        /**
         * 创建保守的重试配置（适用于生产环境）
         */
        fun conservative() = RetryConfig(
            defaultPolicy = RetryPolicy(
                maxRetries = 2,
                baseDelayMs = 1000,
                maxDelayMs = 10000,
                retryOnAuthError = false,
                retryOnParseError = false
            )
        )
        
        /**
         * 创建激进的重试配置（适用于开发和测试）
         */
        fun aggressive() = RetryConfig(
            defaultPolicy = RetryPolicy(
                maxRetries = 5,
                baseDelayMs = 500,
                maxDelayMs = 30000,
                retryOnAuthError = false,
                retryOnParseError = true,
                retryOnUnknownError = true
            )
        )
    }
}

/**
 * 重试策略
 */
@Serializable
data class RetryPolicy(
    /**
     * 最大重试次数（不包括初始尝试）
     */
    val maxRetries: Int = 3,
    
    /**
     * 基础延迟时间（毫秒）
     */
    val baseDelayMs: Long = 1000,
    
    /**
     * 最大延迟时间（毫秒）
     */
    val maxDelayMs: Long = 30000,
    
    /**
     * 最小延迟时间（毫秒）
     */
    val minDelayMs: Long = 100,
    
    /**
     * 抖动因子（0.0-1.0）
     */
    val jitterFactor: Double = 0.1,
    
    /**
     * 是否重试非幂等操作
     */
    val retryNonIdempotent: Boolean = false,
    
    /**
     * 是否在认证错误时重试
     */
    val retryOnAuthError: Boolean = false,
    
    /**
     * 是否在解析错误时重试
     */
    val retryOnParseError: Boolean = true,
    
    /**
     * 是否在未知错误时重试
     */
    val retryOnUnknownError: Boolean = true,
    
    /**
     * 速率限制错误的基础延迟（毫秒）
     */
    val rateLimitBaseDelayMs: Long = 5000,
    
    /**
     * 网络错误的基础延迟（毫秒）
     */
    val networkErrorBaseDelayMs: Long = 1000,
    
    /**
     * 服务器错误的基础延迟（毫秒）
     */
    val serverErrorBaseDelayMs: Long = 2000
)

/**
 * 重试统计信息
 */
data class RetryStats(
    val operationName: String,
    val totalAttempts: Int,
    val successAttempt: Int?, // null 表示最终失败
    val totalDelayMs: Long,
    val errors: List<NymError>
)

/**
 * 带重试的操作扩展函数
 */
suspend fun <T> Result<T>.retryOnFailure(
    retryManager: RetryManager,
    operationName: String,
    isIdempotent: Boolean = true,
    customRetryPolicy: RetryPolicy? = null
): Result<T> {
    return if (this.errorOrNull() != null) {
        retryManager.executeWithRetry(
            operation = { this },
            operationName = operationName,
            isIdempotent = isIdempotent,
            customRetryPolicy = customRetryPolicy
        )
    } else {
        this
    }
}