package io.github.spokk

import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test

/** Subject under test. */
class Foo {
    fun foo(n: Int): Boolean = n == 1

    fun upTo(n: Int): List<Int> = (1..n).toList()

    /** A `suspend` variant, used to show that specifications may be `suspend` functions. */
    suspend fun fooAsync(n: Int): Boolean {
        delay(1)
        return foo(n)
    }
}

/**
 * Spock-style specification.
 *
 * `given`, `when` and `then` act as block markers (they are plain top-level `Unit` values from the
 * `io.github.spokk` package). After a `then` (or `expect`) marker every bare boolean expression that
 * is a *direct* statement of the spec is turned into an assertion by the Spokk compiler plugin, with
 * Power-assert diagrams on failure.
 */
class FooTest {

    @Test
    fun `foo returns true when given number is 1`() {
        given
        val foo = Foo()

        `when`
        val result = foo.foo(1)

        then
        result == true
    }

    @Test
    fun `upTo builds a list using a collection literal`() {
        given
        val foo = Foo()

        `when`
        val numbers = foo.upTo(3)

        then
        numbers == [1, 2, 3]
    }

    @Test
    fun `expect combines the when and then blocks`() {
        given
        val foo = Foo()

        // `expect` plays the role of `when` + `then`: the stimulus and its verification live in the
        // same block, and the bare boolean below becomes an assertion.
        expect
        foo.foo(1) == true
    }

    @Test
    suspend fun `fooAsync returns true when given number is 1`() {
        given
        val foo = Foo()

        `when`
        val result = foo.fooAsync(1)

        then
        result == true
    }
}
