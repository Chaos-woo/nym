package com.cw2.nym.ai.provider

import com.cw2.nym.ai.model.CodeContext
import com.cw2.nym.ai.model.NamingSuggestion
import com.cw2.nym.ai.model.CommentSuggestion
import com.cw2.nym.core.result.Result

/**
 * AI 服务提供商抽象接口
 * 
 * 定义了所有 AI 服务提供商必须实现的核心方法，支持代码命名建议、注释生成和自定义生成功能。
 * 所有实现类都应该支持异步操作，并且具备良好的错误处理能力。
 */
interface AIProvider {
    
    /**
     * 获取提供商名称
     */
    val name: String
    
    /**
     * 获取提供商配置
     */
    val config: AIProviderConfig
    
    /**
     * 生成代码命名建议
     * 
     * @param context 代码上下文信息，包含待命名元素的详细信息
     * @return 包含多个命名建议的结果，每个建议都包含名称和描述
     */
    suspend fun generateNaming(context: CodeContext): Result<List<NamingSuggestion>>
    
    /**
     * 生成代码注释
     * 
     * @param context 代码上下文信息，包含需要注释的代码元素信息
     * @return 包含生成注释内容的结果
     */
    suspend fun generateComment(context: CodeContext): Result<CommentSuggestion>
    
    /**
     * 自定义生成
     * 
     * @param prompt 用户自定义的提示内容
     * @param context 可选的代码上下文信息，为null时仅使用prompt
     * @return 包含生成内容的结果
     */
    suspend fun generateCustom(prompt: String, context: CodeContext? = null): Result<String>
    
    /**
     * 检查服务是否可用
     * 
     * @return 服务可用性检查结果
     */
    suspend fun isAvailable(): Result<Boolean>
    
    /**
     * 获取服务状态信息
     * 
     * @return 服务状态详情
     */
    suspend fun getStatus(): Result<AIProviderStatus>
}

/**
 * AI 服务提供商配置接口
 * 
 * 定义了所有 AI 服务提供商的通用配置选项
 */
interface AIProviderConfig {
    
    /**
     * API 端点地址
     */
    val apiUrl: String
    
    /**
     * API 密钥
     */
    val apiKey: String
    
    /**
     * 使用的模型名称
     */
    val model: String
    
    /**
     * 最大 Token 数量
     */
    val maxTokens: Int
    
    /**
     * 生成温度，控制输出的随机性
     */
    val temperature: Double
    
    /**
     * 请求超时时间（毫秒）
     */
    val timeoutMs: Long
    
    /**
     * 最大重试次数
     */
    val maxRetries: Int
    
    /**
     * 验证配置是否有效
     */
    fun validate(): Result<Unit>
}

/**
 * AI 服务提供商状态信息
 */
data class AIProviderStatus(
    val available: Boolean,
    val latencyMs: Long? = null,
    val quotaRemaining: Int? = null,
    val quotaTotal: Int? = null,
    val rateLimit: RateLimitInfo? = null,
    val lastError: String? = null,
    val lastCheckTime: Long = System.currentTimeMillis()
)

/**
 * 速率限制信息
 */
data class RateLimitInfo(
    val requestsPerMinute: Int,
    val tokensPerMinute: Int,
    val remainingRequests: Int,
    val remainingTokens: Int,
    val resetTime: Long
)