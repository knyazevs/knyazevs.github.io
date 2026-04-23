plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

group = "dev.knyazev"
version = "1.0.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)

    jvm {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
                }
            }
        }
    }

    linuxX64 {
        binaries {
            executable {
                entryPoint = "dev.knyazev.main"
                baseName = "knyazevs-server"
            }
        }
    }

    // Локальный dev-таргет для Apple Silicon: прод так же живёт на K/N (linuxX64),
    // но linuxX64-бинарь не исполняется на macOS — поэтому для разработки
    // на Mac держим параллельный нативный таргет.
    macosArm64 {
        binaries {
            executable {
                entryPoint = "dev.knyazev.main"
                baseName = "knyazevs-server"
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.bundles.ktor.server.common)
            implementation(libs.bundles.ktor.client.common)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.atomicfu)
            implementation(libs.okio)
            implementation(libs.kotlin.logging)
        }

        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.server.call.logging)
            // SLF4J binding — kotlin-logging delegates to SLF4J on JVM
            implementation(libs.logback.classic)
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        val linuxX64Main by getting {
            dependencies {
                implementation(libs.ktor.client.curl.linuxx64)
            }
        }

        val macosArm64Main by getting {
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
    }
}

// Fat JAR for JVM target (replaces shadow plugin — works cleanly with KMP)
tasks.register<Jar>("jvmFatJar") {
    group = "build"
    description = "Assembles a fat JAR for the JVM target (used by Dockerfile)."
    archiveBaseName.set("knyazevs-server")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "dev.knyazev.BootstrapKt"
    }
    val jvmJar = tasks.named<Jar>("jvmJar")
    dependsOn(jvmJar)
    from(jvmJar.map { zipTree(it.archiveFile) })
    from({
        configurations.named("jvmRuntimeClasspath").get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
}

// Alias so existing `gradle shadowJar` command still works in Dockerfile
tasks.register("shadowJar") {
    group = "build"
    description = "Alias for jvmFatJar — preserves existing Dockerfile build command."
    dependsOn("jvmFatJar")
}

// ─── Автозагрузка server/.env для локальных run-тасков ────────────────────────
// Ktor читает $VAR из окружения процесса (на K/N — getenv), а gradlew сам
// .env не грузит. Поэтому перед стартом K/N run-тасков парсим server/.env
// и докидываем пары в Exec.environment. Явно экспортированные переменные
// шелла имеют приоритет над .env — это обычная dotenv-семантика.
fun parseDotenv(file: java.io.File): Map<String, String> {
    if (!file.exists()) return emptyMap()
    return file.readLines()
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key = line.substring(0, idx).trim()
            var value = line.substring(idx + 1).trim()
            // Снимаем парные кавычки, если есть
            val quoted = value.length >= 2 && (
                (value.startsWith("\"") && value.endsWith("\"")) ||
                (value.startsWith("'") && value.endsWith("'"))
            )
            if (quoted) value = value.substring(1, value.length - 1)
            key to value
        }
        .toMap()
}

val dotenvFile = projectDir.resolve(".env")

tasks.withType<Exec>().configureEach {
    val isNativeRun = name.startsWith("runReleaseExecutable") ||
                      name.startsWith("runDebugExecutable")
    if (!isNativeRun) return@configureEach
    doFirst {
        val vars = parseDotenv(dotenvFile)
        if (vars.isEmpty()) return@doFirst
        val applied = vars.filter { (k, _) -> System.getenv(k).isNullOrEmpty() }
        applied.forEach { (k, v) -> environment(k, v) }
        logger.lifecycle(
            "[dotenv] loaded ${applied.size}/${vars.size} keys from ${dotenvFile.name} " +
                "(${vars.size - applied.size} already in shell env)"
        )
    }
}

