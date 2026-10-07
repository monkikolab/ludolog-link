package com.felp.frontcomp

import android.database.Cursor
import java.io.Closeable
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/*
 * Lo que LogStats.kt, Missions.kt, Dossiers.kt, GameDb.kt y Catalog.kt de Ludolog piden de
 * archivos que tocan Android (Logbook.kt, DataHome.kt, Prefs.kt). Aqui va su version de
 * escritorio, con el mismo nombre y paquete, para que esos archivos compilen tal cual y el
 * Companion del PC haga EXACTAMENTE las mismas cuentas que el de la consola.
 *
 * Todo de solo leer: el PC trabaja sobre copias de los cuadernos, y lo que Ludolog escribiria
 * (misiones cumplidas, ajustes) aqui no hace nada. Ver ludolog-front-end/docs/ludolog-link.md.
 */

/** La carpeta de datos de Ludolog, aqui la copia local de la de una consola. */
object DataHome {
    var dir: File = File(".")
    fun file(name: String): File = File(dir, name)

    /** Donde el scraper trabaja de paso (ArtVideo): en la carpeta temporal del PC, no en la copia. */
    fun work(): File = File(System.getProperty("java.io.tmpdir"), "ludolog-link-work").also { it.mkdirs() }
}

/**
 * Los cuadernos, como el Logbook de Ludolog con `withOthers`: el de la consola como `main` y
 * los de las demas enganchados como `other1`, `other2`... con sus numeros corridos.
 */
/** Que cuadernos abre `Logbook(ctx, ...)` en el PC: los de la consola que se esta mirando. */
internal object PcLogbooks {
    @Volatile var own: File? = null
    @Volatile var others: List<File> = emptyList()
}

internal class Logbook(main: File, otherFiles: List<File> = emptyList()) : Closeable {
    /** Como lo abren las pestanas del Companion en Ludolog: el de la consola, y con `withOthers` los demas. */
    constructor(@Suppress("UNUSED_PARAMETER") ctx: android.content.Context, withOthers: Boolean = false) :
        this(PcLogbooks.own ?: error("no logbook"), if (withOthers) PcLogbooks.others else emptyList())

    data class Other(val schema: String, val offset: Long)

    data class NameRef(
        val system: String,
        val file: String,
        val name: String,
        val identity: String?,
        val at: Long,
        val genre: String? = null,
        val doneAt: Long? = null,
    )

    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:${main.absolutePath}")
    val readableDatabase = JdbcDb(conn)
    var others: List<Other> = emptyList()
        private set

    init {
        others = otherFiles.mapIndexedNotNull { i, f ->
            val o = Other("other${i + 1}", OTHER_OFFSET * (i + 1))
            runCatching { readableDatabase.execSQL("ATTACH DATABASE ? AS ${o.schema}", arrayOf(f.absolutePath)); o }.getOrNull()
        }
    }

    /** En Ludolog apunta una mision cumplida. El PC no escribe en los cuadernos. */
    fun missionDone(id: String, at: Long) = Unit

    override fun close() = conn.close()

    companion object {
        const val OTHER_OFFSET = 1_000_000_000L
        fun isOwn(id: Long): Boolean = id < OTHER_OFFSET
    }
}

/** Lo que LogStats usa de SQLiteDatabase, sobre JDBC. */
internal class JdbcDb(private val conn: Connection) {
    fun rawQuery(sql: String, args: Array<String>?): Cursor = conn.prepareStatement(sql).use { st ->
        args?.forEachIndexed { i, a -> st.setString(i + 1, a) }
        JdbcCursor.of(st.executeQuery())
    }

    fun execSQL(sql: String) { conn.createStatement().use { it.execute(sql) } }

    fun execSQL(sql: String, args: Array<Any?>) = conn.prepareStatement(sql).use { st ->
        args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
        st.execute()
        Unit
    }

    fun beginTransaction() { conn.autoCommit = false }
    fun setTransactionSuccessful() { conn.commit() }
    fun endTransaction() { if (!conn.autoCommit) { runCatching { conn.rollback() }; conn.autoCommit = true } }
}

/**
 * Un Cursor de Android sobre un resultado de JDBC, leido entero de una vez: las consultas del
 * Companion devuelven cientos de filas, no millones, y asi se puede volver al principio.
 * Columnas desde 0, como en Android (JDBC empieza en 1).
 */
internal class JdbcCursor private constructor(private val rows: List<Array<Any?>>) : Cursor {
    private var pos = -1

    companion object {
        fun of(rs: java.sql.ResultSet): JdbcCursor = rs.use {
            val n = rs.metaData.columnCount
            val out = ArrayList<Array<Any?>>()
            while (rs.next()) out += Array(n) { rs.getObject(it + 1) }
            JdbcCursor(out)
        }
    }

    private fun v(c: Int): Any? = rows[pos][c]
    private fun num(c: Int): Number = when (val x = v(c)) {
        null -> 0
        is Number -> x
        is String -> x.toDoubleOrNull() ?: 0
        else -> 0
    }

    override fun moveToFirst(): Boolean { pos = 0; return rows.isNotEmpty() }
    override fun moveToNext(): Boolean { pos++; return pos < rows.size }
    override fun isNull(column: Int) = v(column) == null
    override fun getString(column: Int): String? = v(column)?.toString()
    override fun getLong(column: Int) = num(column).toLong()
    override fun getInt(column: Int) = num(column).toInt()
    override fun getDouble(column: Int) = num(column).toDouble()
    override fun getFloat(column: Int) = num(column).toFloat()
    override fun getCount() = rows.size
    override fun close() = Unit
}

/**
 * Lo que Missions.kt lee de Prefs: las misiones que se siguen y las cumplidas, de config.xml de
 * la consola. Escribir no hace nada: el PC no cambia ajustes por aqui.
 */
internal class Prefs(
    private val config: Map<String, Any?>,
    /** Para el scraper del PC: si se buscan videos en esta pasada. Nulo: lo que digan los temas. */
    private val videos: Boolean? = null,
) {
    // ---- lo que pide el scraper (Scrape.kt / ArtSources.kt), con los valores por defecto de Prefs.kt

    /** El nombre que el usuario le puso al juego en Ludolog. */
    fun gameTitle(game: Game): String? = config["name.game.${game.path}"] as? String

    val clipSeconds: Int get() = (config["look.clip"] as? Number)?.toInt() ?: 15
    val videoHeight: Int get() = (config["look.vheight"] as? Number)?.toInt() ?: 240
    val anyThemePlaysVideo: Boolean
        get() = videos ?: AllThemes.any { config["look.video.${it.id}"] as? Boolean ?: true }
    val anyThemeHearsVideo: Boolean get() = AllThemes.any { config["look.sound.${it.id}"] as? Boolean ?: true }

    /** Las credenciales (IGDB) estan selladas en la consola y no salen de ella: en el PC no hay. */
    @Suppress("UNUSED_PARAMETER")
    fun credential(key: String): String = ""

    var trackedMissions: Map<String, Long>
        get() = stamps(config["meta.missions"] as? String).toMap()
        set(@Suppress("UNUSED_PARAMETER") v) = Unit

    var doneMissions: List<Pair<String, Long>>
        get() = stamps(config["meta.missions.done"] as? String)
        set(@Suppress("UNUSED_PARAMETER") v) = Unit

    val completions: List<Long>
        get() = config.filterKeys { it.startsWith("done.") }.values.mapNotNull { (it as? Long)?.takeIf { v -> v > 0 } }

    private fun stamps(s: String?): List<Pair<String, Long>> = s.orEmpty().split(',').mapNotNull { e ->
        val (id, at) = e.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
        at.toLongOrNull()?.let { id to it }
    }
}

// Igual que en Apps.kt (que toca Android): un "juego" que es una app instalada.
const val APP_SCHEME = "app://"
val Game.appPackage: String? get() = path.removePrefix(APP_SCHEME).takeIf { it != path }
