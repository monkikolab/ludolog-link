package com.felp.ludologlink.pc

import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Diagnostico contra una consola real: "Ludolog Link.exe" --selftest <host> <token>
 * Trabaja solo con archivos "romlink-selftest*" que crea y borra el mismo.
 * Usar con la consola apuntando a una carpeta de pruebas.
 */
fun selfTest(host: String, token: String) {
    val l = Link(host, com.felp.ludolog.kit.Protocol.HTTP_PORT, token)
    var fails = 0
    fun check(what: String, ok: Boolean, detail: String = "") {
        println((if (ok) "PASS " else "FAIL ") + what + if (detail.isNotEmpty()) " — $detail" else "")
        if (!ok) fails++
    }
    fun md5(f: File) = MessageDigest.getInstance("MD5").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
    fun err(block: () -> Unit): LinkError? = try { block(); null } catch (e: LinkError) { e }

    val tmp = File(System.getProperty("java.io.tmpdir"), "romlink-selftest").apply { deleteRecursively(); mkdirs() }
    val src = File(tmp, "src.chd").apply { writeBytes(Random(7).nextBytes(5 * 1024 * 1024 + 123)) }
    val sbi = File(tmp, "src.sbi").apply { writeBytes(Random(8).nextBytes(2048)) }

    val info = l.info()
    check("info", info.romsRoot != null, "${info.name} · ${info.romsRoot}")
    val systems = l.systems()
    check("systems", systems.any { it.folder == "psx" && ".chd" in it.exts }, "${systems.size} folders")
    check("roms", l.roms().isNotEmpty())

    var done = 0L
    l.upload(src, "psx", "romlink-selftest.chd", false, { done = it }, { false })
    check("upload", l.roms().any { it.key == "psx/romlink-selftest.chd" && it.size == src.length() } && done == src.length())
    l.upload(sbi, "psx", "romlink-selftest.sbi", false, {}, { false })

    val dup = err { l.upload(src, "psx", "romlink-selftest.chd", false, {}, { false }) }
    check("duplicate rejected with reason", dup?.status == 409, dup?.message ?: "no error")
    val huge = err { l.check("psx", "romlink-selftest-huge.chd", 1L shl 50, false) }
    check("doesn't fit, with reason", huge?.status == 507, huge?.message ?: "no error")
    check("overwrite", err { l.upload(src, "psx", "romlink-selftest.chd", true, {}, { false }) } == null)

    val dest = File(tmp, "bajado.chd")
    l.download("psx", "romlink-selftest.chd", dest, { _, _ -> }, { false })
    check("download (md5)", md5(dest) == md5(src))
    // Retomar: un .part con el primer mega y que siga desde ahi.
    val dest2 = File(tmp, "retomado.chd")
    File(dest2.path + ".part").writeBytes(src.readBytes().copyOf(1024 * 1024))
    File(dest2.path + ".part.id").writeText("psx/romlink-selftest.chd")   // de este mismo archivo
    var first = -1L
    l.download("psx", "romlink-selftest.chd", dest2, { d, _ -> if (first < 0) first = d }, { false })
    check("resume download", md5(dest2) == md5(src) && first > 1024 * 1024, "first progress at $first")
    // Un .part de OTRO archivo no se retoma: se tira y se baja entero.
    val dest3 = File(tmp, "ajeno.chd")
    File(dest3.path + ".part").writeBytes(Random(9).nextBytes(1024 * 1024))
    File(dest3.path + ".part.id").writeText("psx/otro.chd")
    l.download("psx", "romlink-selftest.chd", dest3, { _, _ -> }, { false })
    check("foreign .part not resumed", md5(dest3) == md5(src))
    // Un archivo que no esta (un disco que se saco): error local, y nada vacio en la consola.
    val missing = err { l.upload(File(tmp, "no-esta.chd"), "psx", "romlink-selftest-missing.chd", true, {}, { false }) }
    check("missing local file = local error", missing?.status == LinkError.LOCAL && l.roms().none { it.name.startsWith("romlink-selftest-missing") },
        missing?.message ?: "no error")
    // Cancelar una subida a medias: la consola no debe quedarse el archivo.
    var chunks = 0
    val c = err { l.upload(src, "psx", "romlink-selftest-cancel.chd", false, {}, { ++chunks > 2 }) }
    Thread.sleep(500)   // la consola limpia al notar el corte
    check("cancel upload", c != null && l.roms().none { it.name.startsWith("romlink-selftest-cancel") })

    val renamed = l.rename("psx", "romlink-selftest.chd", "romlink-selftest renombrado.chd")
    check("rename (with its .sbi)", renamed.size == 2, renamed.joinToString())
    val bad = err { l.rename("psx", "romlink-selftest renombrado.chd", "mal:nombre.chd") }
    check("invalid name rejected", bad?.status == 400, bad?.message ?: "no error")
    val case = l.rename("psx", "romlink-selftest renombrado.chd", "ROMLINK-selftest renombrado.chd")
    check("rename case only", case.size == 2, case.joinToString())

    val attack = err { l.delete("psx", "../gba/Golden Sun (USA).gba") }
    check("path with .. rejected", attack != null && l.roms().any { it.name == "Golden Sun (USA).gba" }, attack?.message ?: "")
    val deleted = l.delete("psx", "ROMLINK-selftest renombrado.chd")
    check("delete (with its .sbi)", deleted.size == 2 && l.roms().none { it.name.contains("selftest", true) }, deleted.joinToString())
    val gone = err { l.delete("psx", "romlink-selftest.chd") }
    check("delete missing file = 404", gone?.status == 404)

    // Multidisco: al quedar vacia, la subcarpeta se va; la del sistema, nunca.
    l.upload(src, "psx", "romlink-selftest dir/disc1.chd", false, {}, { false })
    l.delete("psx", "romlink-selftest dir/disc1.chd")
    check("empty subfolder removed", l.systems().any { it.folder == "psx" } &&
        l.roms().none { it.name.startsWith("romlink-selftest dir") })

    val noAuth = err { Link(host, l.port, "x".repeat(48)).roms() }
    check("no token = 401", noAuth?.status == 401)

    tmp.deleteRecursively()
    println(if (fails == 0) "ALL OK" else "$fails FAILURES")
}
