package com.cw2.nym.ai.cache

import com.cw2.nym.core.result.Result
import com.cw2.nym.core.exception.NymError
import com.cw2.nym.core.logging.NymLogger
import com.cw2.nym.core.time.TimeUtils
import com.cw2.nym.ai.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.milliseconds

/**
 * 缓存管理接口 - Cache management interface
 * 
 * 定义了缓存系统的核心接口，支持泛型类型的缓存操作
 */
interface CacheManager {
    
    /**
     * 获取缓存项 - Get cache item
     */
    suspend fun <T> get(key: CacheKey, type: Class<T>): Result<T?>
    
    /**
     * 存储缓存项 - Store cache item
     */
    suspend fun <T> put(key: CacheKey, value: T, ttl: Duration? = null): Result<Unit>
    
    /**
     * 删除缓存项 - Remove cache item
     */
    suspend fun remove(key: CacheKey): Result<Boolean>
    
    /**
     * 批量删除缓存项 - Remove cache items by pattern
     */
    suspend fun removeByPattern(pattern: String): Result<Int>
    
    /**
     * 清空所有缓存 - Clear all cache
     */
    suspend fun clear(): Result<Unit>
    
    /**
     * 获取缓存统计信息 - Get cache statistics
     */
    fun getStats(): CacheStats
    
    /**
     * 检查缓存项是否存在 - Check if cache item exists
     */
    suspend fun exists(key: CacheKey): Boolean
    
    /**
     * 获取缓存项的剩余TTL - Get remaining TTL of cache item
     */
    suspend fun getTtl(key: CacheKey): Duration?
}

/**
 * 智能缓存管理器实现 - Smart cache manager implementation
 * 
 * 基于内存的缓存实现，支持TTL、LRU驱逐、统计监控等功能
 */
class SmartCacheManager(
    private val config: CacheConfig = CacheConfig()
) : CacheManager {
    
    companion object {
        private const val TAG = "SmartCacheManager"
    }

    private val logger = NymLogger
    private val json = Json { 
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // 缓存存储：键 -> 缓存条目
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    
    // 访问时间跟踪（用于LRU）
    private val accessTimes = ConcurrentHashMap<String, Long>()
    
    // 统计信息
    private val totalRequests = AtomicLong(0)
    private val hitCount = AtomicLong(0)
    private val missCount = AtomicLong(0)
    private val evictionCount = AtomicLong(0)
    private val errorCount = AtomicLong(0)
    private val totalSize = AtomicInteger(0)
    
    // 清理任务作用域
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    init {
        startCleanupTask()
    }

    override suspend fun <T> get(key: CacheKey, type: Class<T>): Result<T?> {
        totalRequests.incrementAndGet()
        val keyString = key.toString()
        
        return try {
            val entry = cache[keyString]
            
            if (entry == null) {
                missCount.incrementAndGet()
                logger.logCacheOperation("GET", keyString, hit = false)
                return Result.success(null)
            }
            
            // 检查是否过期
            if (entry.isExpired()) {
                cache.remove(keyString)
                accessTimes.remove(keyString)
                totalSize.decrementAndGet()
                missCount.incrementAndGet()
                logger.logCacheOperation("GET", keyString, hit = false, size = cache.size)
                return Result.success(null)
            }
            
            // 更新访问时间
            accessTimes[keyString] = System.currentTimeMillis()
            hitCount.incrementAndGet()
            
            logger.logCacheOperation("GET", keyString, hit = true, size = cache.size)
            
            // 反序列化数据
            val value = when (type) {
                String::class.java -> entry.data as T
                List::class.java -> json.decodeFromString<List<NamingSuggestion>>(entry.data) as T
                NamingSuggestion::class.java -> json.decodeFromString<NamingSuggestion>(entry.data) as T
                CommentSuggestion::class.java -> json.decodeFromString<CommentSuggestion>(entry.data) as T
                else -> throw UnsupportedOperationException("不支持的类型: ${type.name}")
            }
            
            Result.success(value)
            
        } catch (e: Exception) {
            errorCount.incrementAndGet()
            logger.error(TAG, "缓存获取失败: $keyString", mapOf("error" to e.message), e)
            Result.error(
                NymError.Unknown(
                    message = "缓存获取失败: $keyString",
                    cause = e
                )
            )
        }
    }

    override suspend fun <T> put(key: CacheKey, value: T, ttl: Duration?): Result<Unit> {
        val keyString = key.toString()
        val effectiveTtl = ttl ?: config.defaultTtl
        
        return try {
            // 检查缓存大小限制
            if (cache.size >= config.maxSize && !cache.containsKey(keyString)) {
                evictLeastRecentlyUsed()
            }
            
            // 序列化数据
            val serializedData = when (value) {
                is String -> value
                is List<*> -> json.encodeToString(value as List<NamingSuggestion>)
                is NamingSuggestion -> json.encodeToString(value)
                is CommentSuggestion -> json.encodeToString(value)
                else -> throw UnsupportedOperationException("不支持的类型: ${value?.javaClass?.name ?: "null"}")
            }
            
            val entry = CacheEntry(
                data = serializedData,
                createdAt = System.currentTimeMillis(),
                ttl = effectiveTtl,
                accessCount = 1
            )
            
            val isNew = cache.put(keyString, entry) == null
            if (isNew) {
                totalSize.incrementAndGet()
            }
            
            accessTimes[keyString] = System.currentTimeMillis()
            
            logger.logCacheOperation("PUT", keyString, size = cache.size)
            logger.debug(TAG, "缓存存储成功: $keyString, TTL: ${effectiveTtl.inWholeMilliseconds}ms")
            
            Result.success(Unit)
            
        } catch (e: Exception) {
            errorCount.incrementAndGet()
            logger.error(TAG, "缓存存储失败: $keyString", mapOf("error" to e.message), e)
            Result.error(
                NymError.Unknown(
                    message = "缓存存储失败: $keyString",
                    cause = e
                )
            )
        }
    }

    override suspend fun remove(key: CacheKey): Result<Boolean> {
        val keyString = key.toString()
        
        return try {
            val removed = cache.remove(keyString) != null
            if (removed) {
                accessTimes.remove(keyString)
                totalSize.decrementAndGet()
                logger.logCacheOperation("REMOVE", keyString, size = cache.size)
            }
            
            Result.success(removed)
            
        } catch (e: Exception) {
            errorCount.incrementAndGet()
            logger.error(TAG, "缓存删除失败: $keyString", mapOf("error" to e.message), e)
            Result.error(
                NymError.Unknown(
                    message = "缓存删除失败: $keyString",
                    cause = e
                )
            )
        }
    }

    override suspend fun removeByPattern(pattern: String): Result<Int> {
        return try {
            val regex = pattern.replace("*", ".*").toRegex()
            val keysToRemove = cache.keys.filter { regex.matches(it) }
            
            var removedCount = 0
            keysToRemove.forEach { key ->
                if (cache.remove(key) != null) {
                    accessTimes.remove(key)
                    totalSize.decrementAndGet()
                    removedCount++
                }
            }
            
            logger.debug(TAG, "批量删除缓存: 模式=$pattern, 删除数量=$removedCount")
            Result.success(removedCount)
            
        } catch (e: Exception) {
            errorCount.incrementAndGet()
            logger.error(TAG, "批量删除缓存失败: $pattern", mapOf("error" to e.message), e)
            Result.error(
                NymError.Unknown(
                    message = "批量删除缓存失败: $pattern",
                    cause = e
                )
            )
        }
    }

    override suspend fun clear(): Result<Unit> {
        return try {
            val size = cache.size
            cache.clear()
            accessTimes.clear()
            totalSize.set(0)
            
            logger.info(TAG, "清空所有缓存", mapOf("clearedCount" to size.toString()))
            Result.success(Unit)
            
        } catch (e: Exception) {
            errorCount.incrementAndGet()
            logger.error(TAG, "清空缓存失败", mapOf("error" to e.message), e)
            Result.error(
                NymError.Unknown(
                    message = "清空缓存失败",
                    cause = e
                )
            )
        }
    }

    override fun getStats(): CacheStats {
        val totalReq = totalRequests.get()
        val hits = hitCount.get()
        val misses = missCount.get()
        
        return CacheStats(
            totalRequests = totalReq,
            hitCount = hits,
            missCount = misses,
            hitRate = if (totalReq > 0) hits.toDouble() / totalReq else 0.0,
            currentSize = cache.size,
            maxSize = config.maxSize,
            evictionCount = evictionCount.get(),
            errorCount = errorCount.get(),
            averageLoadTime = calculateAverageLoadTime(),
            memoryUsageEstimate = estimateMemoryUsage()
        )
    }

    override suspend fun exists(key: CacheKey): Boolean {
        val keyString = key.toString()
        val entry = cache[keyString] ?: return false
        
        if (entry.isExpired()) {
            cache.remove(keyString)
            accessTimes.remove(keyString)
            totalSize.decrementAndGet()
            return false
        }
        
        return true
    }

    override suspend fun getTtl(key: CacheKey): Duration? {
        val keyString = key.toString()
        val entry = cache[keyString] ?: return null
        
        if (entry.isExpired()) {
            return null
        }
        
        val elapsed = System.currentTimeMillis() - entry.createdAt
        val remaining = entry.ttl.inWholeMilliseconds - elapsed
        
        return if (remaining > 0) {
            remaining.milliseconds
        } else {
            null
        }
    }

    /**
     * 驱逐最少使用的缓存项 - Evict least recently used cache items
     */
    private fun evictLeastRecentlyUsed() {
        if (cache.isEmpty()) return
        
        val evictionCount = (config.maxSize * config.evictionRatio).toInt().coerceAtLeast(1)
        val sortedByAccess = accessTimes.entries.sortedBy { it.value }
        
        var evicted = 0
        for ((key, _) in sortedByAccess) {
            if (evicted >= evictionCount) break
            
            if (cache.remove(key) != null) {
                accessTimes.remove(key)
                totalSize.decrementAndGet()
                this.evictionCount.incrementAndGet()
                evicted++
            }
        }
        
        logger.debug(TAG, "LRU驱逐: 驱逐数量=$evicted, 当前大小=${cache.size}")
    }

    /**
     * 启动清理任务 - Start cleanup task
     */
    private fun startCleanupTask() {
        cleanupScope.launch {
            while (!currentCoroutineContext().job.isCancelled) {
                try {
                    cleanupExpiredEntries()
                    delay(config.cleanupInterval.inWholeMilliseconds)
                } catch (e: Exception) {
                    logger.error(TAG, "缓存清理任务异常", mapOf("error" to e.message), e)
                    delay(60000) // 出错时等待1分钟
                }
            }
        }
    }

    /**
     * 清理过期条目 - Clean up expired entries
     */
    private fun cleanupExpiredEntries() {
        val expiredKeys = mutableListOf<String>()
        val currentTime = System.currentTimeMillis()
        
        cache.forEach { (key, entry) ->
            if (entry.isExpired(currentTime)) {
                expiredKeys.add(key)
            }
        }
        
        var cleanedCount = 0
        expiredKeys.forEach { key ->
            if (cache.remove(key) != null) {
                accessTimes.remove(key)
                totalSize.decrementAndGet()
                cleanedCount++
            }
        }
        
        if (cleanedCount > 0) {
            logger.debug(TAG, "清理过期缓存: 清理数量=$cleanedCount, 当前大小=${cache.size}")
        }
    }

    /**
     * 计算平均加载时间 - Calculate average load time
     */
    private fun calculateAverageLoadTime(): Double {
        // 这里可以基于实际的加载时间统计来计算
        // 暂时返回一个估算值
        return if (hitCount.get() > 0) 5.0 else 0.0
    }

    /**
     * 估算内存使用量 - Estimate memory usage
     */
    private fun estimateMemoryUsage(): Long {
        var totalBytes = 0L
        
        cache.values.forEach { entry ->
            totalBytes += entry.data.length * 2 // 假设每个字符2字节（UTF-16）
            totalBytes += 64 // 对象开销估算
        }
        
        return totalBytes
    }

    /**
     * 关闭缓存管理器 - Shutdown cache manager
     */
    fun shutdown() {
        logger.info(TAG, "关闭缓存管理器")
        cleanupScope.cancel("CacheManager shutdown")
        runBlocking { clear() }
    }
}

/**
 * 缓存条目 - Cache entry
 */
private data class CacheEntry(
    val data: String,
    val createdAt: Long,
    val ttl: Duration,
    val accessCount: Long = 0
) {
    /**
     * 检查是否过期 - Check if expired
     */
    fun isExpired(currentTime: Long = System.currentTimeMillis()): Boolean {
        return (currentTime - createdAt) >= ttl.inWholeMilliseconds
    }
}

/**
 * 缓存配置 - Cache configuration
 */
@Serializable
data class CacheConfig(
    /** 最大缓存大小 */
    val maxSize: Int = 10000,
    
    /** 默认TTL */
    val defaultTtl: Duration = 1.hours,
    
    /** 清理间隔 */
    val cleanupInterval: Duration = 5.minutes,
    
    /** 驱逐比例（当缓存满时一次驱逐的比例） */
    val evictionRatio: Double = 0.1,
    
    /** 是否启用统计 */
    val enableStats: Boolean = true,
    
    /** 是否启用访问时间跟踪（用于LRU） */
    val enableAccessTimeTracking: Boolean = true
) {
    companion object {
        /**
         * 内存敏感配置 - Memory-sensitive configuration
         */
        fun memoryOptimized(): CacheConfig = CacheConfig(
            maxSize = 5000,
            defaultTtl = 30.minutes,
            cleanupInterval = 2.minutes,
            evictionRatio = 0.2
        )

        /**
         * 性能优先配置 - Performance-first configuration
         */
        fun performanceOptimized(): CacheConfig = CacheConfig(
            maxSize = 20000,
            defaultTtl = 2.hours,
            cleanupInterval = 10.minutes,
            evictionRatio = 0.05
        )

        /**
         * 开发环境配置 - Development configuration
         */
        fun forDevelopment(): CacheConfig = CacheConfig(
            maxSize = 1000,
            defaultTtl = 10.minutes,
            cleanupInterval = 1.minutes,
            evictionRatio = 0.2
        )
    }
}

/**
 * 缓存统计信息 - Cache statistics
 */
@Serializable
data class CacheStats(
    val totalRequests: Long,
    val hitCount: Long,
    val missCount: Long,
    val hitRate: Double,
    val currentSize: Int,
    val maxSize: Int,
    val evictionCount: Long,
    val errorCount: Long,
    val averageLoadTime: Double,
    val memoryUsageEstimate: Long
) {
    val missRate: Double get() = 1.0 - hitRate
    val utilizationRate: Double get() = currentSize.toDouble() / maxSize
}