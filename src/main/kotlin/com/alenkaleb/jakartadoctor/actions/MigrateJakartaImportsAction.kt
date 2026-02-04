package com.alenkaleb.jakartadoctor.actions

import com.alenkaleb.jakartadoctor.licensing.LicenseGate
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.psi.KtFile

/**
 * Batch action to migrate all javax.* imports to jakarta.* equivalents in the selected scope.
 *
 * This action performs the following safety checks before applying migrations:
 * 1. Verifies each javax import is one that was migrated to jakarta in Jakarta EE 9+
 * 2. Checks if the target jakarta.* package/class is available in the module classpath
 * 3. Provides a summary notification of migrated and skipped imports
 */
class MigrateJakartaImportsAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val gate = LicenseGate(project)
        if (gate.state() == LicenseGate.State.UNLICENSED) {
            gate.notifyUnlicensedOnce()
            return
        }

        // Escopo: se usuário selecionou arquivos/pastas no Project view, usa isso; senão, varre o projeto todo.
        val selection = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList().orEmpty()

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Jakarta Doctor: migrate imports", true) {
                override fun run(indicator: ProgressIndicator) {
                    val files = collectCandidateFiles(project, selection, indicator)
                    if (files.isEmpty()) {
                        showNotification(
                            project,
                            "Nenhum arquivo encontrado",
                            "Não foram encontrados arquivos com imports javax.* no escopo selecionado.",
                            NotificationType.INFORMATION
                        )
                        return
                    }

                    // 1 undo. (Se ficar pesado, dá pra chunkar em lotes)
                    WriteCommandAction.writeCommandAction(project)
                        .withName("Jakarta Doctor: migrate imports")
                        .run<RuntimeException> {
                            val result = applyFixes(project, files, indicator)
                            showMigrationResult(project, result)
                        }
                }
            }
        )
    }

    /**
     * Result of the batch migration operation.
     */
    private data class BatchMigrationResult(
        val filesProcessed: Int,
        val totalMigrated: Int,
        val totalSkipped: Int,
        val skippedReasons: List<String>
    )

    private fun collectCandidateFiles(
        project: Project,
        selection: List<VirtualFile>,
        indicator: ProgressIndicator
    ): List<VirtualFile> {
        val roots: List<VirtualFile> =
            if (selection.isNotEmpty()) selection
            else ProjectRootManager.getInstance(project).contentRoots.toList()

        // 1) coleta "bruta" por extensão (barato, sem índice)
        val rough = ArrayList<VirtualFile>(2048)
        for (root in roots) {
            if (indicator.isCanceled) return emptyList()
            collectCandidatesFromRoot(root, indicator, rough)
        }

        if (rough.isEmpty()) return emptyList()

        // 2) filtra por "isInContent" em uma ReadAction curta (evita crash de threading)
        val inContent = ReadAction.compute<List<VirtualFile>, RuntimeException> {
            val index = ProjectFileIndex.getInstance(project)
            rough.filter { vf -> index.isInContent(vf) }
        }

        if (inContent.isEmpty()) return emptyList()

        // 3) filtro rápido por texto "javax." (VFS loadText; sem PSI)
        val out = ArrayList<VirtualFile>(inContent.size)
        for (vf in inContent) {
            if (indicator.isCanceled) return out
            val text = runCatching { VfsUtilCore.loadText(vf) }.getOrNull()
            if (text != null && text.contains("javax.")) out.add(vf)
        }

        return out
    }

    private fun collectCandidatesFromRoot(
        root: VirtualFile,
        indicator: ProgressIndicator,
        out: MutableList<VirtualFile>
    ) {
        if (!root.isDirectory) {
            addIfRoughCandidate(root, out)
            return
        }

        VfsUtilCore.iterateChildrenRecursively(root, null) { vf ->
            if (indicator.isCanceled) return@iterateChildrenRecursively false
            if (!vf.isDirectory) addIfRoughCandidate(vf, out)
            true
        }
    }

    private fun addIfRoughCandidate(vf: VirtualFile, out: MutableList<VirtualFile>) {
        val ext = vf.extension ?: return
        val isCode = ext == "java" || ext == "kt" || ext == "kts"
        if (isCode) out.add(vf)
    }

    private fun applyFixes(
        project: Project,
        files: List<VirtualFile>,
        indicator: ProgressIndicator
    ): BatchMigrationResult {
        val psiManager = PsiManager.getInstance(project)

        var processed = 0
        var totalMigrated = 0
        var totalSkipped = 0
        val allSkippedReasons = mutableListOf<String>()

        val total = files.size.coerceAtLeast(1)

        for (vf in files) {
            if (indicator.isCanceled) break
            indicator.text = "Migrating imports: ${vf.presentableUrl}"
            indicator.fraction = processed.toDouble() / total

            val psiFile = ReadAction.compute<Any?, RuntimeException> {
                psiManager.findFile(vf)
            }

            if (psiFile == null) {
                processed++
                continue
            }

            when (psiFile) {
                is PsiJavaFile -> {
                    val result = JavaImportMigrator.migrate(psiFile, checkClasspath = true)
                    totalMigrated += result.migratedCount
                    totalSkipped += result.skippedCount
                    allSkippedReasons.addAll(result.skippedReasons)
                }
                is KtFile -> {
                    val result = KotlinImportMigrator.migrate(psiFile, checkClasspath = true)
                    totalMigrated += result.migratedCount
                    totalSkipped += result.skippedCount
                    allSkippedReasons.addAll(result.skippedReasons)
                }
            }

            processed++
        }

        return BatchMigrationResult(processed, totalMigrated, totalSkipped, allSkippedReasons)
    }

    private fun showMigrationResult(project: Project, result: BatchMigrationResult) {
        val title = if (result.totalMigrated > 0) "Migração concluída" else "Nenhuma migração realizada"

        val message = buildString {
            append("Arquivos processados: ${result.filesProcessed}. ")
            append("Imports migrados: ${result.totalMigrated}. ")
            if (result.totalSkipped > 0) {
                append("Imports ignorados: ${result.totalSkipped} (Jakarta não encontrado no classpath).")
            }
        }

        val type = when {
            result.totalMigrated > 0 && result.totalSkipped == 0 -> NotificationType.INFORMATION
            result.totalMigrated > 0 && result.totalSkipped > 0 -> NotificationType.WARNING
            result.totalSkipped > 0 -> NotificationType.WARNING
            else -> NotificationType.INFORMATION
        }

        showNotification(project, title, message, type)
    }

    private fun showNotification(
        project: Project,
        title: String,
        message: String,
        type: NotificationType
    ) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("JakartaDoctor")
            .createNotification(title, message, type)
            .notify(project)
    }
}
