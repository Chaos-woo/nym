package com.cw2.nym.ai.provider.openai

import com.cw2.nym.ai.model.*
import com.cw2.nym.core.result.Result
import com.cw2.nym.core.exception.NymError
import com.cw2.nym.core.serialization.fromJson
import com.cw2.nym.core.logging.NymLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * OpenAI 响应解析器
 * 
 * 负责解析 OpenAI API 的响应，将 JSON 格式的响应转换为具体的建议对象。
 * 包含对不同类型响应的解析逻辑和错误处理。
 */
object OpenAIResponseParser {
    
    /**
     * 解析命名建议响应
     */
    fun parseNamingResponse(response: OpenAIResponse, context: CodeContext): Result<List<NamingSuggestion>> {
        return try {
            val content = response.choices.firstOrNull()?.message?.content
                ?: return Result.error(NymError.ParseError.InvalidResponse("响应内容为空"))
            
            NymLogger.debug("parseNamingResponse", "解析响应内容: $content")
            
            // 尝试解析 JSON 响应
            val jsonResponse = parseJsonContent(content)
            if (jsonResponse.errorOrNull() != null) {
                // 如果 JSON 解析失败，尝试解析纯文本响应
                return parseNamingFromPlainText(content, context)
            }
            val jsonElement = jsonResponse.getOrNull() ?: return Result.error(NymError.ParseError.JsonParse("JSON解析失败"))
            
            val suggestionsJson = jsonElement.jsonObject["suggestions"]?.jsonArray
                ?: return Result.error(NymError.ParseError.InvalidResponse("响应中缺少 suggestions 字段"))
            
            val suggestions = suggestionsJson.mapNotNull { suggestionJson ->
                try {
                    parseSingleNamingSuggestion(suggestionJson.jsonObject, context)
                } catch (e: Exception) {
                    NymLogger.warn("parseNamingResponse", "解析单个建议失败: ${e.message}")
                    null
                }
            }
            
            if (suggestions.isEmpty()) {
                return Result.error(NymError.ParseError.InvalidResponse("未能解析出有效的命名建议"))
            }
            
            // 添加元数据
            val enrichedSuggestions = suggestions.map { suggestion ->
                suggestion.copy(
                    metadata = suggestion.metadata.copy(
                        source = "OpenAI",
                        model = response.model,
                        tokenCount = response.usage?.totalTokens,
                        contextHash = generateContextHash(context)
                    )
                )
            }
            
            Result.success(enrichedSuggestions)
            
        } catch (e: Exception) {
            NymLogger.logError("parseNamingResponse", NymError.ParseError.JsonParse(), 
                context = mapOf("error" to e.message, "response" to response.toString()))
            Result.error(NymError.ParseError.JsonParse("解析命名建议响应失败: ${e.message}"))
        }
    }
    
    /**
     * 解析注释建议响应
     */
    fun parseCommentResponse(response: OpenAIResponse, context: CodeContext): Result<CommentSuggestion> {
        return try {
            val content = response.choices.firstOrNull()?.message?.content
                ?: return Result.error(NymError.ParseError.InvalidResponse("响应内容为空"))
            
            NymLogger.debug("parseCommentResponse", "解析响应内容: $content")
            
            // 尝试解析 JSON 响应
            val jsonResponse = parseJsonContent(content)
            if (jsonResponse.errorOrNull() != null) {
                // 如果 JSON 解析失败，使用纯文本作为注释内容
                return parseCommentFromPlainText(content, context)
            }
            val jsonElement = jsonResponse.getOrNull() ?: return Result.error(NymError.ParseError.JsonParse("JSON解析失败"))
            
            val commentContent = jsonElement.jsonObject["content"]?.jsonPrimitive?.content
                ?: return Result.error(NymError.ParseError.InvalidResponse("响应中缺少 content 字段"))
            
            // 解析结构化信息
            val structure = parseCommentStructure(jsonElement.jsonObject)
            
            val commentSuggestion = CommentSuggestion(
                content = commentContent,
                format = determineCommentFormat(context),
                structure = structure,
                score = 0.8, // 默认分数，可根据实际情况调整
                confidence = 0.85,
                language = CommentLanguage.CHINESE, // 根据实际需求调整
                metadata = SuggestionMetadata(
                    source = "OpenAI",
                    model = response.model,
                    tokenCount = response.usage?.totalTokens,
                    contextHash = generateContextHash(context)
                )
            )
            
            Result.success(commentSuggestion)
            
        } catch (e: Exception) {
            NymLogger.logError("parseCommentResponse", NymError.ParseError.JsonParse(), 
                context = mapOf("error" to e.message))
            Result.error(NymError.ParseError.JsonParse("解析注释建议响应失败: ${e.message}"))
        }
    }
    
    /**
     * 解析自定义生成响应
     */
    fun parseCustomResponse(response: OpenAIResponse): Result<String> {
        return try {
            val content = response.choices.firstOrNull()?.message?.content
                ?: return Result.error(NymError.ParseError.InvalidResponse("响应内容为空"))
            
            Result.success(content.trim())
            
        } catch (e: Exception) {
            NymLogger.logError("parseCustomResponse", NymError.ParseError.JsonParse(), 
                context = mapOf("error" to e.message))
            Result.error(NymError.ParseError.JsonParse("解析自定义响应失败: ${e.message}"))
        }
    }
    
    /**
     * 解析 JSON 内容
     */
    private fun parseJsonContent(content: String): Result<JsonElement> {
        return try {
            val cleanContent = content.trim()
            val jsonStart = cleanContent.indexOf('{')
            val jsonEnd = cleanContent.lastIndexOf('}')
            
            if (jsonStart == -1 || jsonEnd == -1) {
                return Result.error(NymError.ParseError.JsonParse("未找到有效的 JSON 内容"))
            }
            
            val jsonContent = cleanContent.substring(jsonStart, jsonEnd + 1)
            Result.success(Json.parseToJsonElement(jsonContent))
            
        } catch (e: Exception) {
            Result.error(NymError.ParseError.JsonParse("JSON 解析失败: ${e.message}"))
        }
    }
    
    /**
     * 解析单个命名建议
     */
    private fun parseSingleNamingSuggestion(jsonObject: JsonObject, context: CodeContext): NamingSuggestion {
        val name = jsonObject["name"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("建议中缺少 name 字段")
        
        val description = jsonObject["description"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("建议中缺少 description 字段")
        
        val score = jsonObject["score"]?.jsonPrimitive?.doubleOrNull ?: 0.8
        val reasoning = jsonObject["reasoning"]?.jsonPrimitive?.content
        
        return NamingSuggestion(
            name = name,
            description = description,
            score = score,
            namingConvention = determineNamingConvention(name, context),
            applicableFor = listOf(context.elementType),
            confidence = score, // 使用 score 作为置信度
            reasoning = reasoning
        )
    }
    
    /**
     * 从纯文本解析命名建议
     */
    private fun parseNamingFromPlainText(content: String, context: CodeContext): Result<List<NamingSuggestion>> {
        return try {
            val suggestions = mutableListOf<NamingSuggestion>()
            val lines = content.lines().filter { it.isNotBlank() }
            
            lines.forEach { line ->
                // 尝试解析 "名称 - 描述" 格式
                val parts = line.split(" - ", limit = 2)
                if (parts.size == 2) {
                    val name = parts[0].trim().removePrefix("•").removePrefix("-").removePrefix("*").trim()
                    val description = parts[1].trim()
                    
                    if (name.isNotBlank() && description.isNotBlank()) {
                        suggestions.add(
                            NamingSuggestion(
                                name = name,
                                description = description,
                                score = 0.7,
                                namingConvention = determineNamingConvention(name, context),
                                applicableFor = listOf(context.elementType),
                                confidence = 0.75
                            )
                        )
                    }
                }
            }
            
            if (suggestions.isEmpty()) {
                return Result.error(NymError.ParseError.InvalidResponse("无法从纯文本中解析出命名建议"))
            }
            
            Result.success(suggestions)
            
        } catch (e: Exception) {
            Result.error(NymError.ParseError.InvalidResponse("纯文本解析失败: ${e.message}"))
        }
    }
    
    /**
     * 从纯文本解析注释建议
     */
    private fun parseCommentFromPlainText(content: String, context: CodeContext): Result<CommentSuggestion> {
        return try {
            val commentSuggestion = CommentSuggestion(
                content = content.trim(),
                format = determineCommentFormat(context),
                score = 0.7,
                confidence = 0.75,
                language = CommentLanguage.CHINESE,
                metadata = SuggestionMetadata(
                    source = "OpenAI",
                    contextHash = generateContextHash(context)
                )
            )
            
            Result.success(commentSuggestion)
            
        } catch (e: Exception) {
            Result.error(NymError.ParseError.InvalidResponse("纯文本注释解析失败: ${e.message}"))
        }
    }
    
    /**
     * 解析注释结构
     */
    private fun parseCommentStructure(jsonObject: JsonObject): CommentStructure? {
        return try {
            val parameters = jsonObject["parameters"]?.jsonArray?.mapNotNull { paramJson ->
                val paramObj = paramJson.jsonObject
                val name = paramObj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val description = paramObj["description"]?.jsonPrimitive?.content ?: return@mapNotNull null
                ParameterComment(name = name, description = description)
            } ?: emptyList()
            
            val returnDescription = jsonObject["returnDescription"]?.jsonPrimitive?.content
            
            val exceptions = jsonObject["exceptions"]?.jsonArray?.mapNotNull { exceptionJson ->
                val exceptionObj = exceptionJson.jsonObject
                val type = exceptionObj["type"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val description = exceptionObj["description"]?.jsonPrimitive?.content ?: return@mapNotNull null
                ExceptionComment(type = type, description = description)
            } ?: emptyList()
            
            if (parameters.isEmpty() && returnDescription == null && exceptions.isEmpty()) {
                return null
            }
            
            CommentStructure(
                parameters = parameters,
                returnDescription = returnDescription,
                exceptions = exceptions
            )
            
        } catch (e: Exception) {
            NymLogger.warn("parseCommentStructure", "解析注释结构失败: ${e.message}")
            null
        }
    }
    
    /**
     * 确定命名约定
     */
    private fun determineNamingConvention(name: String, context: CodeContext): NamingConvention {
        // 先检查上下文中的命名模式分析
        context.surroundingContext.namingPatterns?.conventionType?.let { convention ->
            return convention
        }
        
        // 基于名称本身推断
        return when {
            name.contains("_") && name.all { it.isUpperCase() || it == '_' || it.isDigit() } -> 
                NamingConvention.UPPER_SNAKE_CASE
            name.contains("_") -> NamingConvention.SNAKE_CASE
            name.contains("-") -> NamingConvention.KEBAB_CASE
            name.first().isUpperCase() -> NamingConvention.PASCAL_CASE
            else -> NamingConvention.CAMEL_CASE
        }
    }
    
    /**
     * 确定注释格式
     */
    private fun determineCommentFormat(context: CodeContext): CommentFormat {
        return when (context.language) {
            ProgrammingLanguage.JAVA -> CommentFormat.JAVADOC
            ProgrammingLanguage.KOTLIN -> CommentFormat.KDOC
            ProgrammingLanguage.JAVASCRIPT, ProgrammingLanguage.TYPESCRIPT -> CommentFormat.JSDOC
            else -> CommentFormat.JAVADOC
        }
    }
    
    /**
     * 生成上下文哈希值
     */
    private fun generateContextHash(context: CodeContext): String {
        val contextString = buildString {
            append(context.language)
            append(context.elementType)
            when (context) {
                is MethodContext -> {
                    append(context.methodName)
                    append(context.parameters.size)
                    append(context.returnType.typeName)
                }
                is ClassContext -> {
                    append(context.className)
                    append(context.packageName)
                    append(context.methods.size)
                }
                is VariableContext -> {
                    append(context.variableName)
                    append(context.variableType.typeName)
                    append(context.scope)
                }
            }
        }
        
        return contextString.hashCode().toString(16)
    }
}

/**
 * OpenAI 命名建议响应结构（用于 JSON 解析）
 */
@Serializable
private data class OpenAINamingResponse(
    val suggestions: List<OpenAINamingSuggestion>
)

/**
 * OpenAI 单个命名建议结构
 */
@Serializable
private data class OpenAINamingSuggestion(
    val name: String,
    val description: String,
    val score: Double,
    val reasoning: String? = null
)

/**
 * OpenAI 注释响应结构
 */
@Serializable
private data class OpenAICommentResponse(
    val content: String,
    val parameters: List<OpenAIParameterComment>? = null,
    val returnDescription: String? = null,
    val exceptions: List<OpenAIExceptionComment>? = null
)

/**
 * OpenAI 参数注释结构
 */
@Serializable
private data class OpenAIParameterComment(
    val name: String,
    val description: String
)

/**
 * OpenAI 异常注释结构
 */
@Serializable
private data class OpenAIExceptionComment(
    val type: String,
    val description: String
)