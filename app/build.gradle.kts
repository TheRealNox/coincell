import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---- optional Pokemon sprite pack (pkassets/) -------------------------------------------------------------------
// The pack is Nintendo artwork, not GPL-3.0, and stays optional (pkassets/README.md). It is wired in
// only when the directory exists: the task below copies the sprites the editor uses plus a slim lookup index
// (sprites/index.json) into generated assets. Without pkassets/ nothing is added and the app shows text and initials.
val pkassetsAssets = rootProject.file("pkassets/src/main/assets")
val pkassetsManifest = rootProject.file("pkassets/manifest.json")
val hasPkAssets = pkassetsAssets.isDirectory && pkassetsManifest.isFile

abstract class PreparePokemonSprites : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val spritesDir: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        @Suppress("UNCHECKED_CAST")
        val root = JsonSlurper().parse(manifest.get().asFile, "UTF-8") as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val entries = root["entries"] as Map<String, Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val maps = root["itemIdMaps"] as Map<String, Any?>
        // Only what the editor can show: species 1-386 (Generations 1-3) with their shiny and form files, and the items
        // that the Gen 1 / 2 / 3 id tables map to (normal art and the outline art used on dark backgrounds).
        val itemIds = listOf("gen1BagByName", "gen2", "gen3")
            .flatMap { (maps[it] as? List<*>).orEmpty() }
            .mapNotNull { (it as? Number)?.toInt() }
            .filter { it > 0 }
            .toSet()
        val slim = sortedMapOf<String, String>()
        val src = spritesDir.get().asFile
        for ((key, v) in entries) {
            val path = v["path"] as? String ?: continue
            val keep = when {
                key.startsWith("pokemon/box/") || key.startsWith("pokemon/box_shiny/") -> {
                    val dex = (v["dex"] as? Number)?.toInt()
                    (dex != null && dex in 1..386) || key.endsWith("/egg") || key.endsWith("/unknown")
                }
                key.startsWith("item/") || key.startsWith("item_outline/") -> key.substringAfter('/').toIntOrNull() in itemIds
                else -> false
            }
            val from = File(src, path)
            if (!keep || !from.isFile) continue
            val to = File(out, path)
            if (!to.exists()) {
                to.parentFile.mkdirs()
                from.copyTo(to)
            }
            slim[key] = path
        }
        val index = mapOf(
            "version" to 1,
            "itemMaps" to mapOf("gen1" to maps["gen1BagByName"], "gen2" to maps["gen2"], "gen3" to maps["gen3"]),
            "entries" to slim,
        )
        File(out, "sprites/index.json").writeText(JsonOutput.toJson(index), Charsets.UTF_8)
    }
}

val preparePokemonSprites = if (hasPkAssets) {
    tasks.register<PreparePokemonSprites>("preparePokemonSprites") {
        spritesDir.set(pkassetsAssets)
        manifest.set(pkassetsManifest)
        outputDir.set(layout.buildDirectory.dir("generated/pkassets"))
    }
} else {
    null
}

// ---- release signing ----------------------------------------------------------------------------------------------
// key.properties (git-ignored, repo root) points at the keystore outside the repo. Without it the release build
// stays unsigned and cannot be installed.
val keyProperties = Properties().apply {
    val f = rootProject.file("key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "org.noxeng.coincell"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "org.noxeng.coincell"
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        versionName = "0.4.0"
    }

    signingConfigs {
        if (keyProperties.isNotEmpty()) {
            create("release") {
                storeFile = file(keyProperties.getProperty("storeFile"))
                storePassword = keyProperties.getProperty("storePassword")
                keyAlias = keyProperties.getProperty("keyAlias")
                keyPassword = keyProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Installs next to the release build ("Coincell Dev"), so development never touches the real library.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // The GBA database asset is also read by the JVM unit tests.
    sourceSets["test"].resources.srcDir("src/main/assets")
}

androidComponents {
    onVariants { variant ->
        preparePokemonSprites?.let { variant.sources.assets?.addGeneratedSourceDirectory(it, PreparePokemonSprites::outputDir) }
    }
}

// The tests that use the user's own saves (pkcore/src/test/fixtures-local, git-ignored) skip themselves when the files
// are absent; making them a task input re-runs the tests when a fixture is added or removed instead of replaying a cache.
tasks.withType<Test>().configureEach {
    inputs.files(fileTree(rootProject.file("pkcore/src/test/fixtures-local"))).withPropertyName("pokemonLocalFixtures").optional()
}

dependencies {
    implementation(project(":pkcore"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.usb.serial)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.orgjson)
    testImplementation(libs.kotlinx.coroutines.test)
}
