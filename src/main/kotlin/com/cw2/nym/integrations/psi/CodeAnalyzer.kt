package com.cw2.nym.integrations.psi

import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.cw2.nym.ai.model.*
import com.cw2.nym.core.result.Result
import com.cw2.nym.core.exception.NymError
import com.cw2.nym.core.logging.NymLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.PsiDocumentManager
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtParameter

/**
 * 代码分析器接口
 * 
 * 定义了代码元素分析的核心接口，支持方法、类、变量的深度分析。
 * 实现了安全的 PSI 操作和多语言支持。
 */
interface CodeAnalyzer {
    
    /**
     * 分析方法信息
     */
    fun analyzeMethod(method: PsiElement): Result<MethodContext>
    
    /**
     * 分析类信息
     */
    fun analyzeClass(clazz: PsiElement): Result<ClassContext>
    
    /**
     * 分析变量信息
     */
    fun analyzeVariable(variable: PsiElement): Result<VariableContext>
    
    /**
     * 提取周围上下文
     */
    fun extractSurroundingContext(element: PsiElement, radius: Int = 5): Result<SurroundingContext>
    
    /**
     * 检测编程语言
     */
    fun detectLanguage(element: PsiElement): ProgrammingLanguage
}

/**
 * 通用代码分析器实现
 * 
 * 提供了对 Java 和 Kotlin 代码的统一分析功能，
 * 包含详细的错误处理和日志记录。
 */
class UniversalCodeAnalyzer(
    private val project: Project
) : CodeAnalyzer {
    
    private val javaAnalyzer = JavaCodeAnalyzer(project)
    private val kotlinAnalyzer = KotlinCodeAnalyzer(project)
    
    override fun analyzeMethod(method: PsiElement): Result<MethodContext> {
        return try {
            val language = detectLanguage(method)
            
            when (language) {
                ProgrammingLanguage.JAVA -> {
                    if (method is PsiMethod) {
                        javaAnalyzer.analyzeJavaMethod(method)
                    } else {
                        Result.error(NymError.PlatformError.EditorUnavailable("元素不是有效的 Java 方法"))
                    }
                }
                ProgrammingLanguage.KOTLIN -> {
                    if (method is KtFunction) {
                        kotlinAnalyzer.analyzeKotlinFunction(method)
                    } else {
                        Result.error(NymError.PlatformError.EditorUnavailable("元素不是有效的 Kotlin 函数"))
                    }
                }
                else -> {
                    Result.error(NymError.PlatformError.EditorUnavailable("不支持的语言类型: $language"))
                }
            }
        } catch (e: Exception) {
            NymLogger.logError("analyzeMethod", NymError.Unknown("方法分析失败: ${e.message}"))
            Result.error(NymError.Unknown("方法分析失败: ${e.message}"))
        }
    }
    
    override fun analyzeClass(clazz: PsiElement): Result<ClassContext> {
        return try {
            val language = detectLanguage(clazz)
            
            when (language) {
                ProgrammingLanguage.JAVA -> {
                    if (clazz is PsiClass) {
                        javaAnalyzer.analyzeJavaClass(clazz)
                    } else {
                        Result.error(NymError.PlatformError.EditorUnavailable("元素不是有效的 Java 类"))
                    }
                }
                ProgrammingLanguage.KOTLIN -> {
                    if (clazz is KtClass) {
                        kotlinAnalyzer.analyzeKotlinClass(clazz)
                    } else {
                        Result.error(NymError.PlatformError.EditorUnavailable("元素不是有效的 Kotlin 类"))
                    }
                }
                else -> {
                    Result.error(NymError.PlatformError.EditorUnavailable("不支持的语言类型: $language"))
                }
            }
        } catch (e: Exception) {
            NymLogger.logError("analyzeClass", NymError.Unknown("类分析失败: ${e.message}"))
            Result.error(NymError.Unknown("类分析失败: ${e.message}"))
        }
    }
    
    override fun analyzeVariable(variable: PsiElement): Result<VariableContext> {
        return try {
            val language = detectLanguage(variable)
            
            when (language) {
                ProgrammingLanguage.JAVA -> {
                    when (variable) {
                        is PsiVariable -> javaAnalyzer.analyzeJavaVariable(variable)
                        is PsiField -> javaAnalyzer.analyzeJavaField(variable)
                        else -> Result.error(NymError.PlatformError.EditorUnavailable("元素不是有效的 Java 变量"))
                    }
                }
                ProgrammingLanguage.KOTLIN -> {
                    when (variable) {
                        is KtProperty -> kotlinAnalyzer.analyzeKotlinProperty(variable)
                        is KtParameter -> kotlinAnalyzer.analyzeKotlinParameter(variable)
                        else -> Result.error(NymError.PlatformError.EditorUnavailable("元素不是有效的 Kotlin 变量"))
                    }
                }
                else -> {
                    Result.error(NymError.PlatformError.EditorUnavailable("不支持的语言类型: $language"))
                }
            }
        } catch (e: Exception) {
            NymLogger.logError("analyzeVariable", NymError.Unknown("变量分析失败: ${e.message}"))
            Result.error(NymError.Unknown("变量分析失败: ${e.message}"))
        }
    }
    
    override fun extractSurroundingContext(element: PsiElement, radius: Int): Result<SurroundingContext> {
        return try {
            val containingFile = element.containingFile
            val document = PsiDocumentManager.getInstance(project).getDocument(containingFile)
                ?: return Result.error(NymError.PlatformError.EditorUnavailable("无法获取文档"))
            
            val elementStartOffset = element.textRange.startOffset
            val elementEndOffset = element.textRange.endOffset
            
            val startLine = document.getLineNumber(elementStartOffset)
            val endLine = document.getLineNumber(elementEndOffset)
            
            // 提取前置代码
            val precedingLines = mutableListOf<String>()
            for (i in maxOf(0, startLine - radius) until startLine) {
                val lineStartOffset = document.getLineStartOffset(i)
                val lineEndOffset = document.getLineEndOffset(i)
                precedingLines.add(document.getText().substring(lineStartOffset, lineEndOffset).trim())
            }
            
            // 提取后续代码
            val followingLines = mutableListOf<String>()
            val totalLines = document.lineCount
            for (i in (endLine + 1)..minOf(totalLines - 1, endLine + radius)) {
                val lineStartOffset = document.getLineStartOffset(i)
                val lineEndOffset = document.getLineEndOffset(i)
                followingLines.add(document.getText().substring(lineStartOffset, lineEndOffset).trim())
            }
            
            // 提取导入语句
            val imports = extractImportStatements(containingFile)
            
            // 提取包声明
            val packageName = extractPackageDeclaration(containingFile)
            
            // 提取文件级注释
            val fileComments = extractFileComments(containingFile)
            
            // 分析命名模式
            val namingPatterns = analyzeNamingPatterns(containingFile)
            
            // 分析代码风格
            val codeStyleAnalysis = analyzeCodeStyle(containingFile)
            
            val surroundingContext = SurroundingContext(
                precedingCode = precedingLines.filter { it.isNotBlank() },
                followingCode = followingLines.filter { it.isNotBlank() },
                imports = imports,
                packageDeclaration = packageName,
                fileComments = fileComments,
                siblingElements = extractSiblingElements(element),
                namingPatterns = namingPatterns,
                codeStyleAnalysis = codeStyleAnalysis
            )
            
            Result.success(surroundingContext)
            
        } catch (e: Exception) {
            NymLogger.logError("extractSurroundingContext", NymError.Unknown("上下文提取失败: ${e.message}"))
            Result.error(NymError.Unknown("上下文提取失败: ${e.message}"))
        }
    }
    
    override fun detectLanguage(element: PsiElement): ProgrammingLanguage {
        val file = element.containingFile
        return when {
            file.language.id == "JAVA" || file.name.endsWith(".java") -> ProgrammingLanguage.JAVA
            file.language.id == "kotlin" || file.name.endsWith(".kt") -> ProgrammingLanguage.KOTLIN
            file.name.endsWith(".py") -> ProgrammingLanguage.PYTHON
            file.name.endsWith(".js") -> ProgrammingLanguage.JAVASCRIPT
            file.name.endsWith(".ts") -> ProgrammingLanguage.TYPESCRIPT
            file.name.endsWith(".cs") -> ProgrammingLanguage.C_SHARP
            file.name.endsWith(".cpp") || file.name.endsWith(".cc") -> ProgrammingLanguage.CPP
            file.name.endsWith(".go") -> ProgrammingLanguage.GO
            file.name.endsWith(".rs") -> ProgrammingLanguage.RUST
            file.name.endsWith(".swift") -> ProgrammingLanguage.SWIFT
            else -> ProgrammingLanguage.OTHER
        }
    }
    
    /**
     * 提取导入语句
     */
    private fun extractImportStatements(file: PsiFile): List<String> {
        val imports = mutableListOf<String>()
        
        when (file) {
            is PsiJavaFile -> {
                file.importList?.allImportStatements?.forEach { importStatement ->
                    importStatement.importReference?.qualifiedName?.let { qualifiedName ->
                        imports.add("import $qualifiedName")
                    }
                }
            }
            is KtFile -> {
                file.importDirectives.forEach { importDirective ->
                    // K2-compatible approach: extract import from the directive text directly
                    importDirective.text.let { importText ->
                        if (importText.startsWith("import ") && !importText.contains("*")) {
                            imports.add(importText.trim())
                        }
                    }
                }
            }
        }
        
        return imports
    }
    
    /**
     * 提取包声明
     */
    private fun extractPackageDeclaration(file: PsiFile): String? {
        return when (file) {
            is PsiJavaFile -> file.packageName.takeIf { it.isNotEmpty() }
            is KtFile -> file.packageDirective?.fqName?.asString()?.takeIf { it.isNotEmpty() }
            else -> null
        }
    }
    
    /**
     * 提取文件级注释
     */
    private fun extractFileComments(file: PsiFile): List<String> {
        val comments = mutableListOf<String>()
        
        // 查找文件开头的注释
        var element: PsiElement? = file.firstChild
        while (element != null && element is PsiComment) {
            comments.add(element.text.trim())
            element = element.nextSibling
        }
        
        return comments
    }
    
    /**
     * 提取兄弟元素
     */
    private fun extractSiblingElements(element: PsiElement): List<String> {
        val siblings = mutableListOf<String>()
        val parent = element.parent
        
        if (parent != null) {
            parent.children.forEach { child ->
                if (child != element && child.javaClass == element.javaClass) {
                    // 获取元素的简化表示
                    val representation = when (child) {
                        is PsiMethod -> "${child.name}(${child.parameterList.parameters.joinToString { it.type.presentableText }})"
                        is PsiField -> "${child.name}: ${child.type.presentableText}"
                        is KtFunction -> "${child.name}(${child.valueParameters.joinToString { "${it.name}: ${it.typeReference?.text}" }})"
                        is KtProperty -> "${child.name}: ${child.typeReference?.text}"
                        else -> child.text.take(50) // 限制长度
                    }
                    siblings.add(representation)
                }
            }
        }
        
        return siblings
    }
    
    /**
     * 分析命名模式
     */
    private fun analyzeNamingPatterns(file: PsiFile): NamingPatternAnalysis {
        val names = mutableListOf<String>()
        val prefixes = mutableMapOf<String, Int>()
        val suffixes = mutableMapOf<String, Int>()
        
        // 收集文件中的标识符
        file.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                when (element) {
                    is PsiMethod -> names.add(element.name)
                    is PsiField -> names.add(element.name)
                    is PsiClass -> names.add(element.name ?: "")
                    is KtFunction -> names.add(element.name ?: "")
                    is KtProperty -> names.add(element.name ?: "")
                    is KtClass -> names.add(element.name ?: "")
                }
                super.visitElement(element)
            }
        })
        
        // 分析命名约定
        val camelCaseCount = names.count { name -> name.matches(Regex("[a-z][a-zA-Z0-9]*")) }
        val pascalCaseCount = names.count { name -> name.matches(Regex("[A-Z][a-zA-Z0-9]*")) }
        val snakeCaseCount = names.count { name -> name.contains("_") }
        
        val convention = when {
            pascalCaseCount > camelCaseCount && pascalCaseCount > snakeCaseCount -> NamingConvention.PASCAL_CASE
            snakeCaseCount > camelCaseCount && snakeCaseCount > pascalCaseCount -> NamingConvention.SNAKE_CASE
            camelCaseCount > 0 -> NamingConvention.CAMEL_CASE
            else -> NamingConvention.MIXED
        }
        
        // 分析前缀和后缀
        names.forEach { name ->
            if (name.length > 3) {
                val prefix = name.take(3)
                val suffix = name.takeLast(3)
                prefixes[prefix] = prefixes.getOrDefault(prefix, 0) + 1
                suffixes[suffix] = suffixes.getOrDefault(suffix, 0) + 1
            }
        }
        
        return NamingPatternAnalysis(
            conventionType = convention,
            commonPrefixes = prefixes.filter { it.value > 1 }.keys.toList(),
            commonSuffixes = suffixes.filter { it.value > 1 }.keys.toList(),
            averageLength = if (names.isNotEmpty()) names.map { it.length }.average().toInt() else 0,
            domainTerms = extractDomainTerms(names)
        )
    }
    
    /**
     * 分析代码风格
     */
    private fun analyzeCodeStyle(file: PsiFile): CodeStyleAnalysis {
        val document = PsiDocumentManager.getInstance(project).getDocument(file)
        val text = document?.text ?: file.text
        
        // 分析缩进
        val lines = text.lines()
        val indentedLines = lines.filter { it.startsWith(" ") || it.startsWith("\t") }
        val spaceIndents = indentedLines.count { it.startsWith(" ") }
        val tabIndents = indentedLines.count { it.startsWith("\t") }
        
        val indentationType = if (spaceIndents > tabIndents) "spaces" else "tabs"
        
        // 分析缩进大小（基于空格）
        val spaceIndentSizes = indentedLines
            .filter { it.startsWith(" ") }
            .map { line -> line.takeWhile { it == ' ' }.length }
            .filter { it > 0 }
            .groupBy { it }
        
        val indentationSize = spaceIndentSizes.maxByOrNull { it.value.size }?.key ?: 4
        
        // 分析平均行长度
        val averageLineLength = lines.filter { it.isNotBlank() }.map { it.length }.average().toInt()
        
        return CodeStyleAnalysis(
            indentationType = indentationType,
            indentationSize = indentationSize,
            bracketStyle = "same_line", // 默认值，可以进一步分析
            commentStyle = detectCommentStyle(file),
            lineLength = averageLineLength,
            useBraces = text.contains("{") && text.contains("}")
        )
    }
    
    /**
     * 检测注释风格
     */
    private fun detectCommentStyle(file: PsiFile): String {
        return when (detectLanguage(file)) {
            ProgrammingLanguage.JAVA, ProgrammingLanguage.KOTLIN -> "javadoc"
            ProgrammingLanguage.JAVASCRIPT, ProgrammingLanguage.TYPESCRIPT -> "jsdoc"
            else -> "line"
        }
    }
    
    /**
     * 提取领域术语
     */
    private fun extractDomainTerms(names: List<String>): List<String> {
        val commonTerms = setOf(
            "service", "manager", "handler", "controller", "repository", "dao",
            "util", "helper", "factory", "builder", "config", "settings"
        )
        
        return names.flatMap { name ->
            commonTerms.filter { term ->
                name.lowercase().contains(term)
            }
        }.distinct()
    }
    
    /**
     * 获取项目信息
     */
    fun getProjectInfo(): ProjectInfo {
        return ProjectInfo(
            projectName = project.name,
            projectType = detectProjectType(),
            framework = detectFramework(),
            buildTool = detectBuildTool(),
            javaVersion = detectJavaVersion(),
            kotlinVersion = detectKotlinVersion()
        )
    }
    
    private fun detectProjectType(): String? {
        // 基于文件结构检测项目类型
        val baseDir = project.baseDir
        return when {
            baseDir?.findChild("pom.xml") != null -> "Maven"
            baseDir?.findChild("build.gradle") != null || baseDir?.findChild("build.gradle.kts") != null -> "Gradle"
            else -> null
        }
    }
    
    private fun detectFramework(): String? {
        // 可以通过依赖或注解检测框架
        return null // 简化实现
    }
    
    private fun detectBuildTool(): String? {
        return detectProjectType() // 简化实现
    }
    
    private fun detectJavaVersion(): String? {
        // 可以通过模块设置检测Java版本
        return null // 简化实现
    }
    
    private fun detectKotlinVersion(): String? {
        // 可以通过Kotlin插件检测版本
        return null // 简化实现
    }
}