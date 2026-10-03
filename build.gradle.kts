plugins {
    application
    alias(libs.plugins.shadow)
}

group = "dev.shortsbot"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.telegrambots.longpolling)
    implementation(libs.telegrambots.client)
    implementation(libs.slf4j.api)
    implementation(libs.jackson.databind)
    runtimeOnly(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass = "dev.shortsbot.Main"
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveFileName = "tg-shorts-bot.jar"
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
