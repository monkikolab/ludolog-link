package com.felp.ludologlink

import android.content.Context
import android.os.Build
import org.json.JSONObject

object Prefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("link", Context.MODE_PRIVATE)

    fun romsRoot(ctx: Context): String? = prefs(ctx).getString("roms_root", null)

    fun setRomsRoot(ctx: Context, path: String) =
        prefs(ctx).edit().putString("roms_root", path).apply()

    /** El aspecto de Link: "ludolog" (el de Ludolog en esta consola) o el id de un tema. */
    fun look(ctx: Context): String = prefs(ctx).getString("look", "ludolog")!!
        .let { com.felp.frontcomp.LEGACY_THEME_IDS[it] ?: it }

    fun setLook(ctx: Context, id: String) = prefs(ctx).edit().putString("look", id).apply()

    /** Si Ludolog pregunta a Link antes de abrir un juego si hay una partida mas nueva en otro device (ver SaveCheck). */
    fun saveCheck(ctx: Context): Boolean = prefs(ctx).getBoolean("save_check", true)

    fun setSaveCheck(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("save_check", on).apply()

    /**
     * PC Link: lo que solo sirve para el PC. Que el PC la encuentre al buscar (y el bloqueo de Wi-Fi
     * que hace falta para oirlo, lo que mas gasta en reposo) y que atienda sus peticiones. Apagado,
     * Link sigue escuchando a sus devices. Se apaga solo tras [idleMinutes] sin que el PC haga nada.
     */
    fun pcLink(ctx: Context): Boolean = prefs(ctx).getBoolean("pc_link", false)

    fun setPcLink(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("pc_link", on).apply()

    /**
     * Si el mosaico de PC Link esta en el panel rapido: lo dicen Android al pedirlo y el propio
     * mosaico al ponerlo o quitarlo (LinkTileService). Con eso el boton «Add tile» deja paso a un
     * «Added» en vez de seguir ofreciendo lo que ya esta (07-10-2026).
     */
    fun tileAdded(ctx: Context): Boolean = prefs(ctx).getBoolean("tile_added", false)

    fun setTileAdded(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("tile_added", on).apply()
        LinkState.post { LinkState.tileAdded.value = on }
    }

    /**
     * Compartir con mis devices (partidas, Companion, ROMs entre consolas). Lo decide la persona y
     * casi nunca se toca: apagado no viaja nada entre consolas, pero lo jugado se sigue apuntando
     * (played.tsv de Ludolog, el cuaderno) y al volver a encenderlo se pone al dia. Antes de jugar,
     * si otro device tiene una partida mas nueva, Ludolog avisa en vez de traerla (SaveCheck.OFF).
     */
    fun syncDevices(ctx: Context): Boolean = prefs(ctx).getBoolean("sync_devices", true)

    fun setSyncDevices(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("sync_devices", on).apply()

    /** Minutos sin que el PC haga nada antes de que PC Link se apague solo; 0: nunca. */
    fun idleMinutes(ctx: Context): Int = prefs(ctx).getInt("idle_minutes", 15)

    fun setIdleMinutes(ctx: Context, minutes: Int) = prefs(ctx).edit().putInt("idle_minutes", minutes).apply()

    /** Mirar una vez al dia si hay version nueva de Link en GitHub (ver ReleaseCheck). */
    fun updateCheck(ctx: Context): Boolean = prefs(ctx).getBoolean("update_check", true)

    fun setUpdateCheck(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("update_check", on).apply()

    /** Cuando se pregunto a GitHub por ultima vez, haya contestado o no. */
    fun updateAt(ctx: Context): Long = prefs(ctx).getLong("update_at", 0L)

    fun setUpdateAt(ctx: Context, t: Long) = prefs(ctx).edit().putLong("update_at", t).apply()

    /** La ultima version publicada que se conoce, y aquella de la que ya se aviso (una vez cada una). */
    fun updateLatest(ctx: Context): String? = prefs(ctx).getString("update_latest", null)

    fun setUpdateLatest(ctx: Context, v: String) = prefs(ctx).edit().putString("update_latest", v).apply()

    fun updateNotified(ctx: Context): String? = prefs(ctx).getString("update_notified", null)

    fun setUpdateNotified(ctx: Context, v: String) = prefs(ctx).edit().putString("update_notified", v).apply()

    val IDLE_CHOICES = listOf(5, 15, 30, 60, 0)

    /** Si ya se paso (o se salto) el wizard del primer arranque. Ver SetupWizard. */
    fun setupDone(ctx: Context): Boolean = prefs(ctx).getBoolean("setup_done", false)

    fun setSetupDone(ctx: Context, done: Boolean) = prefs(ctx).edit().putBoolean("setup_done", done).apply()

    /** Apodo de la consola: lo que ve el PC. Por defecto, el modelo. */
    fun deviceName(ctx: Context): String = prefs(ctx).getString("device_name", null) ?: Build.MODEL

    fun setDeviceName(ctx: Context, name: String) {
        val clean = name.trim().take(40)
        prefs(ctx).edit().apply {
            if (clean.isEmpty()) remove("device_name") else putString("device_name", clean)
        }.apply()
    }

    /** token -> nombre del PC. */
    @Synchronized
    fun tokens(ctx: Context): Map<String, String> {
        val raw = prefs(ctx).getString("tokens", null) ?: return emptyMap()
        val obj = JSONObject(raw)
        return obj.keys().asSequence().associateWith { obj.getString(it) }
    }

    @Synchronized
    fun putToken(ctx: Context, token: String, pc: String) {
        val obj = JSONObject(tokens(ctx))
        obj.put(token, pc)
        prefs(ctx).edit().putString("tokens", obj.toString()).apply()
    }

    @Synchronized
    fun forgetToken(ctx: Context, token: String) {
        val obj = JSONObject(tokens(ctx).filterKeys { it != token })
        prefs(ctx).edit().putString("tokens", obj.toString()).apply()
    }

    @Synchronized
    fun forgetPc(ctx: Context, pc: String) {
        val obj = JSONObject(tokens(ctx).filterValues { it != pc })
        prefs(ctx).edit().putString("tokens", obj.toString()).apply()
    }
}
