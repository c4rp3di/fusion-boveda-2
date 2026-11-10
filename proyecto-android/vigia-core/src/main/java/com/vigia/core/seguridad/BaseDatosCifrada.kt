package com.vigia.core.seguridad

import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Entrega a Room la fábrica de SQLCipher con la clave custodiada por el Keystore. Cada app define sus entidades:
 *
 *     Room.databaseBuilder(ctx, MiBd::class.java, "mi.db")
 *         .openHelperFactory(BaseDatosCifrada.fabrica(almacen, "mi.db"))
 *         .build()
 */
object BaseDatosCifrada {
    fun fabrica(almacen: AlmacenClaves, nombreBd: String): SupportOpenHelperFactory {
        System.loadLibrary("sqlcipher")
        return SupportOpenHelperFactory(almacen.obtenerClaveBd(nombreBd))
    }

    /**
     * Fábrica con una clave que la app ya tiene en memoria (por ejemplo, derivada de una contraseña maestra).
     * SQLCipher puede poner a cero el array que recibe, así que se le entrega una copia.
     */
    fun fabricaConClave(clave: ByteArray): SupportOpenHelperFactory {
        System.loadLibrary("sqlcipher")
        return SupportOpenHelperFactory(clave.copyOf())
    }
}
