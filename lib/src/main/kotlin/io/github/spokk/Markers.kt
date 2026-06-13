package io.github.spokk

/**
 * Spokk block markers, inspired by [Spock](https://spockframework.org/)'s `given:`/`when:`/`then:`
 * labels.
 *
 * They are plain `Unit` constants, so writing them on their own line is a valid (no-op) statement:
 *
 * ```
 * given
 * val foo = Foo()
 *
 * `when`
 * val result = foo.foo(1)
 *
 * then
 * result == true   // becomes an assertion, with a power-assert diagram on failure
 * ```
 *
 * The Spokk compiler plugin recognises these markers and turns every bare boolean expression that is
 * a *direct* statement of the spec following a [then] (or [expect]) marker into a `kotlin.assert(...)`
 * call. Power-assert then renders the familiar diagram when such an assertion fails. Booleans nested
 * inside a closure are not asserted automatically — use an explicit `assert` there (Spock semantics).
 */

/** Sets up the fixture / preconditions. Closes any preceding assertion block. */
public val given: Unit get() = Unit

/** Alias for [given] (Spock's `setup:`). Sets up the fixture and closes any preceding assertion block. */
public val setup: Unit get() = Unit

/** Performs the stimulus / action under test. Closes any preceding assertion block. */
public val `when`: Unit get() = Unit

/**
 * Opens an assertion block: every following bare boolean expression that is a direct statement of the
 * spec is asserted. Booleans inside a closure are not asserted — write an explicit `assert` there.
 */
public val then: Unit get() = Unit

/**
 * Plays the role of [`when`] + [then]: performs the stimulus and verifies it in the same block. Like
 * [then], every following bare boolean (direct) statement is asserted.
 */
public val expect: Unit get() = Unit

/** Continuation marker (Spock's `and:`); keeps the kind of the previous block. */
public val and: Unit get() = Unit
