package com.alenkaleb.jakartadoctor.report

import com.alenkaleb.jakartadoctor.util.JakartaClasspathChecker
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.psi.KtFile
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Generates migration reports for javax.* to jakarta.* migrations.
 *
 * Counts only imports eligible for migration:
 * - Only migratable javax packages (javax.persistence, javax.servlet, etc.)
 * - Excludes non-migratable packages (javax.sql, Java SE javax.xml.*, etc.)
 * - Ignores Java static imports (MVP)
 */
object JakartaReportGenerator {

    data class PkgStats(var files: Int = 0, var imports: Int = 0)

    data class ReportResult(
        val markdown: String,
        val totalFiles: Int,
        val totalImports: Int,
        val byPackage: Map<String, PkgStats>
    )

    fun generate(project: Project): ReportResult {
        val files = collectCandidateFiles(project)
        val psiManager = PsiManager.getInstance(project)

        val byPkg = linkedMapOf<String, PkgStats>()
        var totalImports = 0
        var filesWithFindings = 0

        for (vf in files) {
            val res = ReadAction.compute<Pair<String, Int>?, RuntimeException> {
                val psi = psiManager.findFile(vf) ?: return@compute null

                val pkgAndCount = when (psi) {
                    is PsiJavaFile -> psi.packageName.ifBlank { "<default>" } to countJavaImports(psi)
                    is KtFile      -> psi.packageFqName.asString().ifBlank { "<default>" } to countKotlinImports(psi)
                    else           -> "<unknown>" to 0
                }

                pkgAndCount
            } ?: continue

            val (pkg, count) = res
            if (count > 0) {
                filesWithFindings++
                totalImports += count
                val stat = byPkg.getOrPut(pkg) { PkgStats() }
                stat.files++
                stat.imports += count
            }
        }

        val md = buildMarkdown(
            projectName = project.name,
            scannedFiles = files.size,
            filesWithFindings = filesWithFindings,
            totalImports = totalImports,
            byPackage = byPkg
        )

        return ReportResult(
            markdown = md,
            totalFiles = files.size,
            totalImports = totalImports,
            byPackage = byPkg.toList()
                .sortedByDescending { it.second.imports }
                .toMap(linkedMapOf())
        )
    }

    /**
     * Writes report file to project root using VFS + WriteAction.
     */
    fun writeToProjectRoot(project: Project, markdown: String): VirtualFile {
        val basePath = project.basePath ?: error("project.basePath é null")
        val ioFile = File(basePath, "jakarta-doctor-report.md")

        if (!ioFile.exists()) {
            ioFile.parentFile?.mkdirs()
            ioFile.writeText("", Charsets.UTF_8)
        }

        val vf = LocalFileSystem.getInstance()
            .refreshAndFindFileByIoFile(ioFile)
            ?: error("Não consegui localizar no VFS: ${ioFile.absolutePath}")

        ApplicationManager.getApplication().runWriteAction {
            VfsUtil.saveText(vf, markdown)
        }

        vf.refresh(false, false)
        return vf
    }

    private fun collectCandidateFiles(project: Project): List<VirtualFile> {
        val roots = ProjectRootManager.getInstance(project).contentRoots.toList()

        // 1) rough: só por extensão (barato)
        val rough = ArrayList<VirtualFile>(2048)
        for (root in roots) {
            VfsUtilCore.iterateChildrenRecursively(root, null) { vf ->
                if (!vf.isDirectory) {
                    val ext = vf.extension?.lowercase()
                    if (ext == "java" || ext == "kt" || ext == "kts") rough.add(vf)
                }
                true
            }
        }
        if (rough.isEmpty()) return emptyList()

        // 2) filtra por content root em ReadAction
        val inContent = ReadAction.compute<List<VirtualFile>, RuntimeException> {
            val index = ProjectFileIndex.getInstance(project)
            rough.filter { vf -> index.isInContent(vf) }
        }
        if (inContent.isEmpty()) return emptyList()

        // 3) filtro rápido por texto
        val out = ArrayList<VirtualFile>(inContent.size)
        for (vf in inContent) {
            val text = runCatching { VfsUtilCore.loadText(vf) }.getOrNull()
            if (text != null && text.contains("javax.")) out.add(vf)
        }

        return out
    }

    private fun countJavaImports(file: PsiJavaFile): Int {
        val importList = file.importList ?: return 0
        var hits = 0

        // ✅ só imports normais (não inclui import static)
        for (stmt in importList.importStatements) {
            val q = stmt.importReference?.qualifiedName ?: continue
            if (!JakartaClasspathChecker.isMigratableImport(q)) continue

            val rendered = if (stmt.isOnDemand) "$q.*" else q
            if (rendered.startsWith("javax.")) hits++
        }
        return hits
    }

    private fun countKotlinImports(file: KtFile): Int {
        val importList = file.importList ?: return 0
        var hits = 0

        for (imp in importList.imports) {
            val q = imp.importedFqName?.asString() ?: continue
            if (!JakartaClasspathChecker.isMigratableImport(q)) continue

            val rendered = if (imp.isAllUnder) "$q.*" else q
            if (rendered.startsWith("javax.")) hits++
        }
        return hits
    }

    private fun buildMarkdown(
        projectName: String,
        scannedFiles: Int,
        filesWithFindings: Int,
        totalImports: Int,
        byPackage: Map<String, PkgStats>
    ): String = buildString {
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

        appendLine("# Jakarta Doctor Migration Report")
        appendLine()
        appendLine("- Project: `$projectName`")
        appendLine("- Generated at: `$now`")
        appendLine("- Scanned files: `$scannedFiles`")
        appendLine("- Files with migratable javax imports: `$filesWithFindings`")
        appendLine("- Total migratable javax imports found: `$totalImports`")
        appendLine()

        appendLine("## By package")
        appendLine()
        appendLine("| Package | Files | Migratable javax imports |")
        appendLine("|---|---:|---:|")

        val sorted = byPackage.toList().sortedByDescending { it.second.imports }
        for ((pkg, s) in sorted) {
            appendLine("| `$pkg` | ${s.files} | ${s.imports} |")
        }

        appendLine()
        appendLine("## Notes")
        appendLine("- Counts are based on `import` statements eligible for javax→jakarta migration.")
        appendLine("- Only migratable packages are counted (javax.persistence, javax.servlet, etc.).")
        appendLine("- Non-migratable packages (javax.sql, Java SE javax.xml.*, etc.) are excluded.")
        appendLine("- Star imports (`.*`) are counted as 1 import.")
        appendLine("- Static imports are ignored.")
    }
}
