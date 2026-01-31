package com.alenkaleb.jakartadoctor.actions

import com.alenkaleb.jakartadoctor.inspection.JakartaImportInspection
import com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective

object KotlinImportMigrator {

    fun migrate(file: KtFile) {
        val importList = file.importList ?: return
        val imports = importList.imports.toList()

        for (imp in imports) {
            val rendered = renderKotlinImport(imp) ?: continue

            val suggested = JakartaImportInspection.suggest(rendered) ?: continue

            // Segurança extra: preserva star import e alias conforme o original
            val desired = ensureKotlinStarAndAliasIfNeeded(imp, suggested)
            if (desired == rendered) continue

            // Dedupe: se já existe import idêntico, remove o atual
            if (kotlinImportExists(importList.imports, imp, desired)) {
                imp.delete()
                continue
            }

            val newDirective = parseKotlinImportDirective(file, desired) ?: continue
            imp.replace(newDirective)
        }
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
