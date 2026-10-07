plugins {
    id("com.android.application") version "9.3.0" apply false
    // Desde AGP 9 el soporte de Kotlin viene integrado: ya no se aplica kotlin.android.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
