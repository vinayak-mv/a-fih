import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("net.java.dev.jna:jna-platform:5.17.0")
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "app.afih.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "a-Fih"
            packageVersion = "0.1.0"
            description = "Personal Telegram media library"
            vendor = "a-Fih"
            modules("java.desktop", "java.logging", "jdk.unsupported")
            windows {
                menuGroup = "a-Fih"
                upgradeUuid = "ce60ba32-8c4e-49ed-ad4d-282197e80183"
            }
        }
    }
}
