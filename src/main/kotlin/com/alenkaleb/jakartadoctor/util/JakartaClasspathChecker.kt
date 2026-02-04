package com.alenkaleb.jakartadoctor.util

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement

/**
 * Utility class to check the module classpath for Jakarta EE support.
 *
 * Before applying any javax→jakarta migration, we must verify that:
 * 1. The target jakarta.* class/package is actually resolvable in the module classpath
 * 2. The javax.* import is one that was migrated to jakarta.* in Jakarta EE 9+
 *
 * Some javax packages remain unchanged in Jakarta EE 9+, such as:
 * - javax.sql (remains javax.sql)
 * - javax.naming (remains javax.naming)
 * - javax.xml (mostly remains javax.xml)
 * - javax.crypto (remains javax.crypto)
 * - javax.net (remains javax.net)
 * - javax.security.auth (remains javax.security.auth)
 * - javax.management (remains javax.management)
 *
 * This checker ensures we only suggest migrations that are safe and will resolve.
 */
object JakartaClasspathChecker {

    /**
     * Known javax packages that were migrated to jakarta in Jakarta EE 9+.
     * Only these prefixes should be migrated.
     */
    val MIGRATABLE_JAVAX_PREFIXES = setOf(
        "javax.persistence",
        "javax.validation",
        "javax.servlet",
        "javax.annotation",
        "javax.ws.rs",
        "javax.inject",
        "javax.enterprise",
        "javax.json",
        "javax.websocket",
        "javax.faces",
        "javax.mail",
        "javax.jms",
        "javax.batch",
        "javax.resource",
        "javax.transaction",
        "javax.ejb",
        "javax.activation",
        "javax.el",
        "javax.interceptor",
        "javax.security.enterprise"
    )

    /**
     * Known javax packages that remain as javax.* even in Jakarta EE 9+.
     * These should NEVER be migrated.
     */
    val NON_MIGRATABLE_JAVAX_PREFIXES = setOf(
        "javax.sql",
        "javax.naming",
        "javax.xml",
        "javax.crypto",
        "javax.net",
        "javax.security.auth",
        "javax.security.cert",
        "javax.management",
        "javax.rmi",
        "javax.swing",
        "javax.sound",
        "javax.print",
        "javax.imageio",
        "javax.accessibility",
        "javax.tools",
        "javax.lang.model",
        "javax.script"
    )

    /**
     * Checks if a given javax import is safe to migrate (i.e., it's one of the packages
     * that was migrated from javax to jakarta in Jakarta EE 9+).
     *
     * @param javaxImport The fully qualified javax import (e.g., "javax.persistence.Entity")
     * @return true if this import can potentially be migrated to jakarta
     */
    fun isMigratableImport(javaxImport: String): Boolean {
        // First check if it's explicitly non-migratable
        if (NON_MIGRATABLE_JAVAX_PREFIXES.any { prefix ->
                javaxImport == prefix || javaxImport.startsWith("$prefix.")
            }) {
            return false
        }

        // Check if it's explicitly migratable
        return MIGRATABLE_JAVAX_PREFIXES.any { prefix ->
            javaxImport == prefix || javaxImport.startsWith("$prefix.")
        }
    }

    /**
     * Checks if the target jakarta.* class/package is resolvable in the module's classpath.
     *
     * @param element A PSI element from the file (used to determine the module)
     * @param jakartaFqn The fully qualified name of the jakarta class/package to check
     * @return true if the jakarta class/package is resolvable in the module classpath
     */
    fun isJakartaAvailable(element: PsiElement, jakartaFqn: String): Boolean {
        val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return false
        val project = module.project
        val facade = JavaPsiFacade.getInstance(project)
        val scope = module.moduleWithDependenciesAndLibrariesScope

        // Handle wildcard imports (e.g., "jakarta.persistence.*")
        val fqnToCheck = jakartaFqn.removeSuffix(".*")

        // Try to find the class or package
        val psiClass = facade.findClass(fqnToCheck, scope)
        if (psiClass != null) {
            return true
        }

        // If it's a package reference, try to find the package
        val psiPackage = facade.findPackage(fqnToCheck)
        if (psiPackage != null) {
            // Check if the package has any classes in the module scope
            val classes = psiPackage.getClasses(scope)
            if (classes.isNotEmpty()) {
                return true
            }
            // Also check for subpackages
            val subPackages = psiPackage.getSubPackages(scope)
            if (subPackages.isNotEmpty()) {
                return true
            }
        }

        return false
    }

    /**
     * Checks if any Jakarta EE library is present in the module's classpath.
     * This is a quick check to determine if the module is likely a Jakarta EE project.
     *
     * @param element A PSI element from the file (used to determine the module)
     * @return true if any Jakarta EE library is found in the module classpath
     */
    fun hasAnyJakartaInClasspath(element: PsiElement): Boolean {
        val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return false
        val project = module.project
        val facade = JavaPsiFacade.getInstance(project)
        val scope = module.moduleWithDependenciesAndLibrariesScope

        // Check for common Jakarta EE packages
        val commonJakartaPackages = listOf(
            "jakarta.persistence",
            "jakarta.validation",
            "jakarta.servlet",
            "jakarta.annotation",
            "jakarta.ws.rs",
            "jakarta.inject",
            "jakarta.enterprise.context"
        )

        return commonJakartaPackages.any { pkg ->
            val psiPackage = facade.findPackage(pkg)
            psiPackage != null && psiPackage.getClasses(scope).isNotEmpty()
        }
    }

    /**
     * Determines the corresponding jakarta import for a given javax import.
     *
     * @param javaxImport The fully qualified javax import
     * @return The corresponding jakarta import, or null if migration is not applicable
     */
    fun toJakartaImport(javaxImport: String): String? {
        if (!isMigratableImport(javaxImport)) {
            return null
        }
        return javaxImport.replaceFirst("javax.", "jakarta.")
    }

    /**
     * Result of a migration eligibility check.
     */
    data class MigrationCheckResult(
        val canMigrate: Boolean,
        val jakartaImport: String?,
        val reason: String?
    )

    /**
     * Performs a comprehensive check to determine if a javax import can be safely migrated.
     *
     * @param element A PSI element from the file
     * @param javaxImport The fully qualified javax import to check
     * @return A MigrationCheckResult with the migration eligibility and reason
     */
    fun checkMigrationEligibility(element: PsiElement, javaxImport: String): MigrationCheckResult {
        // First check if this is a migratable javax package
        if (!isMigratableImport(javaxImport)) {
            val reason = if (NON_MIGRATABLE_JAVAX_PREFIXES.any { prefix ->
                    javaxImport == prefix || javaxImport.startsWith("$prefix.")
                }) {
                "This javax.* package remains unchanged in Jakarta EE 9+ and should not be migrated"
            } else {
                "This javax.* import is not recognized as a Jakarta EE migration target"
            }
            return MigrationCheckResult(canMigrate = false, jakartaImport = null, reason = reason)
        }

        // Calculate the target jakarta import
        val jakartaImport = toJakartaImport(javaxImport)
            ?: return MigrationCheckResult(
                canMigrate = false,
                jakartaImport = null,
                reason = "Could not determine jakarta equivalent"
            )

        // Check if the jakarta library is available in the classpath
        if (!isJakartaAvailable(element, jakartaImport)) {
            return MigrationCheckResult(
                canMigrate = false,
                jakartaImport = jakartaImport,
                reason = "Jakarta library not found in module classpath. Add the corresponding Jakarta EE dependency first."
            )
        }

        // All checks passed
        return MigrationCheckResult(
            canMigrate = true,
            jakartaImport = jakartaImport,
            reason = null
        )
    }
}
