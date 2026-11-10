package com.vigia.core.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.vigia.core.R

enum class TipoBoton { PRIMARIO, TONAL, PELIGRO }

/**
 * Componentes visuales comunes (botones, tarjetas, textos) construidos en código con los colores del tema de la
 * suite. Así cada app tiene el mismo aspecto sin copiar layouts XML.
 */
object Vistas {

    fun dp(contexto: Context, valor: Int): Int {
        return (valor * contexto.resources.displayMetrics.density + 0.5f).toInt()
    }

    fun color(contexto: Context, recurso: Int): Int = ContextCompat.getColor(contexto, recurso)

    fun titulo(contexto: Context, texto: String): TextView {
        val t = TextView(contexto)
        t.text = texto
        t.textSize = 22f
        t.setTypeface(t.typeface, Typeface.BOLD)
        t.setTextColor(color(contexto, R.color.vc_texto))
        return t
    }

    fun subtitulo(contexto: Context, texto: String): TextView {
        val t = TextView(contexto)
        t.text = texto
        t.textSize = 16f
        t.setTypeface(t.typeface, Typeface.BOLD)
        t.setTextColor(color(contexto, R.color.vc_acento))
        return t
    }

    fun texto(contexto: Context, texto: String, secundario: Boolean = false): TextView {
        val t = TextView(contexto)
        t.text = texto
        t.textSize = 14f
        t.setTextColor(color(contexto, if (secundario) R.color.vc_texto_sec else R.color.vc_texto))
        return t
    }

    fun boton(contexto: Context, texto: String, tipo: TipoBoton = TipoBoton.PRIMARIO, alPulsar: () -> Unit): MaterialButton {
        val b = MaterialButton(contexto)
        b.text = texto
        b.setAllCaps(false)
        b.cornerRadius = dp(contexto, 12)
        val fondo: Int
        val letra: Int
        when (tipo) {
            TipoBoton.PRIMARIO -> {
                fondo = color(contexto, R.color.vc_acento)
                letra = color(contexto, R.color.vc_fondo)
            }
            TipoBoton.TONAL -> {
                fondo = color(contexto, R.color.vc_superficie_alta)
                letra = color(contexto, R.color.vc_texto)
            }
            TipoBoton.PELIGRO -> {
                fondo = color(contexto, R.color.vc_peligro)
                letra = Color.WHITE
            }
        }
        b.backgroundTintList = ColorStateList.valueOf(fondo)
        b.setTextColor(letra)
        b.setOnClickListener { alPulsar() }
        return b
    }

    fun columna(contexto: Context, relleno: Int = 16): LinearLayout {
        val c = LinearLayout(contexto)
        c.orientation = LinearLayout.VERTICAL
        val p = dp(contexto, relleno)
        c.setPadding(p, p, p, p)
        return c
    }

    fun fila(contexto: Context): LinearLayout {
        val f = LinearLayout(contexto)
        f.orientation = LinearLayout.HORIZONTAL
        f.gravity = Gravity.CENTER_VERTICAL
        return f
    }

    /** Contenedor vertical con fondo de tarjeta redondeada. */
    fun tarjeta(contexto: Context): LinearLayout {
        val c = columna(contexto, 14)
        val fondo = GradientDrawable()
        fondo.setColor(color(contexto, R.color.vc_superficie))
        fondo.cornerRadius = dp(contexto, 16).toFloat()
        c.background = fondo
        return c
    }

    fun espacio(contexto: Context, alto: Int): View {
        val v = View(contexto)
        v.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(contexto, alto))
        return v
    }

    /** Parámetros «ancho completo, alto según contenido» con margen inferior. */
    fun anchoCompleto(contexto: Context, margenInferior: Int = 8): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(contexto, margenInferior)
        return lp
    }
}
