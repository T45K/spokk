package io.github.spokk.compiler

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.getPackageFragment
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Package that hosts the `given`/`when`/`then`/`expect`/`and` markers.
 */
private const val SPOKK_PACKAGE = "io.github.spokk"

/** Markers that *start* an assertion block: every following bare boolean statement is asserted. */
private val ASSERTION_OPENERS = setOf("then", "expect")

/** Markers that *close* an assertion block. */
private val ASSERTION_CLOSERS = setOf("given", "setup", "when")

/** Continuation marker (Spock's `and:`): keeps the kind of the previous block. */
private const val CONTINUATION = "and"

private val ALL_MARKERS = ASSERTION_OPENERS + ASSERTION_CLOSERS + setOf(CONTINUATION)

/**
 * Rewrites the bodies of Spokk specifications so that every bare boolean expression located after a
 * `then` (or `expect`) marker becomes a `kotlin.assert(...)` call. The official Power-assert plugin
 * (registered right after this extension) then renders the diagram on failure.
 *
 * Following Spock's semantics, only the *direct* statements of the block that opened the assertion
 * are rewritten. A bare `==` (or any boolean expression) nested inside a closure/lambda is **not**
 * asserted automatically, because a closure body is a separate block whose own assertion state starts
 * closed; to assert inside a closure you have to write an explicit `assert`.
 */
class SpokkIrGenerationExtension : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        moduleFragment.transform(SpokkAssertionTransformer(pluginContext), null)
    }
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private class SpokkAssertionTransformer(
    private val pluginContext: IrPluginContext,
) : IrElementTransformerVoidWithContext() {

    @Suppress("DEPRECATION")
    private val assertSymbol = pluginContext
        .referenceFunctions(CallableId(FqName("kotlin"), Name.identifier("assert")))
        .first { symbol ->
            val parameters = symbol.owner.parameters
            parameters.size == 1 && parameters.single().type == pluginContext.irBuiltIns.booleanType
        }

    override fun visitBlockBody(body: IrBlockBody): IrBody {
        val transformed = super.visitBlockBody(body)
        if (transformed is IrBlockBody) {
            rewriteAssertions(transformed)
        }
        return transformed
    }

    private fun rewriteAssertions(body: IrBlockBody) {
        // `asserting` is intentionally local to each block body. A closure/lambda has its own block
        // body, so it always starts with `asserting == false`: bare booleans inside a closure are
        // never asserted automatically (Spock semantics), unless an explicit `assert` is written.
        var asserting = false
        val statements = body.statements
        for (index in statements.indices) {
            val statement = statements[index]

            val marker = statement.markerName()
            if (marker != null) {
                when (marker) {
                    in ASSERTION_OPENERS -> asserting = true
                    in ASSERTION_CLOSERS -> asserting = false
                    else -> Unit // CONTINUATION ("and"): keep the current block kind
                }
                continue
            }

            if (asserting) {
                val condition = statement.unwrapImplicitCoercionToUnit()
                if (condition != null && condition.type == pluginContext.irBuiltIns.booleanType) {
                    statements[index] = wrapInAssert(condition)
                }
            }
        }
    }

    private fun wrapInAssert(condition: IrExpression): IrStatement {
        val scopeSymbol = currentScope!!.scope.scopeOwnerSymbol
        val builder = DeclarationIrBuilder(pluginContext, scopeSymbol, condition.startOffset, condition.endOffset)
        return builder.irCall(assertSymbol).apply {
            arguments[0] = condition
        }
    }

    /** Returns the marker name (`given`/`when`/`then`/`expect`/`and`) if this statement is a Spokk marker. */
    private fun IrStatement.markerName(): String? {
        val call = this as? IrCall ?: return null
        val getter = call.symbol.owner

        // The property name: prefer the corresponding property, but fall back to parsing the accessor
        // name (`<get-then>` -> `then`) because cross-module (deserialized) symbols may not link the
        // corresponding property.
        val name = getter.correspondingPropertySymbol?.owner?.name?.asString()
            ?: getter.name.asString().let { accessor ->
                accessor.removeSurrounding("<get-", ">").takeIf { it != accessor }
            }
            ?: return null

        if (name !in ALL_MARKERS) return null
        return name.takeIf { getter.getPackageFragment().packageFqName.asString() == SPOKK_PACKAGE }
    }

    /**
     * A bare boolean expression used as a statement is wrapped by the compiler in an
     * `IMPLICIT_COERCION_TO_UNIT` type-operator call. This unwraps it so the real (boolean) condition
     * can be asserted.
     */
    private fun IrStatement.unwrapImplicitCoercionToUnit(): IrExpression? = when (this) {
        is IrTypeOperatorCall ->
            if (operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT) argument else this
        is IrExpression -> this
        else -> null
    }
}
