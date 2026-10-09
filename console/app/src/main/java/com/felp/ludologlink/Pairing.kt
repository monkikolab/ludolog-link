package com.felp.ludologlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.security.SecureRandom

/**
 * Emparejamiento como el del Bluetooth: el PC lo pide, la consola muestra un
 * codigo, y quien lo teclee en el PC recibe un token. Sin token no se puede
 * leer ni escribir nada: cualquier dispositivo de la Wi-Fi veria el puerto.
 */
object Pairing {
    private const val TTL_MS = 120_000L
    private const val MAX_ATTEMPTS = 5

    private val rnd = SecureRandom()
    private var code: String? = null
    private var expires = 0L
    private var attempts = 0
    private var appCtx: Context? = null
    /** Fallos seguidos en total (no por codigo) y hasta cuando no se dan codigos nuevos. */
    private var failures = 0
    private var lockedUntil = 0L

    private const val CHANNEL = "pairing"
    private const val NOTIF_ID = 2

    /**
     * Mientras hay un codigo vigente no se da otro ni se reinician los intentos: pedir otro codigo
     * cada cinco fallos era probar a ciegas sin limite, y cualquiera de la Wi-Fi podia pisar el codigo
     * de quien estaba emparejando de verdad. Tras muchos fallos, diez minutos sin codigos.
     */
    @Synchronized
    fun request(ctx: Context, pc: String) {
        appCtx = ctx.applicationContext
        val now = System.currentTimeMillis()
        if (now < lockedUntil) return
        if (code != null && now < expires) return
        val c = "%06d".format(rnd.nextInt(1_000_000))
        code = c
        expires = now + TTL_MS
        attempts = 0
        LinkState.post {
            LinkState.pairingCode.value = c
            LinkState.pairingPc.value = pc
        }
        LinkState.addLog("$pc requests pairing", "pairing")
        notify(ctx.applicationContext, pc, c)
    }

    /**
     * El aviso de que un PC pide emparejarse, con el codigo: sale por encima de lo que haya en
     * pantalla —un juego incluido— sin tener que abrir Link. Se va al emparejar o al caducar.
     * En la pantalla de bloqueo no se ve el codigo.
     */
    private fun notify(ctx: Context, pc: String, code: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Pairing requests", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Pairing codes" })
        val open = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val spaced = code.chunked(3).joinToString(" ")
        val public = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Pairing request")
            .setContentText("Someone wants to pair")
            .build()
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("$pc wants to pair")
            .setContentText("Code: $spaced")
            .setStyle(Notification.BigTextStyle().bigText("Code: $spaced\nEnter it on $pc. Ignore it if it wasn't you."))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(TTL_MS)
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
    }

    /**
     * Devuelve el token si el codigo es correcto; null si no. [owner] es el id fijo del PC (desde la
     * 0.5.3): con el, los emparejamientos anteriores del mismo PC se olvidan (ver [forgetOlder]).
     */
    @Synchronized
    fun confirm(ctx: Context, given: String, pc: String, owner: String? = null): String? {
        val c = code ?: return null
        if (System.currentTimeMillis() > expires) {
            clear()
            return null
        }
        if (given.trim() != c) {
            attempts++
            if (attempts >= MAX_ATTEMPTS) clear()     // sin fuerza bruta
            if (++failures >= 15) {
                lockedUntil = System.currentTimeMillis() + 10 * 60_000L
                failures = 0
                clear()
                LinkState.addLog("Too many wrong codes: pairing paused 10 min", "pairing", error = true)
            }
            return null
        }
        failures = 0
        clear()
        val bytes = ByteArray(24).also { rnd.nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        Prefs.putToken(ctx, token, pc, owner)
        if (owner != null) forgetOlder(ctx, token, owner, pc)
        refresh(ctx)
        LinkState.addLog("Paired with $pc", "pairing")
        return token
    }

    /**
     * Un PC con el emparejamiento de antes de la 0.5.3 dice cual es su id (POST /pair/claim, con su
     * clave, en la direccion de siempre). Desde entonces puede comprobar a esta consola en una IP nueva
     * sin mandar su clave (ver HttpServer.pingProof), y los emparejamientos viejos suyos se olvidan.
     * Null si la clave ya es de otro PC; si no, cuantos se olvidaron.
     */
    @Synchronized
    fun claim(ctx: Context, token: String, owner: String, pc: String): Int? {
        val had = Prefs.tokenOwners(ctx)[token]
        if (had != null && had != owner) return null
        if (had == null) Prefs.setOwner(ctx, token, owner)
        return forgetOlder(ctx, token, owner, pc).also { refresh(ctx) }
    }

    /**
     * Olvida los otros emparejamientos del PC [owner] (09-10-2026): cada vez que un PC se emparejaba
     * de nuevo, por ejemplo tras reinstalar Link en el, sumaba una clave y las viejas seguian valiendo
     * hasta «Forget» (la RP5 tenia seis de «MSI»). Los suyos con id, y los de antes del id que llevan
     * su mismo nombre: dos PCs con el mismo nombre y uno sin id es raro, y ese solo tendria que
     * emparejarse otra vez. Nunca las claves que se dieron a otras consolas.
     */
    private fun forgetOlder(ctx: Context, keep: String, owner: String, pc: String): Int {
        val owners = Prefs.tokenOwners(ctx)
        val devices = Peers.everyone(ctx).map { it.backToken }.toSet()
        val gone = Prefs.tokens(ctx).filter { (t, name) ->
            t != keep && t !in devices && (owners[t] == owner || (owners[t] == null && name == pc))
        }.keys
        if (gone.isNotEmpty()) {
            Prefs.forgetTokens(ctx, gone)
            LinkState.addLog("Forgot ${gone.size} older pairing${if (gone.size == 1) "" else "s"} of $pc", "pairing")
        }
        return gone.size
    }

    /** La clave del PC [owner], si se emparejo con su id o lo dijo despues. */
    fun tokenOf(ctx: Context, owner: String): String? =
        Prefs.tokenOwners(ctx).entries.firstOrNull { it.value == owner }?.key
            ?.takeIf { Prefs.tokens(ctx).containsKey(it) }

    fun isValid(ctx: Context, token: String?): Boolean =
        !token.isNullOrEmpty() && Prefs.tokens(ctx).containsKey(token)

    fun pcFor(ctx: Context, token: String?): String =
        Prefs.tokens(ctx)[token] ?: "PC"

    fun refresh(ctx: Context) {
        val pcs = Prefs.tokens(ctx).values.distinct().sorted()
        LinkState.post {
            LinkState.pairedPcs.clear()
            LinkState.pairedPcs.addAll(pcs)
        }
    }

    @Synchronized
    private fun clear() {
        code = null
        LinkState.post { LinkState.pairingCode.value = null }
        appCtx?.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
    }
}
