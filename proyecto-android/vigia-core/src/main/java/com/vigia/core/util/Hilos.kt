package com.vigia.core.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/** Hilos y corrutinas con las mismas reglas en toda la suite. */
object Hilos {

    /** Ámbito que sobrevive al fallo de una tarea hija (SupervisorJob). Hay que cancelarlo al destruir el componente. */
    fun nuevoAmbito(despachador: CoroutineDispatcher = Dispatchers.Default): CoroutineScope {
        return CoroutineScope(SupervisorJob() + despachador)
    }

    suspend fun <T> enIO(bloque: () -> T): T = withContext(Dispatchers.IO) { bloque() }

    /** Hilos «demonio»: no impiden que el proceso termine y llevan nombre reconocible en los volcados. */
    fun fabricaDemonio(prefijo: String): ThreadFactory {
        val contador = AtomicInteger(0)
        return ThreadFactory { r ->
            val h = Thread(r, prefijo + "-" + contador.incrementAndGet())
            h.isDaemon = true
            h
        }
    }

    fun grupoFijo(tamano: Int, prefijo: String): ExecutorService {
        return Executors.newFixedThreadPool(tamano, fabricaDemonio(prefijo))
    }
}
