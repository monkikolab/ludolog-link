package com.felp.ludologlink

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import com.felp.ludolog.kit.Protocol
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import kotlin.concurrent.thread

/**
 * Link escuchando: servidor HTTP, respuesta a las busquedas, y bloqueos de Wi-Fi/CPU durante las
 * transferencias para que una ISO grande no se corte al apagarse la pantalla.
 *
 * No se enciende ni se apaga a mano: esta encendido mientras haga falta ([needed]). Con devices
 * emparejados escucha siempre, y sincroniza cuando toca (al cerrar un juego, al terminar una
 * partida del Companion, al encenderse). Quieto casi no gasta: un servidor esperando no usa CPU.
 * Lo que gasta es oir las busquedas del PC con la pantalla apagada (el bloqueo de multicast), y eso
 * va solo con PC Link, que se apaga solo tras Prefs.idleMinutes sin que el PC haga nada.
 */
class LinkService : Service(), TransferHooks {

    companion object {
        /** Cualquier otra accion (REFRESH, o WAKE desde Ludolog) es "ponte como debes". */
        private const val ACTION_REFRESH = "com.felp.ludologlink.REFRESH"
        private const val ACTION_PC_OFF = "com.felp.ludologlink.PC_OFF"
        private const val EXTRA_CATCH_UP = "catch_up"
        private const val CHANNEL = "link"
        private const val NOTIF_ID = 1

        /** El servicio en marcha, si lo esta: [ensure] le habla directo, sin pasar por Android. */
        @Volatile private var instance: LinkService? = null

        /**
         * Si hace falta escuchar: PC Link encendido, devices con los que compartir, o la app a la
         * vista (para emparejar una consola o un PC por primera vez hay que estar escuchando).
         */
        fun needed(ctx: Context) = Prefs.pcLink(ctx) || (Prefs.syncDevices(ctx) && Peers.all(ctx).isNotEmpty()) ||
            LinkState.uiVisible

        /**
         * Que el servicio este como debe: en marcha si hace falta ([needed]), parado si no, y con PC
         * Link como diga Prefs. Se llama al abrir la app, al arrancar la consola, cuando Ludolog
         * vuelve a primer plano (pregunta "state") y al cambiar PC Link, compartir o los devices.
         * [catchUp]: ponerse al dia con los devices (se acaba de encender compartir). Android puede
         * no dejar arrancarlo con la app en segundo plano: se queda para la proxima.
         */
        fun ensure(ctx: Context, catchUp: Boolean = false): Boolean {
            val app = ctx.applicationContext
            val pc = Prefs.pcLink(app)
            val sync = Prefs.syncDevices(app)
            LinkState.post { LinkState.pcLink.value = pc; LinkState.syncDevices.value = sync }
            val s = instance
            if (s != null) { s.handler.post { s.apply(catchUp) }; return true }
            if (!needed(app)) { Ludolog.linkState(app, false, ""); LinkTileService.refresh(app); return true }
            return runCatching {
                app.startForegroundService(Intent(app, LinkService::class.java).setAction(ACTION_REFRESH))
            }.onFailure { LinkState.addLog("Android didn't let Link start now: ${it.message}", "link", error = true) }.isSuccess
        }

        fun setPcLink(ctx: Context, on: Boolean) {
            Prefs.setPcLink(ctx, on)
            ensure(ctx)
        }

        fun setSyncDevices(ctx: Context, on: Boolean) {
            Prefs.setSyncDevices(ctx, on)
            LinkState.addLog(if (on) "Sharing on" else "Sharing off")
            ensure(ctx, catchUp = on)
        }

        fun wifiAddress(): String =
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull()?.hostAddress ?: "no network"
    }

    private var http: HttpServer? = null
    private var udp: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    /** PC Link puesto ahora (los bloqueos y la espera por inactividad van con el). */
    @Volatile private var pcOn = false
    /** Lo ultimo que hizo el PC: PC Link se apaga solo tras Prefs.idleMinutes sin nada. */
    @Volatile private var lastPcActivity = System.currentTimeMillis()

    /**
     * Ludolog avisa de que su cuaderno cambio (una partida nueva): se comparte con las consolas
     * emparejadas, con una espera por si llegan varios seguidos. Solo de quien tenga el permiso de
     * firma (Ludolog). Con compartir apagado no sale nada (CompanionShare.syncAll lo mira).
     */
    private val shareSoon = Runnable { CompanionShare.syncAll(this, "new session") }

    /** Emuladores cerrados hace poco, a la espera de mandar sus partidas (ver SaveSync). */
    private val closed = java.util.Collections.synchronizedSet(HashSet<String>())
    private val savesSoon: Runnable = Runnable {
        val pkgs = synchronized(closed) { closed.toSet().also { closed.clear() } }
        // Con otra pasada en curso (al encender, antes de jugar, a mano) no se pierde: se reintenta.
        if (pkgs.isNotEmpty() && !SaveSync.syncAll(this, "game closed", pkgs)) {
            closed += pkgs
            if (!destroyed) handler.postDelayed(savesSoonRef, 20_000)
        }
    }
    private val savesSoonRef get() = savesSoon

    /** Ponerse al dia con los devices (al encender, o al volver a compartir): con nombre para quitarlo. */
    private val shareOnStart = Runnable {
        // Lo corregido en Ludolog mientras no escuchaba, antes de compartir.
        kotlin.concurrent.thread(isDaemon = true) {
            runCatching { MetaEdits.ingest(this) }
            CompanionShare.syncAll(this, "catching up")
        }
    }
    private val savesOnStart = Runnable {
        kotlin.concurrent.thread(isDaemon = true) {
            Saves.catchUp(this)
            SaveSync.syncAll(this, "catching up")
        }
    }

    /** Ya se apago: lo que termine despues (una subida a medias) no vuelve a poner la notificacion. */
    @Volatile private var destroyed = false
    private val gameReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val pkg = i.getStringExtra("pkg") ?: return
            if (i.action == "com.felp.frontcomp.link.GAME_CLOSED") {
                // Lo jugado se apunta siempre; mandarlo, solo compartiendo (si no, al volver a compartir).
                Saves.played(this@LinkService, pkg, i.getLongExtra("at", System.currentTimeMillis()))
                if (!Prefs.syncDevices(this@LinkService)) return
                closed += pkg
                handler.removeCallbacks(savesSoon)
                handler.postDelayed(savesSoon, 5_000)
            }
        }
    }

    /** Cada hora, los respaldos que toquen (ver Saves.scheduled). */
    private val backupTick = object : Runnable {
        override fun run() {
            kotlin.concurrent.thread(isDaemon = true) { runCatching { Saves.scheduled(this@LinkService) } }
            handler.postDelayed(this, 3_600_000L)
        }
    }
    private val companionReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // Una correccion en Ludolog (EDITS_CHANGED): a su registro, y se comparte como una partida.
            if (i.action == "com.felp.frontcomp.link.EDITS_CHANGED")
                kotlin.concurrent.thread(isDaemon = true) { runCatching { MetaEdits.ingest(this@LinkService) } }
            handler.removeCallbacks(shareSoon)
            handler.postDelayed(shareSoon, 5_000)
        }
    }
    @Volatile private var transferring = 0
    @Volatile private var transferSize = 1L
    @Volatile private var lastProgressPost = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PC_OFF) {
            LinkState.addLog("PC Link turned off")
            setPcLink(this, false)
            return START_STICKY
        }
        if (http == null) startAll()
        else {
            // Cada startForegroundService pide su startForeground, aunque ya lo estuviera.
            runCatching { startForeground(NOTIF_ID, notification(statusText()), fgsType()) }
            apply(intent?.getBooleanExtra(EXTRA_CATCH_UP, false) == true)
        }
        // Si Android lo para por memoria, que lo vuelva a poner: escuchar es su trabajo.
        return START_STICKY
    }

    /**
     * El tipo de servicio: en Android 14+ "uso especial", que no tiene el tope diario de seis horas
     * de dataSync (Android 15 lo cortaba) y puede arrancar con la consola. Antes, dataSync.
     */
    private fun fgsType() = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

    @SuppressLint("HardwareIds")
    private fun startAll() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Ludolog Link", NotificationManager.IMPORTANCE_LOW))
        val address = "${wifiAddress()}:${Protocol.HTTP_PORT}"
        LinkState.post { LinkState.address.value = address }
        // Android puede negarlo: con la app en segundo plano, sin una excepcion que lo permita.
        // Sin esto, la app se caia.
        try {
            startForeground(NOTIF_ID, notification("Listening to devices"), fgsType())
        } catch (e: Exception) {
            LinkState.addLog("Android didn't let Link start now: ${e.message}", "link", error = true)
            stopSelf()
            return
        }
        if (!needed(this)) {
            stopSelf()
            return
        }

        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION") // sin efecto desde Android 14; en Android 13 aun ayuda
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "link-transfer")
            .apply { setReferenceCounted(false) }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ludologlink:transfer")
            .apply { setReferenceCounted(false) }

        val id = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "desconocido"
        try {
            http = HttpServer(applicationContext, Protocol.HTTP_PORT, id, this).also { it.start() }
        } catch (e: Exception) {
            LinkState.addLog("Couldn't open port ${Protocol.HTTP_PORT}: ${e.message}", "link", error = true)
            stopSelf()
            return
        }
        instance = this
        startDiscovery(id)

        Pairing.refresh(applicationContext)
        LinkState.post { LinkState.running.value = true }
        LinkState.addLog("Listening at $address")

        registerReceiver(companionReceiver, android.content.IntentFilter().apply {
            addAction("com.felp.frontcomp.link.COMPANION_CHANGED"); addAction("com.felp.frontcomp.link.EDITS_CHANGED")
        },
            "com.felp.frontcomp.permission.LINK", null, Context.RECEIVER_EXPORTED)
        registerReceiver(gameReceiver, android.content.IntentFilter().apply {
            addAction("com.felp.frontcomp.link.GAME_OPENED"); addAction("com.felp.frontcomp.link.GAME_CLOSED")
        }, "com.felp.frontcomp.permission.LINK", null, Context.RECEIVER_EXPORTED)
        handler.postDelayed(backupTick, 60_000)
        kotlin.concurrent.thread(isDaemon = true) { runCatching { RomStore.dropStaleParts(this) } }
        // Al encenderse, ponerse al dia: lo jugado aqui y alla mientras no escuchaba.
        apply(catchUp = true)
    }

    /**
     * Lo de Prefs, aplicado: parar si ya no hace falta, PC Link encendido o apagado (sus bloqueos y
     * su espera por inactividad), y ponerse al dia si [catchUp] y se comparte. En el hilo principal.
     */
    private fun apply(catchUp: Boolean) {
        if (destroyed) return
        if (!needed(this)) {
            LinkState.addLog("Stopped: nothing to listen to")
            stopSelf()
            return
        }
        val pc = Prefs.pcLink(this)
        if (pc != pcOn) {
            pcOn = pc
            if (pc) {
                lastPcActivity = System.currentTimeMillis()
                // Para que la busqueda del PC llegue con la pantalla apagada: es lo que mas gasta.
                multicastLock = applicationContext.getSystemService(WifiManager::class.java)
                    .createMulticastLock("link-discovery").apply { setReferenceCounted(false); acquire() }
                handler.removeCallbacks(idleCheck)
                handler.postDelayed(idleCheck, 60_000)
                LinkState.addLog("PC Link on")
            } else {
                multicastLock?.let { if (it.isHeld) it.release() }
                multicastLock = null
                handler.removeCallbacks(idleCheck)
            }
        }
        if (catchUp && Prefs.syncDevices(this)) {
            handler.removeCallbacks(shareOnStart); handler.postDelayed(shareOnStart, 4_000)
            handler.removeCallbacks(savesOnStart); handler.postDelayed(savesOnStart, 8_000)
        }
        updateNotification(statusText())
        Ludolog.linkState(this, true, LinkState.address.value)
        LinkTileService.refresh(this)
    }

    private fun statusText() = if (pcOn) "PC Link on · ${LinkState.address.value}" else "Listening to devices"

    /**
     * Contesta a las busquedas: la de otra consola (para emparejarse, o encontrarla en otra IP)
     * mientras se comparta, y la del PC siempre, diciendo si PC Link esta puesto: asi el PC ensena
     * "PC Link off" en vez de no verla. Sin PC Link puede que la del PC no llegue con la pantalla
     * apagada (no hay bloqueo de multicast): no importa, entonces no hay nada que hacer con el PC.
     */
    private fun startDiscovery(id: String) {
        val sock = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(Protocol.DISCOVERY_PORT))
            }
        } catch (e: Exception) {
            LinkState.addLog("Discovery disabled: ${e.message}", "link", error = true)
            return
        }
        udp = sock
        thread(name = "link-udp", isDaemon = true) {
            val buf = ByteArray(512)
            while (!sock.isClosed) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    val msg = String(p.data, 0, p.length, Charsets.UTF_8)
                    if (!msg.startsWith(Protocol.DISCOVERY_MAGIC)) continue
                    val fromDevice = msg == Protocol.DISCOVERY_DEVICE || msg == Protocol.DISCOVERY_MAGIC
                    if (fromDevice && !Prefs.syncDevices(applicationContext)) continue
                    val reply = JSONObject()
                        .put("app", Protocol.APP).put("version", BuildInfo.VERSION).put("id", id)
                        .put("name", Prefs.deviceName(applicationContext))
                        .put("model", android.os.Build.MODEL).put("port", Protocol.HTTP_PORT)
                        .put("pcLink", pcOn)
                        .toString().toByteArray(Charsets.UTF_8)
                    sock.send(DatagramPacket(reply, reply.size, p.socketAddress))
                } catch (_: Exception) {
                    if (sock.isClosed) break
                }
            }
        }
    }

    /** PC Link se apaga solo tras Prefs.idleMinutes sin que el PC haga nada. Link sigue escuchando. */
    private val idleCheck = object : Runnable {
        override fun run() {
            if (!pcOn || destroyed) return
            val idle = System.currentTimeMillis() - lastPcActivity
            // 0: no se apaga nunca solo.
            val limit = Prefs.idleMinutes(this@LinkService).let { if (it <= 0) Long.MAX_VALUE else it * 60_000L }
            if (idle <= limit || transferring > 0 || LinkState.uiVisible) {
                handler.postDelayed(this, 60_000); return
            }
            LinkState.addLog("PC Link off (unused)")
            setPcLink(this@LinkService, false)
        }
    }

    /** Antes de Android 14 (dataSync): el tope diario. Con "uso especial" no llega. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        LinkState.addLog("Stopped by Android (daily limit)", "link", error = true)
        stopSelf()
    }

    // ------------------------------------------------------- TransferHooks

    override fun activity(fromPc: Boolean) {
        if (fromPc) lastPcActivity = System.currentTimeMillis()
    }

    override fun begin(name: String, size: Long, outgoing: Boolean) {
        synchronized(this) {
            transferring++
            wifiLock?.acquire()
            wakeLock?.acquire(6 * 60 * 60 * 1000L)
        }
        transferSize = maxOf(size, 1L)
        LinkState.post {
            LinkState.transferName.value = name
            LinkState.transferProgress.value = 0f
            LinkState.transferOutgoing.value = outgoing
        }
        updateNotification(if (outgoing) "Sending $name" else "Receiving $name")
    }

    override fun progress(done: Long) {
        val now = System.currentTimeMillis()
        lastPcActivity = now
        if (now - lastProgressPost < 250) return      // no inundar la interfaz
        lastProgressPost = now
        val frac = done.toFloat() / transferSize
        LinkState.post { LinkState.transferProgress.value = frac }
    }

    override fun end(name: String, ok: Boolean, outgoing: Boolean) {
        synchronized(this) {
            transferring = maxOf(0, transferring - 1)
            if (transferring == 0) {
                wifiLock?.let { if (it.isHeld) it.release() }
                wakeLock?.let { if (it.isHeld) it.release() }
            }
        }
        LinkState.post {
            if (transferring == 0) LinkState.transferName.value = null
        }
        LinkState.addLog(when {
            !ok -> "Failed: $name"
            outgoing -> "Sent: $name"
            else -> "Received: $name"
        }, "files", error = !ok)
        updateNotification(statusText())
    }

    // ------------------------------------------------------- notificacion

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Ludolog Link")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
        // Solo PC Link se apaga desde aqui: escuchar a los devices no es algo que se ande tocando.
        if (pcOn) {
            val off = PendingIntent.getService(this, 1,
                Intent(this, LinkService::class.java).setAction(ACTION_PC_OFF), PendingIntent.FLAG_IMMUTABLE)
            b.addAction(Notification.Action.Builder(null, "Turn off PC Link", off).build())
        }
        return b.build()
    }

    private fun updateNotification(text: String) {
        if (destroyed) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
    }

    override fun onDestroy() {
        destroyed = true
        if (instance === this) instance = null
        handler.removeCallbacks(shareOnStart)
        handler.removeCallbacks(savesOnStart)
        handler.removeCallbacks(idleCheck)
        handler.removeCallbacks(shareSoon)
        runCatching { unregisterReceiver(companionReceiver) }
        handler.removeCallbacks(savesSoon)
        handler.removeCallbacks(backupTick)
        runCatching { unregisterReceiver(gameReceiver) }
        http?.stop()
        http = null
        udp?.close()
        udp = null
        multicastLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock?.let { if (it.isHeld) it.release() }
        LinkState.post {
            LinkState.running.value = false
            LinkState.transferName.value = null
        }
        LinkState.addLog("Stopped")
        Ludolog.linkState(this, false, "")
        super.onDestroy()
        LinkTileService.refresh(this)
    }
}
