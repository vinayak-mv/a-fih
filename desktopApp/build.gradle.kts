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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("uk.co.caprica:vlcj:4.8.3")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.register<JavaExec>("playerSmoke") {
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.afih.desktop.playback.PlayerSmoke")
    workingDir(rootProject.projectDir)
}

compose.desktop {
    application {
        mainClass = "app.afih.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "a-Fih"
            packageVersion = "0.4.0"
            appResourcesRootDir.set(rootProject.layout.projectDirectory.dir("native"))
            description = "Personal Telegram media library"
            vendor = "a-Fih"
            modules("java.desktop", "java.logging", "java.sql", "java.management", "java.net.http", "jdk.httpserver", "jdk.crypto.ec", "jdk.unsupported")
            windows {
                menuGroup = "a-Fih"
                upgradeUuid = "ce60ba32-8c4e-49ed-ad4d-282197e80183"
            }
        }
    }
}
