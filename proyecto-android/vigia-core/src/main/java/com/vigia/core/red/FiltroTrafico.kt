package com.vigia.core.red

/**
 * Decide qué se bloquea. Cada app de la suite aporta el suyo: Vigía (lista del usuario), AdBlock Soberano
 * (millones de dominios en un trie), etc. Se consulta desde varios hilos, así que debe ser seguro para ello
 * y MUY rápido: se llama una vez por consulta DNS y, con SNI activo, una vez por conexión TLS.
 */
interface FiltroTrafico {
    /** Falso si no hay nada que bloquear: permite saltarse trabajo (por ejemplo la retención del SNI). */
    val hayDominios: Boolean

    /** Verdadero si [nombre] (o un dominio padre suyo) está bloqueado. [nombre] llega en minúsculas. */
    fun dominioBloqueado(nombre: String): Boolean

    /** Verdadero si la IPv4 (como Int de 32 bits) está bloqueada. */
    fun ipBloqueada(ip: Int): Boolean

    companion object {
        val NINGUNO: FiltroTrafico = object : FiltroTrafico {
            override val hayDominios: Boolean get() = false
            override fun dominioBloqueado(nombre: String): Boolean = false
            override fun ipBloqueada(ip: Int): Boolean = false
        }
    }
}

/**
 * Filtro sencillo basado en conjuntos (la lógica del «Escudo» de Vigía 1.0): un dominio bloqueado incluye
 * todos sus subdominios. Sirve para listas pequeñas; para listas masivas hará falta un trie o un filtro Bloom.
 */
class FiltroPorConjuntos : FiltroTrafico {
    @Volatile
    private var dominios: Set<String> = emptySet()

    @Volatile
    private var ips: Set<Int> = emptySet()

    override val hayDominios: Boolean get() = dominios.isNotEmpty()

    /** Sustituye las listas de golpe (la sustitución es atómica para los hilos que consultan). */
    fun actualizar(nuevosDominios: Collection<String>, nuevasIps: Collection<Int> = emptyList()) {
        dominios = HashSet(nuevosDominios)
        ips = HashSet(nuevasIps)
    }

    override fun ipBloqueada(ip: Int): Boolean = ips.contains(ip)

    override fun dominioBloqueado(nombre: String): Boolean {
        val bloqueados = dominios
        if (bloqueados.isEmpty()) return false
        var actual = nombre.lowercase()
        while (true) {
            if (bloqueados.contains(actual)) return true
            val punto = actual.indexOf('.')
            if (punto < 0) return false
            actual = actual.substring(punto + 1)
            if (!actual.contains('.')) return bloqueados.contains(actual)
        }
    }
}
