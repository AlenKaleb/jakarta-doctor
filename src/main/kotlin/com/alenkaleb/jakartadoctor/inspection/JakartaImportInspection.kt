package com.alenkaleb.jakartadoctor.inspection

import com.intellij.codeInspection.AbstractBaseUastLocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiImportStatementBase
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.uast.UastVisitorAdapter
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.uast.UImportStatement
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor

class JakartaImportInspection : AbstractBaseUastLocalInspectionTool() {

    companion object {
        private val javaxToJakarta = mapOf(
            "javax.persistence" to "jakarta.persistence",
            "javax.validation" to "jakarta.validation",
            "javax.servlet" to "jakarta.servlet",
            "javax.annotation" to "jakarta.annotation",
            "javax.ws.rs" to "jakarta.ws.rs"
        )

        fun suggest(importText: String): String? {
            val (raw, alias) = importText.split(" as ", limit = 2).let { it[0] to it.getOrNull(1) }

            val hit = javaxToJakarta.entries.firstOrNull { (from, _) ->
                raw == from || raw.startsWith("$from.") || raw == "$from.*"
            } ?: return null

            val migrated = raw.replaceFirst(hit.key, hit.value)
            return if (alias != null) "$migrated as $alias" else migrated
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
                    val q = javaStmt.importReference?.qualifiedName ?: return false
                    val rendered = if (javaStmt.isOnDemand) "$q.*" else q

                    val suggested = suggest(rendered) ?: return false
                    if (suggested == rendered) return false
                    if (!isJakartaOnClasspath(javaStmt, suggested)) return false

                    // ✅ registra no statement (não na referência interna)
                    holder.registerProblem(
                        javaStmt,
                        "Migrar javax→jakarta",
                        ReplaceImportQuickFix(suggested)
                    )
                    return false
                }

                // ✅ 2) Kotlin: pegar o KtImportDirective real
                val ktStmt = (source as? KtImportDirective)
                    ?: (source.parent as? KtImportDirective)

                if (ktStmt != null) {
                    val q = ktStmt.importedFqName?.asString() ?: return false
                    val base = if (ktStmt.isAllUnder) "$q.*" else q
                    val rendered = ktStmt.aliasName?.let { "$base as $it" } ?: base

                    val suggested = suggest(rendered) ?: return false
                    if (suggested == rendered) return false
                    if (!isJakartaOnClasspath(ktStmt, suggested)) return false

                    holder.registerProblem(
                        ktStmt,
                        "Migrar javax→jakarta",
                        ReplaceImportQuickFix(suggested)
                    )
                    return false
                }

                return false
            }
        }, true)
    }

    private fun isJakartaOnClasspath(element: PsiElement, suggested: String): Boolean {
        val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return false
        val scope = GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module)
        val cleaned = suggested.split(" as ", limit = 2)[0].trim()
        val base = cleaned.removeSuffix(".*").trim()
        if (base.isBlank()) return false

        val facade = JavaPsiFacade.getInstance(element.project)
        if (cleaned.endsWith(".*")) {
            if (facade.findClass(base, scope) != null) return true
            return packageHasTypes(facade, base, scope)
        }

        if (facade.findClass(base, scope) != null) return true

        val packageName = base.substringBeforeLast('.', "")
        if (packageName.isBlank()) return false

        return packageHasTypes(facade, packageName, scope)
    }

    private fun packageHasTypes(
        facade: JavaPsiFacade,
        packageName: String,
        scope: GlobalSearchScope
    ): Boolean {
        val pkg = facade.findPackage(packageName) ?: return false
        return pkg.getClasses(scope).isNotEmpty()
    }
}
