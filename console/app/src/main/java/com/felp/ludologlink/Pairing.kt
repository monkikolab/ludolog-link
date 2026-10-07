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

    /** Devuelve el token si el codigo es correcto; null si no. */
    @Synchronized
    fun confirm(ctx: Context, given: String, pc: String): String? {
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
        Prefs.putToken(ctx, token, pc)
        refresh(ctx)
        LinkState.addLog("Paired with $pc", "pairing")
        return token
    }

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
