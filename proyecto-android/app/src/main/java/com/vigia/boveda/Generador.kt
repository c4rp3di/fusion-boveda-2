package com.vigia.boveda

import java.security.SecureRandom
import kotlin.math.ln

class OpcionesClave(
    val largo: Int = 20,
    val minusculas: Boolean = true,
    val mayusculas: Boolean = true,
    val digitos: Boolean = true,
    val simbolos: Boolean = true,
    val evitarAmbiguos: Boolean = true
)

/** Generador de contraseñas con SecureRandom (criptográficamente seguro) y selección sin sesgo. */
object GeneradorClaves {
    private val azar = SecureRandom()
    private const val MINUSCULAS = "abcdefghijklmnopqrstuvwxyz"
    private const val MAYUSCULAS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val DIGITOS = "0123456789"
    private const val SIMBOLOS = "!@#\$%^&*()-_=+[]{};:,.?"
    private const val AMBIGUOS = "Il1O0o"

    const val LARGO_MIN = 8
    const val LARGO_MAX = 64

    private fun conjuntos(o: OpcionesClave): List<String> {
        val res = ArrayList<String>()
        if (o.minusculas) res.add(MINUSCULAS)
        if (o.mayusculas) res.add(MAYUSCULAS)
        if (o.digitos) res.add(DIGITOS)
        if (o.simbolos) res.add(SIMBOLOS)
        if (!o.evitarAmbiguos) return res
        return res.map { s -> s.filter { !AMBIGUOS.contains(it) } }
    }

    fun hayConjuntos(o: OpcionesClave): Boolean = conjuntos(o).isNotEmpty()

    /** Siempre incluye al menos un carácter de cada tipo elegido. Devuelve null si no hay ningún tipo marcado. */
    fun generar(o: OpcionesClave): String? {
        val sets = conjuntos(o)
        if (sets.isEmpty()) return null
        val largo = o.largo.coerceIn(maxOf(LARGO_MIN, sets.size), LARGO_MAX)
        val todos = sets.joinToString("")
        val c = CharArray(largo)
        for (i in sets.indices) c[i] = sets[i][azar.nextInt(sets[i].length)]
        for (i in sets.size until largo) c[i] = todos[azar.nextInt(todos.length)]
        // Fisher-Yates: las posiciones obligatorias no quedan siempre al principio.
        for (i in largo - 1 downTo 1) {
            val j = azar.nextInt(i + 1)
            val t = c[i]
            c[i] = c[j]
            c[j] = t
        }
        return String(c)
    }

    /** Entropía teórica de una clave generada con estas opciones. */
    fun entropiaBits(o: OpcionesClave): Double {
        val sets = conjuntos(o)
        if (sets.isEmpty()) return 0.0
        val tam = sets.joinToString("").length
        return maxOf(o.largo, sets.size) * ln(tam.toDouble()) / ln(2.0)
    }
}

/** Estimación orientativa de la fuerza de una contraseña escrita por una persona. */
object FuerzaClave {
    /** 0 = muy débil … 4 = muy fuerte. */
    fun nivel(clave: CharArray): Int {
        val bits = bitsEstimados(clave)
        return if (bits < 28) 0 else if (bits < 40) 1 else if (bits < 60) 2 else if (bits < 80) 3 else 4
    }

    fun etiqueta(nivel: Int): String {
        return when (nivel) {
            0 -> "muy débil"
            1 -> "débil"
            2 -> "aceptable"
            3 -> "fuerte"
            else -> "muy fuerte"
        }
    }

    /** Longitud × tamaño del alfabeto, con castigo a repeticiones y secuencias. Es una cota optimista, no una garantía. */
    fun bitsEstimados(clave: CharArray): Double {
        if (clave.isEmpty()) return 0.0
        var pool = 0
        if (clave.any { it in 'a'..'z' }) pool += 26
        if (clave.any { it in 'A'..'Z' }) pool += 26
        if (clave.any { it in '0'..'9' }) pool += 10
        if (clave.any { !it.isLetterOrDigit() }) pool += 32
        if (clave.any { it.isLetter() && it.code > 127 }) pool += 20
        if (pool == 0) return 0.0
        val distintos = clave.toSet().size
        val efectivo = minOf(clave.size.toDouble(), distintos * 2.5)
        var bits = efectivo * ln(pool.toDouble()) / ln(2.0)
        var secuencias = 0
        for (i in 2 until clave.size) {
            val a = clave[i - 2].code
            val b = clave[i - 1].code
            val c = clave[i].code
            if (b - a == 1 && c - b == 1) secuencias++
        }
        bits -= secuencias * 3.0
        // Una contraseña basada en una palabra típica (aunque lleve @, 0, 1, !…) cae en los primeros intentos de un atacante.
        val normal = normalizar(clave)
        if (COMUNES.any { normal.contains(it) }) bits = minOf(bits, 24.0)
        return maxOf(bits, 0.0)
    }

    private val COMUNES = listOf(
        "password", "passw0rd", "contrasena", "contraseña", "qwerty", "asdfg", "zxcv", "123456", "654321", "abcdef",
        "admin", "letmein", "welcome", "iloveyou", "monkey", "dragon", "master", "login", "princess", "sunshine",
        "secret", "bienvenido", "usuario", "futbol", "barcelona", "realmadrid", "america", "teamo", "te amo",
        "carino", "cariño", "123123", "111111", "000000", "football", "baseball", "superman", "batman"
    )

    /** Deshace las sustituciones habituales (@→a, 0→o, 1→l, 3→e, $→s…) para reconocer palabras disfrazadas. */
    private fun normalizar(clave: CharArray): String {
        val sb = StringBuilder()
        for (c in String(clave).lowercase()) {
            sb.append(
                when (c) {
                    '@' -> 'a'
                    '4' -> 'a'
                    '0' -> 'o'
                    '1' -> 'l'
                    '3' -> 'e'
                    '5' -> 's'
                    '7' -> 't'
                    '\$' -> 's'
                    '!' -> 'i'
                    else -> c
                }
            )
        }
        return sb.toString()
    }
}
