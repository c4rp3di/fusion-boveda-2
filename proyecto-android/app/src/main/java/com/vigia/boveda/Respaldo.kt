package com.vigia.boveda

import com.vigia.core.seguridad.CifradoArchivos
import org.json.JSONArray
import org.json.JSONObject

/** Copia de seguridad de las contraseñas: JSON cifrado con AES-256-GCM y una contraseña que elige la persona. */
object Respaldo {
    private val cifrador = CifradoArchivos("BOVED2")

    fun exportar(lista: List<Credencial>, contrasena: CharArray): ByteArray {
        val arr = JSONArray()
        for (c in lista) {
            val o = JSONObject()
            o.put("titulo", c.titulo)
            o.put("usuario", c.usuario)
            o.put("clave", c.clave)
            o.put("url", c.url)
            o.put("app", c.appPaquete)
            o.put("notas", c.notas)
            o.put("favorito", c.favorito)
            o.put("creado", c.creado)
            o.put("modificado", c.modificado)
            arr.put(o)
        }
        val raiz = JSONObject()
        raiz.put("version", 1)
        raiz.put("credenciales", arr)
        return cifrador.cifrar(raiz.toString().toByteArray(Charsets.UTF_8), contrasena)
    }

    /** @throws IllegalStateException contraseña incorrecta o archivo dañado; IllegalArgumentException si no es una copia de La Bóveda. */
    fun leer(datos: ByteArray, contrasena: CharArray): List<Credencial> {
        val json = String(cifrador.descifrar(datos, contrasena), Charsets.UTF_8)
        val raiz = JSONObject(json)
        val arr = raiz.getJSONArray("credenciales")
        val res = ArrayList<Credencial>()
        val ahora = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            res.add(
                Credencial(
                    titulo = o.optString("titulo"), usuario = o.optString("usuario"), clave = o.optString("clave"),
                    url = o.optString("url"), appPaquete = o.optString("app"), notas = o.optString("notas"),
                    favorito = o.optBoolean("favorito"), creado = o.optLong("creado", ahora), modificado = o.optLong("modificado", ahora)
                )
            )
        }
        return res
    }
}
