package com.felp.ludologlink

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Estado que comparten el servicio y la pantalla. Siempre se escribe en el hilo principal. */
object LinkState {
    /** El servicio escuchando: a los devices, y al PC si [pcLink]. */
    val running = mutableStateOf(false)
    /** Como en Prefs, para la pantalla: se cambian por LinkService.setPcLink / setSyncDevices. */
    val pcLink = mutableStateOf(false)
    /** El mosaico de PC Link en el panel rapido (ver Prefs.tileAdded); [tileNote], lo que dijo Android al pedirlo. */
    val tileAdded = mutableStateOf(false)
    val tileNote = mutableStateOf<String?>(null)
    val syncDevices = mutableStateOf(true)
    val address = mutableStateOf("")
    val pairingCode = mutableStateOf<String?>(null)
    val pairingPc = mutableStateOf("")
    val transferName = mutableStateOf<String?>(null)
    val transferProgress = mutableStateOf(0f)
    /** true: la consola envia al PC; false: recibe. */
    val transferOutgoing = mutableStateOf(false)
    val pairedPcs = mutableStateListOf<String>()
    /** Sube cuando cambian las consolas emparejadas (ver Peers): la pantalla vuelve a leerlas. */
    val peersChanged = androidx.compose.runtime.mutableIntStateOf(0)
    /** Cambiaron los ajustes de partidas guardadas (ver Saves), y si se esta sincronizando ahora. */
    val savesChanged = androidx.compose.runtime.mutableIntStateOf(0)
    val savesSyncing = mutableStateOf(false)
    /** Cambio el catalogo del PC o lo pedido de el (ver PcRequests). Aparte de [savesChanged]: el PC
     *  manda su lista a menudo, y con un solo contador cada envio volvia a preguntar las partidas. */
    val pcChanged = androidx.compose.runtime.mutableIntStateOf(0)
    /** Los conflictos (su clave) que la persona dejo para despues: la ventana no vuelve hasta que cambien. */
    val conflictsDismissed = mutableStateOf<String?>(null)
    /** Compartiendo el Companion ahora mismo (ver CompanionShare). */
    val sharing = mutableStateOf(false)
    val log = mutableStateListOf<String>()

    /** La pantalla esta a la vista: mientras tanto el servicio no se apaga por inactividad. */
    @Volatile var uiVisible = false

    private val main = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /**
     * Apunta algo en la actividad: en pantalla (las ultimas 40) y guardado (LinkLog, que lee el PC).
     * [kind]: de que va (ver LinkLog); [error]: si algo fallo.
     */
    fun addLog(msg: String, kind: String = "link", error: Boolean = false) {
        val e = LinkLog.Entry(System.currentTimeMillis(), kind, msg, error)
        LinkLog.add(e)
        post {
            log.add(0, line(e))
            while (log.size > 40) log.removeAt(log.size - 1)
        }
    }

    fun line(e: LinkLog.Entry): String = "${clock.format(Date(e.t))}  ${e.text}"
}
