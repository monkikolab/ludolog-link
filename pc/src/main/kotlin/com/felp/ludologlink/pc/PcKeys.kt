package com.felp.ludologlink.pc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.felp.ludolog.kit.KeyBox
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Las claves de las fuentes de arte de Ludolog (IGDB) en este PC (10-10-2026): con ellas el scraper
 * del PC busca igual que el de las consolas, y el PC las pasa entre las consolas emparejadas (ver
 * AppState.syncKeys). Se escriben aqui o llegan de un device; gana la puesta mas tarde (ver KeyBox).
 *
 * En disco van cifradas con la cuenta de Windows (DPAPI): copiado a otro PC o a otro usuario, el
 * archivo no se abre. En claro solo en memoria, que es donde tienen que estar para usarse.
 *
 * El archivo es `art-keys.bin` en PcDirs.home. Las lee el scraper (Prefs.credential, en
 * LudologDataShims.kt) y las enseña y cambia Settings → This PC (ArtSourcesPane, en Sections.kt).
 */
object PcKeys {
    /** Las casillas de IGDB, con su nombre: las mismas que en Ludolog (ART_TIERS, en ScrapeSetup.kt). */
    val IGDB = listOf("igdb.id" to "Client ID", "igdb.secret" to "Client secret")

    private val file get() = File(PcDirs.home, "art-keys.bin")

    private var entries: Map<String, KeyBox.Entry>? = null

    /** De donde llegaron por ultima vez: el nombre de un device, o nulo si se escribieron aqui. */
    var origin by mutableStateOf<String?>(null)
        private set

    /** Sube con cada cambio, para que Settings se repinte. */
    var revision by mutableIntStateOf(0)
        private set

    @Synchronized
    fun all(): Map<String, KeyBox.Entry> = entries ?: load().also { entries = it }

    fun value(key: String): String = all()[key]?.value.orEmpty()

    /** Si el scraper del PC puede usar IGDB: las dos casillas puestas. */
    fun ready(): Boolean = IGDB.all { value(it.first).isNotEmpty() }

    /** Cuando cambio IGDB por ultima vez (0: nunca). */
    fun at(): Long = IGDB.maxOf { all()[it.first]?.at ?: 0L }

    /** Escritas en este PC: con la hora de ahora, y solo las que cambian. Si cambio alguna. */
    @Synchronized
    fun set(values: Map<String, String>): Boolean {
        val cur = all()
        val now = System.currentTimeMillis()
        val changed = values.mapValues { it.value.trim() }
            .filter { (k, v) -> v != cur[k]?.value.orEmpty() }
            .mapValues { KeyBox.Entry(it.value, now) }
        if (changed.isEmpty()) return false
        save(cur + changed, null)
        return true
    }

    /** Llegadas de un device: solo las mas nuevas que las de aqui. Las que cambiaron. */
    @Synchronized
    fun merge(incoming: Map<String, KeyBox.Entry>, from: String): Set<String> {
        val cur = all()
        val take = KeyBox.newer(cur, incoming).filter { it.value.value.length <= 512 }
        if (take.isEmpty()) return emptySet()
        save(cur + take, from)
        return take.keys
    }

    private fun save(m: Map<String, KeyBox.Entry>, from: String?) {
        val j = JSONObject().put("keys", JSONObject().also { k ->
            m.forEach { (key, e) -> k.put(key, JSONObject().put("v", e.value).put("t", e.at)) }
        })
        from?.let { j.put("origin", it) }
        val sealed = Dpapi.protect(j.toString().toByteArray(Charsets.UTF_8))
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(sealed)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        entries = m
        origin = from
        revision++
    }

    /** Lo guardado; vacio si no hay, o si no se abre (de otro usuario, o dañado). */
    private fun load(): Map<String, KeyBox.Entry> = runCatching {
        if (!file.isFile) return emptyMap()
        val j = JSONObject(String(Dpapi.unprotect(file.readBytes()), Charsets.UTF_8))
        origin = j.optString("origin").ifEmpty { null }
        val k = j.optJSONObject("keys") ?: JSONObject()
        k.keys().asSequence().mapNotNull { key ->
            val o = k.optJSONObject(key) ?: return@mapNotNull null
            o.optLong("t").takeIf { it > 0 }?.let { key to KeyBox.Entry(o.optString("v"), it) }
        }.toMap()
    }.getOrElse { PcLog.add(null, "art", "Couldn't read the saved art source keys: ${it.message}", error = true); emptyMap() }
}

/**
 * La proteccion de datos de Windows (DPAPI) para la cuenta del usuario, por JNA. Con algo propio de
 * Link de mas (la entropia): otro programa de la misma cuenta tendria que saberlo para abrirlo.
 */
private object Dpapi {
    @Structure.FieldOrder("cbData", "pbData")
    class Blob() : Structure() {
        @JvmField var cbData: Int = 0
        @JvmField var pbData: Pointer? = null

        constructor(bytes: ByteArray) : this() {
            val m = Memory(maxOf(1, bytes.size).toLong())
            m.write(0, bytes, 0, bytes.size)
            cbData = bytes.size
            pbData = m
        }
    }

    private interface Crypt32 : Library {
        fun CryptProtectData(dataIn: Blob, description: WString?, entropy: Blob?, reserved: Pointer?, prompt: Pointer?,
                             flags: Int, dataOut: Blob): Boolean
        fun CryptUnprotectData(dataIn: Blob, description: PointerByReference?, entropy: Blob?, reserved: Pointer?,
                               prompt: Pointer?, flags: Int, dataOut: Blob): Boolean
    }

    private interface Kernel32 : Library {
        fun LocalFree(p: Pointer?): Pointer?
    }

    private val crypt by lazy { Native.load("Crypt32", Crypt32::class.java) }
    private val kernel by lazy { Native.load("Kernel32", Kernel32::class.java) }

    /** Sin ventanas: si Windows quisiera preguntar algo, que falle. */
    private const val UI_FORBIDDEN = 0x1
    private val entropy = "ludolog-link art keys".toByteArray(Charsets.UTF_8)

    fun protect(plain: ByteArray): ByteArray = run(plain) { input, out ->
        crypt.CryptProtectData(input, WString("Ludolog Link"), Blob(entropy), null, null, UI_FORBIDDEN, out)
    }

    fun unprotect(sealed: ByteArray): ByteArray = run(sealed) { input, out ->
        crypt.CryptUnprotectData(input, null, Blob(entropy), null, null, UI_FORBIDDEN, out)
    }

    private fun run(bytes: ByteArray, call: (Blob, Blob) -> Boolean): ByteArray {
        val out = Blob()
        if (!call(Blob(bytes), out)) throw IllegalStateException("Windows couldn't protect or open the data")
        out.read()
        try {
            return out.pbData?.getByteArray(0, out.cbData) ?: ByteArray(0)
        } finally {
            kernel.LocalFree(out.pbData)
        }
    }
}
