import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm") version "1.9.23"
    id("io.ktor.plugin") version "2.3.10"
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
    mavenCentral()
}

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-netty:2.3.10")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.10")
    implementation("io.ktor:ktor-server-status-pages:2.3.10")
    implementation("io.ktor:ktor-server-double-receive:2.3.10")
    implementation("io.ktor:ktor-serialization-jackson:2.3.10")

    // Kotlin coroutines (explicit for withTimeout)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Decompilers
    implementation("org.benf:cfr:0.152")
    implementation("io.github.skylot:jadx-core:1.5.0")
    implementation("io.github.skylot:jadx-dex-input:1.5.0")
    implementation("io.github.skylot:jadx-java-input:1.5.0")
    implementation("org.vineflower:vineflower:1.11.2")           // BUG FIX: removed duplicate 1.10.1
    implementation("org.bitbucket.mstrobel:procyon-decompiler:0.6.0")
    implementation("org.jd:jd-core:1.1.3")

    // Test
    testImplementation("io.ktor:ktor-server-test-host:2.3.10")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.23")
}

tasks.withType<ShadowJar> {
    archiveClassifier.set("")
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
