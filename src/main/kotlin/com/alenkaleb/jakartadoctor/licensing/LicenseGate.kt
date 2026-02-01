package com.alenkaleb.jakartadoctor.licensing

import com.intellij.openapi.project.Project

class LicenseGate(@Suppress("UNUSED_PARAMETER") private val project: Project) {

    enum class State { LICENSED, UNLICENSED, UNKNOWN }

    fun state(): State = State.LICENSED

    fun notifyUnlicensedOnce() {
        // no-op
    }
}
