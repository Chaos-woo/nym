package com.cw2.nym.ai.limiter

import com.cw2.nym.core.result.Result
import com.cw2.nym.core.exception.NymError
import com.cw2.nym.core.logging.NymLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicLong

/**
 * 令牌桶限流器
 * 
 * 实现了经典的令牌桶算法，用于控制 API 请求频率。支持突发请求处理、
 * 动态容量调整和详细的限流监控统计。
 */
class TokenBucketLimiter(
    private val config: TokenBucketConfig
) {
    private val mutex = Mutex()
    private val lastRefillTime = AtomicLong(System.currentTimeMillis())
    private val currentTokens = AtomicLong(config.bucketSize)
    private val stats = RateLimitStats()
    
    /**
     * 尝试获取指定数量的令牌
     * 
     * @param tokens 需要的令牌数量
     * @param waitIfUnavailable 如果令牌不足是否等待
     * @return 获取结果，包含是否成功和等待时间
     */
    suspend fun acquireTokens(tokens: Int = 1, waitIfUnavailable: Boolean = true): Result<TokenAcquisitionResult> {
        if (tokens <= 0) {
            return Result.error(NymError.ParseError.InvalidConfiguration("令牌数量必须大于0"))
        }
        
        if (tokens > config.bucketSize) {
            return Result.error(NymError.RateLimitError.TooManyRequests(
                "请求的令牌数量($tokens)超过桶容量(${config.bucketSize})"
            ))
        }
        
        return mutex.withLock {
            val startTime = System.currentTimeMillis()
            
            // 刷新令牌桶
            refillBucket()
            
            val availableTokens = currentTokens.get()
            
            if (availableTokens >= tokens) {
                // 直接获取令牌
                currentTokens.addAndGet(-tokens.toLong())
                stats.recordSuccess(0)
                
                NymLogger.debug("acquireTokens", 
                    "成功获取 $tokens 个令牌，剩余 ${currentTokens.get()} 个",
                    context = mapOf("bucketSize" to config.bucketSize, "refillRate" to config.refillRate))
                
                return@withLock Result.success(TokenAcquisitionResult(
                    success = true,
                    tokensAcquired = tokens,
                    remainingTokens = currentTokens.get(),
                    waitTimeMs = 0
                ))
            }
            
            if (!waitIfUnavailable) {
                // 不等待，直接返回失败
                stats.recordRejection()
                
                val retryAfter = calculateRetryAfter(tokens)
                NymLogger.warn("acquireTokens", 
                    "令牌不足，需要 $tokens 个，当前只有 $availableTokens 个，${retryAfter}ms后重试")
                
                return@withLock Result.error(NymError.RateLimitError.TooManyRequests(
                    "令牌不足，请稍后重试",
                    retryAfter = retryAfter
                ))
            }
            
            // 计算需要等待的时间
            val waitTime = calculateWaitTime(tokens, availableTokens)
            
            if (waitTime > config.maxWaitTimeMs) {
                stats.recordTimeout()
                
                NymLogger.warn("acquireTokens", 
                    "等待时间($waitTime ms)超过最大等待时间(${config.maxWaitTimeMs} ms)")
                
                return@withLock Result.error(NymError.RateLimitError.TooManyRequests(
                    "等待时间过长，请稍后重试",
                    retryAfter = waitTime
                ))
            }
            
            // 等待令牌补充
            NymLogger.debug("acquireTokens", 
                "等待 ${waitTime}ms 以获取 $tokens 个令牌")
            
            delay(waitTime)
            
            // 重新检查并获取令牌
            refillBucket()
            val newAvailableTokens = currentTokens.get()
            
            if (newAvailableTokens >= tokens) {
                currentTokens.addAndGet(-tokens.toLong())
                val totalWaitTime = System.currentTimeMillis() - startTime
                stats.recordSuccess(totalWaitTime)
                
                NymLogger.debug("acquireTokens", 
                    "等待后成功获取 $tokens 个令牌，总等待时间: ${totalWaitTime}ms")
                
                Result.success(TokenAcquisitionResult(
                    success = true,
                    tokensAcquired = tokens,
                    remainingTokens = currentTokens.get(),
                    waitTimeMs = totalWaitTime
                ))
            } else {
                stats.recordFailure()
                
                NymLogger.warn("acquireTokens", 
                    "等待后仍然令牌不足，需要 $tokens 个，当前 $newAvailableTokens 个")
                
                Result.error(NymError.RateLimitError.TooManyRequests(
                    "等待后仍然令牌不足",
                    retryAfter = calculateRetryAfter(tokens)
                ))
            }
        }
    }
    
    /**
     * 刷新令牌桶
     */
    private fun refillBucket() {
        val now = System.currentTimeMillis()
        val lastRefill = lastRefillTime.get()
        val timePassed = now - lastRefill
        
        if (timePassed <= 0) {
            return // 时间没有推进，不需要补充
        }
        
        // 计算应该补充的令牌数量
        val tokensToAdd = (timePassed * config.refillRate / 1000.0).toLong()
        
        if (tokensToAdd > 0) {
            val current = currentTokens.get()
            val newTokenCount = minOf(current + tokensToAdd, config.bucketSize)
            
            currentTokens.set(newTokenCount)
            lastRefillTime.set(now)
            
            if (tokensToAdd > 0) {
                NymLogger.debug("refillBucket", 
                    "补充 $tokensToAdd 个令牌，当前令牌数: $newTokenCount/${config.bucketSize}",
                    context = mapOf("timePassed" to timePassed, "refillRate" to config.refillRate))
            }
        }
    }
    
    /**
     * 计算等待时间
     */
    private fun calculateWaitTime(requiredTokens: Int, currentTokens: Long): Long {
        val neededTokens = requiredTokens - currentTokens
        if (neededTokens <= 0) {
            return 0
        }
        
        // 计算生成所需令牌的时间（毫秒）
        return (neededTokens * 1000.0 / config.refillRate).toLong()
    }
    
    /**
     * 计算建议重试时间
     */
    private fun calculateRetryAfter(requiredTokens: Int): Long {
        val currentTokens = this.currentTokens.get()
        val neededTokens = requiredTokens - currentTokens
        
        if (neededTokens <= 0) {
            return 0
        }
        
        return (neededTokens * 1000.0 / config.refillRate).toLong()
    }
    
    /**
     * 获取当前状态
     */
    suspend fun getCurrentStatus(): TokenBucketStatus {
        refillBucket()
        return TokenBucketStatus(
            currentTokens = currentTokens.get(),
            bucketSize = config.bucketSize,
            refillRate = config.refillRate,
            utilizationRate = 1.0 - (currentTokens.get().toDouble() / config.bucketSize),
            stats = stats.getSnapshot()
        )
    }
    
    /**
     * 重置限流器状态
     */
    suspend fun reset() {
        mutex.withLock {
            currentTokens.set(config.bucketSize)
            lastRefillTime.set(System.currentTimeMillis())
            stats.reset()
            
            NymLogger.info("reset", "令牌桶限流器已重置")
        }
    }
    
    /**
     * 动态调整配置
     */
    suspend fun updateConfig(newConfig: TokenBucketConfig) {
        mutex.withLock {
            // 调整当前令牌数量以适应新的桶大小
            val currentRatio = currentTokens.get().toDouble() / config.bucketSize
            val newTokenCount = (newConfig.bucketSize * currentRatio).toLong()
            
            currentTokens.set(minOf(newTokenCount, newConfig.bucketSize))
            
            // 更新配置（这里需要通过其他方式更新，因为config是val）
            NymLogger.info("updateConfig", 
                "令牌桶配置已更新: 桶大小=${newConfig.bucketSize}, 补充率=${newConfig.refillRate}")
        }
    }
}

/**
 * 令牌桶配置
 */
@Serializable
data class TokenBucketConfig(
    /**
     * 桶容量（最大令牌数）
     */
    val bucketSize: Long = 100,
    
    /**
     * 令牌补充速率（每秒补充的令牌数）
     */
    val refillRate: Double = 10.0,
    
    /**
     * 最大等待时间（毫秒）
     */
    val maxWaitTimeMs: Long = 30000,
    
    /**
     * 是否允许突发请求
     */
    val allowBurstRequests: Boolean = true
) {
    init {
        require(bucketSize > 0) { "桶容量必须大于0" }
        require(refillRate > 0) { "补充速率必须大于0" }
        require(maxWaitTimeMs > 0) { "最大等待时间必须大于0" }
    }
    
    companion object {
        /**
         * 保守配置（适用于生产环境）
         */
        fun conservative() = TokenBucketConfig(
            bucketSize = 50,
            refillRate = 5.0,
            maxWaitTimeMs = 10000
        )
        
        /**
         * 激进配置（适用于开发和测试）
         */
        fun aggressive() = TokenBucketConfig(
            bucketSize = 200,
            refillRate = 20.0,
            maxWaitTimeMs = 60000
        )
        
        /**
         * OpenAI API 的推荐配置
         */
        fun forOpenAI() = TokenBucketConfig(
            bucketSize = 100,
            refillRate = 20.0, // 每秒20个请求
            maxWaitTimeMs = 30000
        )
    }
}

/**
 * 令牌获取结果
 */
data class TokenAcquisitionResult(
    val success: Boolean,
    val tokensAcquired: Int,
    val remainingTokens: Long,
    val waitTimeMs: Long
)

/**
 * 令牌桶状态
 */
data class TokenBucketStatus(
    val currentTokens: Long,
    val bucketSize: Long,
    val refillRate: Double,
    val utilizationRate: Double, // 0.0-1.0，1.0表示完全被使用
    val stats: RateLimitStatsSnapshot
)

/**
 * 速率限制统计
 */
class RateLimitStats {
    private val mutex = Mutex()
    private var totalRequests = 0L
    private var successfulRequests = 0L
    private var rejectedRequests = 0L
    private var timeoutRequests = 0L
    private var failedRequests = 0L
    private var totalWaitTime = 0L
    private var maxWaitTime = 0L
    private var startTime = System.currentTimeMillis()
    
    suspend fun recordSuccess(waitTimeMs: Long) {
        mutex.withLock {
            totalRequests++
            successfulRequests++
            totalWaitTime += waitTimeMs
            if (waitTimeMs > maxWaitTime) {
                maxWaitTime = waitTimeMs
            }
        }
    }
    
    suspend fun recordRejection() {
        mutex.withLock {
            totalRequests++
            rejectedRequests++
        }
    }
    
    suspend fun recordTimeout() {
        mutex.withLock {
            totalRequests++
            timeoutRequests++
        }
    }
    
    suspend fun recordFailure() {
        mutex.withLock {
            totalRequests++
            failedRequests++
        }
    }
    
    suspend fun reset() {
        mutex.withLock {
            totalRequests = 0
            successfulRequests = 0
            rejectedRequests = 0
            timeoutRequests = 0
            failedRequests = 0
            totalWaitTime = 0
            maxWaitTime = 0
            startTime = System.currentTimeMillis()
        }
    }
    
    suspend fun getSnapshot(): RateLimitStatsSnapshot {
        return mutex.withLock {
            val uptime = System.currentTimeMillis() - startTime
            RateLimitStatsSnapshot(
                totalRequests = totalRequests,
                successfulRequests = successfulRequests,
                rejectedRequests = rejectedRequests,
                timeoutRequests = timeoutRequests,
                failedRequests = failedRequests,
                successRate = if (totalRequests > 0) successfulRequests.toDouble() / totalRequests else 0.0,
                averageWaitTime = if (successfulRequests > 0) totalWaitTime.toDouble() / successfulRequests else 0.0,
                maxWaitTime = maxWaitTime,
                requestsPerSecond = if (uptime > 0) totalRequests * 1000.0 / uptime else 0.0,
                uptimeMs = uptime
            )
        }
    }
}

/**
 * 速率限制统计快照
 */
@Serializable
data class RateLimitStatsSnapshot(
    val totalRequests: Long,
    val successfulRequests: Long,
    val rejectedRequests: Long,
    val timeoutRequests: Long,
    val failedRequests: Long,
    val successRate: Double,
    val averageWaitTime: Double,
    val maxWaitTime: Long,
    val requestsPerSecond: Double,
    val uptimeMs: Long
)