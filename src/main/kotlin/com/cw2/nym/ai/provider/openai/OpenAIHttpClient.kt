package com.cw2.nym.ai.provider.openai

import com.cw2.nym.core.result.Result
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OpenAI API HTTP 客户端
 *
 * 负责与 OpenAI API 的网络通信，包括请求发送、响应处理、错误处理等。
 * 支持重试机制、超时控制和详细的日志记录。
 */
class OpenAIHttpClient(
    private val config: OpenAIConfig
) {

    /**
     * 发送请求到 OpenAI API
     *
     * @param request 请求数据
     * @return API 响应结果
     */
    fun sendRequest(request: OpenAIRequest): Result<OpenAIResponse> {
        throw UnsupportedOperationException("Use Custom endpoint")
    }

    /**
     * 同步发送请求到 OpenAI API（用于后台线程调用，避免 runBlocking 导致的线程模型违规）
     *
     * @param request 请求数据
     * @return API 响应结果
     */
    fun sendRequestSync(request: OpenAIRequest): Result<OpenAIResponse> {
        throw UnsupportedOperationException("Use Custom endpoint")
    }
}

/**
 * OpenAI API 请求数据结构
 */
@Serializable
data class OpenAIRequest(
    val model: String,
    val messages: List<OpenAIMessage>,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val n: Int = 1,
    val stop: List<String>? = null,
    @SerialName("presence_penalty")
    val presencePenalty: Double? = null,
    @SerialName("frequency_penalty")
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