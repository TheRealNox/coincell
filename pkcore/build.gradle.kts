// :pkcore - pure Kotlin/JVM port of the PKHeX Gen 1/2 save-editing core. No Android dependencies.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // No version: the Kotlin Gradle plugin is already on the build classpath (AGP 9 built-in Kotlin).
    id("org.jetbrains.kotlin.jvm")
}

// Same bytecode level as :app so the module can be consumed from it later.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    // Tests that need the user's personal saves (pkcore/src/test/fixtures-local) skip themselves when absent.
    useJUnit()
    // The tests read the (git-ignored) personal saves straight from disk: make them an input so that adding or
    // removing a fixture re-runs the tests instead of replaying a cached result.
    inputs.files(fileTree("src/test/fixtures-local")).withPropertyName("localFixtures").optional()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
