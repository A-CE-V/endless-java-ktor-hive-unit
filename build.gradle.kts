import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm") version "2.0.20"
    id("io.ktor.plugin") version "2.3.12"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "org.endless"
version = "1.0.0"

application {
    mainClass.set("org.endless.MainKt")
}

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenLocal()
    mavenCentral()
    // google() removed — jadx-dex-input (Android DEX support) is not needed
    // for Java JAR decompilation and pulls aapt2-proto / smali off Google Maven
}

dependencies {
    // ── Ktor ──────────────────────────────────────────────────────────────────
    implementation("io.ktor:ktor-server-core-jvm:2.3.12")
    implementation("io.ktor:ktor-server-netty-jvm:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:2.3.12")
    implementation("io.ktor:ktor-serialization-jackson-jvm:2.3.12")
    implementation("io.ktor:ktor-server-status-pages-jvm:2.3.12")
    implementation("io.ktor:ktor-server-double-receive:2.3.12")

    // ── Coroutines ────────────────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // ── Logging ───────────────────────────────────────────────────────────────
    implementation("ch.qos.logback:logback-classic:1.5.18")

    // ── Utils ─────────────────────────────────────────────────────────────────
    implementation("commons-io:commons-io:2.20.0")

    // ── Decompilers ───────────────────────────────────────────────────────────
    implementation("org.benf:cfr:0.152")

    // JADX: java-input only — dex-input removed (Android-only, pulls deps off Google Maven)
    implementation("io.github.skylot:jadx-core:1.5.3")
    implementation("io.github.skylot:jadx-java-input:1.5.3")

    implementation("org.vineflower:vineflower:1.11.2")

    // FIX: procyon-compilertools (not procyon-decompiler — that artifact doesn't exist on Maven Central)
    implementation("org.bitbucket.mstrobel:procyon-compilertools:0.6.0")

    // FIX: nbauma109 fork (not org.jd:jd-core — JdCoreAdapter uses the nbauma109 API)
    implementation("io.github.nbauma109:jd-core:1.3.3")

    // ── Test ──────────────────────────────────────────────────────────────────
    testImplementation("io.ktor:ktor-server-test-host:2.3.12")
    testImplementation(kotlin("test"))
}

tasks.withType<ShadowJar> {
    archiveClassifier.set("")
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
