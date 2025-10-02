package com.cw2.nym.core.serialization

import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import com.cw2.nym.core.exception.NymError
import com.cw2.nym.core.result.Result

/**
 * JSON 序列化配置 - JSON serialization configuration
 * 
 * 配置 Kotlinx Serialization，提供自定义序列化器和异常处理
 * Configure Kotlinx Serialization with custom serializers and exception handling
 */
object JsonConfig {
    
    /**
     * 主要的 JSON 配置 - Main JSON configuration
     * 用于大多数场景的序列化和反序列化
     * Used for serialization and deserialization in most scenarios
     */
    val json = Json {
        // 允许结构化并发 - Allow structured concurrency
        ignoreUnknownKeys = true
        // 美化输出 - Pretty print
        prettyPrint = false
        // 使用默认值 - Use default values
        encodeDefaults = true
        // 允许特殊浮点值 - Allow special floating point values
        allowSpecialFloatingPointValues = true
        // 允许结构化键 - Allow structured map keys
        allowStructuredMapKeys = true
        // 宽松模式 - Lenient mode
        isLenient = true
        // 使用数组多态性 - Use array polymorphism
        useArrayPolymorphism = false
        // 类鉴别器 - Class discriminator
        classDiscriminator = "type"
        // 强制引用 - Coerce input values
        coerceInputValues = true
        // 解码时使用默认值 - Use defaults on null
        explicitNulls = false
    }
    
    /**
     * 紧凑的 JSON 配置 - Compact JSON configuration
     * 用于需要最小化输出大小的场景，如网络传输
     * Used for scenarios requiring minimal output size, such as network transmission
     */
    val compactJson = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = false
        isLenient = false
        coerceInputValues = true
        explicitNulls = false
    }
    
    /**
     * 调试用的 JSON 配置 - Debug JSON configuration
     * 用于调试和开发环境，提供详细的格式化输出
     * Used for debugging and development environment with detailed formatted output
     */
    val debugJson = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        prettyPrintIndent = "  "
    }
    
    /**
     * 严格的 JSON 配置 - Strict JSON configuration
     * 用于需要严格验证的场景，如配置文件解析
     * Used for scenarios requiring strict validation, such as configuration file parsing
     */
    val strictJson = Json {
        ignoreUnknownKeys = false
        prettyPrint = false
        encodeDefaults = true
        isLenient = false
        coerceInputValues = false
        explicitNulls = true
    }
}

/**
 * Instant 序列化器 - Instant serializer
 * 将 Instant 序列化为 ISO 8601 格式的字符串
 * Serialize Instant to ISO 8601 format string
 */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    
    private val formatter = DateTimeFormatter.ISO_INSTANT
    
    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(formatter.format(value))
    }
    
    override fun deserialize(decoder: Decoder): Instant {
        val string = decoder.decodeString()
        return try {
            Instant.from(formatter.parse(string))
        } catch (e: DateTimeParseException) {
            throw SerializationException("无法解析 Instant: $string", e)
        }
    }
}

/**
 * LocalDateTime 序列化器 - LocalDateTime serializer
 * 将 LocalDateTime 序列化为可读的日期时间格式
 * Serialize LocalDateTime to readable datetime format
 */
object LocalDateTimeSerializer : KSerializer<LocalDateTime> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LocalDateTime", PrimitiveKind.STRING)
    
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    
    override fun serialize(encoder: Encoder, value: LocalDateTime) {
        encoder.encodeString(value.format(formatter))
    }
    
    override fun deserialize(decoder: Decoder): LocalDateTime {
        val string = decoder.decodeString()
        return try {
            LocalDateTime.parse(string, formatter)
        } catch (e: DateTimeParseException) {
            throw SerializationException("无法解析 LocalDateTime: $string", e)
        }
    }
}

/**
 * Result 序列化器 - Result serializer
 * 将 Result 类型序列化为 JSON 对象，包含成功数据或错误信息
 * Serialize Result type to JSON object containing success data or error information
 */
class ResultSerializer<T>(private val dataSerializer: KSerializer<T>) : KSerializer<Result<T>> {
    
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Result") {
        element<Boolean>("success")
        element("data", dataSerializer.descriptor, isOptional = true)
        element<String>("error", isOptional = true)
        element<String>("errorType", isOptional = true)
    }
    
    override fun serialize(encoder: Encoder, value: Result<T>) {
        val compositeOutput = encoder.beginStructure(descriptor)
        when (value) {
            is Result.Success -> {
                compositeOutput.encodeBooleanElement(descriptor, 0, true)
                compositeOutput.encodeSerializableElement(descriptor, 1, dataSerializer, value.data)
            }
            is Result.Error -> {
                compositeOutput.encodeBooleanElement(descriptor, 0, false)
                compositeOutput.encodeStringElement(descriptor, 2, value.error.message)
                compositeOutput.encodeStringElement(descriptor, 3, value.error::class.simpleName ?: "Unknown")
            }
        }
        compositeOutput.endStructure(descriptor)
    }
    
    override fun deserialize(decoder: Decoder): Result<T> {
        val compositeInput = decoder.beginStructure(descriptor)
        
        var success: Boolean? = null
        var data: T? = null
        var errorMessage: String? = null
        var errorType: String? = null
        
        while (true) {
            when (val index = compositeInput.decodeElementIndex(descriptor)) {
                0 -> success = compositeInput.decodeBooleanElement(descriptor, index)
                1 -> data = compositeInput.decodeSerializableElement(descriptor, index, dataSerializer)
                2 -> errorMessage = compositeInput.decodeStringElement(descriptor, index)
                3 -> errorType = compositeInput.decodeStringElement(descriptor, index)
                CompositeDecoder.DECODE_DONE -> break
                else -> throw SerializationException("未知字段索引: $index")
            }
        }
        compositeInput.endStructure(descriptor)
        
        return when (success) {
            true -> data?.let { Result.Success(it) } 
                ?: throw SerializationException("成功结果缺少数据字段")
            false -> {
                val error = when (errorType) {
                    "ParseError" -> NymError.ParseError.JsonParse(
                        errorMessage ?: "JSON解析失败"
                    )
                    else -> NymError.Unknown(errorMessage ?: "未知错误")
                }
                Result.Error(error)
            }
            null -> throw SerializationException("缺少 success 字段")
        }
    }
}

/**
 * JSON 工具类 - JSON utility class
 * 提供便捷的序列化和反序列化方法
 * Provide convenient serialization and deserialization methods
 */
object JsonUtils {
    
    /**
     * 将对象序列化为 JSON 字符串 - Serialize object to JSON string
     */
    inline fun <reified T> toJson(value: T, config: Json = JsonConfig.json): Result<String> = try {
        Result.Success(config.encodeToString(value))
    } catch (e: SerializationException) {
        Result.Error(NymError.ParseError.JsonParse("序列化失败: ${e.message}", e))
    } catch (e: Exception) {
        Result.Error(NymError.Unknown("序列化过程中发生未知错误: ${e.message}", e))
    }
    
    /**
     * 从 JSON 字符串反序列化对象 - Deserialize object from JSON string
     */
    inline fun <reified T> fromJson(json: String, config: Json = JsonConfig.json): Result<T> = try {
        Result.Success(config.decodeFromString<T>(json))
    } catch (e: SerializationException) {
        Result.Error(NymError.ParseError.JsonParse("反序列化失败: ${e.message}", e))
    } catch (e: IllegalArgumentException) {
        Result.Error(NymError.ParseError.InvalidResponse("JSON 格式不正确: ${e.message}", e))
    } catch (e: Exception) {
        Result.Error(NymError.Unknown("反序列化过程中发生未知错误: ${e.message}", e))
    }
    
    /**
     * 安全地序列化对象，返回可空字符串 - Safely serialize object, return nullable string
     */
    inline fun <reified T> toJsonOrNull(value: T, config: Json = JsonConfig.json): String? = 
        toJson(value, config).getOrNull()
    
    /**
     * 安全地反序列化对象，返回可空对象 - Safely deserialize object, return nullable object
     */
    inline fun <reified T> fromJsonOrNull(json: String, config: Json = JsonConfig.json): T? = 
        fromJson<T>(json, config).getOrNull()
    
    /**
     * 格式化 JSON 字符串 - Format JSON string
     */
    fun formatJson(json: String): Result<String> = try {
        val element = JsonConfig.json.parseToJsonElement(json)
        Result.Success(JsonConfig.debugJson.encodeToString(JsonElement.serializer(), element))
    } catch (e: Exception) {
        Result.Error(NymError.ParseError.JsonParse("JSON 格式化失败: ${e.message}", e))
    }
    
    /**
     * 压缩 JSON 字符串 - Compress JSON string
     */
    fun compactJson(json: String): Result<String> = try {
        val element = JsonConfig.json.parseToJsonElement(json)
        Result.Success(JsonConfig.compactJson.encodeToString(JsonElement.serializer(), element))
    } catch (e: Exception) {
        Result.Error(NymError.ParseError.JsonParse("JSON 压缩失败: ${e.message}", e))
    }
    
    /**
     * 验证 JSON 字符串格式 - Validate JSON string format
     */
    fun validateJson(json: String): Result<JsonElement> = try {
        val element = JsonConfig.json.parseToJsonElement(json)
        Result.Success(element)
    } catch (e: Exception) {
        Result.Error(NymError.ParseError.JsonParse("JSON 验证失败: ${e.message}", e))
    }
    
    /**
     * 合并两个 JSON 对象 - Merge two JSON objects
     */
    fun mergeJsonObjects(json1: String, json2: String): Result<String> = try {
        val obj1 = JsonConfig.json.parseToJsonElement(json1).jsonObject
        val obj2 = JsonConfig.json.parseToJsonElement(json2).jsonObject
        
        val merged = buildJsonObject {
            obj1.forEach { (key, value) -> put(key, value) }
            obj2.forEach { (key, value) -> put(key, value) }
        }
        
        Result.Success(JsonConfig.json.encodeToString(JsonElement.serializer(), merged))
    } catch (e: Exception) {
        Result.Error(NymError.ParseError.JsonParse("JSON 合并失败: ${e.message}", e))
    }
    
    /**
     * 从 JSON 对象中提取指定路径的值 - Extract value from JSON object by path
     */
    fun extractJsonPath(json: String, path: String): Result<JsonElement> = try {
        var current = JsonConfig.json.parseToJsonElement(json)
        val parts = path.split(".")
        
        for (part in parts) {
            current = when {
                current is JsonObject && current.containsKey(part) -> current[part]!!
                current is JsonArray && part.toIntOrNull() != null -> {
                    val index = part.toInt()
                    if (index >= 0 && index < current.size) current[index]
                    else throw IllegalArgumentException("数组索引超出范围: $index")
                }
                else -> throw IllegalArgumentException("路径不存在: $part")
            }
        }
        
        Result.Success(current)
    } catch (e: Exception) {
        Result.Error(NymError.ParseError.JsonParse("JSON 路径提取失败: ${e.message}", e))
    }
}

/**
 * 扩展函数：为任意对象添加 JSON 序列化功能 - Extension function: add JSON serialization to any object
 */
inline fun <reified T> T.toJson(config: Json = JsonConfig.json): Result<String> = 
    JsonUtils.toJson(this, config)

/**
 * 扩展函数：为字符串添加 JSON 反序列化功能 - Extension function: add JSON deserialization to String
 */
inline fun <reified T> String.fromJson(config: Json = JsonConfig.json): Result<T> = 
    JsonUtils.fromJson(this, config)

/**
 * 扩展函数：安全的 JSON 序列化 - Extension function: safe JSON serialization
 */
inline fun <reified T> T.toJsonOrNull(config: Json = JsonConfig.json): String? = 
    JsonUtils.toJsonOrNull(this, config)

/**
 * 扩展函数：安全的 JSON 反序列化 - Extension function: safe JSON deserialization
 */
inline fun <reified T> String.fromJsonOrNull(config: Json = JsonConfig.json): T? = 
    JsonUtils.fromJsonOrNull<T>(this, config)