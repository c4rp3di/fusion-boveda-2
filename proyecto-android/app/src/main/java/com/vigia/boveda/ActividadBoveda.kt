package com.vigia.boveda

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog as AppAlertDialog
import com.vigia.core.ui.ActividadBase

/**
 * Base de todas las pantallas de La Bóveda: oculta el contenido en capturas y en «recientes» (FLAG_SECURE),
 * redirige al desbloqueo si la bóveda está cerrada y gestiona el autobloqueo.
 */
abstract class ActividadBoveda : ActividadBase() {

    protected open val requiereDesbloqueo: Boolean get() = true

    private var redirigida = false

    /** Aquí va lo que normalmente iría en onCreate: solo se llama con la bóveda abierta (o si no hace falta). */
    protected abstract fun alCrear(estado: Bundle?)

    final override fun onCreate(savedInstanceState: Bundle?) {
        if (!AjustesBoveda.permitirCapturas(this)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        super.onCreate(savedInstanceState)
        if (requiereDesbloqueo && !SesionBoveda.desbloqueada) {
            redirigir()
            return
        }
        alCrear(savedInstanceState)
    }

    override fun onStart() {
        super.onStart()
        SesionBoveda.alIniciarPantalla()
        if (!redirigida && requiereDesbloqueo && !SesionBoveda.desbloqueada) redirigir()
    }

    override fun onStop() {
        SesionBoveda.alDetenerPantalla(applicationContext)
        super.onStop()
    }

    private fun redirigir() {
        redirigida = true
        startActivity(Intent(this, DesbloqueoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    /** Muestra un diálogo que tampoco aparece en capturas (los diálogos son ventanas aparte). */
    protected fun mostrarSeguro(dialogo: AppAlertDialog) {
        if (!AjustesBoveda.permitirCapturas(this)) {
            dialogo.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        dialogo.show()
    }
}
