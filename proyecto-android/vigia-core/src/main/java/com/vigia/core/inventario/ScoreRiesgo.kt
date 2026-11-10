package com.vigia.core.inventario

/**
 * Puntuación de «cuánto conviene revisar esta app». Cada punto tiene una razón escrita: es una guía para ordenar
 * una lista, nunca un veredicto. Es la lógica del score de Vigía 1.0, aislada para que la usen Task Master, el
 * Gestor de Permisos y el Archivista sin repetirla.
 */
object ScoreRiesgo {
    private const val SIETE_DIAS_MS = 7L * 24L * 3_600_000L

    class Resultado(val puntos: Int, val motivos: List<String>) {
        /** normal, atencion o revisar. */
        val nivel: String
            get() = if (puntos >= 6) "revisar" else if (puntos >= 3) "atencion" else "normal"
    }

    fun calcular(
        sistema: Boolean,
        instalador: String,
        accesibilidad: Boolean,
        lectorNotificaciones: Boolean,
        administrador: Boolean,
        teclado: Boolean,
        permisos: List<String>,
        especiales: List<String>,
        conLanzador: Boolean,
        instalada: Long,
        ahora: Long
    ): Resultado {
        var puntos = 0
        val motivos = ArrayList<String>()
        fun sumar(p: Int, motivo: String) {
            puntos += p
            motivos.add(motivo)
        }
        if (!sistema) {
            if (!CatalogoPermisos.TIENDAS.contains(instalador)) sumar(2, "instalada fuera de una tienda conocida")
            if (accesibilidad) sumar(3, "tiene un servicio de accesibilidad activo")
            if (lectorNotificaciones) sumar(2, "puede leer tus notificaciones")
            if (administrador) sumar(3, "es administradora del dispositivo")
            if (teclado) sumar(2, "es un teclado activo")
            if (permisos.any { it == "READ_SMS" || it == "RECEIVE_SMS" || it == "SEND_SMS" }) sumar(2, "tiene permiso de SMS")
            if (permisos.contains("RECORD_AUDIO")) sumar(1, "puede usar el micrófono")
            if (permisos.contains("CAMERA")) sumar(1, "puede usar la cámara")
            if (permisos.contains("ACCESS_BACKGROUND_LOCATION")) sumar(1, "puede ver tu ubicación en segundo plano")
            if (permisos.contains("READ_CALL_LOG")) sumar(1, "puede leer el registro de llamadas")
            if (especiales.contains("SYSTEM_ALERT_WINDOW")) sumar(1, "puede dibujar sobre otras apps")
            if (especiales.contains("REQUEST_INSTALL_PACKAGES")) sumar(1, "puede instalar otras apps")
            if (!conLanzador) sumar(2, "no tiene icono para abrirla")
            if (instalada > 0L && ahora - instalada < SIETE_DIAS_MS) sumar(1, "se instaló hace menos de una semana")
        }
        return Resultado(puntos, motivos)
    }
}
