package com.vigia.boveda

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import com.vigia.core.R
import com.vigia.core.ui.Vistas

/**
 * Teclado propio en pantalla para escribir la contraseña maestra sin que el teclado del sistema (ni sus
 * sugerencias, historial o diccionario) vea las pulsaciones. Sin vista previa de teclas ni autocorrección.
 */
class TecladoSeguro(contexto: Context, var destino: EditText) : LinearLayout(contexto) {

    private var mayusculas = false
    private var simbolos = false

    init {
        orientation = VERTICAL
        construir()
    }

    private fun construir() {
        removeAllViews()
        val filas = if (simbolos) {
            listOf("1234567890", "!@#\$%^&*()", "-_=+[]{};:", "'\",.<>/?\\|", "`~€£¥§¿¡")
        } else {
            listOf("1234567890", "qwertyuiop", "asdfghjklñ", "zxcvbnm,.-")
        }
        for (f in filas) {
            val fila = nuevaFila()
            for (ch in f) {
                val texto = if (!simbolos && mayusculas) ch.uppercaseChar().toString() else ch.toString()
                fila.addView(tecla(texto, 1f) { escribir(texto) })
            }
            addView(fila)
        }
        val ultima = nuevaFila()
        if (!simbolos) ultima.addView(tecla(if (mayusculas) "⇧ ON" else "⇧", 1.4f) { mayusculas = !mayusculas; construir() })
        ultima.addView(tecla(if (simbolos) "ABC" else "#+=", 1.4f) { simbolos = !simbolos; construir() })
        ultima.addView(tecla("espacio", 3f) { escribir(" ") })
        ultima.addView(tecla("⌫", 1.4f) { borrar() })
        addView(ultima)
    }

    private fun nuevaFila(): LinearLayout {
        val f = LinearLayout(context)
        f.orientation = HORIZONTAL
        f.gravity = Gravity.CENTER
        return f
    }

    private fun tecla(texto: String, peso: Float, alPulsar: () -> Unit): Button {
        val b = Button(context)
        b.text = texto
        b.setAllCaps(false)
        b.textSize = 15f
        b.minWidth = 0
        b.minimumWidth = 0
        b.minHeight = 0
        b.minimumHeight = 0
        b.setPadding(0, Vistas.dp(context, 10), 0, Vistas.dp(context, 10))
        b.setTextColor(Vistas.color(context, R.color.vc_texto))
        val fondo = GradientDrawable()
        fondo.setColor(Vistas.color(context, R.color.vc_superficie_alta))
        fondo.cornerRadius = Vistas.dp(context, 6).toFloat()
        b.background = fondo
        val lp = LayoutParams(0, LayoutParams.WRAP_CONTENT, peso)
        val m = Vistas.dp(context, 2)
        lp.setMargins(m, m, m, m)
        b.layoutParams = lp
        b.setOnClickListener { alPulsar() }
        return b
    }

    private fun escribir(texto: String) {
        val ini = maxOf(0, minOf(destino.selectionStart, destino.selectionEnd))
        val fin = maxOf(0, maxOf(destino.selectionStart, destino.selectionEnd))
        destino.text.replace(ini, fin, texto)
        if (mayusculas) {
            mayusculas = false
            construir()
        }
    }

    private fun borrar() {
        val ini = maxOf(0, minOf(destino.selectionStart, destino.selectionEnd))
        val fin = maxOf(0, maxOf(destino.selectionStart, destino.selectionEnd))
        if (fin > ini) {
            destino.text.delete(ini, fin)
        } else if (ini > 0) {
            destino.text.delete(ini - 1, ini)
        }
    }
}
