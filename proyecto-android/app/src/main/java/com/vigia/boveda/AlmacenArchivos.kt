package com.vigia.boveda

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.vigia.core.seguridad.CifradoFlujo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Archivos cifrados uno a uno. En disco solo hay bloques AES-256-GCM con nombre aleatorio; nombre, tipo y carpeta están en la base cifrada. */
class AlmacenArchivos(contexto: Context) {
    private val app = contexto.applicationContext

    private fun carpetaDisco(): File {
        val d = File(app.filesDir, "boveda_archivos")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun clave(): ByteArray = SesionBoveda.clave() ?: throw IllegalStateException("La bóveda está bloqueada")

    private fun db(): BovedaDb = BovedaDb.actual() ?: throw IllegalStateException("La bóveda está bloqueada")

    /** Lee el archivo del selector del sistema y lo guarda cifrado. El original no se toca. */
    suspend fun importar(uri: Uri, carpetaId: Long): ArchivoBoveda = withContext(Dispatchers.IO) {
        val clave = clave()
        var nombre = "archivo"
        var tamano = -1L
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val iNombre = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val iTam = c.getColumnIndex(OpenableColumns.SIZE)
                if (iNombre >= 0 && !c.isNull(iNombre)) nombre = c.getString(iNombre)
                if (iTam >= 0 && !c.isNull(iTam)) tamano = c.getLong(iTam)
            }
        }
        val mime = app.contentResolver.getType(uri) ?: "application/octet-stream"
        val interno = UUID.randomUUID().toString().replace("-", "")
        val destino = File(carpetaDisco(), interno)
        try {
            val entrada = app.contentResolver.openInputStream(uri) ?: throw IllegalStateException("No se pudo abrir el archivo")
            entrada.use { ent ->
                destino.outputStream().buffered().use { sal -> CifradoFlujo.cifrar(ent, sal, clave) }
            }
        } catch (e: Exception) {
            destino.delete()
            throw e
        }
        if (tamano < 0) tamano = destino.length()
        val registro = ArchivoBoveda(
            nombre = nombre, mime = mime, tamano = tamano, interno = interno,
            carpetaId = carpetaId, creado = System.currentTimeMillis()
        )
        val id = db().archivos().insertar(registro)
        registro.copy(id = id)
    }

    /** Descifra al destino elegido por la persona (ojo: el resultado queda en claro fuera de La Bóveda). */
    suspend fun exportar(a: ArchivoBoveda, destino: Uri) = withContext(Dispatchers.IO) {
        val clave = clave()
        val origen = File(carpetaDisco(), a.interno)
        val salida = app.contentResolver.openOutputStream(destino) ?: throw IllegalStateException("No se pudo escribir el destino")
        salida.use { sal ->
            origen.inputStream().buffered().use { ent -> CifradoFlujo.descifrar(ent, sal, clave) }
        }
    }

    /** Descifra en memoria (para vista previa). Falla si supera [maximo] bytes. */
    suspend fun leerEnMemoria(a: ArchivoBoveda, maximo: Long): ByteArray = withContext(Dispatchers.IO) {
        if (a.tamano > maximo) throw IllegalArgumentException("El archivo es demasiado grande para la vista previa")
        val clave = clave()
        val bytes = ByteArrayOutputStream()
        File(carpetaDisco(), a.interno).inputStream().buffered().use { ent -> CifradoFlujo.descifrar(ent, bytes, clave) }
        bytes.toByteArray()
    }

    suspend fun borrar(a: ArchivoBoveda) = withContext(Dispatchers.IO) {
        File(carpetaDisco(), a.interno).delete()
        db().archivos().borrarPorId(a.id)
    }

    suspend fun renombrar(a: ArchivoBoveda, nuevo: String) = withContext(Dispatchers.IO) {
        db().archivos().actualizar(a.copy(nombre = nuevo))
    }

    suspend fun crearCarpeta(nombre: String, padreId: Long): Long = withContext(Dispatchers.IO) {
        db().carpetas().insertar(Carpeta(nombre = nombre, padreId = padreId))
    }

    suspend fun renombrarCarpeta(c: Carpeta, nuevo: String) = withContext(Dispatchers.IO) {
        db().carpetas().actualizar(c.copy(nombre = nuevo))
    }

    /** Borra la carpeta con todo lo que contiene (subcarpetas y archivos, también del disco). */
    suspend fun borrarCarpeta(id: Long) = withContext(Dispatchers.IO) { borrarRecursivo(id) }

    private suspend fun borrarRecursivo(id: Long) {
        val d = db()
        for (a in d.archivos().deCarpeta(id)) {
            File(carpetaDisco(), a.interno).delete()
            d.archivos().borrarPorId(a.id)
        }
        for (h in d.carpetas().hijas(id)) borrarRecursivo(h.id)
        d.carpetas().borrarPorId(id)
    }

    /** Ruta legible de una carpeta, de la raíz hacia dentro. */
    suspend fun ruta(id: Long): List<Carpeta> = withContext(Dispatchers.IO) {
        val res = ArrayList<Carpeta>()
        var actual = id
        var seguridad = 0
        while (actual != 0L && seguridad < 64) {
            val c = db().carpetas().porId(actual) ?: break
            res.add(0, c)
            actual = c.padreId
            seguridad++
        }
        res
    }
}
