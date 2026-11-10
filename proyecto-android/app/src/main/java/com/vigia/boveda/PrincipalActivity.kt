package com.vigia.boveda

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.autofill.AutofillManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vigia.core.R
import com.vigia.core.ui.Dialogos
import com.vigia.core.ui.TipoBoton
import com.vigia.core.ui.Vistas
import com.vigia.core.util.Formato
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pantalla principal con cuatro pestañas: Claves, Archivos, Generador y Ajustes. */
class PrincipalActivity : ActividadBoveda() {

    private lateinit var contenedor: FrameLayout
    private val botonesPestana = ArrayList<MaterialButton>()
    private var pestana = 0
    private val almacen by lazy { AlmacenArchivos(this) }

    // Claves
    private var credenciales: List<Credencial> = emptyList()
    private var textoFiltro = ""
    private var listaClaves: LinearLayout? = null
    private var textoAuditoria: TextView? = null

    // Archivos
    private var carpetaActual = 0L
    private var listaArchivos: LinearLayout? = null
    private var textoRuta: TextView? = null
    private var padreDeActual = 0L

    // Generador
    private var opciones = OpcionesClave()
    private var textoGenerado: TextView? = null
    private var textoEntropia: TextView? = null

    // Selectores del sistema
    private val selectorImportar = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importarArchivos(uris)
    }
    private var archivoAExportar: ArchivoBoveda? = null
    private val selectorExportar = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null) exportarArchivo(uri)
    }
    private var claveRespaldo: CharArray? = null
    private val selectorCrearRespaldo = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) escribirRespaldo(uri)
    }
    private val selectorAbrirRespaldo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pedirClaveParaLeer(uri)
    }

    override fun alCrear(estado: Bundle?) {
        opciones = AjustesBoveda.opcionesGenerador(this)
        val raiz = LinearLayout(this)
        raiz.orientation = LinearLayout.VERTICAL

        val cabecera = Vistas.fila(this)
        cabecera.setPadding(Vistas.dp(this, 16), Vistas.dp(this, 12), Vistas.dp(this, 16), Vistas.dp(this, 4))
        val tit = Vistas.titulo(this, "La Bóveda")
        cabecera.addView(tit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        cabecera.addView(Vistas.boton(this, "Bloquear", TipoBoton.TONAL) { bloquearYSalir() })
        raiz.addView(cabecera)

        contenedor = FrameLayout(this)
        raiz.addView(contenedor, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val barra = Vistas.fila(this)
        val nombres = listOf("Claves", "Archivos", "Generador", "Ajustes")
        for (i in nombres.indices) {
            val b = Vistas.boton(this, nombres[i], TipoBoton.TONAL) { mostrar(i) }
            b.textSize = 12f
            b.setPadding(Vistas.dp(this, 2), b.paddingTop, Vistas.dp(this, 2), b.paddingBottom)
            botonesPestana.add(b)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(Vistas.dp(this, 3), Vistas.dp(this, 4), Vistas.dp(this, 3), Vistas.dp(this, 8))
            barra.addView(b, lp)
        }
        raiz.addView(barra)
        setContentView(raiz)
        mostrar(0)
    }

    override fun onResume() {
        super.onResume()
        if (!SesionBoveda.desbloqueada) return
        if (pestana == 0) cargarClaves()
        if (pestana == 1) cargarArchivos()
        if (pestana == 3) mostrar(3)
    }

    private fun bloquearYSalir() {
        SesionBoveda.bloquear()
        startActivity(Intent(this, DesbloqueoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    private fun mostrar(i: Int) {
        pestana = i
        contenedor.removeAllViews()
        for (k in botonesPestana.indices) {
            val activo = k == i
            val fondo = if (activo) Vistas.color(this, R.color.vc_acento) else Vistas.color(this, R.color.vc_superficie_alta)
            val letra = if (activo) Vistas.color(this, R.color.vc_fondo) else Vistas.color(this, R.color.vc_texto)
            botonesPestana[k].backgroundTintList = android.content.res.ColorStateList.valueOf(fondo)
            botonesPestana[k].setTextColor(letra)
        }
        when (i) {
            0 -> {
                contenedor.addView(vistaClaves())
                cargarClaves()
            }
            1 -> {
                contenedor.addView(vistaArchivos())
                cargarArchivos()
            }
            2 -> contenedor.addView(vistaGenerador())
            else -> contenedor.addView(vistaAjustes())
        }
    }

    // ================================================================== CLAVES

    private fun vistaClaves(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Vistas.dp(this, 16), Vistas.dp(this, 4), Vistas.dp(this, 16), 0)

        val buscador = EditText(this)
        buscador.hint = "Buscar…"
        buscador.setSingleLine(true)
        buscador.setText(textoFiltro)
        buscador.setTextColor(Vistas.color(this, R.color.vc_texto))
        buscador.setHintTextColor(Vistas.color(this, R.color.vc_texto_sec))
        buscador.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        buscador.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                textoFiltro = s?.toString() ?: ""
                pintarClaves()
            }
        })
        col.addView(buscador, Vistas.anchoCompleto(this, 4))
        col.addView(
            Vistas.boton(this, "Nueva contraseña") { startActivity(Intent(this, EditarCredencialActivity::class.java)) },
            Vistas.anchoCompleto(this, 4)
        )
        val aud = Vistas.texto(this, "", true)
        textoAuditoria = aud
        col.addView(aud, Vistas.anchoCompleto(this, 8))

        val lista = LinearLayout(this)
        lista.orientation = LinearLayout.VERTICAL
        listaClaves = lista
        val scroll = ScrollView(this)
        scroll.addView(lista)
        col.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return col
    }

    private fun cargarClaves() {
        lifecycleScope.launch {
            val l = withContext(Dispatchers.IO) { BovedaDb.actual()?.credenciales()?.todas() ?: emptyList() }
            credenciales = l
            pintarClaves()
        }
    }

    private fun pintarClaves() {
        val lista = listaClaves ?: return
        lista.removeAllViews()

        val repetidas = HashSet<Long>()
        for (grupo in credenciales.filter { it.clave.isNotEmpty() }.groupBy { it.clave }.values) {
            if (grupo.size > 1) for (c in grupo) repetidas.add(c.id)
        }
        val debiles = HashSet<Long>()
        for (c in credenciales) {
            if (c.clave.isNotEmpty() && FuerzaClave.nivel(c.clave.toCharArray()) <= 1) debiles.add(c.id)
        }
        textoAuditoria?.text = credenciales.size.toString() + " contraseñas · " + repetidas.size + " repetidas · " + debiles.size + " débiles"

        val q = textoFiltro.trim().lowercase()
        val visibles = if (q.isEmpty()) credenciales else credenciales.filter {
            it.titulo.lowercase().contains(q) || it.usuario.lowercase().contains(q) || it.url.lowercase().contains(q)
        }
        if (visibles.isEmpty()) {
            lista.addView(Vistas.texto(this, if (credenciales.isEmpty()) "Todavía no hay contraseñas. Pulsa «Nueva contraseña»." else "Nada coincide con la búsqueda.", true))
            return
        }
        for (c in visibles) {
            val t = Vistas.tarjeta(this)
            val nombre = (if (c.favorito) "★ " else "") + c.titulo
            t.addView(Vistas.texto(this, nombre).also { it.setTypeface(it.typeface, Typeface.BOLD); it.textSize = 16f })
            if (c.usuario.isNotEmpty()) t.addView(Vistas.texto(this, c.usuario, true))
            val host = if (c.url.isEmpty()) null else try { URI(if (c.url.contains("://")) c.url else "https://" + c.url).host } catch (e: Exception) { null }
            if (host != null) t.addView(Vistas.texto(this, host, true))
            val avisos = ArrayList<String>()
            if (repetidas.contains(c.id)) avisos.add("repetida")
            if (debiles.contains(c.id)) avisos.add("débil")
            if (avisos.isNotEmpty()) {
                val a = Vistas.texto(this, "⚠ " + avisos.joinToString(" y "))
                a.setTextColor(Vistas.color(this, R.color.vc_aviso))
                t.addView(a)
            }
            val fila = Vistas.fila(this)
            val lp1 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp1.rightMargin = Vistas.dp(this, 6)
            fila.addView(Vistas.boton(this, "Copiar usuario", TipoBoton.TONAL) {
                PortapapelesSeguro.copiar(this, c.usuario, false)
                Dialogos.informar(this, "Copiado", "Usuario copiado.")
            }.also { it.textSize = 12f }, lp1)
            fila.addView(Vistas.boton(this, "Copiar clave", TipoBoton.TONAL) {
                PortapapelesSeguro.copiar(this, c.clave, true)
                val s = AjustesBoveda.portapapelesSegundos(this)
                Dialogos.informar(this, "Copiado", if (s > 0) "Contraseña copiada. Se borrará del portapapeles en " + s + " s." else "Contraseña copiada.")
            }.also { it.textSize = 12f }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val lpf = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lpf.topMargin = Vistas.dp(this, 6)
            t.addView(fila, lpf)
            t.setOnClickListener {
                startActivity(Intent(this, EditarCredencialActivity::class.java).putExtra(EditarCredencialActivity.EXTRA_ID, c.id))
            }
            lista.addView(t, Vistas.anchoCompleto(this, 8))
        }
    }

    // ================================================================== ARCHIVOS

    private fun vistaArchivos(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Vistas.dp(this, 16), Vistas.dp(this, 4), Vistas.dp(this, 16), 0)
        val ruta = Vistas.texto(this, "Inicio", true)
        textoRuta = ruta
        col.addView(ruta, Vistas.anchoCompleto(this, 6))

        val fila = Vistas.fila(this)
        val lp = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.rightMargin = Vistas.dp(this, 6) } }
        fila.addView(Vistas.boton(this, "Importar", TipoBoton.PRIMARIO) { selectorImportar.launch(arrayOf("*/*")) }.also { it.textSize = 12f }, lp())
        fila.addView(Vistas.boton(this, "Carpeta nueva", TipoBoton.TONAL) { nuevaCarpeta() }.also { it.textSize = 12f }, lp())
        fila.addView(Vistas.boton(this, "Subir", TipoBoton.TONAL) { subirNivel() }.also { it.textSize = 12f }, lp())
        col.addView(fila, Vistas.anchoCompleto(this, 8))

        val lista = LinearLayout(this)
        lista.orientation = LinearLayout.VERTICAL
        listaArchivos = lista
        val scroll = ScrollView(this)
        scroll.addView(lista)
        col.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return col
    }

    private fun subirNivel() {
        if (carpetaActual == 0L) return
        carpetaActual = padreDeActual
        cargarArchivos()
    }

    private fun cargarArchivos() {
        lifecycleScope.launch {
            val datos = withContext(Dispatchers.IO) {
                val d = BovedaDb.actual()
                if (d == null) {
                    Triple(emptyList<Carpeta>(), emptyList<ArchivoBoveda>(), emptyList<Carpeta>())
                } else {
                    Triple(d.carpetas().hijas(carpetaActual), d.archivos().deCarpeta(carpetaActual), almacen.ruta(carpetaActual))
                }
            }
            val ruta = datos.third
            padreDeActual = if (ruta.isEmpty()) 0L else ruta.last().padreId
            textoRuta?.text = "Inicio" + ruta.joinToString("") { " › " + it.nombre }
            pintarArchivos(datos.first, datos.second)
        }
    }

    private fun pintarArchivos(carpetas: List<Carpeta>, archivos: List<ArchivoBoveda>) {
        val lista = listaArchivos ?: return
        lista.removeAllViews()
        if (carpetas.isEmpty() && archivos.isEmpty()) {
            lista.addView(Vistas.texto(this, "Esta carpeta está vacía. Importa fotos de documentos, PDFs o cualquier archivo: se cifran uno a uno.", true))
            return
        }
        for (c in carpetas) {
            val t = Vistas.tarjeta(this)
            t.addView(Vistas.texto(this, "📁  " + c.nombre).also { it.textSize = 16f })
            t.setOnClickListener {
                carpetaActual = c.id
                cargarArchivos()
            }
            t.setOnLongClickListener {
                Dialogos.elegir(this, c.nombre, listOf("Renombrar", "Eliminar con todo su contenido")) { i ->
                    if (i == 0) {
                        Dialogos.pedirTexto(this, "Nuevo nombre", c.nombre) { n ->
                            if (n.isNotBlank()) lifecycleScope.launch { almacen.renombrarCarpeta(c, n.trim()); cargarArchivos() }
                        }
                    } else {
                        Dialogos.confirmar(this, "Eliminar carpeta", "Se borrarán la carpeta «" + c.nombre + "» y TODO lo que contiene.", "Eliminar") {
                            lifecycleScope.launch { almacen.borrarCarpeta(c.id); cargarArchivos() }
                        }
                    }
                }
                true
            }
            lista.addView(t, Vistas.anchoCompleto(this, 8))
        }
        for (a in archivos) {
            val t = Vistas.tarjeta(this)
            t.addView(Vistas.texto(this, (if (a.mime.startsWith("image/")) "🖼  " else "📄  ") + a.nombre).also { it.textSize = 16f })
            t.addView(Vistas.texto(this, Formato.bytes(a.tamano) + " · " + Formato.fecha(a.creado), true))
            t.setOnClickListener { menuArchivo(a) }
            lista.addView(t, Vistas.anchoCompleto(this, 8))
        }
    }

    private fun menuArchivo(a: ArchivoBoveda) {
        val esImagen = a.mime.startsWith("image/")
        val opciones = ArrayList<String>()
        if (esImagen) opciones.add("Ver")
        opciones.add("Exportar fuera de La Bóveda…")
        opciones.add("Renombrar")
        opciones.add("Eliminar")
        Dialogos.elegir(this, a.nombre, opciones) { i ->
            when (opciones[i]) {
                "Ver" -> verImagen(a)
                "Renombrar" -> Dialogos.pedirTexto(this, "Nuevo nombre", a.nombre) { n ->
                    if (n.isNotBlank()) lifecycleScope.launch { almacen.renombrar(a, n.trim()); cargarArchivos() }
                }
                "Eliminar" -> Dialogos.confirmar(this, "Eliminar", "¿Eliminar «" + a.nombre + "»? No se puede deshacer.", "Eliminar") {
                    lifecycleScope.launch { almacen.borrar(a); cargarArchivos() }
                }
                else -> Dialogos.confirmar(
                    this, "Exportar",
                    "El archivo se guardará SIN cifrar en el lugar que elijas. Úsalo solo si lo necesitas.", "Elegir destino"
                ) {
                    archivoAExportar = a
                    selectorExportar.launch(a.nombre)
                }
            }
        }
    }

    private fun nuevaCarpeta() {
        Dialogos.pedirTexto(this, "Nombre de la carpeta", "Documentos") { n ->
            if (n.isNotBlank()) {
                lifecycleScope.launch {
                    almacen.crearCarpeta(n.trim(), carpetaActual)
                    cargarArchivos()
                }
            }
        }
    }

    private fun importarArchivos(uris: List<Uri>) {
        lifecycleScope.launch {
            var ok = 0
            val errores = ArrayList<String>()
            for (u in uris) {
                try {
                    almacen.importar(u, carpetaActual)
                    ok++
                } catch (e: Exception) {
                    errores.add(e.message ?: e.javaClass.simpleName)
                }
            }
            cargarArchivos()
            var texto = ok.toString() + " archivo(s) cifrado(s) y guardado(s). Los originales siguen donde estaban: bórralos tú cuando hayas comprobado la copia."
            if (errores.isNotEmpty()) texto += "\n\nNo se pudieron importar " + errores.size + ": " + errores.first()
            Dialogos.informar(this@PrincipalActivity, "Importación terminada", texto)
        }
    }

    private fun exportarArchivo(destino: Uri) {
        val a = archivoAExportar ?: return
        lifecycleScope.launch {
            try {
                almacen.exportar(a, destino)
                Dialogos.informar(this@PrincipalActivity, "Exportado", "«" + a.nombre + "» se guardó sin cifrar en el destino elegido.")
            } catch (e: Exception) {
                try {
                    DocumentsContract.deleteDocument(contentResolver, destino)
                } catch (e2: Exception) {
                }
                Dialogos.informar(this@PrincipalActivity, "No se pudo exportar", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun verImagen(a: ArchivoBoveda) {
        lifecycleScope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) {
                    val bytes = almacen.leerEnMemoria(a, 25L * 1024L * 1024L)
                    val o = BitmapFactory.Options()
                    o.inJustDecodeBounds = true
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                    var s = 1
                    while (o.outWidth / s > 2048 || o.outHeight / s > 2048) s *= 2
                    val o2 = BitmapFactory.Options()
                    o2.inSampleSize = s
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
                }
                if (bmp == null) {
                    Dialogos.informar(this@PrincipalActivity, "Vista previa", "No se pudo interpretar la imagen.")
                    return@launch
                }
                val iv = ImageView(this@PrincipalActivity)
                iv.setImageBitmap(bmp as Bitmap)
                iv.adjustViewBounds = true
                val d = MaterialAlertDialogBuilder(this@PrincipalActivity)
                    .setTitle(a.nombre)
                    .setView(iv)
                    .setPositiveButton("Cerrar", null)
                    .create()
                mostrarSeguro(d)
            } catch (e: Exception) {
                Dialogos.informar(this@PrincipalActivity, "Vista previa", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // ================================================================== GENERADOR

    private fun vistaGenerador(): View {
        val col = Vistas.columna(this, 16)
        col.addView(Vistas.subtitulo(this, "Generador de contraseñas"), Vistas.anchoCompleto(this, 8))

        val salida = TextView(this)
        salida.typeface = Typeface.MONOSPACE
        salida.textSize = 18f
        salida.setTextColor(Vistas.color(this, R.color.vc_texto))
        salida.setTextIsSelectable(true)
        textoGenerado = salida
        val tarjeta = Vistas.tarjeta(this)
        tarjeta.addView(salida)
        col.addView(tarjeta, Vistas.anchoCompleto(this, 6))
        val ent = Vistas.texto(this, "", true)
        textoEntropia = ent
        col.addView(ent, Vistas.anchoCompleto(this, 12))

        val etiquetaLargo = Vistas.texto(this, "Longitud: " + opciones.largo)
        col.addView(etiquetaLargo, Vistas.anchoCompleto(this, 2))
        val barra = SeekBar(this)
        barra.max = GeneradorClaves.LARGO_MAX - GeneradorClaves.LARGO_MIN
        barra.progress = opciones.largo - GeneradorClaves.LARGO_MIN
        barra.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progreso: Int, delUsuario: Boolean) {
                val largo = progreso + GeneradorClaves.LARGO_MIN
                etiquetaLargo.text = "Longitud: " + largo
                if (delUsuario) cambiarOpciones(OpcionesClave(largo, opciones.minusculas, opciones.mayusculas, opciones.digitos, opciones.simbolos, opciones.evitarAmbiguos))
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        col.addView(barra, Vistas.anchoCompleto(this, 8))

        col.addView(casilla("Minúsculas (a-z)", opciones.minusculas) { v -> cambiarOpciones(OpcionesClave(opciones.largo, v, opciones.mayusculas, opciones.digitos, opciones.simbolos, opciones.evitarAmbiguos)) })
        col.addView(casilla("Mayúsculas (A-Z)", opciones.mayusculas) { v -> cambiarOpciones(OpcionesClave(opciones.largo, opciones.minusculas, v, opciones.digitos, opciones.simbolos, opciones.evitarAmbiguos)) })
        col.addView(casilla("Números (0-9)", opciones.digitos) { v -> cambiarOpciones(OpcionesClave(opciones.largo, opciones.minusculas, opciones.mayusculas, v, opciones.simbolos, opciones.evitarAmbiguos)) })
        col.addView(casilla("Símbolos (!@#…)", opciones.simbolos) { v -> cambiarOpciones(OpcionesClave(opciones.largo, opciones.minusculas, opciones.mayusculas, opciones.digitos, v, opciones.evitarAmbiguos)) })
        col.addView(casilla("Evitar caracteres confusos (I l 1 O 0 o)", opciones.evitarAmbiguos) { v -> cambiarOpciones(OpcionesClave(opciones.largo, opciones.minusculas, opciones.mayusculas, opciones.digitos, opciones.simbolos, v)) })

        val fila = Vistas.fila(this)
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp.rightMargin = Vistas.dp(this, 8)
        fila.addView(Vistas.boton(this, "Generar") { regenerar() }, lp)
        fila.addView(Vistas.boton(this, "Copiar", TipoBoton.TONAL) {
            val t = textoGenerado?.text?.toString() ?: ""
            if (t.isNotEmpty()) PortapapelesSeguro.copiar(this, t, true)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val lpf = Vistas.anchoCompleto(this, 0)
        lpf.topMargin = Vistas.dp(this, 12)
        col.addView(fila, lpf)

        regenerar()
        val scroll = ScrollView(this)
        scroll.addView(col)
        return scroll
    }

    private fun casilla(texto: String, marcada: Boolean, alCambiar: (Boolean) -> Unit): MaterialCheckBox {
        val cb = MaterialCheckBox(this)
        cb.text = texto
        cb.setTextColor(Vistas.color(this, R.color.vc_texto))
        cb.isChecked = marcada
        cb.setOnCheckedChangeListener { _, v -> alCambiar(v) }
        return cb
    }

    private fun cambiarOpciones(o: OpcionesClave) {
        opciones = o
        AjustesBoveda.guardarOpcionesGenerador(this, o)
        regenerar()
    }

    private fun regenerar() {
        val c = GeneradorClaves.generar(opciones)
        if (c == null) {
            textoGenerado?.text = "Marca al menos un tipo de carácter."
            textoEntropia?.text = ""
            return
        }
        textoGenerado?.text = c
        textoEntropia?.text = "Entropía: " + GeneradorClaves.entropiaBits(opciones).toInt() + " bits · generada con SecureRandom"
    }

    // ================================================================== AJUSTES

    private fun vistaAjustes(): View {
        val col = Vistas.columna(this, 16)

        col.addView(Vistas.subtitulo(this, "Seguridad"), Vistas.anchoCompleto(this, 6))
        val opcionesBloqueo = listOf("Inmediato (al salir)", "30 segundos", "1 minuto", "5 minutos")
        val segundos = listOf(2, 30, 60, 300)
        val actual = segundos.indexOf(AjustesBoveda.autobloqueoSegundos(this)).let { if (it < 0) 2 else it }
        col.addView(Vistas.boton(this, "Autobloqueo: " + opcionesBloqueo[actual], TipoBoton.TONAL) {
            Dialogos.elegir(this, "Bloquear tras…", opcionesBloqueo) { i ->
                AjustesBoveda.setAutobloqueoSegundos(this, segundos[i])
                mostrar(3)
            }
        }, Vistas.anchoCompleto(this, 6))
        col.addView(casilla("Bloquear al apagar la pantalla", AjustesBoveda.bloquearAlApagarPantalla(this)) { AjustesBoveda.setBloquearAlApagarPantalla(this, it) })
        col.addView(casilla("Permitir capturas de pantalla (se aplica al abrir cada pantalla)", AjustesBoveda.permitirCapturas(this)) { AjustesBoveda.setPermitirCapturas(this, it) })
        col.addView(casilla("Usar teclado seguro al desbloquear", AjustesBoveda.tecladoSeguroPorDefecto(this)) { AjustesBoveda.setTecladoSeguroPorDefecto(this, it) })

        val opcionesPortapapeles = listOf("No borrar", "15 segundos", "30 segundos", "60 segundos")
        val valoresPortapapeles = listOf(0, 15, 30, 60)
        val ip = valoresPortapapeles.indexOf(AjustesBoveda.portapapelesSegundos(this)).let { if (it < 0) 2 else it }
        val lpc = Vistas.anchoCompleto(this, 6)
        lpc.topMargin = Vistas.dp(this, 6)
        col.addView(Vistas.boton(this, "Borrar portapapeles: " + opcionesPortapapeles[ip], TipoBoton.TONAL) {
            Dialogos.elegir(this, "Borrar el portapapeles tras…", opcionesPortapapeles) { i ->
                AjustesBoveda.setPortapapelesSegundos(this, valoresPortapapeles[i])
                mostrar(3)
            }
        }, lpc)

        if (BiometriaBoveda.disponible(this)) {
            val activa = BiometriaBoveda.activada(this)
            col.addView(Vistas.boton(this, "Huella o rostro: " + (if (activa) "activado" else "desactivado"), TipoBoton.TONAL) { alternarBiometria(activa) }, Vistas.anchoCompleto(this, 6))
        }
        col.addView(Vistas.boton(this, "Cambiar contraseña maestra", TipoBoton.TONAL) { cambiarMaestra() }, Vistas.anchoCompleto(this, 14))

        col.addView(Vistas.subtitulo(this, "Autocompletado"), Vistas.anchoCompleto(this, 6))
        val estadoAuto = estadoAutocompletado()
        col.addView(Vistas.texto(this, estadoAuto, true), Vistas.anchoCompleto(this, 6))
        col.addView(Vistas.boton(this, "Activar La Bóveda como autocompletado", TipoBoton.TONAL) { activarAutocompletado() }, Vistas.anchoCompleto(this, 14))

        col.addView(Vistas.subtitulo(this, "Copia de seguridad"), Vistas.anchoCompleto(this, 6))
        col.addView(Vistas.texto(this, "Guarda tus contraseñas en un archivo cifrado con una contraseña que elijas. No incluye los archivos de la pestaña Archivos.", true), Vistas.anchoCompleto(this, 6))
        col.addView(Vistas.boton(this, "Exportar contraseñas (cifradas)", TipoBoton.TONAL) { pedirClaveParaEscribir() }, Vistas.anchoCompleto(this, 6))
        col.addView(Vistas.boton(this, "Importar copia de seguridad", TipoBoton.TONAL) { selectorAbrirRespaldo.launch(arrayOf("*/*")) }, Vistas.anchoCompleto(this, 14))

        col.addView(Vistas.subtitulo(this, "Cómo se protege lo que guardas"), Vistas.anchoCompleto(this, 6))
        col.addView(
            Vistas.texto(
                this,
                "Todo vive solo en este teléfono: la app no tiene permiso de internet. Las contraseñas están en una base de datos " +
                    "SQLCipher cifrada con una clave aleatoria de 256 bits, y cada archivo se cifra aparte con AES-256-GCM. Esa clave " +
                    "se guarda protegida por tu contraseña maestra (PBKDF2, 310 000 iteraciones) y, si lo activas, por la huella o el " +
                    "rostro mediante el Keystore de Android. Si olvidas la contraseña maestra no hay forma de recuperar nada.",
                true
            ),
            Vistas.anchoCompleto(this, 0)
        )

        val scroll = ScrollView(this)
        scroll.addView(col)
        return scroll
    }

    private fun estadoAutocompletado(): String {
        val am = getSystemService(AutofillManager::class.java)
        // hasEnabledAutofillServices() solo devuelve true si el servicio elegido es el de ESTA app.
        val esLaBoveda = am != null && am.hasEnabledAutofillServices()
        val elegido = try {
            Settings.Secure.getString(contentResolver, "autofill_service")
        } catch (e: Exception) {
            null
        }
        return if (esLaBoveda) {
            "✔ La Bóveda es el servicio de autocompletado activo. Si no aparece en una app o web: en Chrome activa «Autocompletar con otro servicio» " +
                "(Ajustes › Servicios de autocompletado), y para ver qué ocurre usa el registro con la etiqueta BovedaAutofill (por ejemplo, con Task Master)."
        } else if (!elegido.isNullOrEmpty() && elegido != "null") {
            "✘ El servicio activo es otro (" + elegido.substringBefore('/') + "). Pulsa el botón de abajo para elegir La Bóveda."
        } else {
            "✘ Ahora mismo no hay ningún servicio de autocompletado activo. Pulsa el botón de abajo para elegir La Bóveda."
        }
    }

    private fun activarAutocompletado() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, Uri.parse("package:" + packageName)))
        } catch (e: Exception) {
            Dialogos.informar(this, "No se pudo abrir", "Ve a Ajustes › Sistema › Idiomas y entrada › Autocompletar y elige La Bóveda.")
        }
    }

    private fun alternarBiometria(activa: Boolean) {
        if (activa) {
            BiometriaBoveda.desactivar(this)
            mostrar(3)
            return
        }
        val clave = SesionBoveda.clave() ?: return
        BiometriaBoveda.activar(
            this, clave,
            {
                Dialogos.informar(this, "Listo", "Podrás desbloquear con huella o rostro. La contraseña maestra seguirá funcionando.")
                mostrar(3)
            },
            { m -> Dialogos.informar(this, "No se activó", m) }
        )
    }

    private fun cambiarMaestra() {
        Dialogos.pedirTexto(this, "Contraseña maestra actual", "", true) { actual ->
            Dialogos.pedirTexto(this, "Nueva contraseña maestra (mín. 10)", "", true) { nueva ->
                Dialogos.pedirTexto(this, "Repite la nueva contraseña", "", true) { repetida ->
                    if (nueva.length < 10) {
                        Dialogos.informar(this, "Demasiado corta", "Debe tener al menos 10 caracteres.")
                    } else if (nueva != repetida) {
                        Dialogos.informar(this, "No coinciden", "Las dos contraseñas nuevas son distintas.")
                    } else {
                        lifecycleScope.launch {
                            val ok = withContext(Dispatchers.IO) { ClavesBoveda.cambiarMaestra(applicationContext, actual.toCharArray(), nueva.toCharArray()) }
                            Dialogos.informar(this@PrincipalActivity, if (ok) "Contraseña cambiada" else "No se cambió", if (ok) "Ya puedes usar la nueva contraseña maestra." else "La contraseña actual no es correcta.")
                        }
                    }
                }
            }
        }
    }

    // ---- copia de seguridad

    private fun pedirClaveParaEscribir() {
        Dialogos.pedirTexto(this, "Contraseña de la copia (mín. 8)", "", true) { p1 ->
            Dialogos.pedirTexto(this, "Repite la contraseña de la copia", "", true) { p2 ->
                if (p1.length < 8 || p1 != p2) {
                    Dialogos.informar(this, "Contraseña no válida", "Deben coincidir y tener al menos 8 caracteres.")
                } else {
                    claveRespaldo = p1.toCharArray()
                    val fecha = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                    selectorCrearRespaldo.launch("boveda-" + fecha + ".boveda")
                }
            }
        }
    }

    private fun escribirRespaldo(uri: Uri) {
        val pwd = claveRespaldo ?: return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val lista = BovedaDb.actual()?.credenciales()?.todas() ?: emptyList()
                    val datos = Respaldo.exportar(lista, pwd)
                    val salida = contentResolver.openOutputStream(uri) ?: throw IllegalStateException("No se pudo escribir el destino")
                    salida.use { it.write(datos) }
                }
                Dialogos.informar(this@PrincipalActivity, "Copia creada", "Guarda también la contraseña de la copia: sin ella no se puede abrir.")
            } catch (e: Exception) {
                Dialogos.informar(this@PrincipalActivity, "No se pudo crear la copia", e.message ?: e.javaClass.simpleName)
            } finally {
                pwd.fill(' ')
                claveRespaldo = null
            }
        }
    }

    private fun pedirClaveParaLeer(uri: Uri) {
        Dialogos.pedirTexto(this, "Contraseña de la copia", "", true) { p ->
            lifecycleScope.launch {
                try {
                    val anadidas = withContext(Dispatchers.IO) {
                        val datos = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("No se pudo leer el archivo")
                        val importadas = Respaldo.leer(datos, p.toCharArray())
                        val dao = BovedaDb.actual()?.credenciales() ?: throw IllegalStateException("La bóveda está bloqueada")
                        val existentes = dao.todas()
                        var n = 0
                        for (c in importadas) {
                            val repetida = existentes.any { it.titulo == c.titulo && it.usuario == c.usuario && it.url == c.url }
                            if (!repetida) {
                                dao.insertar(c.copy(id = 0))
                                n++
                            }
                        }
                        n
                    }
                    cargarClaves()
                    Dialogos.informar(this@PrincipalActivity, "Copia importada", "Se añadieron " + anadidas + " contraseñas (las repetidas se omitieron).")
                } catch (e: IllegalStateException) {
                    Dialogos.informar(this@PrincipalActivity, "No se pudo abrir", "Contraseña incorrecta o archivo dañado.")
                } catch (e: Exception) {
                    Dialogos.informar(this@PrincipalActivity, "No se pudo importar", e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }
}
