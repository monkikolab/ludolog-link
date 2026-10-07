package com.felp.ludologlink.pc

import com.felp.frontcomp.Achievements
import com.felp.frontcomp.Book
import com.felp.frontcomp.PcLogbooks
import com.felp.frontcomp.Catalog
import com.felp.frontcomp.CatalogLoader
import com.felp.frontcomp.DataHome
import com.felp.frontcomp.GameNames
import com.felp.frontcomp.LogStats
import com.felp.frontcomp.Logbook
import com.felp.frontcomp.Metagame
import com.felp.frontcomp.Missions
import java.io.File

/**
 * El Companion de una consola, leido en el PC de una copia de su carpeta de datos.
 *
 * Las cuentas son las de Ludolog: LogStats, Metagame, Achievements y Missions se compilan TAL
 * CUAL (ver build.gradle.kts). Lo unico de aqui es el orden en que se piden, que copia el de
 * StatsWindow.kt (el `produceState` que llena su `Book`): primero las misiones cumplidas, que
 * dan experiencia, y luego el personaje. Si ese orden cambia en Ludolog, cambia aqui.
 * Ver ludolog-front-end/docs/ludolog-link.md.
 */
internal object CompanionReader {

    /** Las consolas del catalogo de serie (sin lo que el usuario creo): para saber cuales son suyas. */
    val builtInIds: Set<String> by lazy {
        runCatching {
            Catalog.parse(CompanionReader::class.java.getResourceAsStream("/ludolog/${CatalogLoader.ASSET}")!!
                .bufferedReader().use { it.readText() }).systems.map { it.id }.toSet()
        }.getOrDefault(emptySet())
    }

    /** El catalogo de consolas de Ludolog: su systems.toml (empaquetado del APK) y lo que el usuario corrigio. */
    fun catalog(): Catalog = CatalogLoader.load { name ->
        CompanionReader::class.java.getResourceAsStream("/ludolog/$name")!!.bufferedReader().use { it.readText() }
    }

    /**
     * [dataDir] es la copia local de `<volumen>/Ludolog` de la consola: `companion/<consola>.db`,
     * `config.xml`, `dossiers/`, `systems*`, `catalog/`. [model] es su Build.MODEL; [ownId], los
     * cuatro digitos de `log.console.id`, que dicen cual de los cuadernos es el suyo.
     */
    /**
     * Pone a punto lo que las cuentas y las pestañas de Ludolog leen de fuera —la carpeta de datos,
     * el modelo, los cuadernos—: es de UNA consola a la vez, la que se esta mirando.
     */
    /** A que consola apunta ahora (ver [read]). */
    private var pointed: Triple<File, String, String?>? = null

    fun point(dataDir: File, model: String, ownId: String?): Pair<File, List<File>> {
        pointed = Triple(dataDir, model, ownId)
        DataHome.dir = dataDir
        android.os.Build.MODEL = model
        val dbs = File(dataDir, "companion").listFiles { f -> f.name.endsWith(".db") }.orEmpty().sortedBy { it.name }
        val own = dbs.firstOrNull { ownId != null && it.name.endsWith("-$ownId.db") }
            ?: dbs.firstOrNull { it.name.startsWith("$model-") } ?: dbs.first()
        PcLogbooks.own = own
        PcLogbooks.others = dbs - own
        return own to (dbs - own)
    }

    /**
     * Lee el cuaderno de una consola. Para leerlo hay que apuntar a ella, pero al terminar se vuelve a
     * la que estaba apuntada: si no, leer otra consola (al arrancar se leen todas) dejaba las graficas
     * y rankings del Companion con los datos de la ultima leida.
     */
    fun read(dataDir: File, model: String, ownId: String?, config: Map<String, Any?>): Book {
        val before = pointed
        try {
            return readPointed(dataDir, model, ownId, config)
        } finally {
            if (before != null && before.first != dataDir) point(before.first, before.second, before.third)
        }
    }

    private fun readPointed(dataDir: File, model: String, ownId: String?, config: Map<String, Any?>): Book {
        val (own, otherDbs) = point(dataDir, model, ownId)
        val dbs = listOf(own) + otherDbs
        val cat = catalog()
        // Sin biblioteca a mano: los nombres de hoy salen de la tabla `names` de los cuadernos,
        // que Ludolog mantiene al dia para las otras consolas (ver GameNames.resolve).
        val names = GameNames.of(emptyMap()).also { GameNames.current = it }
        val prefs = com.felp.frontcomp.Prefs(config)
        Logbook(own, dbs - own).use { db ->
            val s = LogStats(db, cat, names)
            val devs = s.devices()
            val plays = s.plays()
            val years = cat.systems.associate { it.id to it.year }
            val completions = s.completions(prefs.completions)
            // En Ludolog aqui va Missions.settle, que ESCRIBE: asienta las cumplidas. El PC no
            // escribe; lee las ya asentadas, que es lo que la consola enseña despues.
            val missionsDone = s.missionsDone()
            val character = Metagame.of(plays.map { it.session }, System.currentTimeMillis(), Missions.bonuses(missionsDone))
            val ranking = s.ranking()
            val recent = s.recent()
            val feeds = (ranking.map { it.title to (it.system ?: "?") } + recent.map { it.title to (it.system ?: "?") })
                .distinct().associateWith { (title, system) -> Metagame.split(s.genresOf(title, system)) }
            return Book(
                overview = s.overview(null),
                bySystem = s.bySystem(),
                ranking = ranking,
                recent = recent,
                devices = devs,
                splits = s.bySystemPerDevice(devs),
                rates = s.rates(null, model),
                health = s.health(),
                lines = s.lines(),
                bytes = dbs.sumOf { it.length() },
                chargeNowUah = null,
                character = character,
                achievements = Achievements.Input(plays, character, completions, missionsDone.size, years).let { input ->
                    Achievements.dated(Achievements.of(input), input, missionsDone, Missions::bonuses)
                },
                plays = plays,
                years = years,
                completions = completions,
                missionsDone = missionsDone,
                feeds = feeds,
            )
        }
    }
}
