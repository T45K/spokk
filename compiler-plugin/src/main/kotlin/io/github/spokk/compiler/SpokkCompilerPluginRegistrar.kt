package io.github.spokk.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.powerassert.PowerAssertConfiguration
import org.jetbrains.kotlin.powerassert.PowerAssertIrGenerationExtension

/**
 * Entry point of the Spokk compiler plugin.
 *
 * It registers two IR extensions, in this exact order:
 *  1. [SpokkIrGenerationExtension] which rewrites bare boolean expressions located after a `then`
 *     (or `expect`) marker into `kotlin.assert(...)` calls.
 *  2. The official Power-assert [PowerAssertIrGenerationExtension] configured for `kotlin.assert`,
 *     so the calls synthesized in step 1 get the rich "power-assert" failure diagrams.
 *
 * Registering Power-assert here (instead of relying on the standalone Gradle plugin) guarantees the
 * ordering: our transform always runs first, Power-assert second.
 */
@OptIn(ExperimentalCompilerApi::class)
class SpokkCompilerPluginRegistrar : CompilerPluginRegistrar() {

    override val pluginId: String = "io.github.spokk"

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        // FIR: synthesise the `_` matcher property so Spock's `_` placeholder can be written.
        FirExtensionRegistrarAdapter.registerExtension(SpokkFirExtensionRegistrar())

        IrGenerationExtension.registerExtension(SpokkIrGenerationExtension())
        IrGenerationExtension.registerExtension(
            PowerAssertIrGenerationExtension(
                PowerAssertConfiguration(setOf(FqName("kotlin.assert"))),
            ),
        )
    }
}
