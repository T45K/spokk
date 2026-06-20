package io.github.spokk.compiler

import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.ExperimentalTopLevelDeclarationsGenerationApi
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.plugin.createTopLevelProperty
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/** Marks declarations synthesised by the spokk plugin (currently only the `_` matcher property). */
internal object SpokkPluginKey : GeneratedDeclarationKey()

/**
 * Registers spokk's FIR extensions. It synthesises a top-level `_` property in `io.github.spokk` so
 * that Spock's `_` "any argument" placeholder can be written literally, e.g. `mock.call(_) > "ok"`.
 *
 * `_` is a *reserved* name in Kotlin, so it cannot be **declared** in ordinary source — but a
 * plugin-generated declaration is exempt from that source-only check, and a **reference** to `_`
 * resolves to it like any other name.
 */
class SpokkFirExtensionRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +::SpokkUnderscoreMatcherExtension
    }
}

/**
 * Generates the top-level `io.github.spokk._` property (Spock's `_`). Its type is [Nothing] so it is
 * assignable to an argument of *any* type (`mock.call(_)`); every real use sits inside a spokk
 * stubbing/verification and is rewritten by the IR extension into a MockK `any()` matcher, so the
 * property's getter is never actually executed.
 */
@OptIn(ExperimentalTopLevelDeclarationsGenerationApi::class)
class SpokkUnderscoreMatcherExtension(session: FirSession) : FirDeclarationGenerationExtension(session) {

    private val underscoreId = CallableId(FqName("io.github.spokk"), Name.identifier("_"))

    override fun getTopLevelCallableIds(): Set<CallableId> = setOf(underscoreId)

    override fun generateProperties(
        callableId: CallableId,
        context: MemberGenerationContext?,
    ): List<FirPropertySymbol> {
        if (callableId != underscoreId) return emptyList()
        val property = createTopLevelProperty(
            SpokkPluginKey,
            underscoreId,
            session.builtinTypes.nothingType.coneType,
            isVal = true,
            hasBackingField = false,
        )
        return listOf(property.symbol)
    }
}
