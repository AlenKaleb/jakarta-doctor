package com.alenkaleb.jakartadoctor.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
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
import com.alenkaleb.jakartadoctor.licensing.LicenseGate
import org.jetbrains.kotlin.psi.KtFile

class MigrateJakartaImportsAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val gate = LicenseGate(project)
        val state = gate.state()
        if (state == LicenseGate.State.UNLICENSED) {
            gate.notifyUnlicensedOnce()
            return
        }

        // Escopo: se usuário selecionou arquivos/pastas no Project view, usa isso; senão, varre o projeto todo.
        val selection = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList().orEmpty()

        ProgressManager.getInstance()
            .run(object : Task.Backgroundable(project, "Jakarta Doctor: migrate imports", true) {
                override fun run(indicator: ProgressIndicator) {
                    val files = collectCandidateFiles(project, selection, indicator)

                    // Aplica em 1 comando (1 undo), mas cuidado em projetos gigantes.
                    WriteCommandAction.writeCommandAction(project)
                        .withName("Jakarta Doctor: migrate imports")
                        .run<RuntimeException> {
                            applyFixes(project, files, indicator)
                        }
                }
            })
    }

    private fun collectCandidateFiles(
        project: Project,
        selection: List<VirtualFile>,
        indicator: ProgressIndicator
    ): List<VirtualFile> {
        val index = ProjectFileIndex.getInstance(project)
        val roots = if (selection.isNotEmpty()) selection
        else ProjectRootManager.getInstance(project).contentRoots.toList()
        val out = ArrayList<VirtualFile>(1024)

        for (root in roots) {
            collectCandidatesFromRoot(root, index, indicator, out)
        }
        return out
    }

    private fun collectCandidatesFromRoot(
        root: VirtualFile,
        index: ProjectFileIndex,
        indicator: ProgressIndicator,
        out: MutableList<VirtualFile>
    ) {
        if (!root.isDirectory) {
            addIfCandidate(root, index, out)
            return
        }

        VfsUtilCore.iterateChildrenRecursively(root, null) { vf ->
            if (indicator.isCanceled) return@iterateChildrenRecursively false
            if (!vf.isDirectory) addIfCandidate(vf, index, out)
            true
        }
    }

    private fun addIfCandidate(vf: VirtualFile, index: ProjectFileIndex, out: MutableList<VirtualFile>) {
        val isCode = vf.extension == "java" || vf.extension == "kt" || vf.extension == "kts"
        if (!isCode) return
        if (!index.isInContent(vf)) return

        // filtro rápido por texto: evita PSI load desnecessário
        val text = runCatching { VfsUtilCore.loadText(vf) }.getOrNull()
        if (text != null && text.contains("javax.")) out.add(vf)
    }

    private fun applyFixes(project: Project, files: List<VirtualFile>, indicator: ProgressIndicator) {
        val psiManager = PsiManager.getInstance(project)

        var processed = 0
        for (vf in files) {
            if (indicator.isCanceled) return
            indicator.text = "Migrating imports: ${vf.presentableUrl}"
            indicator.fraction = processed.toDouble() / files.size.coerceAtLeast(1)

            val psiFile = psiManager.findFile(vf)
            if (psiFile == null) {
                processed++
                continue
            }

            when (psiFile) {
                is PsiJavaFile -> JavaImportMigrator.migrate(psiFile)
                is KtFile -> KotlinImportMigrator.migrate(psiFile) // ✅ só 1 argumento
            }

            processed++
        }
    }

}
