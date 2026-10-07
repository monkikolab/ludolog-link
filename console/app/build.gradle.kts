import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// El codigo de Ludolog, en el repositorio ludolog-front-end clonado al lado de este (otra ruta con
// -PludologFrontEnd=...).
val frontEnd: File = providers.gradleProperty("ludologFrontEnd").orNull?.let(::file)
    ?: rootProject.file("../../ludolog-front-end").takeIf { it.isDirectory }
    ?: error("Clone ludolog-front-end next to ludolog-link, or set -PludologFrontEnd=<path>")

// La version, la de Ludolog (version.properties de ludolog-front-end): salen juntas. El versionCode
// sale de ella: 0.5.0 -> 500.
val appVersion: String = Properties().apply { frontEnd.resolve("version.properties").inputStream().use { load(it) } }
    .getProperty("version")
val appVersionCode: Int = appVersion.split('.').map(String::toInt).let { (a, b, c) -> a * 10000 + b * 100 + c }

// La firma, fuera del repositorio: la MISMA clave que Ludolog (si no, no se hablan: el permiso LINK
// es de firma). Se lee el keystore.properties de esta carpeta o, si no hay, el de ludolog-front-end.
// Sin ninguno, la version release sale sin firmar.
val keystoreFile = listOf(rootProject.file("keystore.properties"), frontEnd.resolve("keystore.properties")).firstOrNull { it.exists() }
val keystoreProperties = Properties().apply { keystoreFile?.inputStream()?.use { load(it) } }
val hasSigning = keystoreProperties.getProperty("storeFile") != null

// Los temas de Ludolog: se compilan los MISMOS archivos que en Ludolog, no una copia (igual que
// en el PC). Ver docs/ludolog-link.md en ludolog-front-end.
val ludologShared by tasks.registering(Sync::class) {
    from("$frontEnd/app/src/main/java/com/felp/frontcomp") {
        include("ThemeData.kt", "CrtParams.kt", "Ornaments.kt", "Pixel.kt")
    }
    into(layout.buildDirectory.dir("ludolog-src/com/felp/frontcomp"))
}
tasks.named("preBuild") { dependsOn(ludologShared) }

android {
    namespace = "com.felp.ludologlink"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.felp.ludologlink"
        minSdk = 30
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersion
    }

    if (hasSigning) {
        signingConfigs {
            create("release") {
                // La ruta del almacen, relativa al keystore.properties que la nombra.
                storeFile = keystoreFile!!.parentFile.resolve(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    // Codigo comun del kit (protocolo y aspecto): lo compila tambien Ludolog Link del PC.
    sourceSets["main"].kotlin.srcDirs(
        "../../kit/src/common/kotlin",
        "../../kit/src/compose/kotlin",
        "build/ludolog-src",
    )
    // MANAGE_EXTERNAL_STORAGE dispara avisos de lint pensados para la Play Store;
    // esta app se instala a mano.
    lint { checkReleaseBuilds = false; abortOnError = false }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
}
