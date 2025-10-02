package com.cw2.nym.ai.timeout

import com.cw2.nym.core.result.Result
import com.cw2.nym.core.exception.NymError
import com.cw2.nym.core.logging.NymLogger
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 超时管理器 - 负责管理各种超时控制策略
 * Timeout Manager - Manages various timeout control strategies
 *
 * 提供以下功能：
 * - 连接超时控制
 * - 读取超时控制
 * - 整体请求超时控制
 * - 超时异常处理和重试
 */
class TimeoutManager(
    private val config: TimeoutConfig = TimeoutConfig()
) {
    
    companion object {
        private const val TAG = "TimeoutManager"
    }

    private val logger = NymLogger

    /**
     * 执行带超时控制的操作
     * Execute operation with timeout control
     *
     * @param operation 要执行的操作
     * @param operationName 操作名称，用于日志记录
     * @param customTimeout 自定义超时时间，为空则使用默认配置
     * @return 操作结果，包含超时信息
     */
    suspend fun <T> executeWithTimeout(
        operation: suspend () -> Result<T>,
        operationName: String,
        customTimeout: Duration? = null
    ): Result<T> {
        val timeout = customTimeout ?: config.defaultOperationTimeout
        val startTime = System.currentTimeMillis()
        
        logger.debug(TAG, "开始执行超时控制操作: $operationName, 超时时间: ${timeout.inWholeMilliseconds}ms")
        
        return try {
            withTimeout(timeout) {
                val result = operation()
                val duration = System.currentTimeMillis() - startTime
                
                logger.debug(TAG, "操作完成: $operationName, 耗时: ${duration}ms")
                
                // 记录操作统计
                recordOperationStats(operationName, duration, success = true)
                
                result
            }
        } catch (e: TimeoutCancellationException) {
            val duration = System.currentTimeMillis() - startTime
            logger.warn(TAG, "操作超时: $operationName, 超时时间: ${timeout.inWholeMilliseconds}ms, 实际耗时: ${duration}ms")
            
            recordOperationStats(operationName, duration, success = false, timeout = true)
            
            Result.error(
                NymError.TimeoutError.OperationTimeout(
                    message = "操作超时: $operationName",
                    timeoutMs = timeout.inWholeMilliseconds,
                    actualDurationMs = duration
                )
            )
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logger.error(TAG, "操作执行异常: $operationName", mapOf("exception" to e.message), e)
            
            recordOperationStats(operationName, duration, success = false, error = e)
            
            Result.error(
                NymError.Unknown(
                    message = "操作执行异常: $operationName",
                    cause = e
                )
            )
        }
    }

    /**
     * 执行带连接超时的网络操作
     * Execute network operation with connection timeout
     */
    suspend fun <T> executeWithConnectionTimeout(
        operation: suspend () -> Result<T>,
        operationName: String
    ): Result<T> {
        return executeWithTimeout(
            operation = operation,
            operationName = "CONNECTION_$operationName",
            customTimeout = config.connectionTimeout
        )
    }

    /**
     * 执行带读取超时的操作
     * Execute operation with read timeout
     */
    suspend fun <T> executeWithReadTimeout(
        operation: suspend () -> Result<T>,
        operationName: String
    ): Result<T> {
        return executeWithTimeout(
            operation = operation,
            operationName = "READ_$operationName",
            customTimeout = config.readTimeout
        )
    }

    /**
     * 执行带整体请求超时的操作
     * Execute operation with overall request timeout
     */
    suspend fun <T> executeWithRequestTimeout(
        operation: suspend () -> Result<T>,
        operationName: String
    ): Result<T> {
        return executeWithTimeout(
            operation = operation,
            operationName = "REQUEST_$operationName",
            customTimeout = config.requestTimeout
        )
    }

    /**
     * 批量执行操作，每个操作都有独立的超时控制
     * Execute multiple operations with independent timeout control
     */
    suspend fun <T> executeBatchWithTimeout(
        operations: List<Pair<String, suspend () -> Result<T>>>,
        maxConcurrency: Int = config.maxConcurrentOperations
    ): List<Result<T>> {
        return coroutineScope {
            operations.chunked(maxConcurrency).flatMap { chunk ->
                chunk.map { (name, operation) ->
                    async<Result<T>> {
                        executeWithTimeout(operation, name)
                    }
                }.awaitAll()
            }
        }
    }

    /**
     * 记录操作统计信息
     * Record operation statistics
     */
    private fun recordOperationStats(
        operationName: String,
        durationMs: Long,
        success: Boolean,
        timeout: Boolean = false,
        error: Exception? = null
    ) {
        val stats = TimeoutStats(
            operationName = operationName,
            durationMs = durationMs,
            success = success,
            timeout = timeout,
            error = error?.javaClass?.simpleName
        )
        
        logger.logPerformance(
            operation = operationName,
            durationMs = durationMs,
            context = mapOf(
                "success" to success.toString(),
                "timeout" to timeout.toString(),
                "error" to (error?.message ?: "none")
            )
        )
    }

    /**
     * 获取当前超时配置
     * Get current timeout configuration
     */
    fun getTimeoutConfig(): TimeoutConfig = config.copy()

    /**
     * 检查操作是否可能超时
     * Check if operation is likely to timeout
     */
    fun isLikelyToTimeout(
        operationName: String,
        estimatedDurationMs: Long
    ): Boolean {
        val threshold = config.defaultOperationTimeout.inWholeMilliseconds * 0.9
        return estimatedDurationMs > threshold
    }

    /**
     * 根据历史数据预估操作超时时间
     * Estimate operation timeout based on historical data
     */
    fun estimateOptimalTimeout(
        operationName: String,
        percentile: Double = 0.95
    ): Duration {
        // 这里可以基于历史统计数据来动态调整超时时间
        // 暂时返回默认配置，后续可以集成缓存统计数据
        return when {
            operationName.startsWith("CONNECTION_") -> config.connectionTimeout
            operationName.startsWith("READ_") -> config.readTimeout
            operationName.startsWith("REQUEST_") -> config.requestTimeout
            else -> config.defaultOperationTimeout
        }
    }
}

/**
 * 超时配置类
 * Timeout configuration class
 */
@Serializable
data class TimeoutConfig(
    /** 默认操作超时时间（毫秒） */
    val defaultOperationTimeout: Duration = 30.seconds,
    
    /** 连接超时时间（毫秒） */
    val connectionTimeout: Duration = 10.seconds,
    
    /** 读取超时时间（毫秒） */
    val readTimeout: Duration = 20.seconds,
    
    /** 整体请求超时时间（毫秒） */
    val requestTimeout: Duration = 30.seconds,
    
    /** AI服务特定超时时间（毫秒） */
    val aiServiceTimeout: Duration = 45.seconds,
    
    /** 缓存操作超时时间（毫秒） */
    val cacheOperationTimeout: Duration = 5.seconds,
    
    /** 数据库操作超时时间（毫秒） */
    val databaseTimeout: Duration = 15.seconds,
    
    /** 文件操作超时时间（毫秒） */
    val fileOperationTimeout: Duration = 10.seconds,
    
    /** 最大并发操作数 */
    val maxConcurrentOperations: Int = 10,
    
    /** 是否启用自适应超时 */
    val enableAdaptiveTimeout: Boolean = true,
    
    /** 超时重试是否启用 */
    val enableTimeoutRetry: Boolean = true
) {
    companion object {
        /**
         * 创建保守的超时配置（较长的超时时间）
         * Create conservative timeout configuration
         */
        fun conservative(): TimeoutConfig = TimeoutConfig(
            defaultOperationTimeout = 60.seconds,
            connectionTimeout = 20.seconds,
            readTimeout = 40.seconds,
            requestTimeout = 60.seconds,
            aiServiceTimeout = 90.seconds
        )

        /**
         * 创建激进的超时配置（较短的超时时间）
         * Create aggressive timeout configuration
         */
        fun aggressive(): TimeoutConfig = TimeoutConfig(
            defaultOperationTimeout = 15.seconds,
            connectionTimeout = 5.seconds,
            readTimeout = 10.seconds,
            requestTimeout = 15.seconds,
            aiServiceTimeout = 20.seconds
        )

        /**
         * 为本地开发创建超时配置
         * Create timeout configuration for local development
         */
        fun forDevelopment(): TimeoutConfig = TimeoutConfig(
            defaultOperationTimeout = 120.seconds,
            connectionTimeout = 30.seconds,
            readTimeout = 60.seconds,
            requestTimeout = 120.seconds,
            aiServiceTimeout = 180.seconds,
            enableAdaptiveTimeout = false
        )
    }
}

/**
 * 超时统计信息
 * Timeout statistics
 */
@Serializable
data class TimeoutStats(
    val operationName: String,
    val durationMs: Long,
    val success: Boolean,
    val timeout: Boolean = false,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 扩展函数：为挂起函数添加超时支持
 * Extension function: Add timeout support for suspend functions
 */
suspend fun <T> timeoutOnFailure(
    timeoutManager: TimeoutManager,
    operationName: String,
    customTimeout: Duration? = null,
    operation: suspend () -> Result<T>
): Result<T> {
    return timeoutManager.executeWithTimeout(
        operation = operation,
        operationName = operationName,
        customTimeout = customTimeout
    )
}