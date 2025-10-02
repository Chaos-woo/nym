package com.cw2.nym.ai.provider.openai

import com.cw2.nym.core.result.Result
import com.cw2.nym.core.exception.NymError
import com.cw2.nym.core.logging.NymLogger
import com.cw2.nym.core.serialization.toJson
import com.cw2.nym.core.serialization.fromJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonElement
import kotlinx.coroutines.withTimeout
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.URI
import java.time.Duration

/**
 * OpenAI API HTTP 客户端
 * 
 * 负责与 OpenAI API 的网络通信，包括请求发送、响应处理、错误处理等。
 * 支持重试机制、超时控制和详细的日志记录。
 */
class OpenAIHttpClient(
    private val config: OpenAIConfig
) {
    
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(config.timeoutMs))
        .build()
    
    /**
     * 发送请求到 OpenAI API
     * 
     * @param request 请求数据
     * @return API 响应结果
     */
    suspend fun sendRequest(request: OpenAIRequest): Result<OpenAIResponse> {
        return try {
            val startTime = System.currentTimeMillis()
            
            withTimeout(config.timeoutMs) {
                val httpRequest = buildHttpRequest(request)
                val httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
                
                val duration = System.currentTimeMillis() - startTime
                
                when (httpResponse.statusCode()) {
                    200 -> {
                        val response = parseSuccessResponse(httpResponse.body())
                        NymLogger.logAICall(
                            provider = "OpenAI",
                            model = config.model,
                            operation = "sendRequest",
                            success = true,
                            durationMs = duration,
                            tokenCount = response.getOrNull()?.usage?.totalTokens
                        )
                        response
                    }
                    400 -> {
                        NymLogger.logError("sendRequest", NymError.APIError.BadRequest())
                        Result.error(NymError.APIError.BadRequest("请求格式错误: ${httpResponse.body()}"))
                    }
                    401 -> {
                        NymLogger.logError("sendRequest", NymError.AuthenticationError.InvalidApiKey())
                        Result.error(NymError.AuthenticationError.InvalidApiKey())
                    }
                    429 -> {
                        val retryAfter = parseRetryAfter(httpResponse.headers().map())
                        NymLogger.logError("sendRequest", NymError.RateLimitError.TooManyRequests())
                        Result.error(NymError.RateLimitError.TooManyRequests(retryAfter = retryAfter))
                    }
                    500, 502, 503, 504 -> {
                        NymLogger.logError("sendRequest", NymError.APIError.ServerError())
                        Result.error(NymError.APIError.ServerError("服务器错误: ${httpResponse.statusCode()}"))
                    }
                    else -> {
                        val error = NymError.APIError.ServerError("未知错误: ${httpResponse.statusCode()}")
                        NymLogger.logError("sendRequest", error)
                        Result.error(error)
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            val error = NymError.NetworkError.ReadTimeout("请求超时")
            NymLogger.logError("sendRequest", error)
            Result.error(error)
        } catch (e: Exception) {
            val error = NymError.NetworkError.Generic("网络请求失败: ${e.message}")
            NymLogger.logError("sendRequest", error, context = mapOf("exception" to e.message))
            Result.error(error)
        }
    }
    
    /**
     * 构建 HTTP 请求
     */
    private fun buildHttpRequest(request: OpenAIRequest): HttpRequest {
        val jsonBody = request.toJson().getOrNull() ?: throw IllegalArgumentException("无法序列化请求")
        
        return HttpRequest.newBuilder()
            .uri(URI.create("${config.apiUrl}/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("User-Agent", "nym-intellij-plugin/1.0")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .timeout(Duration.ofMillis(config.timeoutMs))
            .build()
    }
    
    /**
     * 解析成功响应
     */
    private fun parseSuccessResponse(body: String): Result<OpenAIResponse> {
        return try {
            body.fromJson<OpenAIResponse>()
        } catch (e: Exception) {
            val error = NymError.ParseError.JsonParse("解析响应失败: ${e.message}")
            NymLogger.logError("parseSuccessResponse", error, context = mapOf("exception" to e.message))
            Result.error(error)
        }
    }
    
    /**
     * 同步发送请求到 OpenAI API（用于后台线程调用，避免 runBlocking 导致的线程模型违规）
     * 
     * @param request 请求数据
     * @return API 响应结果
     */
    fun sendRequestSync(request: OpenAIRequest): Result<OpenAIResponse> {
        return try {
            val startTime = System.currentTimeMillis()
            
            val httpRequest = buildHttpRequest(request)
            val httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            
            val duration = System.currentTimeMillis() - startTime
            
            when (httpResponse.statusCode()) {
                200 -> {
                    val response = parseSuccessResponse(httpResponse.body())
                    NymLogger.logAICall(
                        provider = "OpenAI",
                        model = config.model,
                        operation = "sendRequestSync",
                        success = true,
                        durationMs = duration,
                        tokenCount = response.getOrNull()?.usage?.totalTokens
                    )
                    response
                }
                400 -> {
                    NymLogger.logError("sendRequestSync", NymError.APIError.BadRequest())
                    Result.error(NymError.APIError.BadRequest("请求格式错误: ${httpResponse.body()}"))
                }
                401 -> {
                    NymLogger.logError("sendRequestSync", NymError.AuthenticationError.InvalidApiKey())
                    Result.error(NymError.AuthenticationError.InvalidApiKey())
                }
                429 -> {
                    val retryAfter = parseRetryAfter(httpResponse.headers().map())
                    NymLogger.logError("sendRequestSync", NymError.RateLimitError.TooManyRequests())
                    Result.error(NymError.RateLimitError.TooManyRequests(retryAfter = retryAfter))
                }
                500, 502, 503, 504 -> {
                    NymLogger.logError("sendRequestSync", NymError.APIError.ServerError())
                    Result.error(NymError.APIError.ServerError("服务器错误: ${httpResponse.statusCode()}"))
                }
                else -> {
                    val error = NymError.APIError.ServerError("未知错误: ${httpResponse.statusCode()}")
                    NymLogger.logError("sendRequestSync", error)
                    Result.error(error)
                }
            }
        } catch (e: java.net.http.HttpTimeoutException) {
            val error = NymError.NetworkError.ReadTimeout("请求超时")
            NymLogger.logError("sendRequestSync", error)
            Result.error(error)
        } catch (e: Exception) {
            val error = NymError.NetworkError.Generic("网络请求失败: ${e.message}")
            NymLogger.logError("sendRequestSync", error, context = mapOf("exception" to e.message))
            Result.error(error)
        }
    }
    
    /**
     * 解析 Retry-After 头部
     */
    private fun parseRetryAfter(headers: Map<String, List<String>>): Long? {
        return headers["retry-after"]?.firstOrNull()?.toLongOrNull()?.times(1000)
    }
}

/**
 * OpenAI API 请求数据结构
 */
@Serializable
data class OpenAIRequest(
    val model: String,
    val messages: List<OpenAIMessage>,
    val maxTokens: Int? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val n: Int = 1,
    val stop: List<String>? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    val user: String? = null
)

/**
 * OpenAI API 工具调用
 */
@Serializable
data class ToolCall(
    val id: String,
    val type: String, // "function"
    val function: Function
)

/**
 * OpenAI API 函数调用
 */
@Serializable
data class Function(
    val name: String,
    val arguments: String
)

/**
 * OpenAI API 消息结构
 */
@Serializable
data class OpenAIMessage(
    val role: String, // "system", "user", "assistant"
    val content: String,
    @SerialName("reasoning_content")
    val reasoningContent: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ToolCall>? = null
)

/**
 * OpenAI API 响应数据结构
 */
@Serializable
data class OpenAIResponse(
    val id: String,
    val `object`: String,
    val created: Long,
    val model: String,
    val choices: List<OpenAIChoice>,
    val usage: OpenAIUsage? = null
)

/**
 * OpenAI API 选择结果
 */
@Serializable
data class OpenAIChoice(
    val index: Int,
    val message: OpenAIMessage,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

/**
 * OpenAI API 使用统计
 */
@Serializable
data class OpenAIUsage(
    @SerialName("prompt_tokens")
    val promptTokens: Int,
    @SerialName("completion_tokens")
    val completionTokens: Int,
    @SerialName("total_tokens")
    val totalTokens: Int
)

/**
 * OpenAI API 错误响应
 */
@Serializable
data class OpenAIErrorResponse(
    val error: OpenAIError
)

/**
 * OpenAI API 错误详情
 */
@Serializable
data class OpenAIError(
    val message: String,
    val type: String? = null,
    val param: String? = null,
    val code: String? = null
)