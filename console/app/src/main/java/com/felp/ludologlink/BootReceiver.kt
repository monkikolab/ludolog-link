package com.felp.ludologlink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Al arrancar la consola, y al actualizarse Link: volver a escuchar si hace falta (devices
 * emparejados, o PC Link). Android deja arrancar el servicio desde aqui. Ver LinkService.ensure.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        LinkService.ensure(context)
    }
}
