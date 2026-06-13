package io.github.spokk

import org.junit.jupiter.api.TestFactory

/**
 * Data-driven specifications, mirroring Spock's `where:` block.
 *
 * The feature is written once; [where] feeds it a `` `|` ``-separated data table, and every row
 * becomes its own reported iteration (`@TestFactory` + `DynamicTest`). The usual block markers work
 * inside the body, so a bare boolean after `then`/`expect` is a Power-assert assertion.
 */
class WhereTest {

    @TestFactory
    fun `maximum of two numbers`() = where({
        1 `|` 3 `|` 3
        7 `|` 4 `|` 7
        0 `|` 0 `|` 0
    }) { a: Int, b: Int, c: Int ->
        expect
        maxOf(a, b) == c
    }

    @TestFactory
    fun `foo is true only for 1`() = where({
        1 `|` true
        2 `|` false
        0 `|` false
    }) { n: Int, expected: Boolean ->
        given
        val foo = Foo()

        `when`
        val result = foo.foo(n)

        then
        result == expected
    }

    @TestFactory
    fun `suspend data-driven spec runs each row`() = where({
        1 `|` true
        2 `|` false
    }) { n: Int, expected: Boolean ->
        given
        val foo = Foo()

        `when`
        val result = foo.fooAsync(n)

        then
        result == expected
    }

    @TestFactory
    fun `mixed column types are passed as typed parameters`() = where({
        "ab" `|` 2 `|` listOf('a', 'b')
        "" `|` 0 `|` emptyList<Char>()
    }) { text: String, length: Int, chars: List<Char> ->
        expect
        text.length == length
        text.toList() == chars
    }

    @TestFactory
    fun `wide tables can be read positionally via get`() = where({
        1 `|` 2 `|` 3 `|` 4 `|` 5 `|` 15
        2 `|` 4 `|` 6 `|` 8 `|` 10 `|` 30
    }) { row ->
        // `setup` is an alias for `given`.
        setup
        val a: Int = row[0]
        val b: Int = row[1]
        val c: Int = row[2]
        val d: Int = row[3]
        val e: Int = row[4]

        expect
        a + b + c + d + e == row[5]
    }
}
