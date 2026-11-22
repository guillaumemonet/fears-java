plugins {
    application
}

group = "fears"
version = "0.1.0"

val jmeVersion = "3.9.0-stable"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation("org.jmonkeyengine:jme3-core:$jmeVersion")
    implementation("org.jmonkeyengine:jme3-desktop:$jmeVersion")
    runtimeOnly("org.jmonkeyengine:jme3-lwjgl3:$jmeVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Les tests qui lisent les ressources extraites les cherchent la ou le jeu les range.
    System.getProperties().forEach { key, value ->
        if (key.toString().startsWith("fears.")) {
            systemProperty(key.toString(), value)
        }
    }
}

application {
    mainClass.set("fears.game.Main")
}

tasks.named<JavaExec>("run") {
    workingDir = projectDir
    // gradle run -Dfears.adf=<repertoire des disquettes>, -Dfears.ressources=<cache>…
    System.getProperties().forEach { key, value ->
        if (key.toString().startsWith("fears.")) {
            systemProperty(key.toString(), value)
        }
    }
}

/** L'extracteur seul : gradle extract --args="<sortie> <disk1.adf> <disk2.adf>". */
tasks.register<JavaExec>("extract") {
    group = "application"
    description = "Extrait les ressources des disquettes d'origine"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("fears.extract.ExtractAll")
    workingDir = projectDir
}
