package com.cw2.nym.core.time

import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*

/**
 * 自定义超时异常 - Custom timeout exception
 */
class NymTimeoutException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 时间工具类 - Time utility class
 * 
 * 提供时间格式化、超时控制、性能计时和时间缓存键生成功能
 * Provides time formatting, timeout control, performance timing, and time cache key generation
 */
object TimeUtils {
    
    // 默认时区 - Default timezone
    private val defaultZone: ZoneId = ZoneId.systemDefault()
    
    // 常用日期时间格式 - Common datetime formats
    private val ISO_DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private val READABLE_DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val COMPACT_DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    
    /**
     * 获取当前时间戳（毫秒） - Get current timestamp in milliseconds
     */
    fun currentTimeMillis(): Long = System.currentTimeMillis()
    
    /**
     * 获取当前时间戳（秒） - Get current timestamp in seconds
     */
    fun currentTimeSeconds(): Long = System.currentTimeMillis() / 1000
    
    /**
     * 获取当前 Instant - Get current Instant
     */
    fun now(): Instant = Instant.now()
    
    /**
     * 获取当前 LocalDateTime - Get current LocalDateTime
     */
    fun nowLocal(): LocalDateTime = LocalDateTime.now(defaultZone)
    
    /**
     * 格式化为 ISO 8601 格式 - Format to ISO 8601 format
     */
    fun formatISO(instant: Instant): String = 
        instant.atZone(defaultZone).format(ISO_DATETIME_FORMATTER)
    
    /**
     * 格式化为可读格式 - Format to readable format
     */
    fun formatReadable(instant: Instant): String = 
        instant.atZone(defaultZone).format(READABLE_DATETIME_FORMATTER)
    
    /**
     * 格式化为日期 - Format to date only
     */
    fun formatDate(instant: Instant): String = 
        instant.atZone(defaultZone).format(DATE_FORMATTER)
    
    /**
     * 格式化为时间 - Format to time only
     */
    fun formatTime(instant: Instant): String = 
        instant.atZone(defaultZone).format(TIME_FORMATTER)
    
    /**
     * 格式化为紧凑格式 - Format to compact format
     */
    fun formatCompact(instant: Instant): String = 
        instant.atZone(defaultZone).format(COMPACT_DATETIME_FORMATTER)
    
    /**
     * 解析 ISO 8601 格式时间 - Parse ISO 8601 format time
     */
    fun parseISO(dateTimeString: String): Instant? = try {
        ZonedDateTime.parse(dateTimeString, ISO_DATETIME_FORMATTER).toInstant()
    } catch (e: DateTimeParseException) {
        null
    }
    
    /**
     * 解析可读格式时间 - Parse readable format time
     */
    fun parseReadable(dateTimeString: String): LocalDateTime? = try {
        LocalDateTime.parse(dateTimeString, READABLE_DATETIME_FORMATTER)
    } catch (e: DateTimeParseException) {
        null
    }
    
    /**
     * 格式化持续时间 - Format duration
     */
    fun formatDuration(durationMs: Long): String {
        val duration = Duration.ofMillis(durationMs)
        
        return when {
            duration.toDays() > 0 -> "${duration.toDays()}天${duration.toHours() % 24}小时"
            duration.toHours() > 0 -> "${duration.toHours()}小时${duration.toMinutes() % 60}分钟"
            duration.toMinutes() > 0 -> "${duration.toMinutes()}分钟${duration.seconds % 60}秒"
            duration.seconds > 0 -> "${duration.seconds}秒${duration.toMillis() % 1000}毫秒"
            else -> "${duration.toMillis()}毫秒"
        }
    }
    
    /**
     * 格式化简短持续时间 - Format short duration
     */
    fun formatDurationShort(durationMs: Long): String {
        return when {
            durationMs >= 86400000 -> "${durationMs / 86400000}d"
            durationMs >= 3600000 -> "${durationMs / 3600000}h"
            durationMs >= 60000 -> "${durationMs / 60000}m"
            durationMs >= 1000 -> "${durationMs / 1000}s"
            else -> "${durationMs}ms"
        }
    }
    
    /**
     * 生成基于时间的缓存键 - Generate time-based cache key
     */
    fun generateCacheKey(prefix: String, ttlMinutes: Int = 60): String {
        val timeSlot = currentTimeMillis() / (ttlMinutes * 60 * 1000) // 时间槽，确保在TTL时间内键相同
        return "${prefix}_${timeSlot}"
    }
    
    /**
     * 生成基于日期的缓存键 - Generate date-based cache key
     */
    fun generateDailyCacheKey(prefix: String): String {
        val date = formatDate(now())
        return "${prefix}_${date}"
    }
    
    /**
     * 生成基于小时的缓存键 - Generate hour-based cache key
     */
    fun generateHourlyCacheKey(prefix: String): String {
        val hour = nowLocal().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH"))
        return "${prefix}_${hour}"
    }
    
    /**
     * 检查时间是否过期 - Check if time is expired
     */
    fun isExpired(timestamp: Long, ttlMs: Long): Boolean {
        return currentTimeMillis() - timestamp > ttlMs
    }
    
    /**
     * 检查时间戳是否在指定范围内 - Check if timestamp is within range
     */
    fun isWithinRange(timestamp: Long, startMs: Long, endMs: Long): Boolean {
        return timestamp in startMs..endMs
    }
    
    /**
     * 计算两个时间戳的差值 - Calculate difference between two timestamps
     */
    fun timeDiff(startMs: Long, endMs: Long = currentTimeMillis()): Long {
        return endMs - startMs
    }
    
    /**
     * 性能计时器 - Performance timer
     */
    class PerformanceTimer(private val name: String = "Timer") {
        private val startTime: Long = System.nanoTime()
        private var lastCheckpoint: Long = startTime
        private val checkpoints = mutableListOf<Pair<String, Long>>()
        
        /**
         * 添加检查点 - Add checkpoint
         */
        fun checkpoint(label: String): PerformanceTimer {
            val now = System.nanoTime()
            val elapsed = TimeUnit.NANOSECONDS.toMillis(now - lastCheckpoint)
            checkpoints.add(label to elapsed)
            lastCheckpoint = now
            return this
        }
        
        /**
         * 完成计时并返回总耗时 - Finish timing and return total duration
         */
        fun finish(): Long {
            val totalTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)
            return totalTime
        }
        
        /**
         * 完成计时并返回详细报告 - Finish timing and return detailed report
         */
        fun finishWithReport(): TimingReport {
            val totalTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)
            return TimingReport(name, totalTime, checkpoints.toList())
        }
        
        /**
         * 获取当前已用时间 - Get current elapsed time
         */
        fun elapsed(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)
    }
    
    /**
     * 计时报告 - Timing report
     */
    data class TimingReport(
        val name: String,
        val totalTimeMs: Long,
        val checkpoints: List<Pair<String, Long>>
    ) {
        fun summary(): String {
            val sb = StringBuilder()
            sb.appendLine("计时报告: $name")
            sb.appendLine("总耗时: ${formatDuration(totalTimeMs)}")
            if (checkpoints.isNotEmpty()) {
                sb.appendLine("检查点:")
                checkpoints.forEach { (label, duration) ->
                    sb.appendLine("  $label: ${formatDuration(duration)}")
                }
            }
            return sb.toString()
        }
        
        fun shortSummary(): String {
            return "$name: ${formatDurationShort(totalTimeMs)}" +
                    if (checkpoints.isNotEmpty()) " (${checkpoints.size} checkpoints)" else ""
        }
    }
    
    /**
     * 创建性能计时器 - Create performance timer
     */
    fun createTimer(name: String = "Timer"): PerformanceTimer = PerformanceTimer(name)
    
    /**
     * 超时控制器 - Timeout controller
     */
    class TimeoutController(private val timeoutMs: Long) {
        private val startTime = currentTimeMillis()
        
        /**
         * 检查是否超时 - Check if timeout
         */
        fun isTimeout(): Boolean = currentTimeMillis() - startTime > timeoutMs
        
        /**
         * 获取剩余时间 - Get remaining time
         */
        fun remainingTime(): Long = maxOf(0, timeoutMs - (currentTimeMillis() - startTime))
        
        /**
         * 获取已用时间 - Get elapsed time
         */
        fun elapsedTime(): Long = currentTimeMillis() - startTime
        
        /**
         * 抛出超时异常（如果超时） - Throw timeout exception if timeout
         */
        fun checkTimeout(message: String = "操作超时") {
            if (isTimeout()) {
                throw NymTimeoutException(message)
            }
        }
    }
    
    /**
     * 创建超时控制器 - Create timeout controller
     */
    fun createTimeoutController(timeoutMs: Long): TimeoutController = TimeoutController(timeoutMs)
    
    /**
     * 带超时的协程执行 - Execute coroutine with timeout
     */
    suspend fun <T> withTimeout(timeoutMs: Long, block: suspend CoroutineScope.() -> T): T {
        return withTimeout(timeoutMs) { block() }
    }
    
    /**
     * 带超时的可空协程执行 - Execute nullable coroutine with timeout
     */
    suspend fun <T> withTimeoutOrNull(timeoutMs: Long, block: suspend CoroutineScope.() -> T): T? {
        return withTimeoutOrNull(timeoutMs) { block() }
    }
    
    /**
     * 延迟执行 - Delayed execution
     */
    suspend fun delay(delayMs: Long) {
        delay(delayMs)
    }
    
    /**
     * 指数退避延迟 - Exponential backoff delay
     */
    suspend fun exponentialBackoffDelay(attempt: Int, baseDelayMs: Long = 1000, maxDelayMs: Long = 30000) {
        val delayMs = minOf(baseDelayMs * (1L shl attempt), maxDelayMs)
        delay(delayMs)
    }
    
    /**
     * 抖动延迟 - Jittered delay
     */
    suspend fun jitteredDelay(delayMs: Long, jitterRange: Double = 0.1) {
        val jitter = (Math.random() - 0.5) * 2 * jitterRange * delayMs
        val actualDelay = maxOf(0, delayMs + jitter.toLong())
        delay(actualDelay)
    }
    
    /**
     * 时区转换工具 - Timezone conversion utilities
     */
    object TimezoneUtils {
        /**
         * 转换到 UTC - Convert to UTC
         */
        fun toUTC(localDateTime: LocalDateTime): Instant {
            return localDateTime.atZone(defaultZone).toInstant()
        }
        
        /**
         * 从 UTC 转换到本地时间 - Convert from UTC to local time
         */
        fun fromUTC(instant: Instant): LocalDateTime {
            return instant.atZone(defaultZone).toLocalDateTime()
        }
        
        /**
         * 转换时区 - Convert timezone
         */
        fun convertTimezone(instant: Instant, fromZone: ZoneId, toZone: ZoneId): Instant {
            // Instant 本身是时区无关的，这里主要用于显示转换
            return instant
        }
        
        /**
         * 获取时区偏移 - Get timezone offset
         */
        fun getTimezoneOffset(zoneId: ZoneId = defaultZone): String {
            val offset = zoneId.rules.getOffset(Instant.now())
            return offset.toString()
        }
    }
}

/**
 * 扩展函数：为 Long 添加时间格式化功能 - Extension function: add time formatting to Long
 */
fun Long.formatAsTime(): String = TimeUtils.formatDuration(this)

/**
 * 扩展函数：为 Long 添加短时间格式化功能 - Extension function: add short time formatting to Long
 */
fun Long.formatAsTimeShort(): String = TimeUtils.formatDurationShort(this)

/**
 * 扩展函数：检查时间戳是否过期 - Extension function: check if timestamp is expired
 */
fun Long.isExpired(ttlMs: Long): Boolean = TimeUtils.isExpired(this, ttlMs)

/**
 * 扩展函数：计算与当前时间的差值 - Extension function: calculate diff with current time
 */
fun Long.timeAgo(): Long = TimeUtils.timeDiff(this)

/**
 * 扩展函数：为代码块添加计时功能 - Extension function: add timing to code block
 */
inline fun <T> timed(name: String = "Operation", block: () -> T): Pair<T, Long> {
    val timer = TimeUtils.createTimer(name)
    val result = block()
    val duration = timer.finish()
    return result to duration
}