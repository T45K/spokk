package io.github.spokk

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Verifies Spock's closure semantics inside a `then` / `expect` block.
 *
 * Only *direct* statements of the block are turned into assertions. A bare `==` that lives inside a
 * closure (lambda) is **not** asserted automatically; to assert inside a closure you have to write an
 * explicit `assert`.
 */
class ClosureSemanticsTest {

    @Test
    fun `bare equals inside a closure is not asserted`() {
        given
        val numbers = [1, 2, 3]

        then
        // Each closure body compares against a wrong value. If these were turned into assertions the
        // test would fail with an AssertionError. They must NOT be asserted (Spock semantics).
        numbers.forEach { it == 999 }
        numbers.map { it == 999 }

        // Only this *direct* statement of the block is asserted (and it holds).
        numbers == [1, 2, 3]
    }

    @Test
    fun `explicit assert is required to assert inside a closure`() {
        given
        val numbers = [1, 2, 3]

        // An explicit `assert` inside the closure DOES run, so iterating throws on the first element.
        expect
        assertThrows<AssertionError> {
            numbers.forEach { assert(it == 999) }
        }
    }
}
