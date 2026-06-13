package io.github.spokk

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest

/**
 * Data-driven testing, inspired by [Spock](https://spockframework.org/)'s `where:` block and the
 * `` `|` ``-separated data table of [kotlin-data-table](https://github.com/T45K/kotlin-data-table).
 *
 * ```
 * @TestFactory
 * fun `maximum of two numbers`() = where({
 *     1 `|` 3 `|` 3
 *     7 `|` 4 `|` 7
 *     0 `|` 0 `|` 0
 * }) { a: Int, b: Int, c: Int ->
 *     expect
 *     maxOf(a, b) == c          // a power-assert assertion, exactly like in a normal spec
 * }
 * ```
 *
 * Each physical line inside the block is one row; cells are separated by the `` `|` `` infix function
 * (written backtick-escaped because Kotlin has no `|` operator). [where] runs the body once per row
 * and returns a `List<DynamicTest>`, so the method is annotated with `@TestFactory`. Every row is
 * reported as its own iteration and a failing row does not stop the others. Inside the body the usual
 * block markers work, a bare boolean after [then]/[expect] becomes a Power-assert assertion, and the
 * body may be `suspend`.
 *
 * Body parameters carry the column types (cells are cast to them); typed overloads exist for two to
 * five columns. For wider tables take the whole [DataRow] (`{ row -> ... }`) and read cells with
 * `row[0]`, `row[1]`, …. Use ordinary (non-destructuring) parameters: a destructuring parameter
 * (`{ (a, b) -> ... }`) drops the leading marker statement and silently disables the assertions.
 */

/** One row of a `` `|` ``-separated data table. Cells are stored untyped and cast on access. */
public class DataRow internal constructor(private val cells: MutableList<Any?>) {

    /** Appends a cell — the `` `|` `` separator between two columns. */
    public infix fun `|`(other: Any?): DataRow = apply { cells += other }

    /** Returns the cell at [index], cast to the requested type. */
    @Suppress("UNCHECKED_CAST")
    public operator fun <T> get(index: Int): T = cells[index] as T

    override fun toString(): String = cells.joinToString(prefix = "(", postfix = ")")
}

/**
 * Receiver scope of a [where] data-table block. The first `` `|` `` on a line starts a new [DataRow]
 * and every following `` `|` `` appends a cell to it.
 */
public class TableScope internal constructor() {

    internal val rows: MutableList<DataRow> = mutableListOf()

    /** Starts a new row from its first two cells — the line-initial `` `|` `` separator. */
    public infix fun Any?.`|`(other: Any?): DataRow =
        DataRow(mutableListOf(this, other)).also { rows += it }
}

private fun rows(table: TableScope.() -> Unit): List<DataRow> = TableScope().apply(table).rows

private fun List<DataRow>.toTests(invoke: suspend (DataRow) -> Unit): List<DynamicTest> =
    mapIndexed { index, row -> dynamicTest("[$index] $row") { runBlocking { invoke(row) } } }

/** Runs [body] once per row, handing it the whole [DataRow] (read cells with `row[0]`, `row[1]`, …). */
public fun where(table: TableScope.() -> Unit, body: suspend (DataRow) -> Unit): List<DynamicTest> =
    rows(table).toTests(body)

/** Runs [body] once per two-column row. */
public fun <A, B> where(table: TableScope.() -> Unit, body: suspend (A, B) -> Unit): List<DynamicTest> =
    rows(table).toTests { body(it[0], it[1]) }

/** Runs [body] once per three-column row. */
public fun <A, B, C> where(table: TableScope.() -> Unit, body: suspend (A, B, C) -> Unit): List<DynamicTest> =
    rows(table).toTests { body(it[0], it[1], it[2]) }

/** Runs [body] once per four-column row. */
public fun <A, B, C, D> where(table: TableScope.() -> Unit, body: suspend (A, B, C, D) -> Unit): List<DynamicTest> =
    rows(table).toTests { body(it[0], it[1], it[2], it[3]) }

/** Runs [body] once per five-column row. */
public fun <A, B, C, D, E> where(table: TableScope.() -> Unit, body: suspend (A, B, C, D, E) -> Unit): List<DynamicTest> =
    rows(table).toTests { body(it[0], it[1], it[2], it[3], it[4]) }
