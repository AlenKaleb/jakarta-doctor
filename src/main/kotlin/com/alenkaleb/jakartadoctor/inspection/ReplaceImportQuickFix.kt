package com.alenkaleb.jakartadoctor.inspection

import com.alenkaleb.jakartadoctor.licensing.LicenseGate
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiImportStatementBase
import com.intellij.psi.PsiImportStaticStatement
import com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective

class ReplaceImportQuickFix(private val newImportRefRaw: String) : LocalQuickFix {

    override fun getFamilyName(): String = "Migrar imports javax→jakarta"
    override fun getName(): String = "Migrar import para $newImportRefRaw"
    override fun startInWriteAction(): Boolean = false

    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo {
        val ok = applyInternal(project, previewDescriptor, enforceLicense = false, notifyUnlicensed = false)
        return if (ok) IntentionPreviewInfo.DIFF else IntentionPreviewInfo.EMPTY
    }

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        applyInternal(project, descriptor, enforceLicense = true, notifyUnlicensed = true)
    }

    private fun applyInternal(
        project: Project,
        descriptor: ProblemDescriptor,
        enforceLicense: Boolean,
        notifyUnlicensed: Boolean
    ): Boolean {
        val element = descriptor.psiElement ?: return false
        val file = element.containingFile ?: return false

        if (enforceLicense) {
            val gate = LicenseGate(project)
            val state = gate.state()
            if (state == LicenseGate.State.UNLICENSED) {
                if (notifyUnlicensed) notifyUnlicensedOnce(project)
                return false
            }
            // UNKNOWN -> deixa passar
        }

        val normalized = normalizeImportRef(newImportRefRaw)
        if (normalized.isBlank()) return false

        // ✅ Preserva .* conforme o import ORIGINAL (Java/Kotlin)
        val effectiveRef = when (element) {
            is PsiImportStatementBase -> {
                val base = normalized.split(" as ", limit = 2)[0].trim() // Java não usa alias
                if (element.isOnDemand && !base.endsWith(".*")) "$base.*" else base
            }
            is KtImportDirective -> {
                if (element.isAllUnder && !normalized.endsWith(".*")) "$normalized.*" else normalized
            }
            else -> normalized
        }

        var changed = false

        WriteCommandAction
            .writeCommandAction(project, file)
            .withName("Jakarta Doctor: migrate import")
            .run<RuntimeException> {
                changed = when (element) {
                    is PsiImportStatementBase -> replaceJavaImport(project, element, effectiveRef)
                    is KtImportDirective      -> replaceKotlinImport(project, element, effectiveRef)
                    else                      -> false
                }
            }

        return changed
    }

    private fun replaceJavaImport(project: Project, element: PsiImportStatementBase, newRefAny: String): Boolean {
        // Ignora static import (não mexe nisso)
        if (element is PsiImportStaticStatement) return false

        // Java não tem alias "as". Se vier, corta.
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
            .removeSuffix(";")
            .trim()

    private fun notifyUnlicensedOnce(project: Project) {
        val key = UNLICENSED_NOTIFIED
        if (project.getUserData(key) == true) return
        project.putUserData(key, true)

        NotificationGroupManager.getInstance()
            .getNotificationGroup("JakartaDoctor")
            .createNotification(
                "Recurso pago",
                "A migração automática em lote é parte do plano pago. (A inspeção continua grátis.)",
                NotificationType.INFORMATION
            )
            .notify(project)
    }

    companion object {
        private val UNLICENSED_NOTIFIED = Key.create<Boolean>("JakartaDoctor.unlicensed.notified")
    }
}
