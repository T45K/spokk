package io.github.spokk.compiler

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.isSubtypeOfClass
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.getPackageFragment
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

/**
 * Package that hosts the `given`/`when`/`then`/`expect`/`and` markers.
 */
private const val SPOKK_PACKAGE = "io.github.spokk"

/** Package of the wrapped MockK runtime; the closure-free stubbing rewrite targets these symbols. */
private val MOCKK_FQ_NAME = FqName("io.mockk")

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
@Suppress("DEPRECATION")
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

    // --- MockK symbols used by the closure-free stubbing rewrite ----------------------------------
    // Every symbol is resolved leniently (nullable): when MockK is not on the compilation classpath
    // (e.g. a module that only uses the markers) the stub rewrite simply never fires.

    private val mockKMatcherScopeSymbol =
        pluginContext.referenceClass(ClassId(MOCKK_FQ_NAME, Name.identifier("MockKMatcherScope")))

    private val mockKStubScopeSymbol =
        pluginContext.referenceClass(ClassId(MOCKK_FQ_NAME, Name.identifier("MockKStubScope")))

    private val mockKAdditionalAnswerScopeSymbol =
        pluginContext.referenceClass(ClassId(MOCKK_FQ_NAME, Name.identifier("MockKAdditionalAnswerScope")))

    /** `io.mockk.every { … }` (top-level): records a stubbing. */
    private val everySymbol: IrSimpleFunctionSymbol? =
        pluginContext.referenceFunctions(CallableId(MOCKK_FQ_NAME, Name.identifier("every")))
            .firstOrNull { it.regularParameterCount() == 1 }

    /** `MockKMatcherScope.any()` (the no-argument, reified matcher). */
    private val mockkAnySymbol: IrSimpleFunctionSymbol? =
        pluginContext.referenceFunctions(
            CallableId(ClassId(MOCKK_FQ_NAME, Name.identifier("MockKMatcherScope")), Name.identifier("any")),
        ).firstOrNull { it.regularParameterCount() == 0 }

    /** `MockKStubScope.returns(value)`: completes a stubbing with a fixed return value. */
    private val returnsSymbol: IrSimpleFunctionSymbol? =
        pluginContext.referenceFunctions(
            CallableId(ClassId(MOCKK_FQ_NAME, Name.identifier("MockKStubScope")), Name.identifier("returns")),
        ).firstOrNull { it.regularParameterCount() == 1 }

    /** Spokk's own top-level `any()` marker, replaced by MockK's `any()` on rewrite. */
    private val spokkAnySymbol: IrSimpleFunctionSymbol? =
        pluginContext.referenceFunctions(CallableId(FqName(SPOKK_PACKAGE), Name.identifier("any")))
            .firstOrNull { it.regularParameterCount() == 0 }

    /**
     * Getter of the FIR-synthesised top-level `_` property (Spock's "any argument" placeholder).
     * Like [spokkAnySymbol] it is replaced by MockK's `any()` on rewrite.
     */
    private val spokkUnderscoreGetterSymbol: IrSimpleFunctionSymbol? =
        pluginContext.referenceProperties(CallableId(FqName(SPOKK_PACKAGE), Name.identifier("_")))
            .firstOrNull()?.owner?.getter?.symbol

    override fun visitBlockBody(body: IrBlockBody): IrBody {
        val transformed = super.visitBlockBody(body)
        if (transformed is IrBlockBody) {
            replaceMarkersInMockKScope(transformed)
            rewriteAssertions(transformed)
        }
        return transformed
    }

    /**
     * When the enclosing function is a lambda with a `MockKMatcherScope` (or subtype) receiver — i.e.
     * a `stub { … }`, `n * { … }` or `every`/`verify` block — replaces any `_` / `any()` markers in
     * its body with a real `receiver.any<T>()`, so Spock's `_` can be used there exactly like inside
     * a closure-free stub.
     */
    private fun replaceMarkersInMockKScope(body: IrBlockBody) {
        val mockkAny = mockkAnySymbol ?: return
        val matcherScope = mockKMatcherScopeSymbol ?: return
        val enclosing = currentFunction?.irElement as? IrFunction ?: return
        val receiver = enclosing.parameters.firstOrNull { parameter ->
            (parameter.kind == IrParameterKind.ExtensionReceiver || parameter.kind == IrParameterKind.DispatchReceiver) &&
                parameter.type.isSubtypeOfClass(matcherScope)
        } ?: return

        body.transformChildren(
            object : IrElementTransformerVoid() {
                override fun visitCall(expression: IrCall): IrExpression {
                    val call = super.visitCall(expression) as IrCall
                    replaceAnyMarkers(call, mockkAny, receiver, enclosing.symbol, recurse = false)
                    return call
                }
            },
            null,
        )
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

            // Closure-free stubbing (`mock.call(_) > value`) is rewritten regardless of the block
            // kind: it carries the spokk `any()` marker, so it can never be an ordinary assertion.
            val rewrittenStub = tryRewriteClosureFreeStub(statement)
            if (rewrittenStub != null) {
                statements[index] = rewrittenStub
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

    /**
     * Rewrites a *closure-free* stubbing statement of the form `mock.call(any()) > value` into the
     * MockK form `every { mock.call(any()) } returns value`, returning the rewritten statement (or
     * `null` when [statement] is not such a stub, in which case it is left untouched).
     *
     * Spock writes stubbing as `mock.call(_) >> value`. Kotlin neither lets `_` be an identifier nor
     * `>>` an operator, so spokk spells it `mock.call(any()) > value` (with `_` desugared to `any()`
     * by the FIR side of the plugin). The `>` is an ordinary comparison in the source; here it is
     * recognised and turned into a real stubbing. Detection is anchored on spokk's `any()` marker:
     * only a `>` whose left-hand call uses it is rewritten, so genuine comparisons are never touched.
     */
    private fun tryRewriteClosureFreeStub(statement: IrStatement): IrStatement? {
        val every = everySymbol ?: return null
        val returns = returnsSymbol ?: return null
        val mockkAny = mockkAnySymbol ?: return null
        val matcherScope = mockKMatcherScopeSymbol ?: return null
        val stubScope = mockKStubScopeSymbol ?: return null
        // At least one spelling of the "any argument" marker (`any()` or `_`) must be resolvable.
        if (spokkAnySymbol == null && spokkUnderscoreGetterSymbol == null) return null

        val comparison = statement.unwrapImplicitCoercionToUnit() as? IrCall ?: return null
        if (comparison.origin != IrStatementOrigin.GT) return null

        // `a > b` is lowered either to `greater(a.compareTo(b), 0)` (Comparable types such as String)
        // or to the primitive intrinsic `greater(a, b)` (e.g. Int). Pull out the stubbed call (the
        // left operand) and the desired return value (the right operand) from both shapes.
        val left = comparison.arguments.getOrNull(0)
        val mockCall: IrCall
        val returnValue: IrExpression
        if (left is IrCall && left.symbol.owner.name.asString() == "compareTo") {
            mockCall = left.arguments.getOrNull(0) as? IrCall ?: return null
            returnValue = left.arguments.getOrNull(1) ?: return null
        } else if (left is IrCall) {
            mockCall = left
            returnValue = comparison.arguments.getOrNull(1) ?: return null
        } else {
            return null
        }

        if (!usesAnyMarker(mockCall)) return null

        val mockCallType = mockCall.type
        val matcherScopeType = matcherScope.typeWith()
        val declarationParent = currentDeclarationParent ?: return null

        // Build the `{ scope -> mock.call(any()) }` lambda handed to `every`. The MockKMatcherScope is
        // taken as a plain parameter (not an implicit receiver) so MockK's `any()` can be called on it
        // explicitly — this keeps the synthesised IR straightforward.
        val lambda = pluginContext.irFactory.buildFun {
            name = SpecialNames.ANONYMOUS
            visibility = DescriptorVisibilities.LOCAL
            origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
            returnType = mockCallType
            modality = Modality.FINAL
            isSuspend = false
        }
        lambda.parent = declarationParent
        val scopeParameter = lambda.addValueParameter("scope", matcherScopeType)

        // Replace each `any()` / `_` marker inside the call with `scope.any<T>()` (a MockK matcher).
        replaceAnyMarkers(mockCall, mockkAny, scopeParameter, lambda.symbol, recurse = true)

        lambda.body = DeclarationIrBuilder(pluginContext, lambda.symbol).irBlockBody {
            +irReturn(mockCall)
        }

        val builder = DeclarationIrBuilder(
            pluginContext,
            currentScope!!.scope.scopeOwnerSymbol,
            statement.startOffset,
            statement.endOffset,
        )
        val stubBlockType = pluginContext.irBuiltIns.functionN(1).typeWith(matcherScopeType, mockCallType)
        val stubBlock = IrFunctionExpressionImpl(
            statement.startOffset,
            statement.endOffset,
            stubBlockType,
            lambda,
            IrStatementOrigin.LAMBDA,
        )

        val everyCall = builder.irCall(every).apply {
            typeArguments[0] = mockCallType
            arguments[0] = stubBlock
            type = stubScope.typeWith(mockCallType, mockCallType)
        }
        return builder.irCall(returns).apply {
            arguments[0] = everyCall
            arguments[1] = returnValue
            mockKAdditionalAnswerScopeSymbol?.let { type = it.typeWith(mockCallType, mockCallType) }
        }
    }

    /**
     * Recursively replaces every `any()` / `_` marker found among the arguments of [call] with a real
     * `scope.any<T>()` MockK matcher. The matcher's type argument is taken from the *callee's*
     * parameter type at that position, so the `_` marker (whose own type is `Nothing`) still produces
     * the correct `any<ParameterType>()`.
     */
    private fun replaceAnyMarkers(
        call: IrCall,
        mockkAny: IrSimpleFunctionSymbol,
        scopeParameter: IrValueParameter,
        scopeOwner: IrSymbol,
        recurse: Boolean,
    ) {
        val parameters = call.symbol.owner.parameters
        for (index in call.arguments.indices) {
            val argument = call.arguments[index] as? IrCall ?: continue
            if (isAnyMarker(argument)) {
                val matcherType = parameters.getOrNull(index)?.type ?: argument.type
                val builder =
                    DeclarationIrBuilder(pluginContext, scopeOwner, argument.startOffset, argument.endOffset)
                call.arguments[index] = builder.irCall(mockkAny).apply {
                    arguments[0] = builder.irGet(scopeParameter)
                    typeArguments[0] = matcherType
                    type = matcherType
                }
            } else if (recurse) {
                replaceAnyMarkers(argument, mockkAny, scopeParameter, scopeOwner, recurse = true)
            }
        }
    }

    /** True when [expression] is (or contains, in an argument) an `any()` / `_` matcher marker. */
    private fun usesAnyMarker(expression: IrExpression?): Boolean {
        val call = expression as? IrCall ?: return false
        if (isAnyMarker(call)) return true
        return call.arguments.any { usesAnyMarker(it) }
    }

    /** True when [call] is spokk's top-level `any()` or the synthetic `_` property getter. */
    private fun isAnyMarker(call: IrCall): Boolean =
        call.symbol == spokkAnySymbol || call.symbol == spokkUnderscoreGetterSymbol

    /** Number of ordinary (non-receiver, non-context) value parameters of this function. */
    private fun IrSimpleFunctionSymbol.regularParameterCount(): Int =
        owner.parameters.count { it.kind == IrParameterKind.Regular }

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
