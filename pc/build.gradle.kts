import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.1"
}

// Los otros dos repositorios, clonados al lado de este: ludolog-front-end (el codigo de Ludolog) y
// ludolog-assets (los temas). Se puede indicar otra ruta con -PludologFrontEnd=... y -PludologAssets=...
fun sibling(prop: String, vararg names: String): File =
    providers.gradleProperty(prop).orNull?.let(::file)
        ?: names.map { rootProject.file("../../$it") }.firstOrNull { it.isDirectory }
        ?: error("Clone ${names.first()} next to ludolog-link, or set -P$prop=<path>")
val frontEnd = sibling("ludologFrontEnd", "ludolog-front-end")
val themes = sibling("ludologAssets", "ludolog-assets").resolve("themes")

// La version del programa: la del instalador y la que ensena About (en ludolog-res/link/version.txt).
// Sale de version.properties de ludolog-front-end, la misma de Ludolog y de Link Android.
val appVersion: String = Properties().apply { frontEnd.resolve("version.properties").inputStream().use { load(it) } }
    .getProperty("version")

// Archivos de Ludolog que no tocan Android: se compilan los MISMOS, no una copia. Si un tema
// cambia de color en Ludolog, el PC lo ve en la siguiente compilacion. Se copian a build/ solo
// para poder elegirlos uno a uno sin arrastrar el resto de la carpeta.
val ludologSources = listOf(
    "ThemeData.kt", "CrtParams.kt", "Ornaments.kt",
    "LogStats.kt", "Metagame.kt", "Achievements.kt", "Missions.kt",
    "GameNames.kt", "Dossiers.kt", "GameDb.kt", "Catalog.kt", "Detect.kt", "Toml.kt", "Genres.kt", "GameId.kt",
    "CharacterTab.kt", "MetaTabs.kt", "StatsTabs.kt", "CompanionParts.kt",
    // El scraper, el mismo que en la consola: ver Scraper.kt del PC y sus imitaciones.
    "Scrape.kt", "ArtSources.kt", "ArtFree.kt", "ArtVideo.kt", "Match.kt",
)
val ludologShared by tasks.registering(Sync::class) {
    from("$frontEnd/app/src/main/java/com/felp/frontcomp") { include(ludologSources) }
    into(layout.buildDirectory.dir("ludolog-src/com/felp/frontcomp"))
}

kotlin {
    jvmToolchain(21)
    // Codigo comun del kit (protocolo, reglas de nombres): el mismo que compila la app de la consola.
    sourceSets["main"].kotlin.srcDirs(
        "../kit/src/common/kotlin",
        "../kit/src/compose/kotlin",
        layout.buildDirectory.dir("ludolog-src"),
    )
}
tasks.named("compileKotlin") { dependsOn(ludologShared) }

// Y su catalogo de consolas (el systems.toml de sus assets), el mismo archivo.
val ludologAssets by tasks.registering(Sync::class) {
    from("$frontEnd/app/src/main/assets") { include("systems.toml") }
    // El banner de About, el mismo que lleva Ludolog.
    from("$frontEnd/app/src/main/res/drawable-nodpi") { include("about_banner.png") }
    // La letra de cada tema, para verse igual sin haber bajado nada de una consola. Solo
    // tipografias con su licencia (OFL): la lamina re-dialog.png del gotico es arte de Capcom y NO va.
    from(themes) {
        include("*/font/*.ttf", "*/font/*.otf", "*/font/OFL.txt")
        into("themes")
    }
    into(layout.buildDirectory.dir("ludolog-res/ludolog"))
}
val linkVersion by tasks.registering {
    val v = appVersion
    val out = layout.buildDirectory.file("ludolog-res/link/version.txt")
    inputs.property("version", v)
    outputs.file(out)
    doLast { out.get().asFile.run { parentFile.mkdirs(); writeText(v) } }
}
sourceSets["main"].resources.srcDir(layout.buildDirectory.dir("ludolog-res"))
tasks.named("processResources") { dependsOn(ludologAssets, linkVersion) }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.json:json:20250107")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("net.java.dev.jna:jna:5.17.0")
    implementation("io.github.vinceglb:filekit-dialogs:0.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.1")
    // FFmpeg (compilacion LGPL de bytedeco) para convertir videos del PC a lo que pide Ludolog.
    // Solo FFmpeg y la parte de Windows: JavaCV sin sus dependencias (OpenCV y demas no hacen falta).
    implementation("org.bytedeco:javacv:1.5.14") { isTransitive = false }
    implementation("org.bytedeco:javacpp:1.5.14")
    implementation("org.bytedeco:javacpp:1.5.14:windows-x86_64")
    implementation("org.bytedeco:ffmpeg:8.1.2-1.5.14")
    implementation("org.bytedeco:ffmpeg:8.1.2-1.5.14:windows-x86_64")
}

compose.desktop {
    application {
        mainClass = "com.felp.ludologlink.pc.MainKt"
        // El banner con su barra mientras arranca Java (unos 2 s sin ventana): lo pinta el lanzador
        // antes que nada, y se va solo al abrirse la ventana. Ver resources/windows/splash.gif.
        jvmArgs += listOf("-Dsun.net.http.retryPost=false", "-splash:\$APPDIR/resources/splash.gif")
        nativeDistributions {
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            targetFormats(TargetFormat.Msi)
            packageName = "Ludolog Link"
            packageVersion = appVersion
            description = "Ludolog Link: your handhelds' games, saves, Companion and Ludolog settings, from the PC"
            vendor = "monkikolab"
            modules("java.instrument", "jdk.unsupported", "java.sql")
            windows {
                iconFile.set(project.file("icon.ico"))
                menuGroup = "Ludolog"
                // Para quien lo use: sin pedir permisos de administrador, con acceso en el menu Inicio y
                // en el escritorio. El mismo upgradeUuid SIEMPRE: es lo que hace que una version nueva
                // reemplace a la anterior en vez de instalarse al lado. No cambiarlo nunca.
                perUserInstall = true
                shortcut = true
                menu = true
                upgradeUuid = "71be5483-0abe-407a-a5a5-9f89eb867c09"
            }
        }
        buildTypes.release.proguard { isEnabled.set(false) }
    }
}

// ------------------------------------------------------------------ arranque rapido y entrega
//
// Java puede guardar en un archivo las clases ya cargadas y, al arrancar, mapearlas en vez de
// cargarlas (CDS): la ventana sale en ~1,4 s en vez de ~2,2 (medido el 07-10-2026). Son dos archivos:
//  - el de Java, runtime/bin/server/classes.jsa: el runtime recortado no lo trae y se genera aqui;
//  - el de la app, ludolog-link.jsa en la carpeta de la app: lo crea la propia app en su PRIMER
//    arranque en cada PC (-XX:+AutoCreateSharedArchive). Ese arranque tarda unos 7 s (ver el orden
//    de los jars abajo); tambien el primero tras actualizar, porque los jars cambian. No se puede
//    generar aqui: Java exige que los jars sigan en la ruta exacta donde se grabo, y en otro PC no estan.
// El instalador lo borra al desinstalar (msi/main.wxs). Si la carpeta no se puede escribir (un
// portable en Program Files), la app arranca igual pero en ~4,6 s: Java lo intenta en cada arranque.
//
// La entrega: `buildRelease` hace el instalador (.msi, con jpackage sobre la imagen y la plantilla de
// msi/) y el .zip portable. No packageMsi: arma su propia imagen sin estos cambios.
val cdsJdk: Provider<File> = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    .map { it.metadata.installationPath.asFile }
val appImage: Provider<Directory> = layout.buildDirectory.dir("compose/binaries/main/app/Ludolog Link")

afterEvaluate {
    tasks.named("createRuntimeImage") {
        val runtime = layout.buildDirectory.dir("compose/tmp/main/runtime")
        doLast {
            // El runtime no trae el lanzador java.exe: se pone uno del mismo JDK solo para esto.
            val bin = runtime.get().asFile.resolve("bin")
            val launcher = bin.resolve("java.exe")
            cdsJdk.get().resolve("bin/java.exe").copyTo(launcher, overwrite = true)
            try {
                val p = ProcessBuilder(launcher.path, "-Xshare:dump").redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                check(p.waitFor() == 0) { "-Xshare:dump failed:\n$out" }
            } finally {
                launcher.delete()
            }
        }
    }
    // Solo en la imagen que se entrega, no en `run`: alli no hay carpeta de la app donde guardarlo.
    tasks.named("createDistributable") {
        doLast {
            val cfg = appImage.get().asFile.resolve("app/Ludolog Link.cfg")
            var lines = cfg.readLines().filterNot { "SharedArchiveFile" in it || "AutoCreateSharedArchive" in it }
            // Los jars, de mas a menos clases. Al grabar el archivo, Java busca en que jar de la lista
            // esta cada clase comparandolo con los anteriores, y en Windows cada comparacion abre los
            // dos archivos: con los jars grandes al final, ese primer arranque tardaba ~30 s. El orden
            // no cambia que clase se carga mientras ninguna este en dos jars, y eso se comprueba.
            val owner = HashMap<String, String>()
            fun classes(line: String): Int {
                val jar = line.substringAfterLast('\\')
                return ZipFile(cfg.resolveSibling(jar)).use { z ->
                    z.entries().asSequence().map { it.name }
                        .filter { it.endsWith(".class") && !it.startsWith("META-INF/") && it != "module-info.class" }
                        .onEach { name -> owner.put(name, jar)?.let { error("$name is in $it and $jar: the jar order matters") } }
                        .count()
                }
            }
            val jars = lines.filter { it.startsWith("app.classpath=") }
            val bySize = jars.associateWith(::classes)
            val sorted = jars.sortedByDescending { bySize.getValue(it) }.iterator()
            lines = lines.map { if (it.startsWith("app.classpath=")) sorted.next() else it }
            cfg.writeText((lines + listOf(
                "java-options=-XX:SharedArchiveFile=\$ROOTDIR/ludolog-link.jsa",
                "java-options=-XX:+AutoCreateSharedArchive",
            )).joinToString("\n", postfix = "\n"))
        }
    }
}

val releaseDir: Provider<Directory> = layout.buildDirectory.dir("release")

val buildInstaller by tasks.registering(Exec::class) {
    dependsOn("createDistributable", "unzipWix")
    val wix = layout.buildDirectory.dir("wix311")
    doFirst {
        releaseDir.get().asFile.mkdirs()
        releaseDir.get().asFile.listFiles { f -> f.extension == "msi" }?.forEach { it.delete() }
        environment("PATH", wix.get().asFile.path + File.pathSeparator + System.getenv("PATH"))
    }
    executable(cdsJdk.get().resolve("bin/jpackage.exe"))
    args(
        "--type", "msi", "--app-image", appImage.get().asFile.path, "--dest", releaseDir.get().asFile.path,
        "--resource-dir", project.file("msi").path,
        "--name", "Ludolog Link", "--app-version", appVersion, "--vendor", "monkikolab",
        "--description", "Ludolog Link: your handhelds' games, saves, Companion and Ludolog settings, from the PC",
        "--win-per-user-install", "--win-menu", "--win-menu-group", "Ludolog", "--win-shortcut",
        // El asistente con sus ventanas (ver msi/main.wxs). Sin esto no hay ninguna.
        "--win-dir-chooser",
        // El mismo que packageMsi (ver nativeDistributions): una version nueva reemplaza a la anterior.
        "--win-upgrade-uuid", "71be5483-0abe-407a-a5a5-9f89eb867c09",
    )
}

val buildPortable by tasks.registering(Zip::class) {
    dependsOn("createDistributable")
    from(appImage) { into("Ludolog Link") }
    // La marca de portable: con ella guarda todo en data\ a su lado y no en %APPDATA% (ver PcDirs).
    from(project.file("portable")) { into("Ludolog Link") }
    destinationDirectory.set(releaseDir)
    archiveFileName.set("ludolog-link-pc-$appVersion-portable.zip")
}

tasks.register("buildRelease") {
    dependsOn(buildInstaller, buildPortable)
    doLast { println("Listo en ${releaseDir.get().asFile}") }
}
