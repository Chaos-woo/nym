package com.cw2.nym.ai.provider.openai

import com.cw2.nym.ai.model.*

/**
 * OpenAI 提示模板系统
 * 
 * 负责根据不同的代码上下文生成对应的提示词，包括命名建议、注释生成和自定义生成。
 * 提示模板经过精心设计，以获得高质量的 AI 响应结果。
 */
class OpenAIPromptTemplates {
    // 根据设置生成语言偏好的系统提示，用于控制生成内容语言（注释/说明/描述）
    private fun buildLanguageSystemMessage(): OpenAIMessage? {
        return try {
            val pref = com.cw2.nym.data.settings.NymSettings.getInstance().languagePreference.uppercase()
            val instruction = "System instruction: Use {LANG} for all comments, explanations, and descriptions in your replies."
            val content = when (pref) {
                "EN" -> instruction.replace("{LANG}", "English")
                "ZH", "ZH_CN", "ZH-CN" -> instruction.replace("{LANG}", "Simple Chinese")
                else -> null // AUTO: 由模型自行选择或与输入语言一致
            }
            content?.let { OpenAIMessage("system", it) }
        } catch (_: Throwable) {
            null
        }
    }
    
    companion object {
        const val SYSTEM_PROMPT_BASE = """You are a professional code assistant that helps developers with code naming and comment generation.
Using the provided code context, produce high-quality suggestions.
Your responses must be accurate, concise, and aligned with established programming best practices."""

        const val NAMING_SYSTEM_PROMPT = """$SYSTEM_PROMPT_BASE

For naming suggestions:
1. Provide 3-5 high-quality name candidates.
2. Each suggestion should include: "name - brief description".
3. Follow the naming conventions of the target language.
4. Ensure excellent readability and precise semantics.
5. Avoid abbreviations and ambiguous terms.

Return strictly in JSON format using the structure below. Do not include any extra text:
{
  "suggestions": [
    {
      "name": "proposedName",
      "description": "brief description of the name",
      "score": 0.9,
      "reasoning": "why this name is appropriate"
    }
  ]
}"""

        const val COMMENT_SYSTEM_PROMPT = """$SYSTEM_PROMPT_BASE

For comment generation:
1. Analyze the code's purpose and behavior.
2. Produce clear and accurate comments.
3. Use the appropriate comment style for the target language.
4. Include parameter descriptions, return value description, and exceptions when applicable.
5. Avoid obvious or redundant comments.

Return strictly in JSON format using the structure below. Do not include any extra text:
{
  "content": "primary comment content",
  "parameters": [
    {"name": "parameter name", "description": "parameter description"}
  ],
  "returnDescription": "return value description",
  "exceptions": [
    {"type": "exception type", "description": "exception description"}
  ]
}"""

        const val CUSTOM_SYSTEM_PROMPT = """$SYSTEM_PROMPT_BASE

For custom generation:
1. Generate content according to the user's specific request.
2. Consider the provided code context.
3. Ensure the output is accurate and useful.
4. If the request is code-related, follow best practices."""
    }
    
    /**
     * 创建命名建议的提示
     */
    fun createNamingPrompt(context: CodeContext, model: String = "gpt-4"): OpenAIRequest {
        val userPrompt = buildString {
            appendLine("Please provide naming suggestions for the following code element:")
            appendLine()
            appendLine("Language: ${context.language}")
            appendLine("Element type: ${context.elementType}")
            
            // 根据不同类型的上下文添加特定信息
            when (context) {
                is MethodContext -> appendMethodContext(context)
                is ClassContext -> appendClassContext(context)
                is VariableContext -> appendVariableContext(context)
            }
            
            // 添加周围上下文信息
            if (context.surroundingContext.precedingCode.isNotEmpty()) {
                appendLine("\nPreceding code:")
                context.surroundingContext.precedingCode.take(3).forEach { line ->
                    appendLine("  $line")
                }
            }
            
            if (context.surroundingContext.followingCode.isNotEmpty()) {
                appendLine("\nFollowing code:")
                context.surroundingContext.followingCode.take(3).forEach { line ->
                    appendLine("  $line")
                }
            }
            
            // 用户意图（用于特殊符号生成）
            context.userIntent?.let { intent ->
                appendLine("\nUser intent: $intent")
            }
            
            // 项目信息
            appendLine("\nProject information:")
            appendLine("  Project name: ${context.projectInfo.projectName}")
            context.projectInfo.framework?.let { framework ->
                appendLine("  Framework: $framework")
            }
            
            // 命名模式分析
            context.surroundingContext.namingPatterns?.let { patterns ->
                appendLine("\nProject naming conventions: ${patterns.conventionType}")
                if (patterns.commonPrefixes.isNotEmpty()) {
                    appendLine("  Common prefixes: ${patterns.commonPrefixes.joinToString(", ")}")
                }
                if (patterns.commonSuffixes.isNotEmpty()) {
                    appendLine("  Common suffixes: ${patterns.commonSuffixes.joinToString(", ")}")
                }
            }
        }
        
        val messages = mutableListOf(
            OpenAIMessage("system", NAMING_SYSTEM_PROMPT)
        )
        buildLanguageSystemMessage()?.let { messages.add(it) }
        messages.add(OpenAIMessage("user", userPrompt))
        return OpenAIRequest(
            model = model,
            messages = messages,
            maxTokens = 300,
            temperature = 0.7
        )
    }
    
    /**
     * 创建注释生成的提示
     */
    fun createCommentPrompt(context: CodeContext, model: String = "gpt-4"): OpenAIRequest {
        val userPrompt = buildString {
            appendLine("Please generate comments for the following code element:")
            appendLine()
            appendLine("Language: ${context.language}")
            appendLine("Element type: ${context.elementType}")
            
            when (context) {
                is MethodContext -> appendMethodContextForComment(context)
                is ClassContext -> appendClassContextForComment(context)
                is VariableContext -> appendVariableContextForComment(context)
            }
            
            // 添加代码片段
            if (context.surroundingContext.precedingCode.isNotEmpty() || 
                context.surroundingContext.followingCode.isNotEmpty()) {
                appendLine("\nRelated code:")
                context.surroundingContext.precedingCode.take(2).forEach { line ->
                    appendLine("  $line")
                }
                context.surroundingContext.followingCode.take(2).forEach { line ->
                    appendLine("  $line")
                }
            }
            
            // 注释风格偏好
            context.surroundingContext.codeStyleAnalysis?.commentStyle?.let { style ->
                appendLine("\nComment style: $style")
            }
        }
        
        val messages = mutableListOf(
            OpenAIMessage("system", COMMENT_SYSTEM_PROMPT)
        )
        buildLanguageSystemMessage()?.let { messages.add(it) }
        messages.add(OpenAIMessage("user", userPrompt))
        return OpenAIRequest(
            model = model,
            messages = messages,
            maxTokens = 400,
            temperature = 0.6
        )
    }
    
    /**
     * 创建自定义生成的提示
     */
    fun createCustomPrompt(prompt: String, context: CodeContext?, model: String = "gpt-4"): OpenAIRequest {
        val userPrompt = buildString {
            appendLine("User request: $prompt")
            
            context?.let { ctx ->
                appendLine()
                appendLine("Code context:")
                appendLine("  Language: ${ctx.language}")
                appendLine("  Project: ${ctx.projectInfo.projectName}")
                
                if (ctx.surroundingContext.precedingCode.isNotEmpty()) {
                    appendLine("\nRelated code:")
                    ctx.surroundingContext.precedingCode.take(5).forEach { line ->
                        appendLine("  $line")
                    }
                }
            }
        }
        
        val messages = mutableListOf(
            OpenAIMessage("system", CUSTOM_SYSTEM_PROMPT)
        )
        buildLanguageSystemMessage()?.let { messages.add(it) }
        messages.add(OpenAIMessage("user", userPrompt))
        return OpenAIRequest(
            model = model,
            messages = messages,
            maxTokens = 500,
            temperature = 0.8
        )
    }
    
    /**
     * 为方法上下文添加详细信息（用于命名）
     */
    private fun StringBuilder.appendMethodContext(context: MethodContext) {
        appendLine("\nMethod information:")
        context.methodName?.let { name ->
            appendLine("  Current name: $name")
        }
        appendLine("  Return type: ${context.returnType.typeName}")
        
        if (context.parameters.isNotEmpty()) {
            appendLine("  Parameters:")
            context.parameters.forEach { param ->
                appendLine("    ${param.name}: ${param.type.typeName}")
            }
        }
        
        if (context.modifiers.isNotEmpty()) {
            appendLine("  Modifiers: ${context.modifiers.joinToString(", ")}")
        }
        
        if (context.exceptions.isNotEmpty()) {
            appendLine("  Possible exceptions: ${context.exceptions.map { it.typeName }.joinToString(", ")}")
        }
        
        context.methodBody?.let { body ->
            appendLine("\nMethod body snippet:")
            body.lines().take(5).forEach { line ->
                appendLine("  $line")
            }
        }
        
        context.containingClass?.let { clazz ->
            appendLine("\nContaining class: ${clazz.name}")
        }
    }
    
    /**
     * 为类上下文添加详细信息（用于命名）
     */
    private fun StringBuilder.appendClassContext(context: ClassContext) {
        appendLine("\nClass information:")
        context.className?.let { name ->
            appendLine("  Current name: $name")
        }
        
        context.superClass?.let { superClass ->
            appendLine("  Superclass: ${superClass.typeName}")
        }
        
        if (context.interfaces.isNotEmpty()) {
            appendLine("  Implements: ${context.interfaces.map { it.typeName }.joinToString(", ")}")
        }
        
        if (context.modifiers.isNotEmpty()) {
            appendLine("  Modifiers: ${context.modifiers.joinToString(", ")}")
        }
        
        appendLine("  Package: ${context.packageName}")
        
        if (context.fields.isNotEmpty()) {
            appendLine("  Key fields:")
            context.fields.take(5).forEach { field ->
                appendLine("    ${field.name}: ${field.type.typeName}")
            }
        }
        
        if (context.methods.isNotEmpty()) {
            appendLine("  Key methods:")
            context.methods.take(5).forEach { method ->
                appendLine("    ${method.name}(): ${method.returnType.typeName}")
            }
        }
    }
    
    /**
     * 为变量上下文添加详细信息（用于命名）
     */
    private fun StringBuilder.appendVariableContext(context: VariableContext) {
        appendLine("\nVariable information:")
        context.variableName?.let { name ->
            appendLine("  Current name: $name")
        }
        appendLine("  Type: ${context.variableType.typeName}")
        appendLine("  Scope: ${context.scope}")
        
        if (context.modifiers.isNotEmpty()) {
            appendLine("  Modifiers: ${context.modifiers.joinToString(", ")}")
        }
        
        context.initializer?.let { init ->
            appendLine("  Initializer: $init")
        }
        
        context.usagePattern?.let { usage ->
            appendLine("  Usage pattern:")
            appendLine("    Read count: ${usage.readCount}")
            appendLine("    Write count: ${usage.writeCount}")
            if (usage.isLoopVariable) appendLine("    Loop variable: yes")
            if (usage.isParameterPassed) appendLine("    Passed as parameter: yes")
            if (usage.isReturnValue) appendLine("    Used as return value: yes")
        }
        
        context.containingClass?.let { clazz ->
            appendLine("  Containing class: ${clazz.name}")
        }
        
        context.containingMethod?.let { method ->
            appendLine("  Containing method: ${method.name}")
        }
    }
    
    /**
     * 为方法上下文添加注释生成所需信息
     */
    private fun StringBuilder.appendMethodContextForComment(context: MethodContext) {
        appendLine("\nMethod signature:")
        val modifiers = if (context.modifiers.isNotEmpty()) 
            context.modifiers.joinToString(" ") + " " else ""
        val name = context.methodName ?: "[To be named]"
        val params = context.parameters.joinToString(", ") { "${it.type.typeName} ${it.name}" }
        val returnType = context.returnType.typeName
        
        appendLine("  $modifiers$returnType $name($params)")
        
        if (context.exceptions.isNotEmpty()) {
            appendLine("  throws: ${context.exceptions.joinToString(", ") { it.typeName }}")
        }
        
        context.methodBody?.let { body ->
            appendLine("\nMethod implementation:")
            body.lines().take(8).forEach { line ->
                appendLine("  $line")
            }
        }
    }
    
    /**
     * 为类上下文添加注释生成所需信息
     */
    private fun StringBuilder.appendClassContextForComment(context: ClassContext) {
        appendLine("\nClass definition:")
        val modifiers = if (context.modifiers.isNotEmpty()) 
            context.modifiers.joinToString(" ") + " " else ""
        val type = when {
            context.isInterface -> "interface"
            context.isEnum -> "enum"
            context.isAbstract -> "abstract class"
            else -> "class"
        }
        val name = context.className ?: "[To be named]"
        
        appendLine("  $modifiers$type $name")
        
        context.superClass?.let { superClass ->
            appendLine("    extends ${superClass.typeName}")
        }
        
        if (context.interfaces.isNotEmpty()) {
            appendLine("    implements ${context.interfaces.joinToString(", ") { it.typeName }}")
        }
        
        appendLine("\nClass structure overview:")
        if (context.fields.isNotEmpty()) {
            appendLine("  Field count: ${context.fields.size}")
        }
        if (context.methods.isNotEmpty()) {
            appendLine("  Method count: ${context.methods.size}")
        }
        if (context.innerClasses.isNotEmpty()) {
            appendLine("  Inner class count: ${context.innerClasses.size}")
        }
    }
    
    /**
     * 为变量上下文添加注释生成所需信息
     */
    private fun StringBuilder.appendVariableContextForComment(context: VariableContext) {
        appendLine("\nVariable declaration:")
        val modifiers = if (context.modifiers.isNotEmpty()) 
            context.modifiers.joinToString(" ") + " " else ""
        val type = context.variableType.typeName
        val name = context.variableName ?: "[To be named]"
        val init = context.initializer?.let { " = $it" } ?: ""
        
        appendLine("  $modifiers$type $name$init")
        
        context.usagePattern?.let { usage ->
            if (usage.usageContexts.isNotEmpty()) {
                appendLine("\nUsage scenarios:")
                usage.usageContexts.take(3).forEach { usageContext ->
                    appendLine("  - $usageContext")
                }
            }
        }
    }
}