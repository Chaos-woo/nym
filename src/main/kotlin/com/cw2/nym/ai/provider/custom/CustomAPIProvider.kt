package com.cw2.nym.ai.provider.custom

import com.cw2.nym.ai.provider.AIProvider
import com.cw2.nym.ai.provider.AIProviderConfig
import com.cw2.nym.ai.provider.AIProviderStatus
import com.cw2.nym.ai.provider.openai.*
import com.cw2.nym.ai.model.*
import com.cw2.nym.core.result.Result
import com.cw2.nym.core.exception.NymError
import com.cw2.nym.core.logging.NymLogger
import kotlinx.serialization.Serializable

/**
 * 自定义 API 服务提供商实现
 * 
 * 支持任何与 OpenAI API 兼容的服务端点，包括 Azure OpenAI、本地部署的模型、
 * 或其他兼容 OpenAI API 格式的第三方服务。提供灵活的配置选项和认证方式。
 */
class CustomAPIProvider(
    override val config: CustomAPIConfig
) : AIProvider {
    
    override val name = config.providerName
    
    // 复用 OpenAI 的 HTTP 客户端和模板系统，但使用自定义配置
    private val httpClient by lazy { 
        CustomAPIHttpClient(config)
    }
    private val promptTemplates by lazy { 
        OpenAIPromptTemplates() // 使用相同的提示模板
    }
    
    /**
     * 生成代码命名建议
     */
    override suspend fun generateNaming(context: CodeContext): Result<List<NamingSuggestion>> {
        return try {
            val startTime = System.currentTimeMillis()
            
            val prompt = promptTemplates.createNamingPrompt(context, config.model)
            val response = httpClient.sendRequest(prompt)
            
            val duration = System.currentTimeMillis() - startTime
            
            response.flatMap { openAIResponse ->
                NymLogger.logAICall(name, config.model, "generateNaming", true, duration,
                    tokenCount = openAIResponse.usage?.totalTokens)
                OpenAIResponseParser.parseNamingResponse(openAIResponse, context)
            }.onError { error ->
                NymLogger.logError("generateNaming", error, mapOf("provider" to name))
            }
            
        } catch (e: Exception) {
            val error = NymError.APIError.ServerError("命名生成失败: ${e.message}")
            NymLogger.logError("generateNaming", error, mapOf("provider" to name, "exception" to e.message))
            Result.error(error)
        }
    }
    
    /**
     * 生成代码注释
     */
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
    
    /**
     * 自定义生成
     */
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
    
    /**
     * 检查服务可用性
     */
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
    
    /**
     * 获取服务状态
     */
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
 * 自定义 API 配置实现
 * 
 * 提供比标准 OpenAI 配置更灵活的选项，支持自定义认证方式和请求头
 */
@Serializable
data class CustomAPIConfig(
    /**
     * 提供商显示名称
     */
    val providerName: String,
    
    override val apiUrl: String,
    override val apiKey: String,
    override val model: String,
    override val maxTokens: Int = 150,
    override val temperature: Double = 0.7,
    override val timeoutMs: Long = 30000,
    override val maxRetries: Int = 3,
    
    /**
     * 自定义请求头
     */
    val customHeaders: Map<String, String> = emptyMap(),
    
    /**
     * 认证方式类型
     */
    val authType: AuthenticationType = AuthenticationType.BEARER_TOKEN,
    
    /**
     * API 版本（用于 Azure OpenAI 等服务）
     */
    val apiVersion: String? = null,
    
    /**
     * 部署名称（用于 Azure OpenAI）
     */
    val deploymentName: String? = null,
    
    /**
     * 组织 ID（用于 OpenAI 组织账户）
     */
    val organizationId: String? = null,
    
    /**
     * 请求路径模板（用于非标准端点）
     */
    val pathTemplate: String = "/chat/completions",
    
    /**
     * 是否验证 SSL 证书
     */
    val verifySSL: Boolean = true
    
) : AIProviderConfig {
    
    override fun validate(): Result<Unit> {
        return when {
            providerName.isBlank() -> Result.error(
                NymError.ParseError.InvalidConfiguration("提供商名称不能为空")
            )
            apiUrl.isBlank() -> Result.error(
                NymError.ParseError.InvalidConfiguration("API URL 不能为空")
            )
            !apiUrl.startsWith("http") -> Result.error(
                NymError.ParseError.InvalidConfiguration("无效的API URL")
            )
            apiKey.isBlank() -> Result.error(
                NymError.AuthenticationError.ApiKeyNotConfigured()
            )
            model.isBlank() -> Result.error(
                NymError.ParseError.InvalidConfiguration("模型名称不能为空")
            )
            maxTokens <= 0 -> Result.error(
                NymError.ParseError.InvalidConfiguration("maxTokens必须大于0")
            )
            temperature < 0.0 || temperature > 2.0 -> Result.error(
                NymError.ParseError.InvalidConfiguration("temperature必须在0.0-2.0之间")
            )
            timeoutMs <= 0 -> Result.error(
                NymError.ParseError.InvalidConfiguration("超时时间必须大于0")
            )
            maxRetries < 0 -> Result.error(
                NymError.ParseError.InvalidConfiguration("重试次数不能为负数")
            )
            else -> Result.success(Unit)
        }
    }
    
    /**
     * 构建完整的 API 端点 URL
     */
    fun buildEndpointUrl(): String {
        return buildString {
            append(apiUrl.trimEnd('/'))
            
            // 处理 Azure OpenAI 的特殊路径格式
            if (deploymentName != null && apiVersion != null) {
                append("/openai/deployments/$deploymentName/chat/completions")
                append("?api-version=$apiVersion")
            } else {
                append(pathTemplate)
            }
        }
    }
    
    /**
     * 获取认证头部信息
     */
    fun getAuthHeaders(): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        
        when (authType) {
            AuthenticationType.BEARER_TOKEN -> {
                headers["Authorization"] = "Bearer $apiKey"
            }
            AuthenticationType.API_KEY_HEADER -> {
                headers["api-key"] = apiKey
            }
            AuthenticationType.X_API_KEY -> {
                headers["X-API-Key"] = apiKey
            }
            AuthenticationType.CUSTOM -> {
                // 自定义认证方式通过 customHeaders 传递
            }
        }
        
        // 添加组织 ID（如果有）
        organizationId?.let { orgId ->
            headers["OpenAI-Organization"] = orgId
        }
        
        // 合并自定义头部
        headers.putAll(customHeaders)
        
        return headers
    }
}

/**
 * 认证方式枚举
 */
@Serializable
enum class AuthenticationType {
    BEARER_TOKEN,    // Authorization: Bearer <token>
    API_KEY_HEADER,  // api-key: <key> (Azure OpenAI)
    X_API_KEY,       // X-API-Key: <key>
    CUSTOM           // 通过 customHeaders 自定义
}