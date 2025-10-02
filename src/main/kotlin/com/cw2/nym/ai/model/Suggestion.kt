package com.cw2.nym.ai.model

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * 命名建议数据类
 * 
 * 包含AI生成的命名建议及其相关信息，支持评分排序和元数据管理。
 * 按照需求文档要求，建议展示格式为 "命名内容 - 简单描述"。
 */
@Serializable
data class NamingSuggestion(
    /**
     * 建议的名称
     */
    val name: String,
    
    /**
     * 命名的简单描述或解释
     */
    val description: String,
    
    /**
     * 建议的评分（0.0-1.0），分数越高越推荐
     */
    val score: Double,
    
    /**
     * 命名约定类型（驼峰、下划线等）
     */
    val namingConvention: NamingConvention,
    
    /**
     * 适用的上下文类型
     */
    val applicableFor: List<CodeElementType>,
    
    /**
     * 建议的置信度（0.0-1.0）
     */
    val confidence: Double,
    
    /**
     * 生成时间戳
     */
    val generatedAt: Long = System.currentTimeMillis(),
    
    /**
     * 建议的元数据信息
     */
    val metadata: SuggestionMetadata = SuggestionMetadata(),
    
    /**
     * 语义标签（如：business-logic, utility, data-access等）
     */
    val semanticTags: List<String> = emptyList(),
    
    /**
     * 推理过程说明（可选，用于调试和用户理解）
     */
    val reasoning: String? = null
) {
    /**
     * 获取显示格式：名称 - 描述
     */
    fun getDisplayText(): String = "$name - $description"
    
    /**
     * 检查是否适用于指定的代码元素类型
     */
    fun isApplicableFor(elementType: CodeElementType): Boolean {
        return applicableFor.contains(elementType)
    }
    
    /**
     * 计算综合质量得分，结合评分和置信度
     */
    fun getQualityScore(): Double {
        return (score * 0.7 + confidence * 0.3).coerceIn(0.0, 1.0)
    }
}

/**
 * 注释建议数据类
 * 
 * 包含AI生成的代码注释内容，支持多种注释格式和结构化信息。
 */
@Serializable
data class CommentSuggestion(
    /**
     * 主要注释内容
     */
    val content: String,
    
    /**
     * 注释格式类型
     */
    val format: CommentFormat,
    
    /**
     * 注释的结构化信息
     */
    val structure: CommentStructure? = null,
    
    /**
     * 建议的质量评分（0.0-1.0）
     */
    val score: Double,
    
    /**
     * 生成置信度（0.0-1.0）
     */
    val confidence: Double,
    
    /**
     * 注释语言（中文/英文）
     */
    val language: CommentLanguage,
    
    /**
     * 生成时间戳
     */
    val generatedAt: Long = System.currentTimeMillis(),
    
    /**
     * 建议的元数据信息
     */
    val metadata: SuggestionMetadata = SuggestionMetadata(),
    
    /**
     * 注释覆盖的方面（功能描述、参数说明、返回值等）
     */
    val coverageAspects: List<CommentAspect> = emptyList(),
    
    /**
     * 推理过程说明
     */
    val reasoning: String? = null
) {
    /**
     * 获取格式化后的完整注释文本
     */
    fun getFormattedComment(): String {
        return when (format) {
            CommentFormat.JAVADOC -> formatAsJavaDoc()
            CommentFormat.KDOC -> formatAsKDoc()
            CommentFormat.JSDOC -> formatAsJSDoc()
            CommentFormat.SINGLE_LINE -> "// $content"
            CommentFormat.MULTI_LINE -> "/* $content */"
            CommentFormat.PLAIN -> content
        }
    }
    
    /**
     * 格式化为JavaDoc格式
     */
    private fun formatAsJavaDoc(): String {
        if (structure == null) return "/**\n * $content\n */"
        
        return buildString {
            appendLine("/**")
            appendLine(" * $content")
            
            if (structure.parameters.isNotEmpty()) {
                appendLine(" *")
                structure.parameters.forEach { param ->
                    appendLine(" * @param ${param.name} ${param.description}")
                }
            }
            
            structure.returnDescription?.let { returnDesc ->
                appendLine(" * @return $returnDesc")
            }
            
            if (structure.exceptions.isNotEmpty()) {
                appendLine(" *")
                structure.exceptions.forEach { exception ->
                    appendLine(" * @throws ${exception.type} ${exception.description}")
                }
            }
            
            structure.seeAlso.forEach { reference ->
                appendLine(" * @see $reference")
            }
            
            structure.since?.let { since ->
                appendLine(" * @since $since")
            }
            
            structure.author?.let { author ->
                appendLine(" * @author $author")
            }
            
            append(" */")
        }
    }
    
    /**
     * 格式化为KDoc格式
     */
    private fun formatAsKDoc(): String {
        return formatAsJavaDoc() // KDoc使用相同的格式
    }
    
    /**
     * 格式化为JSDoc格式
     */
    private fun formatAsJSDoc(): String {
        if (structure == null) return "/**\n * $content\n */"
        
        return buildString {
            appendLine("/**")
            appendLine(" * $content")
            
            if (structure.parameters.isNotEmpty()) {
                appendLine(" *")
                structure.parameters.forEach { param ->
                    appendLine(" * @param {${param.type ?: "any"}} ${param.name} ${param.description}")
                }
            }
            
            structure.returnDescription?.let { returnDesc ->
                appendLine(" * @returns $returnDesc")
            }
            
            append(" */")
        }
    }
}

/**
 * 建议元数据信息
 */
@Serializable
data class SuggestionMetadata(
    /**
     * 生成来源（AI提供商名称）
     */
    val source: String? = null,
    
    /**
     * 使用的AI模型
     */
    val model: String? = null,
    
    /**
     * 生成耗时（毫秒）
     */
    val generationTimeMs: Long? = null,
    
    /**
     * 使用的Token数量
     */
    val tokenCount: Int? = null,
    
    /**
     * 提示模板版本
     */
    val promptVersion: String? = null,
    
    /**
     * 上下文哈希值（用于缓存）
     */
    val contextHash: String? = null,
    
    /**
     * 生成参数
     */
    val generationParams: Map<String, String> = emptyMap(),
    
    /**
     * 用户反馈信息
     */
    val userFeedback: UserFeedback? = null
)

/**
 * 注释格式类型
 */
@Serializable
enum class CommentFormat {
    JAVADOC, KDOC, JSDOC, SINGLE_LINE, MULTI_LINE, PLAIN
}

/**
 * 注释语言
 */
@Serializable
enum class CommentLanguage {
    CHINESE, ENGLISH, AUTO
}

/**
 * 注释覆盖方面
 */
@Serializable
enum class CommentAspect {
    FUNCTIONALITY,      // 功能描述
    PARAMETERS,         // 参数说明
    RETURN_VALUE,       // 返回值
    EXCEPTIONS,         // 异常情况
    EXAMPLES,           // 使用示例
    SIDE_EFFECTS,       // 副作用
    COMPLEXITY,         // 复杂度说明
    THREAD_SAFETY,      // 线程安全性
    DEPRECATED,         // 废弃说明
    SEE_ALSO           // 相关引用
}

/**
 * 注释结构化信息
 */
@Serializable
data class CommentStructure(
    /**
     * 参数说明列表
     */
    val parameters: List<ParameterComment> = emptyList(),
    
    /**
     * 返回值描述
     */
    val returnDescription: String? = null,
    
    /**
     * 异常说明列表
     */
    val exceptions: List<ExceptionComment> = emptyList(),
    
    /**
     * 使用示例
     */
    val examples: List<String> = emptyList(),
    
    /**
     * 相关引用
     */
    val seeAlso: List<String> = emptyList(),
    
    /**
     * 版本信息
     */
    val since: String? = null,
    
    /**
     * 作者信息
     */
    val author: String? = null,
    
    /**
     * 废弃信息
     */
    val deprecated: String? = null
)

/**
 * 参数注释信息
 */
@Serializable
data class ParameterComment(
    val name: String,
    val description: String,
    val type: String? = null,
    val isOptional: Boolean = false,
    val defaultValue: String? = null
)

/**
 * 异常注释信息
 */
@Serializable
data class ExceptionComment(
    val type: String,
    val description: String,
    val condition: String? = null
)

/**
 * 用户反馈信息
 */
@Serializable
data class UserFeedback(
    /**
     * 反馈评分（1-5星）
     */
    val rating: Int,
    
    /**
     * 反馈内容
     */
    val comment: String? = null,
    
    /**
     * 是否被采用
     */
    val accepted: Boolean,
    
    /**
     * 反馈时间
     */
    val feedbackTime: Long = System.currentTimeMillis(),
    
    /**
     * 改进建议
     */
    val suggestions: List<String> = emptyList()
)

/**
 * 建议排序器
 * 
 * 提供多种排序策略对建议进行排序
 */
object SuggestionSorter {
    
    /**
     * 按质量得分排序（默认）
     */
    fun sortByQuality(suggestions: List<NamingSuggestion>): List<NamingSuggestion> {
        return suggestions.sortedByDescending { it.getQualityScore() }
    }
    
    /**
     * 按评分排序
     */
    fun sortByScore(suggestions: List<NamingSuggestion>): List<NamingSuggestion> {
        return suggestions.sortedByDescending { it.score }
    }
    
    /**
     * 按置信度排序
     */
    fun sortByConfidence(suggestions: List<NamingSuggestion>): List<NamingSuggestion> {
        return suggestions.sortedByDescending { it.confidence }
    }
    
    /**
     * 按生成时间排序（最新优先）
     */
    fun sortByTime(suggestions: List<NamingSuggestion>): List<NamingSuggestion> {
        return suggestions.sortedByDescending { it.generatedAt }
    }
    
    /**
     * 自定义排序
     */
    fun sortBy(
        suggestions: List<NamingSuggestion>,
        comparator: Comparator<NamingSuggestion>
    ): List<NamingSuggestion> {
        return suggestions.sortedWith(comparator)
    }
}

/**
 * 建议过滤器
 * 
 * 提供多种过滤条件对建议进行筛选
 */
object SuggestionFilter {
    
    /**
     * 按最小质量得分过滤
     */
    fun filterByMinQuality(
        suggestions: List<NamingSuggestion>,
        minQuality: Double
    ): List<NamingSuggestion> {
        return suggestions.filter { it.getQualityScore() >= minQuality }
    }
    
    /**
     * 按代码元素类型过滤
     */
    fun filterByElementType(
        suggestions: List<NamingSuggestion>,
        elementType: CodeElementType
    ): List<NamingSuggestion> {
        return suggestions.filter { it.isApplicableFor(elementType) }
    }
    
    /**
     * 按命名约定过滤
     */
    fun filterByNamingConvention(
        suggestions: List<NamingSuggestion>,
        convention: NamingConvention
    ): List<NamingSuggestion> {
        return suggestions.filter { it.namingConvention == convention }
    }
    
    /**
     * 按语义标签过滤
     */
    fun filterBySemanticTags(
        suggestions: List<NamingSuggestion>,
        tags: List<String>
    ): List<NamingSuggestion> {
        return suggestions.filter { suggestion ->
            tags.any { tag -> suggestion.semanticTags.contains(tag) }
        }
    }
    
    /**
     * 去除重复建议
     */
    fun removeDuplicates(suggestions: List<NamingSuggestion>): List<NamingSuggestion> {
        return suggestions.distinctBy { it.name.lowercase() }
    }
    
    /**
     * 限制结果数量
     */
    fun limitResults(suggestions: List<NamingSuggestion>, limit: Int): List<NamingSuggestion> {
        return suggestions.take(limit)
    }
}