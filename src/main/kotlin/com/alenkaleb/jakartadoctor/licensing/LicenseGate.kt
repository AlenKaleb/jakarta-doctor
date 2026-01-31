package com.alenkaleb.jakartadoctor.licensing

// Licensing API (JetBrains Marketplace)


import com.intellij.openapi.components.State
import com.intellij.openapi.project.Project
import com.intellij.ui.LicensingFacade

class LicenseGate(private val project: Project) {

    enum class State { LICENSED, UNLICENSED, UNKNOWN }

    // TEM que bater com o product-descriptor@code
    private val productCode = "PJAKARTADOCTOR"

//    fun state(): State {
//        val facade = LicensingFacade.getInstance()
//        if (facade == null) return State.UNKNOWN // :contentReference[oaicite:11]{index=11}
//
//        // Se não tem stamp depois que inicializou => sem licença :contentReference[oaicite:12]{index=12}
//        val stamp = facade.getConfirmationStamp(productCode) ?: return State.UNLICENSED
//
//        // Regra simples de MVP:
//        // - se há stamp válido -> licensed (inclui trial/evaluation)
//        // Você pode endurecer depois (paid-only etc.).
//        return if (facade.getConfirmationStamp(stamp) != null) State.LICENSED else State.UNLICENSED
//    }

    fun state(): State {
        return  State.LICENSED
    }
}
