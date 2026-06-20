# spokk

A tiny [Spock](https://spockframework.org/)-style testing framework for Kotlin, built on the **K2 compiler**.

```kotlin
package io.github.spokk

import org.junit.jupiter.api.Test

class FooTest {
    @Test
    fun `foo returns true when given number is 1`() {
        given
        val foo = Foo()

        `when`
        val result = foo.foo(1)

        then
        result == true        // <- this is an assertion
    }
}
```

## Features

### 1. `given` / `when` / `then` block markers

`given`, `` `when` ``, `then` (plus `expect`, `and` and `setup`, an alias for `given`) behave like Spock's block labels.
They are implemented as plain top-level `Unit` values in the `io.github.spokk` package (see [
`Markers.kt`](lib/src/main/kotlin/io/github/spokk/Markers.kt)), so writing one on its own line is just a no-op
statement. A test class in the `io.github.spokk` package can use them directly; any other class imports them (e.g.
`import io.github.spokk.given`).

### 2. Power-assert is enabled by default after `then`

Every bare boolean expression that follows a `then` (or `expect`) marker becomes an assertion — there is no need to call
`assert`/`assertEquals` explicitly. So `result == true` actually fails the test when `result` is `false`, with
a [Power-assert](https://kotlinlang.org/docs/power-assert.html)
diagram:

```
java.lang.AssertionError:
result == true
|      |
|      false
false
```

This is implemented by the **K2 compiler plugin** in
[`:compiler-plugin`](compiler-plugin/src/main/kotlin/io/github/spokk/compiler):

1. `SpokkIrGenerationExtension` walks each function body, finds the `then`/`expect` marker, and rewrites the following
   bare boolean statements into `kotlin.assert(...)` calls.
2. The plugin then reuses the official Power-assert IR transformer (registered straight after our own transform, so
   ordering is guaranteed) to turn those `assert` calls into the familiar diagrams.

Only the **direct** statements of the block are asserted. A bare `==` nested inside a closure is *not* asserted — see
[Closures are not asserted automatically](#4-closures-are-not-asserted-automatically).

### 3. `expect`: `when` + `then` in one block

`expect` plays the role of `when` + `then`: it performs the stimulus and verifies it in the same block. Every bare
boolean expression after `expect` becomes an assertion, exactly like after `then`:

```kotlin
@Test
fun `expect combines the when and then blocks`() {
    given
    val foo = Foo()

    expect
    foo.foo(1) == true        // <- this is an assertion
}
```

### 4. Closures are not asserted automatically

Following Spock, only the *direct* statements of a `then`/`expect` block are turned into assertions. A bare `==` (or any
boolean) inside a closure is **not** asserted. To assert inside a closure you must write an explicit `assert`:

```kotlin
then
numbers.forEach { it == 999 }            // NOT an assertion (just a no-op comparison)
numbers.forEach { assert(it == 999) }    // explicit assert -> fails with a power-assert diagram
numbers == [1, 2, 3]                     // direct statement -> this IS an assertion
```

### 5. `suspend` specifications

Specifications can be `suspend` functions; JUnit invokes them directly:

```kotlin
@Test
suspend fun `fooAsync returns true when given number is 1`() {
    given
    val foo = Foo()

    `when`
    val result = foo.fooAsync(1)

    then
    result == true
}
```

JUnit needs `org.jetbrains.kotlin:kotlin-reflect` and `org.jetbrains.kotlinx:kotlinx-coroutines-core` on the test
classpath to discover and run `suspend` test functions (already wired up in
[`lib/build.gradle.kts`](lib/build.gradle.kts)).

### 6. Collection literals

The `[1, 2, 3]` collection-literal syntax is enabled via the experimental `-Xcollection-literals`
compiler flag, so it can be used in specifications (and asserted on):

```kotlin
then
numbers == [1, 2, 3]
```

```
java.lang.AssertionError:
numbers == [1, 2, 3]
|       |  |
|       |  [1, 2, 3]
|       false
[1, 2]
```

### 7. `where`: data-driven testing

Spock's `where:` block lets you write a feature once and run it against a *data table*, executing one *iteration* per
row. Spokk offers the same with a `` `|` ``-separated data table (inspired by
[kotlin-data-table](https://github.com/T45K/kotlin-data-table); see
[`DataDriven.kt`](lib/src/main/kotlin/io/github/spokk/DataDriven.kt)):

```kotlin
import io.github.spokk.where
import org.junit.jupiter.api.TestFactory

class MathSpec {
    @TestFactory
    fun `maximum of two numbers`() = where({
        1 `|` 3 `|` 3
        7 `|` 4 `|` 7
        0 `|` 0 `|` 0
    }) { a: Int, b: Int, c: Int ->
        expect
        maxOf(a, b) == c        // <- a power-assert assertion, exactly like in a normal spec
    }
}
```

* Each physical line inside the `{ ... }` block is one row, and cells are separated by the `` `|` `` infix function
  (written backtick-escaped because Kotlin has no `|` operator). The cells are heterogeneous, so the body parameters
  carry the column types (`a: Int, b: Int, c: Int`) and the cells are cast to them.
* `where(...) { ... }` runs the body once per row and returns a `List<DynamicTest>`, so the method is annotated with
  **`@TestFactory`** (not `@Test`). Each row becomes its own reported test — Spock's *unrolled* behaviour — and a
  failing iteration does **not** stop the others; every failure is reported with its own Power-assert diagram:

  ```
  maximum of two numbers() > [1] (7, 4, 8) FAILED
      maxOf(a, b) == c
      |     |  |  |  |
      |     |  |  |  8
      |     |  |  false
      |     |  4
      |     7
      7
  ```

* Inside the body the usual block markers work and the body may be a `suspend` lambda. Typed overloads exist for two to
  five columns; for wider tables take the whole row (`{ row -> ... }`) and read cells with `row[0]`, `row[1]`, …. Use
  ordinary lambda parameters — a *destructuring* parameter (`{ (a, b) -> ... }`) drops the leading marker statement and
  silently disables the assertions.

### 8. Interaction-based testing (mocks)

Spokk mirrors Spock's [interaction-based testing](https://spockframework.org/spock/docs/2.4/interaction_based_testing.html)
by wrapping [MockK](https://mockk.io) in a small Spock-flavoured DSL (see
[`Mocking.kt`](lib/src/main/kotlin/io/github/spokk/Mocking.kt)). Collaborators are created with `Mock()` / `Stub()` /
`Spy()`, their responses are stubbed, and the expected interactions are verified in a `then` block with a *cardinality*:

```kotlin
import io.github.spokk.*
import org.junit.jupiter.api.Test

interface Subscriber {
    fun receive(message: String): String
}

class PublisherSpec {
    @Test
    fun `sends each message to all subscribers`() {
        given
        val subscriber = Mock<Subscriber>()
        val subscriber2 = Mock<Subscriber>()
        val publisher = Publisher()
        publisher.subscribe(subscriber)
        publisher.subscribe(subscriber2)

        `when`
        publisher.send("hello")

        then
        1 * { subscriber.receive("hello") }       // exactly one call
        1 * { subscriber2.receive("hello") }
    }
}
```

* **Creating collaborators.** `Mock<T>()` and `Stub<T>()` are *lenient* mocks (like Spock and unlike a strict
  framework): an unstubbed call returns a default value instead of failing. `Spy(instance)` wraps a real object and runs
  its real methods unless they are stubbed.
* **Cardinality (verification).** A statement of the form `n * { … }` in a `then` block verifies how often the call
  inside the lambda happened — `1 * { … }` (exactly once), `0 * { … }` (never), and `(1..3) * { … }` (an inclusive
  range; use `(1..Int.MAX_VALUE)` for Spock's "at least once"). Verification really runs: an unsatisfied cardinality
  fails the test with an `AssertionError`.
* **Stubbing.** The closure-free form mirrors Spock's `subscriber.receive(_) >> "ok"` most closely: write the call
  followed by `> value` and the compiler plugin rewrites it into a real stubbing. The "any argument" placeholder is
  written `` `_` `` (back-tick escaped, since `_` is a reserved name in Kotlin) or `any()`:

  ```kotlin
  given
  subscriber.receive(`_`) > "ok"     // == Spock's subscriber.receive(_) >> "ok"
  subscriber.receive(any()) > "ok"   // the same, spelled with any()
  ```

  `> value` only works when the call's return type is `Comparable` (e.g. `String`, `Int`). For successive values,
  computed answers or throwing — things `>` cannot express — use the `stub { … }` recording with MockK's response
  generators:

  ```kotlin
  stub { subscriber.receive(`_`) } returns "ok"                         // a fixed value
  stub { subscriber.receive(any()) } returnsMany listOf("ok", "error")  // successive values (last repeats)
  stub { subscriber.receive(any()) } answers { firstArg<String>().uppercase() } // computed from the arguments
  stub { subscriber.receive("boom") } throws IllegalStateException()    // throwing
  ```

* **Argument constraints** are the `` `_` `` / `any()` "any argument" markers above, or any plain MockK matcher used
  inside the recording lambda: `match { it.length > 3 }`, `eq(…)`, and so on. `` `_` `` and `any()` work everywhere a
  matcher is expected — inside `stub { … }`, a `n * { … }` cardinality, and a closure-free `> value` stub.

The DSL is a thin shim, so the mapping from Spock to spokk is direct:

| Spock                                         | spokk                                                       |
|-----------------------------------------------|-------------------------------------------------------------|
| `Subscriber subscriber = Mock()`              | `val subscriber = Mock<Subscriber>()`                       |
| `1 * subscriber.receive("hello")`             | `1 * { subscriber.receive("hello") }`                       |
| `(1..3) * subscriber.receive(_)`              | `` (1..3) * { subscriber.receive(`_`) } ``                  |
| `subscriber.receive(_) >> "ok"`               | `` subscriber.receive(`_`) > "ok" ``                        |
| `subscriber.receive(_) >>> ["ok", "error"]`   | `stub { subscriber.receive(any()) } returnsMany [...]`      |
| `subscriber.receive({ it.size() > 3 })`       | `subscriber.receive(match { it.length > 3 })`               |

> Why `` `_` `` and not a bare `_`, and why `>` instead of `>>`? Kotlin reserves `_` as an identifier (it can only be
> written back-tick escaped) and has no `>>` operator, so spokk uses the closest legal spellings: `` `_` `` for the
> matcher and a single `>` for the stub value. The `` `_` `` placeholder is a compiler-synthesised property and the
> closure-free `> value` stub is a plugin rewrite — both are turned into ordinary MockK calls during compilation.
>
> The `n * { … }` cardinality still needs the braces because, unlike Groovy, Kotlin evaluates `subscriber.receive(...)`
> eagerly; the lambda defers it so the call can be verified instead of run.

Because MockK mocks classes via a dynamically attached Java agent, you may see a *"A Java agent has been loaded
dynamically"* warning on modern JDKs; it is harmless. Mocking interfaces (as above) does not need the agent.

## Module layout

| Module             | Description                                                                                          |
|--------------------|------------------------------------------------------------------------------------------------------|
| `:lib`             | The runtime markers (`given`/`when`/`then`/…), the data-driven `where` DSL, the MockK-backed mocking DSL, and example specs. |
| `:compiler-plugin` | The K2 compiler plugin that turns bare booleans after `then` into Power-assert checks.               |

`:lib` loads the plugin (together with the Power-assert plugin it delegates to) via `-Xplugin` and turns on
`-Xcollection-literals` — see [`lib/build.gradle.kts`](lib/build.gradle.kts).

## Build & test

```bash
./gradlew build
```
