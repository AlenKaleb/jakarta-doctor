package com.alenkaleb.jakartadoctor.licensing

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.ui.LicensingFacade

class LicenseGate(private val project: Project) {

    enum class State { LICENSED, UNLICENSED, UNKNOWN }

    // TEM que bater com o product-descriptor@code
    private val productCode = "PJAKARTADOCTOR"

    fun state(): State {
        val facade = LicensingFacade.getInstance() ?: return State.UNKNOWN

        val stamp = facade.getConfirmationStamp(productCode) ?: return State.UNLICENSED

        return State.LICENSED
    }

    fun notifyUnlicensedOnce() {
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
