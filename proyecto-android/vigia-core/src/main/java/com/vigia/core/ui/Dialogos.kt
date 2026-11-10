package com.vigia.core.ui

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Diálogos comunes de la suite. Todos usan el tema oscuro de :vigia-core. */
object Dialogos {

    fun informar(contexto: Context, titulo: String, mensaje: String, textoOk: String = "Aceptar") {
        MaterialAlertDialogBuilder(contexto)
            .setTitle(titulo)
            .setMessage(mensaje)
            .setPositiveButton(textoOk, null)
            .show()
    }

    fun confirmar(
        contexto: Context,
        titulo: String,
        mensaje: String,
        textoOk: String = "Aceptar",
        textoCancelar: String = "Cancelar",
        alAceptar: () -> Unit
    ) {
        MaterialAlertDialogBuilder(contexto)
            .setTitle(titulo)
            .setMessage(mensaje)
            .setPositiveButton(textoOk) { _, _ -> alAceptar() }
            .setNegativeButton(textoCancelar, null)
            .show()
    }

    fun elegir(contexto: Context, titulo: String, opciones: List<String>, alElegir: (Int) -> Unit) {
        MaterialAlertDialogBuilder(contexto)
            .setTitle(titulo)
            .setItems(opciones.toTypedArray()) { _, indice -> alElegir(indice) }
            .show()
    }

    /** Pide un texto. Con [secreto] el campo oculta lo escrito (contraseñas). */
    fun pedirTexto(
        contexto: Context,
        titulo: String,
        sugerencia: String = "",
        secreto: Boolean = false,
        textoOk: String = "Aceptar",
        alAceptar: (String) -> Unit
    ) {
        val campo = EditText(contexto)
        campo.hint = sugerencia
        campo.setSingleLine(true)
        campo.inputType = if (secreto) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT
        }
        val margen = Vistas.dp(contexto, 20)
        val contenedor = FrameLayout(contexto)
        contenedor.setPadding(margen, Vistas.dp(contexto, 8), margen, 0)
        contenedor.addView(campo)
        MaterialAlertDialogBuilder(contexto)
            .setTitle(titulo)
            .setView(contenedor)
            .setPositiveButton(textoOk) { _, _ -> alAceptar(campo.text.toString()) }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
