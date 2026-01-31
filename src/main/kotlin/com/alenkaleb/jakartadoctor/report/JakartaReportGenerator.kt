package com.alenkaleb.jakartadoctor.report

import com.intellij.openapi.application.ApplicationManager
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

object JakartaReportGenerator {

    data class PkgStats(var files: Int = 0, var imports: Int = 0)

    data class ReportResult(
        val markdown: String,
        val totalFiles: Int,
        val totalImports: Int,
        val byPackage: Map<String, PkgStats>
    )

    /**
     * Conta imports que COMEÇAM com "javax." em arquivos Java/Kotlin dentro dos content roots.
     * - Java: conta "import javax.foo.Bar;" e "import javax.foo.*;"
     * - Kotlin: conta "import javax.foo.Bar" e "import javax.foo.*"
     * - Ignora static import em Java (MVP)
     */
    fun generate(project: Project): ReportResult {
        val files = collectCandidateFiles(project)
        val psiManager = PsiManager.getInstance(project)

        val byPkg = linkedMapOf<String, PkgStats>()
        var totalImports = 0
        var filesWithFindings = 0

        for (vf in files) {
            val psi = psiManager.findFile(vf) ?: continue

            val (pkg, count) = when (psi) {
                is PsiJavaFile -> psi.packageName.ifBlank { "<default>" } to countJavaImports(psi)
                is KtFile -> psi.packageFqName.asString().ifBlank { "<default>" } to countKotlinImports(psi)
                else -> "<unknown>" to 0
            }

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
     * Escreve na raiz do projeto usando VFS + WriteAction (pra aparecer/abrir sempre).
     * Retorna o VirtualFile pronto pra abrir.
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
        val index = ProjectFileIndex.getInstance(project)
        val roots = ProjectRootManager.getInstance(project).contentRoots.toList()

        val out = ArrayList<VirtualFile>(2048)

        for (root in roots) {
            VfsUtilCore.iterateChildrenRecursively(root, null) { vf ->
                if (vf.isDirectory) return@iterateChildrenRecursively true

                val ext = vf.extension?.lowercase()
                if (ext != "java" && ext != "kt" && ext != "kts") return@iterateChildrenRecursively true

                // ignora fora do content root (ex.: build, out, caches)
                if (!index.isInContent(vf)) return@iterateChildrenRecursively true

                // filtro rápido por texto
                val text = runCatching { VfsUtilCore.loadText(vf) }.getOrNull()
                if (text != null && text.contains("javax.")) out.add(vf)

                true
            }
        }

        return out
    }

    private fun countJavaImports(file: PsiJavaFile): Int {
        val importList = file.importList ?: return 0
        var hits = 0

        // ✅ só imports normais (não inclui import static)
        for (stmt in importList.importStatements) {
            val q = stmt.importReference?.qualifiedName ?: continue
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
        appendLine("- Files with javax imports: `$filesWithFindings`")
        appendLine("- Total javax imports found: `$totalImports`")
        appendLine()

        appendLine("## By package")
        appendLine()
        appendLine("| Package | Files | javax imports |")
        appendLine("|---|---:|---:|")

        val sorted = byPackage.toList().sortedByDescending { it.second.imports }
        for ((pkg, s) in sorted) {
            appendLine("| `$pkg` | ${s.files} | ${s.imports} |")
        }

        appendLine()
        appendLine("## Notes")
        appendLine("- Counts are based on `import` statements starting with `javax.`.")
        appendLine("- Star imports (`.*`) are counted as 1 import.")
        appendLine("- Static imports may be ignored (MVP).")
    }
}
