package com.vigia.core.util

import android.text.format.DateFormat
import java.util.Date
import java.util.Locale

/** Formatos de texto comunes: fechas, duraciones y tamaños. */
object Formato {
    fun duracion(ms: Long): String {
        val s = if (ms < 0) 0L else ms / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    fun fecha(ms: Long): String = DateFormat.format("d MMM yyyy HH:mm", Date(ms)).toString()

    fun fechaHoraCompleta(ms: Long): String = DateFormat.format("yyyy-MM-dd HH:mm:ss", Date(ms)).toString()

    fun hora(ms: Long): String = DateFormat.format("HH:mm:ss", Date(ms)).toString()

    /** 1536 → «1,5 KB». Usa base 1024. */
    fun bytes(n: Long): String {
        if (n < 1024L) return n.toString() + " B"
        val unidades = arrayOf("KB", "MB", "GB", "TB")
        var valor = n.toDouble()
        var i = -1
        while (valor >= 1024.0 && i < unidades.size - 1) {
            valor /= 1024.0
            i++
        }
        return String.format(Locale.getDefault(), "%.1f %s", valor, unidades[i])
    }
}
