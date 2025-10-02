package com.cw2.nym.integrations.psi

import com.cw2.nym.core.logging.NymLogger
import com.cw2.nym.core.result.Result
import com.cw2.nym.core.exception.NymError
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.*
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import org.jetbrains.kotlin.idea.KotlinFileType
import com.intellij.ide.highlighter.JavaFileType
import org.jetbrains.kotlin.psi.*
import java.io.File

/**
 * 未使用代码扫描器
 *
 * 为什么需要：
 * - 用户希望在附件/当前工程目录下扫描所有文件，找出未被使用的文件、类、方法（函数）以便清理。
 * - 由于 IntelliJ 本身具备引用检索能力（ReferencesSearch），我们在后台任务中遍历 PSI 元素并统计被引用情况。
 *
 * 注意：
 * - 该实现为“保守扫描”，仅判断项目范围内的代码引用。对由反射、外部框架（如 plugin.xml 注册、ServiceLoader）引用的元素，可能无法识别。
 * - 为避免阻塞 EDT，扫描在 DumbService.runWhenSmart + Backgroundable 任务内执行。
 */
internal object UnusedCodeScanner {

    data class UnusedSymbol(
        val fqn: String,
        val locationFile: String,
        val kind: Kind
    ) {
        enum class Kind { FILE, CLASS, METHOD, PROPERTY }
    }

    data class Report(
        val rootPath: String,
        val unused: List<UnusedSymbol>,
        val scannedFiles: Int,
        val scannedSymbols: Int
    )

    /**
     * 启动一个后台任务执行扫描，并将结果以 Result 返回至回调。
     */
    fun scanInBackground(project: Project, onFinish: (Result<Report>) -> Unit) {
        DumbService.getInstance(project).runWhenSmart {
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Nym: 扫描未使用代码", true) {
                override fun run(indicator: ProgressIndicator) {
                    val res = try {
                        indicator.text = "正在收集文件..."
                        val report = scan(project, indicator)
                        Result.Success(report)
                    } catch (t: Throwable) {
                        NymLogger.error("UNUSED_SCAN", "scan failed", error = t)
                        Result.Error(NymError.Unknown(t.message ?: "unknown", t))
                    }
                    onFinish(res)
                }
            })
        }
    }

    /**
     * 核心扫描逻辑（可同步调用于后台线程）。
     */
    fun scan(project: Project, indicator: ProgressIndicator? = null): Report {
        val projectBase = project.basePath ?: ""
        val scope = GlobalSearchScope.projectScope(project)

        val psiManager = PsiManager.getInstance(project)
        val allVFiles = mutableListOf<com.intellij.openapi.vfs.VirtualFile>()

        // 仅扫描 Kotlin 与 Java 源文件
        allVFiles += FileTypeIndex.getFiles(KotlinFileType.INSTANCE, scope)
        allVFiles += FileTypeIndex.getFiles(JavaFileType.INSTANCE, scope)

        var scannedFiles = 0
        var scannedSymbols = 0
        val unused = mutableListOf<UnusedSymbol>()

        for ((i, vFile) in allVFiles.withIndex()) {
            indicator?.checkCanceled()
            indicator?.fraction = (i + 1).toDouble() / allVFiles.size.coerceAtLeast(1)
            indicator?.text2 = vFile.path

            val psiFile = ReadAction.compute<PsiFile?, Throwable> {
                psiManager.findFile(vFile)
            } ?: continue
            scannedFiles++

            if (psiFile is KtFile) {
                val fileUnused = scanKotlinFile(psiFile, scope)
                scannedSymbols += fileUnused.second
                unused += fileUnused.first
            } else if (psiFile is PsiJavaFile) {
                val fileUnused = scanJavaFile(psiFile, scope)
                scannedSymbols += fileUnused.second
                unused += fileUnused.first
            }
        }

        // 额外：如果整个文件里一个被引用的符号都没有，则把文件也标记为 FILE 未使用
        // 注意：这里的“文件未使用”定义是：文件内所有可被引用符号均未被引用。
        val fileGroup = unused.groupBy { it.locationFile }
        val fileUnusedSet = mutableSetOf<String>()
        for (vFile in allVFiles) {
            val path = vFile.path
            val psiFile = psiManager.findFile(vFile) ?: continue
            val totalSymbols = countSymbolsInFile(psiFile)
            val unusedForFile = fileGroup[path]?.size ?: 0
            if (totalSymbols > 0 && unusedForFile >= totalSymbols) {
                fileUnusedSet += path
            }
        }
        for (path in fileUnusedSet) {
            unused += UnusedSymbol(path, path, UnusedSymbol.Kind.FILE)
        }

        return Report(
            rootPath = projectBase,
            unused = unused.sortedWith(compareBy({ it.kind.name }, { it.locationFile }, { it.fqn })),
            scannedFiles = scannedFiles,
            scannedSymbols = scannedSymbols
        )
    }

    private fun countSymbolsInFile(file: PsiFile): Int = when (file) {
        is KtFile -> ReadAction.compute<Int, Throwable> {
            var count = 0
            file.declarations.forEach { decl ->
                when (decl) {
                    is KtClass -> count++
                    is KtNamedFunction -> count++
                    is KtProperty -> count++
                }
            }
            count
        }
        is PsiJavaFile -> ReadAction.compute<Int, Throwable> {
            var count = 0
            file.classes.forEach { cls ->
                count++
                cls.fields.forEach { _ -> count++ }
                cls.methods.forEach { _ -> count++ }
            }
            count
        }
        else -> 0
    }

    private fun scanKotlinFile(file: KtFile, scope: GlobalSearchScope): Pair<List<UnusedSymbol>, Int> {
        return ReadAction.compute<Pair<List<UnusedSymbol>, Int>, Throwable> {
            val unused = mutableListOf<UnusedSymbol>()
            var scanned = 0
            val path = file.virtualFile?.path ?: file.name

            // 顶层声明
            file.declarations.forEach { decl ->
                when (decl) {
                    is KtClass -> {
                        scanned++
                        if (!hasReferences(decl, scope)) {
                            val fqn = decl.fqName?.asString() ?: decl.name ?: "<anonymous>"
                            unused += UnusedSymbol(fqn, path, UnusedSymbol.Kind.CLASS)
                        }
                        // 类内方法/属性
                        decl.getBody()?.declarations?.forEach { member ->
                            when (member) {
                                is KtNamedFunction -> {
                                    scanned++
                                    if (!hasReferences(member, scope)) {
                                        val fqn = (member.fqName?.asString() ?: member.name ?: "<fn>")
                                        unused += UnusedSymbol(fqn, path, UnusedSymbol.Kind.METHOD)
                                    }
                                }
                                is KtProperty -> {
                                    scanned++
                                    if (!hasReferences(member, scope)) {
                                        val fqn = (member.fqName?.asString() ?: member.name ?: "<prop>")
                                        unused += UnusedSymbol(fqn, path, UnusedSymbol.Kind.PROPERTY)
                                    }
                                }
                            }
                        }
                    }
                    is KtNamedFunction -> {
                        scanned++
                        if (!hasReferences(decl, scope)) {
                            val fqn = decl.fqName?.asString() ?: decl.name ?: "<fn>"
                            unused += UnusedSymbol(fqn, path, UnusedSymbol.Kind.METHOD)
                        }
                    }
                    is KtProperty -> {
                        scanned++
                        if (!hasReferences(decl, scope)) {
                            val fqn = decl.fqName?.asString() ?: decl.name ?: "<prop>"
                            unused += UnusedSymbol(fqn, path, UnusedSymbol.Kind.PROPERTY)
                        }
                    }
                }
            }
            Pair(unused, scanned)
        }
    }

    private fun scanJavaFile(file: PsiJavaFile, scope: GlobalSearchScope): Pair<List<UnusedSymbol>, Int> {
        val unused = mutableListOf<UnusedSymbol>()
        var scanned = 0
        val path = file.virtualFile?.path ?: file.name
        ReadAction.run<Throwable> {
            file.classes.forEach { cls ->
                scanned++
                if (!hasReferences(cls, scope)) {
                    val fqn = cls.qualifiedName ?: cls.name ?: "<anonymous>"
                    unused += UnusedSymbol(fqn, path, UnusedSymbol.Kind.CLASS)
                }
                cls.methods.forEach { m ->
                    scanned++
                    if (!hasReferences(m, scope)) {
                        val fqn = (m.containingClass?.qualifiedName ?: "") + "#" + (m.name ?: "<method>")
                        unused += UnusedSymbol(fqn, path, UnusedSymbol.Kind.METHOD)
                    }
                }
                cls.fields.forEach { f ->
                    scanned++
                    if (!hasReferences(f, scope)) {
                        val fqn = (cls.qualifiedName ?: "") + "#" + (f.name ?: "<field>")
                        unused += UnusedSymbol(fqn, path, UnusedSymbol.Kind.PROPERTY)
                    }
                }
            }
        }
        return Pair(unused, scanned)
    }

    private fun hasReferences(element: PsiElement, scope: GlobalSearchScope): Boolean {
        // 排除一些明显不应统计的情况（例如匿名/合成元素）
        if (!element.isValid) return false
        // 可能会有 getter/setter 合成方法，在 Kotlin 下不必单独统计
        val usages = ReferencesSearch.search(element, scope).findAll()
        // 使用计数：排除 self-declaration（ReferencesSearch 不会返回定义处）
        return usages.isNotEmpty()
    }

    /**
     * 将报告写入项目根目录下 build/nym-unused-report.txt。
     */
    fun writeReportToFile(project: Project, report: Report): File? {
        val base = project.basePath ?: return null
        val outDir = File(base, "build")
        if (!outDir.exists()) outDir.mkdirs()
        val out = File(outDir, "nym-unused-report.txt")
        out.printWriter().use { pw ->
            pw.println("Nym 未使用代码报告")
            pw.println("Root: ${report.rootPath}")
            pw.println("Scanned Files: ${report.scannedFiles}, Symbols: ${report.scannedSymbols}")
            pw.println("Unused Count: ${report.unused.size}")
            pw.println()
            report.unused.forEach { u ->
                pw.println("${u.kind}\t${u.fqn}\t${u.locationFile}")
            }
        }
        // 刷新 VFS，以便在 IDE 中可见
        VirtualFileManager.getInstance().refreshAndFindFileByUrl(out.toURI().toURL().toString())
        return out
    }
}
