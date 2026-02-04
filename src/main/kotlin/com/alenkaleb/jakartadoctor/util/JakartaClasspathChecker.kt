package com.alenkaleb.jakartadoctor.util

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPackage
import com.intellij.psi.search.GlobalSearchScope

/**
 * Utility class to check the module classpath for Jakarta EE support.
 *
 * Safety rules:
 * 1) Only migrate allow-listed Java EE/Jakarta EE namespaces (javax.* -> jakarta.*).
 * 2) Never migrate Java SE namespaces that remain javax.* (e.g., javax.sql, javax.xml.parsers, etc.).
 * 3) Only suggest migration if the jakarta target is resolvable in the module classpath.
 * 4) Be resilient in dumb mode (indexes not ready).
 */
object JakartaClasspathChecker {

    /**
     * Known javax packages that were migrated to jakarta in Jakarta EE 9+.
     * Only these prefixes should be migrated.
     */
    val MIGRATABLE_JAVAX_PREFIXES: Set<String> = setOf(
        "javax.activation",
        "javax.annotation",
        "javax.batch",
        "javax.ejb",
        "javax.el",
        "javax.enterprise",
        "javax.faces",
        "javax.inject",
        "javax.interceptor",
        "javax.jms",
        "javax.json",
        "javax.mail",
        "javax.persistence",
        "javax.resource",
        "javax.security.enterprise",
        "javax.servlet",
        "javax.transaction",
        "javax.validation",
        "javax.websocket",
        "javax.ws.rs",

        // Java EE APIs that were under javax.xml.* and DID migrate:
        "javax.xml.bind",
        "javax.xml.soap",
        "javax.xml.ws"
    )

    /**
     * Known javax packages that remain as javax.* (Java SE or not migrated).
     * These should NEVER be migrated.
     *
     * IMPORTANT: Do NOT put "javax.xml" as a whole here, because some subpackages migrated
     * (e.g., javax.xml.bind -> jakarta.xml.bind).
     */
    val NON_MIGRATABLE_JAVAX_PREFIXES: Set<String> = setOf(
        // Clearly Java SE:
        "javax.sql",
        "javax.naming",
        "javax.crypto",
        "javax.net",
        "javax.management",
        "javax.rmi",
        "javax.swing",
        "javax.sound",
        "javax.print",
        "javax.imageio",
        "javax.accessibility",
        "javax.tools",
        "javax.lang.model",
        "javax.script",
        "javax.security.auth",
        "javax.security.cert",

        // Java SE XML packages (remain javax.*):
        "javax.xml.catalog",
        "javax.xml.datatype",
        "javax.xml.namespace",
        "javax.xml.parsers",
        "javax.xml.stream",
        "javax.xml.transform",
        "javax.xml.validation",
        "javax.xml.xpath",
        "javax.xml.crypto",

        // Java SE annotation processing (NOT Jakarta EE):
        "javax.annotation.processing",

        // XA is Java SE module (java.transaction.xa) and does NOT migrate to jakarta.*:
        "javax.transaction.xa"
    )

    /**
     * Normalizes an import-like string:
     * - trims
     * - removes "import"/"import static"
     * - drops alias (Kotlin "as X") by taking the first token
     * - drops trailing ';'
     */
    private fun normalizeImportText(raw: String): String {
        val s = raw.trim()
            .removePrefix("import")
            .trim()
            .removePrefix("static")
            .trim()
        val firstToken = s.split(Regex("\\s+")).firstOrNull().orEmpty()
        return firstToken.removeSuffix(";").trim()
    }

    private fun isPrefixedByAny(fqn: String, prefixes: Set<String>): Boolean =
        prefixes.any { p -> fqn == p || fqn.startsWith("$p.") }

    /**
     * Checks if a given javax import is safe to migrate (i.e., it's one of the packages
     * that was migrated from javax to jakarta in Jakarta EE 9+).
     */
    fun isMigratableImport(javaxImportRaw: String): Boolean {
        val javaxImport = normalizeImportText(javaxImportRaw)
        val base = javaxImport.removeSuffix(".*")

        if (!base.startsWith("javax.")) return false

        // Non-migratable wins (blocks ambiguous namespaces like javax.annotation.processing)
        if (isPrefixedByAny(base, NON_MIGRATABLE_JAVAX_PREFIXES)) return false

        return isPrefixedByAny(base, MIGRATABLE_JAVAX_PREFIXES)
    }

    private fun moduleScopeFor(element: PsiElement): GlobalSearchScope? {
        val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return null
        val vf = element.containingFile?.virtualFile
        val includeTests =
            vf != null && ProjectFileIndex.getInstance(module.project).isInTestSourceContent(vf)

        return GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, includeTests)
    }

    private fun <T> safeIndexCall(project: Project, block: () -> T): T? {
        if (DumbService.isDumb(project)) return null
        return try {
            block()
        } catch (_: IndexNotReadyException) {
            null
        }
    }

    /**
     * Checks if the target jakarta.* class/package is resolvable in the module's classpath.
     */
    fun isJakartaAvailable(element: PsiElement, jakartaFqnRaw: String): Boolean {
        val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return false
        val project = module.project
        val scope = moduleScopeFor(element) ?: return false
        val facade = JavaPsiFacade.getInstance(project)

        val jakartaFqn = normalizeImportText(jakartaFqnRaw)
        val fqnToCheck = jakartaFqn.removeSuffix(".*")

        return safeIndexCall(project) {
            // 1) Class resolve
            facade.findClass(fqnToCheck, scope)?.let { return@safeIndexCall true }

            // 2) Package resolve
            val pkg: PsiPackage = facade.findPackage(fqnToCheck) ?: return@safeIndexCall false

            // directories/classes/subpackages in this module scope
            pkg.getDirectories(scope).isNotEmpty() ||
                    pkg.getClasses(scope).isNotEmpty() ||
                    pkg.getSubPackages(scope).isNotEmpty()
        } ?: false
    }

    /**
     * Quick signal: any common Jakarta EE type resolvable in module scope?
     */
    fun hasAnyJakartaInClasspath(element: PsiElement): Boolean {
        val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return false
        val project = module.project
        val scope = moduleScopeFor(element) ?: return false
        val facade = JavaPsiFacade.getInstance(project)

        val probes = listOf(
            "jakarta.persistence.Entity",
            "jakarta.validation.Constraint",
            "jakarta.servlet.Servlet",
            "jakarta.annotation.PostConstruct",
            "jakarta.ws.rs.Path",
            "jakarta.inject.Inject",
            "jakarta.enterprise.context.ApplicationScoped"
        )

        return safeIndexCall(project) {
            probes.any { fqn -> facade.findClass(fqn, scope) != null }
        } ?: false
    }

    /**
     * Determines the corresponding jakarta import for a given javax import.
     * Handles both regular imports and wildcard imports.
     */
    fun toJakartaImport(javaxImportRaw: String): String? {
        val javaxImport = normalizeImportText(javaxImportRaw)
        val base = javaxImport.removeSuffix(".*")

        if (!isMigratableImport(base)) return null
        if (!base.startsWith("javax.")) return null

        // Keep wildcard if present
        return javaxImport.replaceFirst("javax.", "jakarta.")
    }

    data class MigrationCheckResult(
        val canMigrate: Boolean,
        val jakartaImport: String?,
        val reason: String?
    )

    /**
     * Comprehensive migration eligibility check:
     * - allowlist match
     * - jakarta target resolvable in module classpath
     */
    fun checkMigrationEligibility(element: PsiElement, javaxImportRaw: String): MigrationCheckResult {
        val javaxImport = normalizeImportText(javaxImportRaw)

        if (!isMigratableImport(javaxImport)) {
            val base = javaxImport.removeSuffix(".*")
            val reason = when {
                isPrefixedByAny(base, NON_MIGRATABLE_JAVAX_PREFIXES) ->
                    "Este pacote javax.* é Java SE / não migra em Jakarta EE 9+ (não sugira troca)."
                else ->
                    "Este javax.* não está na allowlist de migração para Jakarta EE."
            }
            return MigrationCheckResult(false, null, reason)
        }

        val jakartaImport = toJakartaImport(javaxImport)
            ?: return MigrationCheckResult(false, null, "Não foi possível determinar o equivalente jakarta.*.")

        if (!isJakartaAvailable(element, jakartaImport)) {
            return MigrationCheckResult(
                canMigrate = false,
                jakartaImport = jakartaImport,
                reason = "O alvo jakarta.* não resolve no classpath do módulo. Adicione a dependência Jakarta correspondente primeiro."
            )
        }

        return MigrationCheckResult(true, jakartaImport, null)
    }
}
