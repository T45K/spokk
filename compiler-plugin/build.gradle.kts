plugins {
    // The compiler plugin itself is a plain Kotlin/JVM library.
    alias(libs.plugins.kotlin.jvm)
}

repositories {
    mavenCentral()
}

dependencies {
    // The Kotlin compiler API (K2 / IR). Provided by the compiler at runtime, so `compileOnly`.
    compileOnly(libs.kotlin.compiler.embeddable)

    // Reuse the official Power-assert IR transformer to render the diagrams.
    // Also `compileOnly`: it is put on the compiler plugin classpath by the consuming module.
    compileOnly(libs.kotlin.power.assert.compiler.plugin.embeddable)
}

// The plugin jar is loaded inside the Kotlin compiler daemon, which runs on the same toolchain as
// the consuming `:lib` module, so the default toolchain JVM target is appropriate.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}
