package com.alenkaleb.jakartadoctor.actions

import com.alenkaleb.jakartadoctor.inspection.JakartaImportInspection
import com.alenkaleb.jakartadoctor.util.JakartaClasspathChecker
import com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective

/**
 * Migrates javax.* imports to jakarta.* equivalents in Kotlin files.
 *
 * This migrator performs the following safety checks before applying migrations:
 * 1. Verifies the javax import is one that was migrated to jakarta in Jakarta EE 9+
 * 2. Checks if the target jakarta.* package/class is available in the module classpath
 */
object KotlinImportMigrator {

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
     * @param file The Kotlin file to process
     * @param checkClasspath Whether to verify jakarta is available in classpath before migrating
     * @return MigrationResult with counts of migrated and skipped imports
     */
    fun migrate(file: KtFile, checkClasspath: Boolean = true): MigrationResult {
        val importList = file.importList ?: return MigrationResult(0, 0, emptyList())
        val imports = importList.imports.toList()

        var migratedCount = 0
        var skippedCount = 0
        val skippedReasons = mutableListOf<String>()

        for (imp in imports) {
            val rendered = renderKotlinImport(imp) ?: continue

            // Extract the base FQN without wildcard for safety check
            val baseFqn = imp.importedFqName?.asString() ?: continue

            // ✅ Check if this is a migratable import (safety check)
            if (!JakartaClasspathChecker.isMigratableImport(baseFqn)) {
                // This javax package should not be migrated (e.g., javax.sql)
                continue
            }

            val suggested = JakartaImportInspection.suggest(rendered) ?: continue

            // Segurança extra: preserva star import e alias conforme o original
            val desired = ensureKotlinStarAndAliasIfNeeded(imp, suggested)
            if (desired == rendered) continue

            // ✅ Check if jakarta library is available in classpath
            val jakartaFqn = desired.split(" as ", limit = 2)[0].trim()
            if (checkClasspath && !JakartaClasspathChecker.isJakartaAvailable(imp, jakartaFqn)) {
                skippedCount++
                skippedReasons.add("$baseFqn: Jakarta library not found in classpath")
                continue
            }

            // Dedupe: se já existe import idêntico, remove o atual
            if (kotlinImportExists(importList.imports, imp, desired)) {
                imp.delete()
                migratedCount++
                continue
            }

            val newDirective = parseKotlinImportDirective(file, desired) ?: continue
            imp.replace(newDirective)
            migratedCount++
        }

        return MigrationResult(migratedCount, skippedCount, skippedReasons)
    }

    private fun renderKotlinImport(imp: KtImportDirective): String? {
        val q = imp.importedFqName?.asString() ?: return null
        val base = if (imp.isAllUnder) "$q.*" else q
        val alias = imp.aliasName
        return if (alias != null) "$base as $alias" else base
    }

    private fun ensureKotlinStarAndAliasIfNeeded(imp: KtImportDirective, suggested: String): String {
        val (raw, sugAlias) = suggested.split(" as ", limit = 2).let { it[0].trim() to it.getOrNull(1)?.trim() }
        val base = if (imp.isAllUnder && !raw.endsWith(".*")) "$raw.*" else raw

        val originalAlias = imp.aliasName
        val finalAlias =
            when {
                sugAlias != null -> sugAlias
                originalAlias != null -> originalAlias
                else -> null
            }

        return if (finalAlias != null) "$base as $finalAlias" else base
    }

    private fun kotlinImportExists(
        existing: List<KtImportDirective>,
        current: KtImportDirective,
        desired: String
    ): Boolean {
        return existing
            .asSequence()
            .filter { it != current }
            .mapNotNull { renderKotlinImport(it) }
            .any { it == desired }
    }

    private fun parseKotlinImportDirective(file: KtFile, newRef: String): KtImportDirective? {
        val dummyText = """
            package dummy
            import $newRef
            class Dummy
        """.trimIndent()

        val dummy = PsiFileFactory.getInstance(file.project)
            .createFileFromText("Dummy.kt", KotlinFileType.INSTANCE, dummyText)

        val kt = dummy as? KtFile ?: return null
        return kt.importList?.imports?.firstOrNull()
    }
}
