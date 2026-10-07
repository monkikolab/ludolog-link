package com.felp.ludologlink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.felp.ludolog.kit.Protocol
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Las caratulas pequeñas del Companion para los juegos de OTRA consola.
 *
 * El Companion de Ludolog pone al lado de cada juego su caratula, y la busca entre los juegos de esta
 * consola. Los que solo se jugaron en otra no estan aqui, y salian con un cuadro de color. Asi que al
 * compartir los cuadernos (CompanionShare) se pide tambien a la otra consola una miniatura de las que
 * faltan, y se deja en `<datos>/companion/covers/<clave>.jpg`, donde Ludolog la busca si no tiene la
 * suya (CompanionCovers en Ludolog). Ver ludolog-front-end/docs/ludolog-link.md.
 *
 * La clave es el nombre con que se apunto la partida (`display_name` del cuaderno), solo letras y
 * numeros y en minusculas: la misma regla en los dos lados.
 */
object CompanionCovers {
    const val DIR = "companion/covers"

    /** Alto de la miniatura: el Companion la enseña a 26-46 dp, asi que 200 px sobran y pesan poco. */
    private const val HEIGHT = 200

    /** Cuantas se piden como mucho por pasada: la primera vez puede haber muchas. */
    private const val PER_PASS = 150

    /** Cuanto se espera para volver a pedir una que la otra consola no tenia. */
    private const val RETRY_MS = 7 * 86_400_000L

    fun key(title: String) = title.lowercase().filter { it.isLetterOrDigit() }

    /**
     * La miniatura de la caratula de ese juego AQUI (consola de Ludolog y archivo del ROM), en JPEG.
     * Nula si no tiene. La pide la otra consola (ruta `/ludolog/companion/cover`).
     */
    fun thumbnail(ctx: Context, system: String, file: String, shown: String = ""): ByteArray? {
        // Por el nombre del archivo o por el del juego, como los compara Ludolog. Y el nombre entero:
        // hay partidas apuntadas sin la extension, y quitarsela cortaba en un punto del nombre
        // ("Blasphemous [...]+[v1.0.5+DLC]" se quedaba en "...[v1").
        val name = file.substringAfterLast('/')
        val keys = setOf(key(Protocol.stemOf(name)), key(name), key(shown))
        val src = Ludolog.findMediaByKey(ctx, listOf(system), keys, video = false) ?: return null
        // Primero solo las medidas, para leerla ya reducida: una caratula puede tener varios megas.
        val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, size)
        if (size.outHeight <= 0) return null
        var sample = 1
        while (size.outHeight / (sample * 2) >= HEIGHT) sample *= 2
        val raw = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val w = (raw.width.toLong() * HEIGHT / raw.height).toInt().coerceAtLeast(1)
        val small = if (raw.height > HEIGHT) Bitmap.createScaledBitmap(raw, w, HEIGHT, true) else raw
        return ByteArrayOutputStream().use { out ->
            small.compress(Bitmap.CompressFormat.JPEG, 82, out)
            if (small !== raw) small.recycle()
            raw.recycle()
            out.toByteArray()
        }
    }

    /**
     * Despues de cruzar los cuadernos con [p]: las miniaturas que faltan de los juegos de los cuadernos
     * de las otras consolas que hay aqui (no del propio). Se salta lo que ya tiene caratula aqui (en
     * la biblioteca o en [DIR]) y lo que [p] no tenia hace poco. Devuelve cuantas trajo.
     */
    fun pull(ctx: Context, p: Peer, dir: File, own: String?): Int {
        val out = File(dir, DIR).apply { mkdirs() }
        val missFile = File(out, ".missing.json")
        val misses = runCatching { JSONObject(missFile.readText()) }.getOrDefault(JSONObject())
        val now = System.currentTimeMillis()
        // Lo que ya tiene caratula en la biblioteca de aqui: Ludolog la encuentra por su cuenta.
        val here = Ludolog.artIndex(ctx).optJSONArray("art")?.let { a -> (0 until a.length()).mapTo(HashSet()) { a.getString(it) } }.orEmpty()

        val wanted = LinkedHashMap<String, Triple<String, String, String>>()   // clave -> (consola, archivo, nombre)
        for (db in File(dir, "companion").listFiles { f -> f.isFile && f.name.endsWith(".db") && !f.name.startsWith(".") && f.name != own }.orEmpty()) {
            for ((system, file, shown) in games(db)) {
                val k = key(shown.ifBlank { Protocol.stemOf(file.substringAfterLast('/')) })
                if (k.isEmpty() || k in wanted || File(out, "$k.jpg").isFile) continue
                if ("${system.lowercase()}/${Protocol.stemOf(file.substringAfterLast('/')).lowercase()}" in here) continue
                if (now - misses.optLong("$k|${p.id}") < RETRY_MS) continue
                wanted[k] = Triple(system, file, shown)
            }
        }
        var got = 0
        for ((k, sf) in wanted.entries.take(PER_PASS)) {
            val (system, file, shown) = sf
            val c = Peers.open(p.host, p.port, "GET", "/ludolog/companion/cover",
                mapOf("system" to system, "file" to file, "name" to shown), p.token, 30_000)
            try {
                when (c.responseCode) {
                    200 -> {
                        val tmp = File(out, ".$k.jpg.part")
                        c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                        if (tmp.length() > 0 && tmp.renameTo(File(out, "$k.jpg"))) got++ else tmp.delete()
                    }
                    404 -> misses.put("$k|${p.id}", now)
                    else -> {}
                }
            } catch (_: Exception) {
                // Una que no llega no para las demas.
            } finally {
                c.disconnect()
            }
        }
        runCatching { missFile.writeText(misses.toString()) }
        return got
    }

    /** Los juegos de un cuaderno: consola, archivo y el nombre con que se apunto (el ultimo). */
    private fun games(db: File): List<Triple<String, String, String>> = runCatching {
        val out = ArrayList<Triple<String, String, String>>()
        android.database.sqlite.SQLiteDatabase.openDatabase(db.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { d ->
            d.rawQuery("SELECT system, title, display_name, MAX(started_at) FROM sessions " +
                "WHERE system IS NOT NULL AND title IS NOT NULL GROUP BY system, title", null).use { c ->
                while (c.moveToNext()) out += Triple(c.getString(0), c.getString(1), c.getString(2).orEmpty())
            }
        }
        out as List<Triple<String, String, String>>
    }.getOrDefault(emptyList())
}
