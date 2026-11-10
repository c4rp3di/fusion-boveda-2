package com.vigia.core.red

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Una consulta DNS vista por el túnel. [bloqueado] es verdadero si el filtro la respondió sin salir a internet. */
data class EventoDns(
    val ts: Long,
    val uid: Int,
    val app: String,
    val dominio: String,
    val tipo: String,
    val respuesta: String,
    val bloqueado: Boolean
)

/** Metadatos de una conexión TCP/UDP (modo conexiones): destino, bytes y nombre de servidor TLS (SNI). Nunca contenido. */
data class EventoRed(
    val ts: Long,
    val uid: Int,
    val app: String,
    val ip: String,
    val puerto: Int,
    val proto: String,
    val sni: String,
    val bytesIn: Long,
    val bytesOut: Long
)

/**
 * Lo que una app quiere saber del tráfico. TODOS los métodos se llaman desde hilos del túnel (no el principal):
 * deben volver rápido y ser seguros entre hilos (lo normal es meter el evento en una cola y escribirlo aparte).
 */
interface ObservadorTrafico {
    /**
     * Falso = «solo me interesan los bloqueos». Con falso el túnel NO resuelve la app de origen ni lee las
     * respuestas, que es lo que cuesta batería: pensado para el bloqueador 24/7.
     */
    val quiereDetalle: Boolean get() = true

    fun alDns(evento: EventoDns) {}

    fun alConexion(evento: EventoRed) {}

    /** Se llama cada vez que el filtro bloquea un dominio (por DNS o por SNI). */
    fun alBloqueo(dominio: String) {}
}

/** Observador mínimo: solo cuenta bloqueos, en memoria, sin guardar nada. */
class ContadorBloqueos : ObservadorTrafico {
    private val _total = MutableStateFlow(0L)

    val total: StateFlow<Long> get() = _total

    override val quiereDetalle: Boolean get() = false

    override fun alBloqueo(dominio: String) {
        _total.update { it + 1L }
    }

    fun reiniciar() {
        _total.value = 0L
    }
}
