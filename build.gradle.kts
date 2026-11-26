plugins {
    application
}

group = "fears"
version = "0.1.1"

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

// --------------------------------------------------------------------------------------
// Le paquet autonome
//
// jpackage ne produit que pour le système qui le fait tourner : la tâche construit le paquet
// de la machine courante, sous forme d'« app-image » — un répertoire qui porte son propre JRE
// et se lance sans rien installer.
//
// Le paquet ne contient aucune donnée du jeu : au premier lancement, il cherche les disquettes
// (ADF ou zip) et extrait ses ressources dans ~/.fears/ressources.
// --------------------------------------------------------------------------------------

val packageDir = layout.buildDirectory.dir("jpackage")
val runtimeDir = layout.buildDirectory.dir("jpackage-input")

/** Le système courant, sous la forme que LWJGL donne à ses jars de natifs. */
val nativesClassifier: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val name = when {
        os.contains("win") -> "windows"
        os.contains("mac") -> "macos"
        else -> "linux"
    }
    when {
        arch == "amd64" || arch == "x86_64" -> name
        arch == "aarch64" -> "$name-arm64"
        else -> "$name-$arch"
    }
}

/** Nom du système, tel qu'il apparaît dans le nom de l'archive publiée. */
val platform: String = nativesClassifier + if (nativesClassifier.contains("-")) "" else "-x64"

/** Le jar du jeu et ses dépendances, sans les natifs LWJGL des autres systèmes. */
val collectRuntime by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Rassemble le jar du jeu et ses dépendances pour jpackage"
    from(tasks.named("jar"))
    from(configurations.runtimeClasspath) {
        exclude { file ->
            val name = file.name
            name.contains("-natives-") && !name.endsWith("-natives-$nativesClassifier.jar")
        }
    }
    into(runtimeDir)
}

val jpackage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Construit le paquet autonome du jeu pour le système courant"
    dependsOn(collectRuntime)

    val out = packageDir.get().asFile
    val input = runtimeDir.get().asFile
    val mainJar = "fears-java-$version.jar"

    doFirst {
        out.deleteRecursively()
        out.mkdirs()
        require(File(input, mainJar).isFile) { "jar principal introuvable : $mainJar" }
    }

    val tool = javaToolchains.launcherFor(java.toolchain).get()
            .metadata.installationPath.file("bin/jpackage").asFile.absolutePath

    commandLine(
        tool,
        "--type", "app-image",
        "--name", "Fears",
        "--app-version", version,
        "--input", input.absolutePath,
        "--main-jar", mainJar,
        "--main-class", "fears.game.Main",
        "--dest", out.absolutePath,
        // Seulement les modules dont le jeu se sert : l'interface (ImageIO, la boîte de choix des
        // disquettes), la journalisation de jMonkeyEngine, les préférences, et sun.misc.Unsafe
        // dont LWJGL a besoin. Sans cette liste, jpackage embarque le JDK entier.
        "--add-modules", listOf(
            "java.base", "java.desktop", "java.logging", "java.management", "java.prefs",
            "java.xml", "jdk.unsupported",
        ).joinToString(","),
        "--java-options", "-Xss4m",
        "--vendor", "Portage Fears",
        "--description", "Fears (Manyk / Bomb Software, 1995), porté en Java",
    )
}

/** L'archive publiée : le paquet, tel quel, compressé. */
val packageZip by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Compresse le paquet autonome, prêt à publier"
    dependsOn(jpackage)
    from(packageDir)
    archiveFileName.set("fears-$version-$platform.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
}
