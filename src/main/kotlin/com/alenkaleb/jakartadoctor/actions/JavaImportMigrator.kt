package com.alenkaleb.jakartadoctor.actions

import com.alenkaleb.jakartadoctor.inspection.JakartaImportInspection
import com.alenkaleb.jakartadoctor.util.JakartaClasspathChecker
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiImportStatement
import com.intellij.psi.PsiJavaFile

/**
 * Migrates javax.* imports to jakarta.* equivalents in Java files.
 *
 * This migrator performs the following safety checks before applying migrations:
 * 1. Verifies the javax import is one that was migrated to jakarta in Jakarta EE 9+
 * 2. Checks if the target jakarta.* package/class is available in the module classpath
 */
object JavaImportMigrator {

    /**
     * Result of migration operation on a file.
     */
    data class MigrationResult(
        val migratedCount: Int,
        val skippedCount: Int,
        val skippedReasons: List<String>
    )

    /**
     * Migrate all eligible javax.* imports in the file to jakarta.*.
     *
     * @param file The Java file to process
     * @param checkClasspath Whether to verify jakarta is available in classpath before migrating
     * @return MigrationResult with counts of migrated and skipped imports
     */
    fun migrate(file: PsiJavaFile, checkClasspath: Boolean = true): MigrationResult {
        val importList = file.importList ?: return MigrationResult(0, 0, emptyList())

        // ✅ importStatements = só imports "normais" (NÃO inclui import static)
        val imports = importList.importStatements.toList()

        var migratedCount = 0
        var skippedCount = 0
        val skippedReasons = mutableListOf<String>()

        for (stmt in imports) {
            // Renderiza corretamente (preserva .* quando for on-demand)
            val qname = stmt.importReference?.qualifiedName ?: continue
            val rendered = if (stmt.isOnDemand) "$qname.*" else qname

            // ✅ Check if this is a migratable import (safety check)
            if (!JakartaClasspathChecker.isMigratableImport(qname)) {
                // This javax package should not be migrated (e.g., javax.sql)
                continue
            }

            val suggested = JakartaImportInspection.suggest(rendered) ?: continue

            // Segurança extra: se o original é on-demand, garante .* no sugerido
            val desired = ensureJavaWildcardIfNeeded(stmt, suggested)
            if (desired == rendered) continue

            // ✅ Check if jakarta library is available in classpath
            if (checkClasspath && !JakartaClasspathChecker.isJakartaAvailable(stmt, desired)) {
                skippedCount++
                skippedReasons.add("$qname: Jakarta library not found in classpath")
                continue
            }

            // Dedupe: se já existe um import idêntico ao desejado, remove o atual
            if (javaImportExists(importList.importStatements.toList(), stmt, desired)) {
                stmt.delete()
                migratedCount++
                continue
            }

            val newStmt = parseJavaImportStatement(file, desired) ?: continue
            stmt.replace(newStmt)
            migratedCount++
        }

        return MigrationResult(migratedCount, skippedCount, skippedReasons)
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
