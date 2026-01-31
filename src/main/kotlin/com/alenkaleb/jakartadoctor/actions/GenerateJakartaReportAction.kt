package com.alenkaleb.jakartadoctor.actions

import com.alenkaleb.jakartadoctor.report.JakartaReportGenerator
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware

class GenerateJakartaReportAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        try {
            val result = JakartaReportGenerator.generate(project)
            val vf = JakartaReportGenerator.writeToProjectRoot(project, result.markdown)

            FileEditorManager.getInstance(project).openFile(vf, true)

            NotificationGroupManager.getInstance()
                .getNotificationGroup("JakartaDoctor")
                .createNotification(
                    "Relatório gerado",
                    "Encontrados ${result.totalImports} imports javax.* em ${result.totalFiles} arquivos. " +
                            "Pacotes afetados: ${result.byPackage.size}.",
                    NotificationType.INFORMATION
                )
                .notify(project)
        } catch (t: Throwable) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("JakartaDoctor")
                .createNotification(
                    "Falha ao gerar relatório",
                    "${t::class.java.simpleName}: ${t.message}",
                    NotificationType.ERROR
                )
                .notify(project)
        }
    }
}
