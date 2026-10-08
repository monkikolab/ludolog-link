package com.felp.ludologlink

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/** Mosaico del panel rapido: encender y apagar PC Link sin abrir la app. Escuchar a los devices no se toca. */
class LinkTileService : TileService() {

    companion object {
        private const val TAG = "LudologLinkTile"
        private val main = Handler(Looper.getMainLooper())

        /** El mosaico mientras esta a la vista, para redibujarlo al instante. */
        @Volatile private var live: LinkTileService? = null

        /**
         * El servicio avisa al encenderse o apagarse. Pedir solo que Android
         * vuelva a escuchar no basta: si el panel ya estaba abierto, no siempre
         * lo hace, y el mosaico se quedaba mostrando el estado anterior.
         */
        fun refresh(ctx: Context) {
            requestListeningState(ctx, ComponentName(ctx, LinkTileService::class.java))
            main.post { live?.update() }
        }
    }

    // Para que la app sepa si el mosaico esta puesto, se lo pongan desde ella o a mano.
    override fun onTileAdded() { Prefs.setTileAdded(this, true) }

    override fun onTileRemoved() { Prefs.setTileAdded(this, false) }

    override fun onStartListening() {
        live = this
        update()
    }

    override fun onStopListening() {
        if (live === this) live = null
    }

    override fun onDestroy() {
        if (live === this) live = null
        super.onDestroy()
    }

    override fun onClick() {
        val wasOn = Prefs.pcLink(this) && LinkState.running.value
        Log.i(TAG, "toque: PC Link=$wasOn")
        Prefs.setPcLink(this, !wasOn)
        if (!LinkService.ensure(this)) {
            // Si Android no deja arrancar el servicio desde aqui, se abre la
            // app, que lo arranca al mostrarse.
            Log.w(TAG, "no se pudo arrancar desde el mosaico")
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
        // Se pinta ya lo esperado (nunca STATE_UNAVAILABLE: Android ignora los
        // toques en ese estado) y se corrige con el estado real poco despues.
        draw(!wasOn)
        main.postDelayed({ update() }, 1500)
    }

    fun update() = draw(Prefs.pcLink(this) && LinkState.running.value)

    private fun draw(on: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = if (on) "PC Link on" else "PC Link off"
        tile.updateTile()
    }
}
