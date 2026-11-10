package com.vigia.boveda

/**
 * Datos de un formulario de registro vistos mientras La Bóveda estaba cerrada. Viven solo en memoria y unos pocos minutos:
 * cuando el usuario desbloquea, se abre el editor ya rellenado para que los confirme, en vez de tener que teclearlos.
 * Nunca se escriben en disco.
 */
object PendienteGuardar {
    private const val VIDA_MS = 5L * 60_000L

    class Datos(val usuario: String, val clave: String, val dominio: String?, val paquete: String, val instante: Long)

    @Volatile
    private var actual: Datos? = null

    /** Sin contraseña no hay nada que guardar, así que no se retiene nada. */
    fun guardar(usuario: String, clave: String, dominio: String?, paquete: String) {
        if (clave.isEmpty()) return
        actual = Datos(usuario, clave, dominio, paquete, System.currentTimeMillis())
    }

    fun hay(): Boolean {
        val d = actual ?: return false
        return System.currentTimeMillis() - d.instante <= VIDA_MS
    }

    /** Devuelve los datos pendientes (si no han caducado) y los olvida. */
    fun tomar(): Datos? {
        val d = actual
        actual = null
        if (d == null) return null
        return if (System.currentTimeMillis() - d.instante <= VIDA_MS) d else null
    }

    fun olvidar() {
        actual = null
    }
}
