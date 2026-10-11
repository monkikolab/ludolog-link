package com.felp.ludologlink.pc

import org.sqlite.SQLiteConfig
import java.io.File

/**
 * Compartir el Companion entre consolas: que cada una tenga, al lado del suyo, el cuaderno al dia
 * de las demas. Ludolog ya esta hecha para eso (Logbooks: uno por consola, `Modelo-ID.db`; los de
 * otras se leen, nunca se escriben, y desde una copia propia), asi que basta con copiar archivos.
 *
 * De cada cuaderno manda la copia de su DUEÑO si esta conectado: es la unica que lo escribe, y
 * tambien la que cuenta si se restauro hacia atras. Si no lo esta, la mas reciente que haya en
 * las demas, por su contenido. El cuaderno propio de una consola nunca se le cambia; la consola,
 * ademas, se niega.
 *
 * Aqui solo se decide ([plan]); lo hace AppState.syncCompanions (boton "Sync companions" de la
 * barra lateral), que manda cada cuaderno con PUT /ludolog/companion (Link.putLogbook).
 */
object CompanionSync {

    /** Lo lejos que llega un cuaderno: su ultima partida, cuantas hay y cuantas misiones. */
    data class Version(val last: Long, val sessions: Int, val missions: Int) : Comparable<Version> {
        override fun compareTo(other: Version) = compareValuesBy(this, other, { it.last }, { it.sessions }, { it.missions })
    }

    fun version(db: File): Version? = runCatching {
        SQLiteConfig().apply { setReadOnly(true) }.createConnection("jdbc:sqlite:${db.path}").use { c ->
            fun long(sql: String) = runCatching {
                c.createStatement().use { s -> s.executeQuery(sql).use { r -> if (r.next()) r.getLong(1) else 0L } }
            }.getOrDefault(0L)
            Version(
                last = long("SELECT MAX(COALESCE(ended_at, started_at)) FROM sessions"),
                sessions = long("SELECT COUNT(*) FROM sessions").toInt(),
                missions = long("SELECT COUNT(*) FROM missions_done").toInt(),
            )
        }
    }.getOrNull()

    /** Una copia de un cuaderno en la copia del PC de una consola. */
    class Copy(val console: ConsoleEntry, val file: File, val version: Version)

    /** Lo que habria que mandar: a quien, que cuaderno y desde que copia. */
    class Send(val to: ConsoleEntry, val name: String, val from: Copy)

    /**
     * El plan, sobre las copias del PC ya al dia. [owners]: el nombre del cuaderno propio de cada
     * consola conectada.
     */
    fun plan(consoles: List<ConsoleEntry>, owners: Map<String, ConsoleEntry>): List<Send> {
        val copies = consoles.flatMap { e ->
            File(Mirror.dir(e.id), "companion").listFiles { f -> f.isFile && f.name.endsWith(".db") && !f.name.startsWith(".") }
                .orEmpty().mapNotNull { f -> version(f)?.let { Copy(e, f, it) } }
        }
        val out = ArrayList<Send>()
        for ((name, all) in copies.groupBy { it.file.name }) {
            val owner = owners[name]
            val best = all.firstOrNull { it.console == owner } ?: all.maxBy { it.version }
            for (d in consoles) {
                if (d == best.console || d == owner) continue
                val mine = all.firstOrNull { it.console == d }
                // Con el dueño delante, su copia manda aunque sea "menor" (un respaldo restaurado);
                // sin el, solo se manda lo que es mas nuevo.
                val stale = when {
                    mine == null -> true
                    owner != null -> mine.version != best.version
                    else -> mine.version < best.version
                }
                if (stale) out += Send(d, name, best)
            }
        }
        return out
    }
}
