package com.vigia.core.red

import java.net.URI

/** Utilidades de direcciones y dominios compartidas por toda la suite. */
object UtilRed {

    /** «192.168.1.1» → Int de 32 bits, o null si no es una IPv4 válida. */
    fun parseIpv4(entrada: String): Int? {
        val partes = entrada.trim().split('.')
        if (partes.size != 4) return null
        var resultado = 0
        for (p in partes) {
            if (p.isEmpty() || p.length > 3) return null
            val n = p.toIntOrNull() ?: return null
            if (n < 0 || n > 255) return null
            resultado = (resultado shl 8) or n
        }
        return resultado
    }

    fun esIpv4Valida(entrada: String): Boolean = parseIpv4(entrada) != null

    fun ipv4ATexto(ip: Int): String {
        return ((ip ushr 24) and 0xFF).toString() + "." + ((ip ushr 16) and 0xFF) + "." +
            ((ip ushr 8) and 0xFF) + "." + (ip and 0xFF)
    }

    /** 10/8, 172.16/12, 192.168/16, 127/8 y 169.254/16 (enlace local). */
    fun esIpPrivada(ip: Int): Boolean {
        val a = (ip ushr 24) and 0xFF
        val b = (ip ushr 16) and 0xFF
        return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
    }

    /**
     * Limpia lo que escribe el usuario: quita http://, rutas, puertos y mayúsculas. Devuelve null si no es un
     * dominio válido (menos de 3 caracteres, sin punto, con caracteres raros o si es una IP).
     */
    fun normalizarDominio(entrada: String): String? {
        var t = entrada.trim().lowercase()
        if (t.startsWith("http://")) t = t.substring(7)
        if (t.startsWith("https://")) t = t.substring(8)
        val barra = t.indexOf('/')
        if (barra >= 0) t = t.substring(0, barra)
        val dosPuntos = t.indexOf(':')
        if (dosPuntos >= 0) t = t.substring(0, dosPuntos)
        t = t.trim('.')
        if (t.length < 3 || t.length > 253 || !t.contains('.')) return null
        for (c in t) {
            val valido = (c in 'a'..'z') || (c in '0'..'9') || c == '-' || c == '.' || c == '_'
            if (!valido) return null
        }
        if (parseIpv4(t) != null) return null
        return t
    }

    /** «a.b.example.com» → [a.b.example.com, b.example.com, example.com, com]. */
    fun cadenaDeSufijos(dominio: String): List<String> {
        val res = ArrayList<String>()
        var actual = dominio.lowercase().trim('.')
        while (actual.isNotEmpty()) {
            res.add(actual)
            val punto = actual.indexOf('.')
            if (punto < 0) break
            actual = actual.substring(punto + 1)
        }
        return res
    }

    private val SEGUNDO_NIVEL = setOf("co", "com", "org", "net", "gov", "edu", "ac", "or", "ne", "go")

    /**
     * Dominio «registrable» aproximado (example.com, example.co.uk). No usa la lista pública de sufijos: es una
     * heurística suficiente para agrupar, no para decisiones de seguridad.
     */
    fun dominioRegistrable(host: String): String {
        val partes = host.lowercase().trim('.').split('.')
        if (partes.size <= 2) return partes.joinToString(".")
        val ultima = partes[partes.size - 1]
        val penultima = partes[partes.size - 2]
        val tomar = if (ultima.length == 2 && SEGUNDO_NIVEL.contains(penultima)) 3 else 2
        return partes.subList(partes.size - tomar, partes.size).joinToString(".")
    }

    /** Extrae el nombre de host de una URL («https://user@Ejemplo.com:8080/x» → «ejemplo.com»), o null. */
    fun extraerHost(url: String): String? {
        val limpio = url.trim()
        val conEsquema = if (limpio.contains("://")) limpio else "http://" + limpio
        return try {
            val host = URI(conEsquema).host
            if (host.isNullOrEmpty()) null else host.lowercase().trim('.')
        } catch (e: Exception) {
            null
        }
    }
}
