package io.github.spokk

import io.mockk.MockKMatcherScope
import io.mockk.MockKStubScope
import io.mockk.MockKVerificationScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify

/**
 * Spock-style interaction-based testing, implemented as a thin wrapper around
 * [MockK](https://mockk.io). Inspired by
 * [Spock's mocking framework](https://spockframework.org/spock/docs/2.4/interaction_based_testing.html).
 *
 * The three building blocks mirror Spock:
 *
 *  * **Creating collaborators** — [Mock], [Stub] and [Spy] (see below).
 *  * **Stubbing** — make a collaborator respond to a call. The closure-free form mirrors Spock's
 *    `subscriber.receive(_) >> "ok"` most closely: write the call followed by `> value` and the
 *    spokk compiler plugin rewrites it into a real stubbing. `_` (back-tick escaped, because `_` is
 *    a reserved name in Kotlin) or [any] stands for Spock's "any argument" placeholder:
 *    ```
 *    subscriber.receive(`_`) > "ok"      // == Spock's subscriber.receive(_) >> "ok"
 *    subscriber.receive(any()) > "ok"    // the same, spelled with any()
 *    ```
 *    For successive values, computed answers or throwing — things `>` cannot express — use the
 *    `stub { … }` recording with MockK's `returns` / `returnsMany` / `answers` / `throws`:
 *    ```
 *    stub { subscriber.receive(`_`) } returns "ok"                  // a fixed value
 *    stub { subscriber.receive(any()) } returnsMany ["ok", "error"] // successive values
 *    ```
 *  * **Mocking / interaction verification** — assert *how often* a call happened, written with a
 *    Spock-like *cardinality* in a `then` block:
 *    ```
 *    then
 *    1 * { subscriber.receive("hello") }       // exactly one call
 *    (1..3) * { subscriber.receive(any()) }    // between one and three calls
 *    0 * { subscriber.receive("bye") }         // no call at all
 *    ```
 *
 * Just like Spock (and unlike a strict mocking framework), a [Mock]/[Stub] is **lenient**: an
 * unstubbed call simply returns a default value instead of failing. Argument constraints are the
 * `_` / [any] "any argument" markers above, or any plain MockK matcher (`match { … }`, `eq(…)`, …).
 */

/**
 * Creates a lenient mock of [T]. Unstubbed calls return default values; calls can be verified
 * afterwards with a cardinality (`1 * { … }`). Mirrors Spock's `Mock()`.
 */
public inline fun <reified T : Any> Mock(): T = mockk(relaxed = true)

/**
 * Creates a stub of [T]. Like [Mock] it is lenient and returns default values, but a stub is meant
 * purely for stubbing responses rather than for verifying interactions. Mirrors Spock's `Stub()`.
 */
public inline fun <reified T : Any> Stub(): T = mockk(relaxed = true)

/**
 * Creates a spy wrapping the real [instance]: calls run the real implementation unless they are
 * stubbed. Mirrors Spock's `Spy()`.
 */
public inline fun <reified T : Any> Spy(instance: T): T = spyk(instance)

/**
 * Opens a stubbing recording for the single call made inside [stubBlock] (an alias for MockK's
 * `every`). Complete it with a response generator — Spock's `>>` becomes MockK's infix `returns`,
 * `>>>` becomes `returnsMany`, a computing closure becomes `answers`, and throwing becomes `throws`:
 *
 * ```
 * stub { subscriber.receive(any()) } returns "ok"
 * stub { subscriber.receive(any()) } returnsMany ["ok", "error"]
 * stub { subscriber.receive(any()) } answers { firstArg<String>().uppercase() }
 * stub { subscriber.receive("boom") } throws RuntimeException("nope")
 * ```
 */
public fun <T> stub(stubBlock: MockKMatcherScope.() -> T): MockKStubScope<T, T> = every(stubBlock)

/**
 * Verifies that the interaction recorded inside [interaction] happened *exactly* this many times,
 * mirroring Spock's `n * subscriber.receive("hello")`. Use `0 * { … }` to assert a call never
 * happened.
 */
public operator fun Int.times(interaction: MockKVerificationScope.() -> Unit) {
    verify(exactly = this) { interaction() }
}

/**
 * Verifies that the interaction recorded inside [interaction] happened a number of times within this
 * range (inclusive), mirroring Spock's `(1..3) * subscriber.receive("hello")`. For an open upper
 * bound (Spock's `(1.._)`, "at least once") use `(1..Int.MAX_VALUE) * { … }`.
 */
public operator fun IntRange.times(interaction: MockKVerificationScope.() -> Unit) {
    verify(atLeast = first, atMost = last) { interaction() }
}

/**
 * Spock's `_` "any argument" placeholder, spelled `any()`. Use it (or the back-tick `` `_` ``
 * property) inside a stubbing or verification to match an argument of any value, e.g.
 * `subscriber.receive(any()) > "ok"` or `1 * { subscriber.receive(any()) }`.
 *
 * It is only a *marker*: the spokk compiler plugin replaces every occurrence with a real MockK
 * `any()` matcher bound to the surrounding mocking scope. Calling it anywhere else throws, because
 * outside a spokk stubbing/verification there is no MockK scope to register the matcher in.
 */
public fun <T> any(): T =
    throw IllegalStateException("spokk's any() / `_` may only be used inside a spokk stubbing or verification")
