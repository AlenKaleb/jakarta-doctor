package com.alenkaleb.jakartadoctor.actions

import com.alenkaleb.jakartadoctor.inspection.JakartaImportInspection
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiImportStatement
import com.intellij.psi.PsiJavaFile

object JavaImportMigrator {

    fun migrate(file: PsiJavaFile) {
        val importList = file.importList ?: return

        // ✅ importStatements = só imports "normais" (NÃO inclui import static)
        val imports = importList.importStatements.toList()

        for (stmt in imports) {
            // Renderiza corretamente (preserva .* quando for on-demand)
            val qname = stmt.importReference?.qualifiedName ?: continue
            val rendered = if (stmt.isOnDemand) "$qname.*" else qname

            val suggested = JakartaImportInspection.suggest(rendered) ?: continue

            // Segurança extra: se o original é on-demand, garante .* no sugerido
            val desired = ensureJavaWildcardIfNeeded(stmt, suggested)
            if (desired == rendered) continue

            // Dedupe: se já existe um import idêntico ao desejado, remove o atual
            if (javaImportExists(importList.importStatements.toList(), stmt, desired)) {
                stmt.delete()
                continue
            }

            val newStmt = parseJavaImportStatement(file, desired) ?: continue
            stmt.replace(newStmt)
        }
    }

    private fun ensureJavaWildcardIfNeeded(stmt: PsiImportStatement, suggested: String): String {
        // suggested pode vir sem alias (Java não tem), mas pode vir com ".*"
        val base = suggested.split(" as ", limit = 2)[0].trim()
        return if (stmt.isOnDemand && !base.endsWith(".*")) "$base.*" else base
    }

    private fun javaImportExists(
        existing: List<PsiImportStatement>,
        current: PsiImportStatement,
        desired: String
    ): Boolean {
        return existing
            .asSequence()
            .filter { it != current }
            .any { other ->
                val q = other.importReference?.qualifiedName ?: return@any false
                val otherRendered = if (other.isOnDemand) "$q.*" else q
                otherRendered == desired
            }
    }

    private fun parseJavaImportStatement(file: PsiJavaFile, newRef: String): PsiImportStatement? {
        val dummyText = """
            import $newRef;
            class Dummy {}
        """.trimIndent()

        val dummy = PsiFileFactory.getInstance(file.project)
            .createFileFromText("Dummy.java", JavaFileType.INSTANCE, dummyText)

        val java = dummy as? PsiJavaFile ?: return null
        return java.importList?.importStatements?.firstOrNull()
    }
}
