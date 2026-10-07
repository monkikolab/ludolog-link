package com.felp.ludolog.kit

import java.net.HttpURLConnection
import java.net.URL

/**
 * Si hay una version nueva de Ludolog Link publicada en GitHub (07-10-2026). La usan la app de la
 * consola y la del PC.
 *
 * Link se instala fuera de la tienda, asi que nadie avisa de las versiones nuevas. Como mucho una
 * vez al dia se mira el ultimo release del repositorio y, si es mas nuevo, se dice (una vez por
 * version, y en About). Solo avisa: no baja ni instala nada. Lo unico que sale es la peticion a
 * GitHub, y se puede apagar.
 */
object ReleaseCheck {
    const val PAGE = "https://github.com/monkikolab/ludolog-link/releases/latest"
    private const val API = "https://api.github.com/repos/monkikolab/ludolog-link/releases/latest"
    const val DAY_MS = 24 * 60 * 60 * 1000L

    /** a es mas nueva que b, numero a numero: «0.10.0» va despues de «0.9.3». */
    fun newer(a: String, b: String): Boolean {
        val x = nums(a)
        val y = nums(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    private fun nums(v: String) =
        v.trim().removePrefix("v").split('.', '-').mapNotNull { p -> p.takeWhile(Char::isDigit).toIntOrNull() }

    /**
     * La version del ultimo release, preguntando a GitHub. Bloquea: fuera del hilo de la pantalla.
     * Sin red, o sin nada publicado todavia (GitHub contesta 404), falla con un motivo legible.
     */
    fun fetch(userAgent: String): Result<String> = runCatching {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 8_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", userAgent)
        try {
            when (c.responseCode) {
                200 -> {}
                404 -> error("nothing published yet")
                else -> error("GitHub answered ${c.responseCode}")
            }
            val body = c.inputStream.bufferedReader().use { it.readText() }
            Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)?.removePrefix("v")
                ?: error("no version in GitHub's answer")
        } finally {
            c.disconnect()
        }
    }
}

/**
 * Lo que se tapa en un diagnostico antes de guardarlo: las dos ultimas cifras de cada IP de la red
 * local y el nombre del usuario de las rutas. Un diagnostico se adjunta a un aviso publico en GitHub.
 * Solo las IP locales: «21.0.12.101» tiene forma de IP y es una version de Java.
 */
object Redact {
    private val ip = Regex("""\b(\d{1,3})\.(\d{1,3})\.\d{1,3}\.\d{1,3}\b""")

    private fun local(a: Int, b: Int) =
        a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254) || (a == 100 && b in 64..127)

    fun text(s: String, user: String? = null): String {
        var out = ip.replace(s) { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            if (local(a, b)) "$a.$b.x.x" else m.value
        }
        if (!user.isNullOrBlank() && user.length > 1) out = out.replace(user, "<user>", ignoreCase = true)
        return out
    }
}
