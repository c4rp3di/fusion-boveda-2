package com.vigia.boveda

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import com.vigia.core.seguridad.BaseDatosCifrada

@Entity(tableName = "credenciales")
data class Credencial(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val titulo: String,
    val usuario: String,
    val clave: String,
    val url: String,
    val appPaquete: String,
    val notas: String,
    val favorito: Boolean,
    val creado: Long,
    val modificado: Long
)

/** padreId = 0 es la raíz. */
@Entity(tableName = "carpetas")
data class Carpeta(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nombre: String,
    val padreId: Long
)

/** El contenido del archivo vive cifrado en disco bajo [interno] (nombre aleatorio); aquí solo van sus datos. */
@Entity(tableName = "archivos")
data class ArchivoBoveda(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nombre: String,
    val mime: String,
    val tamano: Long,
    val interno: String,
    val carpetaId: Long,
    val creado: Long
)

@Dao
interface CredencialDao {
    @Query("SELECT * FROM credenciales ORDER BY favorito DESC, titulo COLLATE NOCASE ASC")
    suspend fun todas(): List<Credencial>

    @Query("SELECT * FROM credenciales WHERE id = :id")
    suspend fun porId(id: Long): Credencial?

    @Insert
    suspend fun insertar(c: Credencial): Long

    @Update
    suspend fun actualizar(c: Credencial)

    @Delete
    suspend fun borrar(c: Credencial)
}

@Dao
interface CarpetaDao {
    @Query("SELECT * FROM carpetas WHERE padreId = :padre ORDER BY nombre COLLATE NOCASE ASC")
    suspend fun hijas(padre: Long): List<Carpeta>

    @Query("SELECT * FROM carpetas WHERE id = :id")
    suspend fun porId(id: Long): Carpeta?

    @Insert
    suspend fun insertar(c: Carpeta): Long

    @Update
    suspend fun actualizar(c: Carpeta)

    @Query("DELETE FROM carpetas WHERE id = :id")
    suspend fun borrarPorId(id: Long)
}

@Dao
interface ArchivoDao {
    @Query("SELECT * FROM archivos WHERE carpetaId = :carpeta ORDER BY nombre COLLATE NOCASE ASC")
    suspend fun deCarpeta(carpeta: Long): List<ArchivoBoveda>

    @Insert
    suspend fun insertar(a: ArchivoBoveda): Long

    @Update
    suspend fun actualizar(a: ArchivoBoveda)

    @Query("DELETE FROM archivos WHERE id = :id")
    suspend fun borrarPorId(id: Long)
}

@Database(entities = [Credencial::class, Carpeta::class, ArchivoBoveda::class], version = 1, exportSchema = false)
abstract class BovedaDb : RoomDatabase() {

    abstract fun credenciales(): CredencialDao

    abstract fun carpetas(): CarpetaDao

    abstract fun archivos(): ArchivoDao

    companion object {
        const val NOMBRE = "boveda.db"

        @Volatile
        private var instancia: BovedaDb? = null

        /** Abre la base con la clave de la bóveda (solo existe en memoria mientras está desbloqueada). */
        fun abrir(contexto: Context, clave: ByteArray): BovedaDb {
            synchronized(this) {
                val actual = instancia
                if (actual != null) return actual
                val nueva = Room.databaseBuilder(contexto.applicationContext, BovedaDb::class.java, NOMBRE)
                    .openHelperFactory(BaseDatosCifrada.fabricaConClave(clave))
                    .build()
                instancia = nueva
                return nueva
            }
        }

        fun actual(): BovedaDb? = instancia

        fun cerrar() {
            synchronized(this) {
                try {
                    instancia?.close()
                } catch (e: Exception) {
                }
                instancia = null
            }
        }
    }
}
