package com.cw2.nym.ai.cache

import com.cw2.nym.ai.model.*
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * 缓存键 - Cache key
 * 
 * 用于唯一标识缓存项的键，支持多种生成策略
 */
@Serializable
data class CacheKey(
    /** 键类型 */
    val type: CacheKeyType,
    
    /** 主要标识符 */
    val identifier: String,
    
    /** 版本号（用于缓存版本控制） */
    val version: String = "1.0",
    
    /** 额外的上下文信息 */
    val context: Map<String, String> = emptyMap()
) {
    
    /**
     * 生成缓存键字符串
     * Generate cache key string
     */
    override fun toString(): String {
        val contextStr = if (context.isEmpty()) "" else context.entries.joinToString(";") { "${it.key}=${it.value}" }
        return "${type.prefix}:${version}:${identifier}${if (contextStr.isNotEmpty()) ":$contextStr" else ""}"
    }
    
    /**
     * 生成哈希形式的缓存键（用于长键）
     * Generate hash-based cache key (for long keys)
     */
    fun toHashKey(): String {
        val fullKey = toString()
        return if (fullKey.length > 250) {
            "${type.prefix}:${version}:${fullKey.sha256().take(32)}"
        } else {
            fullKey
        }
    }
    
    /**
     * 获取键的匹配模式（用于模式匹配删除）
     * Get key pattern for pattern matching deletion
     */
    fun toPattern(): String = "${type.prefix}:${version}:*"
    
    companion object {
        /**
         * 为命名建议创建缓存键
         * Create cache key for naming suggestions
         */
        fun forNaming(
            codeContext: CodeContext,
            language: String? = null,
            namingStyle: String? = null
        ): CacheKey {
            val contextHash = codeContext.generateContentHash()
            val contextMap = mutableMapOf<String, String>()
            
            language?.let { contextMap["lang"] = it }
            namingStyle?.let { contextMap["style"] = it }
            
            return CacheKey(
                type = CacheKeyType.NAMING,
                identifier = contextHash,
                context = contextMap
            )
        }
        
        /**
         * 为注释建议创建缓存键
         * Create cache key for comment suggestions
         */
        fun forComment(
            codeContext: CodeContext,
            language: String? = null,
            commentFormat: String? = null
        ): CacheKey {
            val contextHash = codeContext.generateContentHash()
            val contextMap = mutableMapOf<String, String>()
            
            language?.let { contextMap["lang"] = it }
            commentFormat?.let { contextMap["format"] = it }
            
            return CacheKey(
                type = CacheKeyType.COMMENT,
                identifier = contextHash,
                context = contextMap
            )
        }
        
        /**
         * 为自定义请求创建缓存键
         * Create cache key for custom requests
         */
        fun forCustom(
            prompt: String,
            codeContext: CodeContext? = null,
            model: String? = null
        ): CacheKey {
            val promptHash = prompt.sha256()
            val contextHash = codeContext?.generateContentHash() ?: ""
            val identifier = if (contextHash.isNotEmpty()) "${promptHash}_${contextHash}" else promptHash
            
            val contextMap = mutableMapOf<String, String>()
            model?.let { contextMap["model"] = it }
            
            return CacheKey(
                type = CacheKeyType.CUSTOM,
                identifier = identifier,
                context = contextMap
            )
        }
        
        /**
         * 为代码分析创建缓存键
         * Create cache key for code analysis
         */
        fun forAnalysis(
            filePath: String,
            fileHash: String,
            analysisType: String
        ): CacheKey {
            return CacheKey(
                type = CacheKeyType.ANALYSIS,
                identifier = "${filePath.sha256()}_${fileHash}",
                context = mapOf("type" to analysisType)
            )
        }
        
        /**
         * 为配置创建缓存键
         * Create cache key for configuration
         */
        fun forConfig(
            configType: String,
            configId: String
        ): CacheKey {
            return CacheKey(
                type = CacheKeyType.CONFIG,
                identifier = "${configType}_${configId}"
            )
        }
    }
}

/**
 * 缓存键类型 - Cache key type
 */
enum class CacheKeyType(val prefix: String) {
    /** 命名建议缓存 */
    NAMING("naming"),
    
    /** 注释建议缓存 */
    COMMENT("comment"),
    
    /** 自定义请求缓存 */
    CUSTOM("custom"),
    
    /** 代码分析缓存 */
    ANALYSIS("analysis"),
    
    /** 配置缓存 */
    CONFIG("config"),
    
    /** 用户偏好缓存 */
    PREFERENCE("pref"),
    
    /** 统计数据缓存 */
    STATS("stats")
}

/**
 * 缓存键生成策略 - Cache key generation strategy
 */
object CacheKeyStrategy {
    
    /**
     * 生成基于内容的缓存键
     * Generate content-based cache key
     */
    fun contentBased(content: String, keyType: CacheKeyType): CacheKey {
        val contentHash = content.sha256()
        return CacheKey(
            type = keyType,
            identifier = contentHash
        )
    }
    
    /**
     * 生成基于时间的缓存键
     * Generate time-based cache key
     */
    fun timeBased(
        baseKey: String,
        keyType: CacheKeyType,
        timeWindow: TimeWindow = TimeWindow.HOURLY
    ): CacheKey {
        val timestamp = when (timeWindow) {
            TimeWindow.MINUTELY -> System.currentTimeMillis() / (60 * 1000)
            TimeWindow.HOURLY -> System.currentTimeMillis() / (60 * 60 * 1000)
            TimeWindow.DAILY -> System.currentTimeMillis() / (24 * 60 * 60 * 1000)
        }
        
        return CacheKey(
            type = keyType,
            identifier = "${baseKey}_$timestamp"
        )
    }
    
    /**
     * 生成基于用户的缓存键
     * Generate user-based cache key
     */
    fun userBased(
        userId: String,
        baseKey: String,
        keyType: CacheKeyType
    ): CacheKey {
        return CacheKey(
            type = keyType,
            identifier = "${userId}_${baseKey.sha256()}"
        )
    }
    
    /**
     * 生成基于项目的缓存键
     * Generate project-based cache key
     */
    fun projectBased(
        projectId: String,
        baseKey: String,
        keyType: CacheKeyType
    ): CacheKey {
        return CacheKey(
            type = keyType,
            identifier = "${projectId}_${baseKey.sha256()}"
        )
    }
    
    /**
     * 生成层级缓存键
     * Generate hierarchical cache key
     */
    fun hierarchical(
        levels: List<String>,
        keyType: CacheKeyType
    ): CacheKey {
        val hierarchyHash = levels.joinToString("/").sha256()
        return CacheKey(
            type = keyType,
            identifier = hierarchyHash,
            context = mapOf("levels" to levels.size.toString())
        )
    }
}

/**
 * 时间窗口 - Time window
 */
enum class TimeWindow {
    MINUTELY,
    HOURLY,
    DAILY
}

/**
 * 生成内容哈希的扩展函数
 * Extension function to generate content hash
 */
private fun CodeContext.generateContentHash(): String {
    // 根据上下文类型生成不同的哈希
    val contentBuilder = StringBuilder()
    
    when (this) {
        is MethodContext -> {
            contentBuilder.append("method:")
            contentBuilder.append(methodName)
            contentBuilder.append(":")
            parameters.forEach { param ->
                contentBuilder.append(param.name).append(":").append(param.type.typeName).append(";")
            }
            contentBuilder.append("return:").append(returnType.typeName)
        }
        is ClassContext -> {
            contentBuilder.append("class:")
            contentBuilder.append(className)
            contentBuilder.append(":")
            superClass?.let { 
                contentBuilder.append("super:").append(it.typeName).append(";")
            }
            interfaces.forEach { 
                contentBuilder.append("impl:").append(it.typeName).append(";")
            }
        }
        is VariableContext -> {
            contentBuilder.append("var:")
            contentBuilder.append(variableName)
            contentBuilder.append(":")
            contentBuilder.append(variableType.typeName)
            contentBuilder.append(":")
            contentBuilder.append(scope.toString())
        }
        else -> {
            // 对于其他类型的上下文，使用toString()生成哈希
            contentBuilder.append(this.toString())
        }
    }
    
    return contentBuilder.toString().sha256()
}

/**
 * SHA256哈希扩展函数
 * SHA256 hash extension function
 */
private fun String.sha256(): String {
    val bytes = this.toByteArray()
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(bytes)
    return digest.fold("") { str, it -> str + "%02x".format(it) }
}