package com.cw2.nym.ai.provider.openai

import com.cw2.nym.ai.provider.AIProvider
import com.cw2.nym.ai.provider.AIProviderConfig
import com.cw2.nym.ai.provider.AIProviderStatus
import com.cw2.nym.ai.model.*
import com.cw2.nym.core.result.Result
import com.cw2.nym.core.exception.NymError
import com.cw2.nym.core.logging.NymLogger
import kotlinx.serialization.Serializable
import kotlinx.coroutines.withTimeout

/**
 * OpenAI API 服务提供商实现
 * 
 * 实现了与 OpenAI API 兼容接口的通信，支持代码命名建议、注释生成和自定义生成功能。
 * 包含完整的错误处理、重试机制和性能监控。
 */
class OpenAIProvider(
    override val config: OpenAIConfig
) : AIProvider {
    
    override val name = "OpenAI"
    
    private val httpClient by lazy { OpenAIHttpClient(config) }
    private val promptTemplates by lazy { OpenAIPromptTemplates() }
    
    /**
     * 生成代码命名建议
     */
    override suspend fun generateNaming(context: CodeContext): Result<List<NamingSuggestion>> {
        return try {
            NymLogger.logAICall(name, config.model, "generateNaming", true, 0)
            
            val prompt = promptTemplates.createNamingPrompt(context, config.model)
            val response = httpClient.sendRequest(prompt)
            
            response.flatMap { openAIResponse ->
                OpenAIResponseParser.parseNamingResponse(openAIResponse, context)
            }
            
        } catch (e: Exception) {
            NymLogger.logError("generateNaming", NymError.APIError.ServerError(), mapOf("provider" to name))
            Result.error(NymError.APIError.ServerError())
        }
    }
    
    override suspend fun generateComment(context: CodeContext): Result<CommentSuggestion> {
        return try {
            val startTime = System.currentTimeMillis()
            
            val prompt = promptTemplates.createCommentPrompt(context, config.model)
            val response = httpClient.sendRequest(prompt)
            
            val duration = System.currentTimeMillis() - startTime
            
            response.flatMap { openAIResponse ->
                NymLogger.logAICall(name, config.model, "generateComment", true, duration, 
                    tokenCount = openAIResponse.usage?.totalTokens)
                OpenAIResponseParser.parseCommentResponse(openAIResponse, context)
            }.onError { error ->
                NymLogger.logError("generateComment", error, mapOf("provider" to name))
            }
            
        } catch (e: Exception) {
            val error = NymError.APIError.ServerError("注释生成失败: ${e.message}")
            NymLogger.logError("generateComment", error, mapOf("provider" to name, "exception" to e.message))
            Result.error(error)
        }
    }
    
    override suspend fun generateCustom(prompt: String, context: CodeContext?): Result<String> {
        return try {
            val startTime = System.currentTimeMillis()
            
            val request = promptTemplates.createCustomPrompt(prompt, context, config.model)
            val response = httpClient.sendRequest(request)
            
            val duration = System.currentTimeMillis() - startTime
            
            response.flatMap { openAIResponse ->
                NymLogger.logAICall(name, config.model, "generateCustom", true, duration,
                    tokenCount = openAIResponse.usage?.totalTokens)
                OpenAIResponseParser.parseCustomResponse(openAIResponse)
            }.onError { error ->
                NymLogger.logError("generateCustom", error, mapOf("provider" to name))
            }
            
        } catch (e: Exception) {
            val error = NymError.APIError.ServerError("自定义生成失败: ${e.message}")
            NymLogger.logError("generateCustom", error, mapOf("provider" to name, "exception" to e.message))
            Result.error(error)
        }
    }
    
    override suspend fun isAvailable(): Result<Boolean> {
        return try {
            // 发送一个简单的测试请求检查服务可用性
            val testRequest = OpenAIRequest(
                model = config.model,
                messages = listOf(
                    OpenAIMessage("user", "test")
                ),
                maxTokens = 1
            )
            
            val response = httpClient.sendRequest(testRequest)
            response.map { true }.onError { error ->
                NymLogger.logError("isAvailable", error, mapOf("provider" to name))
            }
            
        } catch (e: Exception) {
            NymLogger.logError("isAvailable", NymError.NetworkError.Generic(), 
                mapOf("provider" to name, "exception" to e.message))
            Result.success(false)
        }
    }
    
    override suspend fun getStatus(): Result<AIProviderStatus> {
        return try {
            val startTime = System.currentTimeMillis()
            val available = isAvailable()
            val latency = System.currentTimeMillis() - startTime
            
            available.map { isAvailable ->
                AIProviderStatus(
                    available = isAvailable,
                    latencyMs = latency,
                    lastCheckTime = System.currentTimeMillis()
                )
            }
            
        } catch (e: Exception) {
            val error = NymError.APIError.ServerError("获取状态失败: ${e.message}")
            NymLogger.logError("getStatus", error, mapOf("provider" to name, "exception" to e.message))
            Result.error(error)
        }
    }
}

/**
 * OpenAI 配置实现
 */
@Serializable
data class OpenAIConfig(
    override val apiUrl: String = "https://api.openai.com/v1",
    override val apiKey: String,
    override val model: String = "gpt-4",
    override val maxTokens: Int = 150,
    override val temperature: Double = 0.7,
    override val timeoutMs: Long = 30000,
    override val maxRetries: Int = 3
) : AIProviderConfig {
    
    override fun validate(): Result<Unit> {
        return when {
            apiKey.isBlank() -> Result.error(NymError.AuthenticationError.ApiKeyNotConfigured())
            !apiUrl.startsWith("http") -> Result.error(NymError.ParseError.InvalidConfiguration("无效的API URL"))
            maxTokens <= 0 -> Result.error(NymError.ParseError.InvalidConfiguration("maxTokens必须大于0"))
            temperature < 0.0 || temperature > 2.0 -> Result.error(NymError.ParseError.InvalidConfiguration("temperature必须在0.0-2.0之间"))
            else -> Result.success(Unit)
        }
    }
}