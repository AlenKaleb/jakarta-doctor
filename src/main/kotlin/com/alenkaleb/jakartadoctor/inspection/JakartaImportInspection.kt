package com.alenkaleb.jakartadoctor.inspection

import com.alenkaleb.jakartadoctor.util.JakartaClasspathChecker
import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiImportStatementBase
import com.intellij.uast.UastVisitorAdapter
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.uast.UImportStatement
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

/**
 * Inspection that detects javax.* imports eligible for migration to jakarta.*.
 *
 * Safety checks:
 * 1) Only migratable javax packages
 * 2) Optionally warns if jakarta target is not available in module classpath
 */
class JakartaImportInspection : AbstractBaseUastLocalInspectionTool() {

    companion object {
        /**
         * Suggests a jakarta equivalent for a given javax import, if applicable.
         * @param importText The fully qualified import (possibly with Kotlin alias)
         */
        fun suggest(importText: String): String? {
            val (raw, alias) = importText.split(" as ", limit = 2).let { it[0] to it.getOrNull(1) }
            val fqnWithoutWildcard = raw.removeSuffix(".*")

            if (!JakartaClasspathChecker.isMigratableImport(fqnWithoutWildcard)) {
                return null
            }

            val migrated = JakartaClasspathChecker.toJakartaImport(raw) ?: return null
            return if (alias != null) "$migrated as ${alias.trim()}" else migrated
        }

        /**
         * Checks if the target jakarta import is available in the module classpath.
         * During indexing (dumb mode), do NOT warn (avoid false negatives).
         */
        fun checkClasspathAvailability(element: PsiElement, jakartaImport: String): String? {
            if (DumbService.isDumb(element.project)) return null
            val base = jakartaImport.split(" as ", limit = 2)[0].trim()
            return if (!JakartaClasspathChecker.isJakartaAvailable(element, base)) {
                "Jakarta library not found in classpath"
            } else null
        }
    }

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return UastVisitorAdapter(object : AbstractUastNonRecursiveVisitor() {

            override fun visitImportStatement(node: UImportStatement): Boolean {
                val source = node.sourcePsi ?: return false

                val javaStmt = (source as? PsiImportStatementBase)
                    ?: (source.parent as? PsiImportStatementBase)

                if (javaStmt != null) {
                    processJavaImport(javaStmt, holder)
                    return false
                }

                val ktStmt = (source as? KtImportDirective)
                    ?: (source.parent as? KtImportDirective)

                if (ktStmt != null) {
                    processKotlinImport(ktStmt, holder)
                    return false
                }

                return false
            }
        }, true)
    }

    private fun processJavaImport(javaStmt: PsiImportStatementBase, holder: ProblemsHolder) {
        val q = javaStmt.importReference?.qualifiedName ?: return
        val rendered = if (javaStmt.isOnDemand) "$q.*" else q

        val suggested = suggest(rendered) ?: return
        if (suggested == rendered) return

        val classpathWarning = checkClasspathAvailability(javaStmt, suggested)

        val message = if (classpathWarning != null) {
            "Migrar javax→jakarta ($classpathWarning)"
        } else {
            "Migrar javax→jakarta"
        }

        val highlightType = if (classpathWarning != null) {
            ProblemHighlightType.WEAK_WARNING
        } else {
            ProblemHighlightType.WARNING
        }

        holder.registerProblem(
            javaStmt,
            message,
            highlightType,
            ReplaceImportQuickFix(suggested, classpathWarning != null)
        )
    }

    private fun processKotlinImport(ktStmt: KtImportDirective, holder: ProblemsHolder) {
        val q = ktStmt.importedFqName?.asString() ?: return
        val base = if (ktStmt.isAllUnder) "$q.*" else q
        val rendered = ktStmt.aliasName?.let { "$base as $it" } ?: base

        val suggested = suggest(rendered) ?: return
        if (suggested == rendered) return

        val classpathWarning = checkClasspathAvailability(ktStmt, suggested)

        val message = if (classpathWarning != null) {
            "Migrar javax→jakarta ($classpathWarning)"
        } else {
            "Migrar javax→jakarta"
        }

        val highlightType = if (classpathWarning != null) {
            ProblemHighlightType.WEAK_WARNING
        } else {
            ProblemHighlightType.WARNING
        }

        holder.registerProblem(
            ktStmt,
            message,
            highlightType,
            ReplaceImportQuickFix(suggested, classpathWarning != null)
        )
    }
}
