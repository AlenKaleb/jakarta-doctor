package com.alenkaleb.jakartadoctor.inspection

import com.alenkaleb.jakartadoctor.licensing.LicenseGate
import com.alenkaleb.jakartadoctor.util.JakartaClasspathChecker
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiImportStatementBase
import com.intellij.psi.PsiImportStaticStatement
import com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective

/**
 * Quick fix to replace a javax.* import with its jakarta.* equivalent.
 *
 * @param newImportRefRaw The target jakarta import reference (may include .* and Kotlin alias "as")
 * @param classpathWarning Whether the classpath check failed at inspection time
 */
class ReplaceImportQuickFix(
    private val newImportRefRaw: String,
    private val classpathWarning: Boolean = false
) : LocalQuickFix {

    override fun getFamilyName(): String = "Migrar imports javax→jakarta"

    override fun getName(): String {
        return if (classpathWarning) {
            "Migrate import to $newImportRefRaw (Jakarta not found in classpath)"
        } else {
            "Migrate import to $newImportRefRaw"
        }
    }

    override fun startInWriteAction(): Boolean = false

    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo {
        val ok = applyInternal(
            project = project,
            descriptor = previewDescriptor,
            enforceLicense = false,
            notifyUnlicensed = false,
            checkClasspath = false
        )
        return if (ok) IntentionPreviewInfo.DIFF else IntentionPreviewInfo.EMPTY
    }

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        applyInternal(
            project = project,
            descriptor = descriptor,
            enforceLicense = true,
            notifyUnlicensed = true,
            checkClasspath = true
        )
    }

    private fun applyInternal(
        project: Project,
        descriptor: ProblemDescriptor,
        enforceLicense: Boolean,
        notifyUnlicensed: Boolean,
        checkClasspath: Boolean
    ): Boolean {
        val element = descriptor.psiElement ?: return false
        val file = element.containingFile ?: return false

        if (enforceLicense) {
            val gate = LicenseGate(project)
            val state = gate.state()
            if (state == LicenseGate.State.UNLICENSED) {
                if (notifyUnlicensed) gate.notifyUnlicensedOnce()
                return false
            }
        }

        val normalized = normalizeImportRef(newImportRefRaw)
        if (normalized.isBlank()) return false

        // Se pediram check de classpath, não dá pra afirmar nada durante indexing.
        if (checkClasspath && DumbService.isDumb(project)) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("JakartaDoctor")
                .createNotification(
                    "Indexação em andamento",
                    "O IDE está indexando. Não é possível validar o classpath agora. Tente novamente em alguns segundos.",
                    NotificationType.INFORMATION
                )
                .notify(project)
            return false
        }

        // Preserva .* e alias conforme o import ORIGINAL (Java/Kotlin)
        val effectiveRef = when (element) {
            is PsiImportStatementBase -> {
                if (element is PsiImportStaticStatement) return false // não mexe em static import
                val base = normalized.split(" as ", limit = 2)[0].trim() // Java não tem alias
                if (element.isOnDemand && !base.endsWith(".*")) "$base.*" else base
            }

            is KtImportDirective -> {
                val (rawBase, alias) = splitAlias(normalized)
                val base = if (element.isAllUnder && !rawBase.endsWith(".*")) "$rawBase.*" else rawBase
                if (alias != null) "$base as $alias" else base
            }

            else -> normalized
        }

        // Re-check classpath antes de aplicar fix
        if (checkClasspath && !JakartaClasspathChecker.isJakartaAvailable(element, effectiveRef)) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("JakartaDoctor")
                .createNotification(
                    "Jakarta não encontrado no classpath",
                    "O pacote '$effectiveRef' não foi encontrado no classpath do módulo. " +
                            "Adicione a dependência Jakarta correspondente antes de migrar.",
                    NotificationType.WARNING
                )
                .notify(project)
            return false
        }

        var changed = false
        WriteCommandAction
            .writeCommandAction(project, file)
            .withName("Jakarta Doctor: migrate import")
            .run<RuntimeException> {
                changed = when (element) {
                    is PsiImportStatementBase -> replaceJavaImport(project, element, effectiveRef)
                    is KtImportDirective -> replaceKotlinImport(project, element, effectiveRef)
                    else -> false
                }
            }

        return changed
    }

    private fun replaceJavaImport(project: Project, element: PsiImportStatementBase, newRefAny: String): Boolean {
        if (element is PsiImportStaticStatement) return false

        val newRef = newRefAny.split(" as ", limit = 2)[0].trim()
        if (newRef.isBlank()) return false

        val currentBase = element.importReference?.qualifiedName ?: return false
        val currentRendered = if (element.isOnDemand) "$currentBase.*" else currentBase
        if (currentRendered == newRef) return false

        val dummyText = """
            import $newRef;
            class Dummy {}
        """.trimIndent()

        val dummy = PsiFileFactory.getInstance(project)
            .createFileFromText("Dummy.java", JavaFileType.INSTANCE, dummyText)

        val importStmt = (dummy as? PsiJavaFile)
            ?.importList
            ?.allImportStatements
            ?.firstOrNull() as? PsiImportStatementBase
            ?: return false

        element.replace(importStmt)
        return true
    }

    private fun replaceKotlinImport(project: Project, element: KtImportDirective, newRef: String): Boolean {
        val currentBase = element.importedFqName?.asString() ?: return false
        val currentRendered = buildString {
            append(if (element.isAllUnder) "$currentBase.*" else currentBase)
            element.aliasName?.let { append(" as ").append(it) }
        }
        if (currentRendered == newRef) return false

        val dummyText = """
            package dummy
            import $newRef
            class Dummy
        """.trimIndent()

        val dummy = PsiFileFactory.getInstance(project)
            .createFileFromText("Dummy.kt", KotlinFileType.INSTANCE, dummyText)

        val importDirective = (dummy as? KtFile)
            ?.importList
            ?.imports
            ?.firstOrNull()
            ?: return false

        element.replace(importDirective)
        return true
    }

    private fun normalizeImportRef(ref: String): String =
        ref.trim()
            .removePrefix("import")
            .trim()
            .removePrefix("static")
            .trim()
            .removeSuffix(";")
            .trim()

    private fun splitAlias(s: String): Pair<String, String?> {
        val parts = s.split(" as ", limit = 2)
        val base = parts[0].trim()
        val alias = parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        return base to alias
    }
}
