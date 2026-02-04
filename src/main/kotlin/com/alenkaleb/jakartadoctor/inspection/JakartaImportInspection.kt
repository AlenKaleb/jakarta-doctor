package com.alenkaleb.jakartadoctor.inspection

import com.alenkaleb.jakartadoctor.util.JakartaClasspathChecker
import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
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
 * This inspection performs the following safety checks before suggesting a migration:
 * 1. Verifies the javax import is one that was migrated to jakarta in Jakarta EE 9+
 *    (some javax packages like javax.sql remain unchanged and should not be migrated)
 * 2. Checks if the target jakarta.* package/class is available in the module classpath
 *    (to prevent suggesting imports that won't resolve)
 */
class JakartaImportInspection : AbstractBaseUastLocalInspectionTool() {

    companion object {
        /**
         * Suggests a jakarta equivalent for a given javax import, if applicable.
         * Uses the centralized migratable packages list from JakartaClasspathChecker.
         *
         * @param importText The fully qualified import (possibly with Kotlin alias)
         * @return The suggested jakarta import, or null if migration is not applicable
         */
        fun suggest(importText: String): String? {
            val (raw, alias) = importText.split(" as ", limit = 2).let { it[0] to it.getOrNull(1) }
            val fqnWithoutWildcard = raw.removeSuffix(".*")

            // Check if this is a migratable javax package
            if (!JakartaClasspathChecker.isMigratableImport(fqnWithoutWildcard)) {
                return null
            }

            // Use the centralized conversion method
            val migrated = JakartaClasspathChecker.toJakartaImport(raw)
                ?: return null

            return if (alias != null) "$migrated as $alias" else migrated
        }

        /**
         * Checks if the target jakarta import is available in the module classpath.
         * If not available, returns a warning reason; otherwise returns null.
         *
         * @param element The PSI element to determine the module
         * @param jakartaImport The target jakarta import to check
         * @return Warning reason if jakarta is not available, null if it is available
         */
        fun checkClasspathAvailability(element: PsiElement, jakartaImport: String): String? {
            if (!JakartaClasspathChecker.isJakartaAvailable(element, jakartaImport)) {
                return "Jakarta library not found in classpath"
            }
            return null
        }
    }

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return UastVisitorAdapter(object : AbstractUastNonRecursiveVisitor() {

            override fun visitImportStatement(node: UImportStatement): Boolean {
                val source = node.sourcePsi ?: return false

                // ✅ 1) Java: pegar o PsiImportStatementBase real (às vezes sourcePsi não é o statement direto)
                val javaStmt = (source as? PsiImportStatementBase)
                    ?: (source.parent as? PsiImportStatementBase)

                if (javaStmt != null) {
                    processJavaImport(javaStmt, holder)
                    return false
                }

                // ✅ 2) Kotlin: pegar o KtImportDirective real
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

    /**
     * Process a Java import statement and register a problem if migration is applicable.
     */
    private fun processJavaImport(javaStmt: PsiImportStatementBase, holder: ProblemsHolder) {
        val q = javaStmt.importReference?.qualifiedName ?: return
        val rendered = if (javaStmt.isOnDemand) "$q.*" else q

        val suggested = suggest(rendered) ?: return
        if (suggested == rendered) return

        // ✅ Check if jakarta library is available in classpath
        val classpathWarning = checkClasspathAvailability(javaStmt, suggested)

        val message = if (classpathWarning != null) {
            "Migrar javax→jakarta ($classpathWarning)"
        } else {
            "Migrar javax→jakarta"
        }

        // Use a different highlight type if classpath check failed
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

    /**
     * Process a Kotlin import directive and register a problem if migration is applicable.
     */
    private fun processKotlinImport(ktStmt: KtImportDirective, holder: ProblemsHolder) {
        val q = ktStmt.importedFqName?.asString() ?: return
        val base = if (ktStmt.isAllUnder) "$q.*" else q
        val rendered = ktStmt.aliasName?.let { "$base as $it" } ?: base

        val suggested = suggest(rendered) ?: return
        if (suggested == rendered) return

        // ✅ Check if jakarta library is available in classpath
        val classpathWarning = checkClasspathAvailability(ktStmt, suggested)

        val message = if (classpathWarning != null) {
            "Migrar javax→jakarta ($classpathWarning)"
        } else {
            "Migrar javax→jakarta"
        }

        // Use a different highlight type if classpath check failed
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
