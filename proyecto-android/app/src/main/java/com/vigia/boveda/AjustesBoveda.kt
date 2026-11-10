package com.vigia.boveda

import android.content.Context

/** Preferencias no secretas de La Bóveda. Nada de lo que hay aquí permite leer datos de la bóveda. */
object AjustesBoveda {
    private const val PREFS = "boveda_ajustes"

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Segundos en segundo plano antes de bloquear. 2 = «inmediato» (margen para giros de pantalla). */
    fun autobloqueoSegundos(c: Context): Int = p(c).getInt("autobloqueo", 60)
    fun setAutobloqueoSegundos(c: Context, v: Int) = p(c).edit().putInt("autobloqueo", v).apply()

    fun bloquearAlApagarPantalla(c: Context): Boolean = p(c).getBoolean("bloq_pantalla", true)
    fun setBloquearAlApagarPantalla(c: Context, v: Boolean) = p(c).edit().putBoolean("bloq_pantalla", v).apply()

    fun permitirCapturas(c: Context): Boolean = p(c).getBoolean("capturas", false)
    fun setPermitirCapturas(c: Context, v: Boolean) = p(c).edit().putBoolean("capturas", v).apply()

    /** 0 = no limpiar. */
    fun portapapelesSegundos(c: Context): Int = p(c).getInt("portapapeles", 30)
    fun setPortapapelesSegundos(c: Context, v: Int) = p(c).edit().putInt("portapapeles", v).apply()

    fun tecladoSeguroPorDefecto(c: Context): Boolean = p(c).getBoolean("teclado_seguro", false)
    fun setTecladoSeguroPorDefecto(c: Context, v: Boolean) = p(c).edit().putBoolean("teclado_seguro", v).apply()

    // ---- opciones del generador (se recuerdan)
    fun opcionesGenerador(c: Context): OpcionesClave {
        val q = p(c)
        return OpcionesClave(
            largo = q.getInt("gen_largo", 20),
            minusculas = q.getBoolean("gen_min", true),
            mayusculas = q.getBoolean("gen_may", true),
            digitos = q.getBoolean("gen_dig", true),
            simbolos = q.getBoolean("gen_sim", true),
            evitarAmbiguos = q.getBoolean("gen_amb", true)
        )
    }

    fun guardarOpcionesGenerador(c: Context, o: OpcionesClave) {
        p(c).edit()
            .putInt("gen_largo", o.largo)
            .putBoolean("gen_min", o.minusculas)
            .putBoolean("gen_may", o.mayusculas)
            .putBoolean("gen_dig", o.digitos)
            .putBoolean("gen_sim", o.simbolos)
            .putBoolean("gen_amb", o.evitarAmbiguos)
            .apply()
    }

    // ---- freno a los intentos fallidos de contraseña maestra
    fun intentosFallidos(c: Context): Int = p(c).getInt("fallos", 0)

    fun bloqueadoHasta(c: Context): Long = p(c).getLong("bloqueado_hasta", 0L)

    fun registrarFallo(c: Context) {
        val n = intentosFallidos(c) + 1
        val espera = if (n >= 5) minOf(3600L, 30L shl minOf(n - 5, 7)) else 0L
        p(c).edit().putInt("fallos", n).putLong("bloqueado_hasta", System.currentTimeMillis() + espera * 1000L).apply()
    }

    fun registrarAcierto(c: Context) {
        p(c).edit().putInt("fallos", 0).putLong("bloqueado_hasta", 0L).apply()
    }
}
