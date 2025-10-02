package com.cw2.nym.ai.concurrency

import com.cw2.nym.core.result.Result
import com.cw2.nym.core.exception.NymError
import com.cw2.nym.core.logging.NymLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 并发控制管理器 - 负责管理并发请求和队列
 * Concurrency Manager - Manages concurrent requests and queues
 *
 * 提供以下功能：
 * - 控制最大并发连接数
 * - 请求队列管理
 * - 优先级队列支持
 * - 队列满载处理
 * - 并发统计监控
 */
class ConcurrencyManager(
    private val config: ConcurrencyConfig = ConcurrencyConfig()
) {
    
    companion object {
        private const val TAG = "ConcurrencyManager"
    }

    private val logger = NymLogger
    
    // 信号量控制并发数量
    private val semaphore = Semaphore(config.maxConcurrentRequests)
    
    // 请求队列（不同优先级）
    private val highPriorityQueue = Channel<QueuedRequest<*>>(Channel.UNLIMITED)
    private val normalPriorityQueue = Channel<QueuedRequest<*>>(Channel.UNLIMITED)
    private val lowPriorityQueue = Channel<QueuedRequest<*>>(Channel.UNLIMITED)
    
    // 统计信息
    private val activeRequests = AtomicInteger(0)
    private val totalRequests = AtomicLong(0)
    private val completedRequests = AtomicLong(0)
    private val rejectedRequests = AtomicLong(0)
    private val timedoutRequests = AtomicLong(0)

    // 队列处理协程作用域
    private val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // 启动队列处理器
        startQueueProcessor()
    }

    /**
     * 执行带并发控制的操作
     * Execute operation with concurrency control
     */
    suspend fun <T> executeWithConcurrencyControl(
        operation: suspend () -> Result<T>,
        operationName: String,
        priority: RequestPriority = RequestPriority.NORMAL,
        timeout: Duration = config.queueTimeout
    ): Result<T> {
        val requestId = totalRequests.incrementAndGet()
        
        logger.debug(TAG, "提交并发请求: $operationName, 请求ID: $requestId, 优先级: $priority")
        
        // 检查队列是否已满
        if (isQueueFull()) {
            rejectedRequests.incrementAndGet()
            return Result.error(
                NymError.RateLimitError.TooManyRequests(
                    message = "请求队列已满，请稍后重试",
                    retryAfter = config.retryAfterMs
                )
            )
        }

        return try {
            withTimeout(timeout) {
                semaphore.withPermit {
                    val activeCount = activeRequests.incrementAndGet()
                    
                    logger.debug(TAG, "开始执行请求: $operationName, 当前并发数: $activeCount")
                    
                    try {
                        val startTime = System.currentTimeMillis()
                        val result = operation()
                        val duration = System.currentTimeMillis() - startTime
                        
                        // 记录统计信息
                        recordRequestStats(operationName, duration, result.isSuccess, priority)
                        
                        completedRequests.incrementAndGet()
                        result
                    } finally {
                        activeRequests.decrementAndGet()
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            timedoutRequests.incrementAndGet()
            logger.warn(TAG, "请求队列等待超时: $operationName, 等待时间: ${timeout.inWholeMilliseconds}ms")
            
            Result.error(
                NymError.TimeoutError.RequestTimeout(
                    message = "请求队列等待超时: $operationName",
                    timeoutMs = timeout.inWholeMilliseconds
                )
            )
        } catch (e: Exception) {
            logger.error(TAG, "并发请求执行异常: $operationName", mapOf("exception" to e.message), e)
            
            Result.error(
                NymError.Unknown(
                    message = "并发请求执行异常: $operationName",
                    cause = e
                )
            )
        }
    }

    /**
     * 将请求加入优先级队列
     * Add request to priority queue
     */
    suspend fun <T> enqueueRequest(
        operation: suspend () -> Result<T>,
        operationName: String,
        priority: RequestPriority = RequestPriority.NORMAL,
        timeout: Duration = config.queueTimeout
    ): Result<T> {
        val deferred = CompletableDeferred<Result<T>>()
        val queuedRequest = QueuedRequest(
            id = totalRequests.incrementAndGet(),
            operationName = operationName,
            priority = priority,
            operation = operation,
            deferred = deferred as CompletableDeferred<Result<Any>>,
            enqueueTime = System.currentTimeMillis(),
            timeout = timeout
        )

        // 根据优先级选择队列
        val success = when (priority) {
            RequestPriority.HIGH -> highPriorityQueue.trySend(queuedRequest).isSuccess
            RequestPriority.NORMAL -> normalPriorityQueue.trySend(queuedRequest).isSuccess
            RequestPriority.LOW -> lowPriorityQueue.trySend(queuedRequest).isSuccess
        }

        if (!success) {
            rejectedRequests.incrementAndGet()
            return Result.error(
                NymError.RateLimitError.TooManyRequests(
                    message = "优先级队列已满: $priority",
                    retryAfter = config.retryAfterMs
                )
            )
        }

        logger.debug(TAG, "请求已加入队列: $operationName, 优先级: $priority, 队列长度: ${getQueueSize(priority)}")

        return try {
            withTimeout(timeout) {
                deferred.await() as Result<T>
            }
        } catch (e: TimeoutCancellationException) {
            timedoutRequests.incrementAndGet()
            deferred.cancel(CancellationException("队列等待超时"))
            
            Result.error(
                NymError.TimeoutError.RequestTimeout(
                    message = "队列等待超时: $operationName",
                    timeoutMs = timeout.inWholeMilliseconds
                )
            )
        }
    }

    /**
     * 批量执行操作，支持并发控制
     * Execute batch operations with concurrency control
     */
    suspend fun <T> executeBatchWithConcurrency(
        operations: List<Pair<String, suspend () -> Result<T>>>,
        priority: RequestPriority = RequestPriority.NORMAL,
        maxBatchConcurrency: Int? = null
    ): List<Result<T>> {
        val effectiveConcurrency = maxBatchConcurrency ?: (config.maxConcurrentRequests / 2).coerceAtLeast(1)
        
        return coroutineScope {
            operations.chunked(effectiveConcurrency).flatMap { chunk ->
                chunk.map { (name, operation) ->
                    async {
                        executeWithConcurrencyControl(operation, name, priority)
                    }
                }.awaitAll()
            }
        }
    }

    /**
     * 启动队列处理器
     * Start queue processor
     */
    private fun startQueueProcessor() {
        processingScope.launch {
            while (!currentCoroutineContext().job.isCancelled) {
                try {
                    processNextRequest()
                } catch (e: Exception) {
                    logger.error(TAG, "队列处理器异常", mapOf("error" to e.message), e)
                    delay(1000) // 避免快速重试
                }
            }
        }
    }

    /**
     * 处理下一个请求（按优先级顺序）
     * Process next request (by priority order)
     */
    private suspend fun processNextRequest() {
        val request = selectNextRequest()
        
        if (request != null) {
            // 检查请求是否已超时
            val waitTime = System.currentTimeMillis() - request.enqueueTime
            if (waitTime > request.timeout.inWholeMilliseconds) {
                timedoutRequests.incrementAndGet()
                request.deferred.complete(
                    Result.error(
                        NymError.TimeoutError.RequestTimeout(
                            message = "队列中等待超时: ${request.operationName}",
                            timeoutMs = request.timeout.inWholeMilliseconds,
                            actualDurationMs = waitTime
                        )
                    )
                )
                return
            }

            // 执行请求
            try {
                val result = executeWithConcurrencyControl(
                    request.operation,
                    request.operationName,
                    request.priority
                )
                @Suppress("UNCHECKED_CAST")
                request.deferred.complete(result as Result<Any>)
            } catch (e: Exception) {
                request.deferred.complete(
                    Result.error(
                        NymError.Unknown(
                            message = "队列请求执行异常: ${request.operationName}",
                            cause = e
                        )
                    )
                )
            }
        } else {
            // 没有请求时短暂等待
            delay(10)
        }
    }

    /**
     * 按优先级选择下一个请求
     * Select next request by priority
     */
    private suspend fun selectNextRequest(): QueuedRequest<*>? {
        // 优先处理高优先级队列
        highPriorityQueue.tryReceive().getOrNull()?.let { return it }
        
        // 然后是普通优先级队列
        normalPriorityQueue.tryReceive().getOrNull()?.let { return it }
        
        // 最后是低优先级队列
        lowPriorityQueue.tryReceive().getOrNull()?.let { return it }
        
        return null
    }

    /**
     * 检查队列是否已满
     * Check if queue is full
     */
    private fun isQueueFull(): Boolean {
        val totalQueueSize = getTotalQueueSize()
        return totalQueueSize >= config.maxQueueSize
    }

    /**
     * 获取指定优先级队列的长度
     * Get queue size for specified priority
     */
    private fun getQueueSize(priority: RequestPriority): Int {
        return when (priority) {
            RequestPriority.HIGH -> highPriorityQueue.tryReceive().getOrNull()?.let { 1 } ?: 0
            RequestPriority.NORMAL -> normalPriorityQueue.tryReceive().getOrNull()?.let { 1 } ?: 0
            RequestPriority.LOW -> lowPriorityQueue.tryReceive().getOrNull()?.let { 1 } ?: 0
        }
    }

    /**
     * 获取总队列长度
     * Get total queue size
     */
    private fun getTotalQueueSize(): Int {
        return getQueueSize(RequestPriority.HIGH) + 
               getQueueSize(RequestPriority.NORMAL) + 
               getQueueSize(RequestPriority.LOW)
    }

    /**
     * 记录请求统计信息
     * Record request statistics
     */
    private fun recordRequestStats(
        operationName: String,
        durationMs: Long,
        success: Boolean,
        priority: RequestPriority
    ) {
        logger.logPerformance(
            operation = operationName,
            durationMs = durationMs,
            context = mapOf(
                "success" to success.toString(),
                "priority" to priority.name,
                "activeRequests" to activeRequests.get().toString(),
                "queueSize" to getTotalQueueSize().toString()
            )
        )
    }

    /**
     * 获取当前并发状态
     * Get current concurrency status
     */
    fun getConcurrencyStatus(): ConcurrencyStatus {
        return ConcurrencyStatus(
            activeRequests = activeRequests.get(),
            maxConcurrentRequests = config.maxConcurrentRequests,
            totalRequests = totalRequests.get(),
            completedRequests = completedRequests.get(),
            rejectedRequests = rejectedRequests.get(),
            timedoutRequests = timedoutRequests.get(),
            queueSizes = QueueSizes(
                high = getQueueSize(RequestPriority.HIGH),
                normal = getQueueSize(RequestPriority.NORMAL),
                low = getQueueSize(RequestPriority.LOW)
            ),
            utilizationRate = activeRequests.get().toDouble() / config.maxConcurrentRequests
        )
    }

    /**
     * 关闭并发管理器
     * Shutdown concurrency manager
     */
    fun shutdown() {
        logger.info(TAG, "关闭并发管理器")
        processingScope.cancel("ConcurrencyManager shutdown")
    }
}

/**
 * 请求优先级
 * Request priority
 */
enum class RequestPriority {
    HIGH,    // 高优先级 - 用户交互操作
    NORMAL,  // 普通优先级 - 常规请求
    LOW      // 低优先级 - 后台任务
}

/**
 * 队列中的请求
 * Queued request
 */
private data class QueuedRequest<T>(
    val id: Long,
    val operationName: String,
    val priority: RequestPriority,
    val operation: suspend () -> Result<T>,
    val deferred: CompletableDeferred<Result<Any>>,
    val enqueueTime: Long,
    val timeout: Duration
)

/**
 * 并发配置
 * Concurrency configuration
 */
@Serializable
data class ConcurrencyConfig(
    /** 最大并发请求数 */
    val maxConcurrentRequests: Int = 10,
    
    /** 最大队列大小 */
    val maxQueueSize: Int = 100,
    
    /** 队列超时时间 */
    val queueTimeout: Duration = 30.seconds,
    
    /** 请求被拒绝后建议重试间隔（毫秒） */
    val retryAfterMs: Long = 5000,
    
    /** 是否启用优先级队列 */
    val enablePriorityQueue: Boolean = true,
    
    /** 高优先级请求比例限制 */
    val highPriorityRatio: Double = 0.3,
    
    /** 队列处理间隔（毫秒） */
    val processingIntervalMs: Long = 10
) {
    companion object {
        /**
         * 保守配置（较小的并发数）
         * Conservative configuration
         */
        fun conservative(): ConcurrencyConfig = ConcurrencyConfig(
            maxConcurrentRequests = 5,
            maxQueueSize = 50,
            queueTimeout = 60.seconds
        )

        /**
         * 激进配置（较大的并发数）
         * Aggressive configuration
         */
        fun aggressive(): ConcurrencyConfig = ConcurrencyConfig(
            maxConcurrentRequests = 20,
            maxQueueSize = 200,
            queueTimeout = 15.seconds
        )

        /**
         * 开发环境配置
         * Development configuration
         */
        fun forDevelopment(): ConcurrencyConfig = ConcurrencyConfig(
            maxConcurrentRequests = 3,
            maxQueueSize = 20,
            queueTimeout = 120.seconds
        )
    }
}

/**
 * 队列大小信息
 * Queue sizes information
 */
@Serializable
data class QueueSizes(
    val high: Int,
    val normal: Int,
    val low: Int
) {
    val total: Int get() = high + normal + low
}

/**
 * 并发状态信息
 * Concurrency status information
 */
@Serializable
data class ConcurrencyStatus(
    val activeRequests: Int,
    val maxConcurrentRequests: Int,
    val totalRequests: Long,
    val completedRequests: Long,
    val rejectedRequests: Long,
    val timedoutRequests: Long,
    val queueSizes: QueueSizes,
    val utilizationRate: Double
) {
    val successRate: Double get() = if (totalRequests > 0) completedRequests.toDouble() / totalRequests else 0.0
    val rejectionRate: Double get() = if (totalRequests > 0) rejectedRequests.toDouble() / totalRequests else 0.0
    val timeoutRate: Double get() = if (totalRequests > 0) timedoutRequests.toDouble() / totalRequests else 0.0
}

/**
 * 扩展函数：为挂起函数添加并发控制支持
 * Extension function: Add concurrency control support for suspend functions
 */
suspend fun <T> concurrencyControlled(
    concurrencyManager: ConcurrencyManager,
    operationName: String,
    priority: RequestPriority = RequestPriority.NORMAL,
    timeout: Duration = 30.seconds,
    operation: suspend () -> Result<T>
): Result<T> {
    return concurrencyManager.executeWithConcurrencyControl(
        operation = operation,
        operationName = operationName,
        priority = priority,
        timeout = timeout
    )
}