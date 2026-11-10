package com.vigia.boveda

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.checkbox.MaterialCheckBox
import com.vigia.core.R
import com.vigia.core.ui.Dialogos
import com.vigia.core.ui.TipoBoton
import com.vigia.core.ui.Vistas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditarCredencialActivity : ActividadBoveda() {

    private var id = 0L
    private var original: Credencial? = null
    private lateinit var titulo: EditText
    private lateinit var usuario: EditText
    private lateinit var clave: EditText
    private lateinit var url: EditText
    private lateinit var app: EditText
    private lateinit var notas: EditText
    private lateinit var favorito: MaterialCheckBox
    private lateinit var fuerza: TextView
    private var visible = false

    override fun alCrear(estado: Bundle?) {
        id = intent.getLongExtra(EXTRA_ID, 0L)
        construirUi()
        if (id == 0L) {
            // Relleno previo (por ejemplo, desde un formulario de registro visto por el autorrelleno)
            titulo.setText(intent.getStringExtra(EXTRA_PRE_TITULO) ?: "")
            usuario.setText(intent.getStringExtra(EXTRA_PRE_USUARIO) ?: "")
            clave.setText(intent.getStringExtra(EXTRA_PRE_CLAVE) ?: "")
            url.setText(intent.getStringExtra(EXTRA_PRE_URL) ?: "")
            app.setText(intent.getStringExtra(EXTRA_PRE_APP) ?: "")
            actualizarFuerza()
        }
        if (id != 0L) {
            lifecycleScope.launch {
                val c = withContext(Dispatchers.IO) { BovedaDb.actual()?.credenciales()?.porId(id) }
                if (c == null) {
                    finish()
                    return@launch
                }
                original = c
                titulo.setText(c.titulo)
                usuario.setText(c.usuario)
                clave.setText(c.clave)
                url.setText(c.url)
                app.setText(c.appPaquete)
                notas.setText(c.notas)
                favorito.isChecked = c.favorito
                actualizarFuerza()
            }
        }
    }

    private fun campo(pista: String, tipo: Int, lineas: Int = 1): EditText {
        val e = EditText(this)
        e.hint = pista
        e.inputType = tipo
        if (lineas > 1) {
            e.minLines = lineas
            e.gravity = Gravity.TOP or Gravity.START
        } else {
            e.setSingleLine(true)
        }
        e.imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        e.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        e.setTextColor(Vistas.color(this, R.color.vc_texto))
        e.setHintTextColor(Vistas.color(this, R.color.vc_texto_sec))
        return e
    }

    private fun construirUi() {
        val raiz = Vistas.columna(this, 16)
        raiz.addView(Vistas.titulo(this, if (id == 0L) "Nueva contraseña" else "Editar"), Vistas.anchoCompleto(this, 12))
        val texto = InputType.TYPE_CLASS_TEXT
        titulo = campo("Título (por ejemplo, Banco)", texto or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        usuario = campo("Usuario o correo", texto or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        clave = campo("Contraseña", texto or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        url = campo("Sitio web (https://…)", texto or InputType.TYPE_TEXT_VARIATION_URI)
        app = campo("App vinculada (paquete)", texto or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        notas = campo("Notas", texto or InputType.TYPE_TEXT_FLAG_MULTI_LINE, 4)
        fuerza = Vistas.texto(this, "", true)

        raiz.addView(titulo, Vistas.anchoCompleto(this, 4))
        raiz.addView(usuario, Vistas.anchoCompleto(this, 4))
        raiz.addView(clave, Vistas.anchoCompleto(this, 4))
        raiz.addView(fuerza, Vistas.anchoCompleto(this, 8))

        val fila = Vistas.fila(this)
        val lp = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp.rightMargin = Vistas.dp(this, 8)
        fila.addView(Vistas.boton(this, "Mostrar", TipoBoton.TONAL) { alternarVisible() }, lp)
        val lp2 = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        fila.addView(Vistas.boton(this, "Generar", TipoBoton.TONAL) { generar() }, lp2)
        raiz.addView(fila, Vistas.anchoCompleto(this, 12))

        raiz.addView(url, Vistas.anchoCompleto(this, 4))
        raiz.addView(app, Vistas.anchoCompleto(this, 4))
        raiz.addView(Vistas.boton(this, "Elegir app…", TipoBoton.TONAL) { elegirApp() }, Vistas.anchoCompleto(this, 8))
        raiz.addView(notas, Vistas.anchoCompleto(this, 8))
        favorito = MaterialCheckBox(this)
        favorito.text = "Favorito"
        favorito.setTextColor(Vistas.color(this, R.color.vc_texto))
        raiz.addView(favorito, Vistas.anchoCompleto(this, 12))

        raiz.addView(Vistas.boton(this, "Guardar") { guardar() }, Vistas.anchoCompleto(this, 8))
        if (id != 0L) {
            raiz.addView(Vistas.boton(this, "Eliminar", TipoBoton.PELIGRO) { eliminar() }, Vistas.anchoCompleto(this, 0))
        }

        clave.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                actualizarFuerza()
            }
        })

        val scroll = ScrollView(this)
        scroll.addView(raiz)
        setContentView(scroll)
    }

    private fun actualizarFuerza() {
        val c = clave.text.toString()
        fuerza.text = if (c.isEmpty()) "" else "Fuerza estimada: " + FuerzaClave.etiqueta(FuerzaClave.nivel(c.toCharArray()))
    }

    private fun alternarVisible() {
        visible = !visible
        val base = InputType.TYPE_CLASS_TEXT
        val pos = clave.selectionStart
        clave.inputType = if (visible) base or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else base or InputType.TYPE_TEXT_VARIATION_PASSWORD
        if (pos >= 0) clave.setSelection(pos)
    }

    private fun generar() {
        val o = AjustesBoveda.opcionesGenerador(this)
        val nueva = GeneradorClaves.generar(o)
        if (nueva == null) {
            Dialogos.informar(this, "Generador", "Elige al menos un tipo de carácter en la pestaña Generador.")
            return
        }
        clave.setText(nueva)
    }

    private fun elegirApp() {
        val pm = packageManager
        val intento = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intento, 0)
            .map { Pair(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.first }
            .filter { it.first != packageName }
            .sortedBy { it.second.lowercase() }
        Dialogos.elegir(this, "App vinculada", apps.map { it.second }) { i ->
            app.setText(apps[i].first)
            if (titulo.text.isNullOrBlank()) titulo.setText(apps[i].second)
        }
    }

    private fun guardar() {
        val t = titulo.text.toString().trim()
        if (t.isEmpty()) {
            Dialogos.informar(this, "Falta el título", "Ponle un nombre para reconocerla.")
            return
        }
        val ahora = System.currentTimeMillis()
        val previo = original
        val nueva = Credencial(
            id = id, titulo = t, usuario = usuario.text.toString().trim(), clave = clave.text.toString(),
            url = url.text.toString().trim(), appPaquete = app.text.toString().trim(), notas = notas.text.toString(),
            favorito = favorito.isChecked, creado = previo?.creado ?: ahora, modificado = ahora
        )
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val dao = BovedaDb.actual()?.credenciales()
                if (dao != null) {
                    if (id == 0L) dao.insertar(nueva) else dao.actualizar(nueva)
                }
            }
            finish()
        }
    }

    private fun eliminar() {
        val c = original ?: return
        Dialogos.confirmar(this, "Eliminar", "¿Eliminar «" + c.titulo + "»? No se puede deshacer.", "Eliminar") {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { BovedaDb.actual()?.credenciales()?.borrar(c) }
                finish()
            }
        }
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_PRE_TITULO = "pre_titulo"
        const val EXTRA_PRE_USUARIO = "pre_usuario"
        const val EXTRA_PRE_CLAVE = "pre_clave"
        const val EXTRA_PRE_URL = "pre_url"
        const val EXTRA_PRE_APP = "pre_app"
    }
}
