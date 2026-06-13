# AGENTS.md

Guidance for AI coding agents (and humans) working on **spokk**.

## What this project is

`spokk` is a tiny [Spock](https://spockframework.org/)-style testing framework for Kotlin, built on
the **K2 compiler**. Inside a test method, `given` / `when` / `then` (plus `expect`, `and`, `setup`)
act as Spock-like block labels, and a *bare* boolean expression after `then` / `expect` is rewritten
into a [Power-assert](https://kotlinlang.org/docs/power-assert.html)-backed `kotlin.assert(...)` call
by a custom K2 compiler plugin. A `` `|` ``-separated data table powers Spock's `where:`
(data-driven) testing.

A spec looks like an ordinary JUnit 5 test:

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
        result == true        // <- this becomes a Power-assert assertion
    }
}
```

See `README.md` for the full, user-facing feature walkthrough.

## Module layout

| Module             | Path                | Description                                                                                  |
|--------------------|---------------------|----------------------------------------------------------------------------------------------|
| `:lib`             | `lib/`              | Runtime markers (`given`/`when`/`then`/…), the `where(...)` data-driven DSL, and example specs. |
| `:compiler-plugin` | `compiler-plugin/`  | The K2 compiler plugin that turns bare booleans after `then`/`expect` into Power-assert checks. |

Key source files:

- `lib/src/main/kotlin/io/github/spokk/Markers.kt` — the block markers, declared as top-level
  `Unit` properties so that writing one on its own line is a valid no-op statement.
- `lib/src/main/kotlin/io/github/spokk/DataDriven.kt` — the `` `|` `` data table (`TableScope`,
  `DataRow`) and the `where(...)` overloads (2–5 typed columns + a whole-`DataRow` form).
- `compiler-plugin/src/main/kotlin/io/github/spokk/compiler/SpokkCompilerPluginRegistrar.kt` — the
  `CompilerPluginRegistrar` entry point (K2).
- `compiler-plugin/src/main/kotlin/io/github/spokk/compiler/SpokkIrGenerationExtension.kt` — the IR
  transform that rewrites the bare booleans.
- `lib/src/test/kotlin/io/github/spokk/` — the example specs that double as the test suite
  (`FooTest`, `ClosureSemanticsTest`, `WhereTest`).

## Tech stack (pinned)

- **Kotlin `2.4.0`** (K2). The compiler plugin uses internal Power-assert APIs
  (`PowerAssertConfiguration`, `PowerAssertIrGenerationExtension`) that are version-coupled to this
  exact Kotlin version — do not bump Kotlin without re-checking those APIs.
- **JUnit Jupiter `6.0.1`** (test engine + API).
- **kotlinx-coroutines `1.10.2`** (drives `suspend` data-driven bodies via `runBlocking`).
- **Gradle `9.5.1`** (wrapper), **Java toolchain 25**, **configuration cache enabled**
  (`org.gradle.configuration-cache=true`).
- Versions live in `gradle/libs.versions.toml`; prefer the version catalog over hardcoding.

## Build & test commands

Always use the Gradle wrapper from the project root.

```bash
./gradlew build                 # compile + run all specs
./gradlew :lib:test             # run the spec suite only
./gradlew clean build           # full clean build (use before submitting changes)
```

Run a single spec class / data table:

```bash
./gradlew :lib:test --tests "io.github.spokk.FooTest" --rerun-tasks --console=plain
```

Notes:

- The K2 plugin runs *during compilation*, so changes to `:compiler-plugin` only take effect after
  the consuming `:lib` is recompiled. Use `--rerun-tasks` when iterating, since Gradle may otherwise
  treat compilation as up-to-date.
- The plugin emits noisy `[SPOKK]` lines; filter them when scanning logs, e.g.
  `... --console=plain 2>&1 | grep -vE "SPOKK\]|^\s+at |^\[|^> Task"`.
- `testLogging` is configured to print Power-assert diagrams (`exceptionFormat = FULL`,
  `showStandardStreams = true`), so failing assertions show their diagrams in the build output.

## How the compiler plugin works (architecture)

1. `SpokkCompilerPluginRegistrar` registers **two** IR extensions, in this exact order:
   1. `SpokkIrGenerationExtension` — rewrites bare booleans after a `then`/`expect` marker into
      `kotlin.assert(...)` calls.
   2. The official `PowerAssertIrGenerationExtension`, configured for `kotlin.assert`, which turns
      those calls into the diagram-rendering form.
   Registering Power-assert here (rather than relying on the standalone Gradle plugin) is what
   guarantees the ordering — **spokk first, Power-assert second**.
2. `:lib` loads both jars into the compiler via `-Xplugin=` (configured in `lib/build.gradle.kts`
   through the `spokkCompilerPlugin` resolvable configuration, with `isTransitive = false`), and
   enables collection literals via `-Xcollection-literals`.
3. `SpokkAssertionTransformer` walks each block body. It keeps a **block-local** `asserting` flag:
   - `then` / `expect` open the block (`asserting = true`);
   - `given` / `setup` / `when` close it (`asserting = false`);
   - `and` is a continuation (keeps the current state).
   Each direct boolean statement while `asserting` is unwrapped from the compiler's
   `IMPLICIT_COERCION_TO_UNIT` and wrapped in `assert(...)`.
4. Because the flag is local to each block body and a closure/lambda is a *separate* block body that
   starts with `asserting = false`, bare booleans inside closures are **not** auto-asserted — this is
   deliberate Spock semantics (use an explicit `assert` inside a closure).

## Conventions

- **Language:** all source, KDoc, and `README.md` are in **English**; keep new contributions in
  English for consistency.
- **Formatting:** 4-space indentation, trailing commas in multi-line argument lists, explicit
  `public` visibility on the runtime API surface. Mirror the surrounding style.
- **Specs:** live in `lib/src/test/.../io/github/spokk/` and serve as both documentation and the test
  suite. A normal spec uses `@Test`; a data-driven spec returns `List<DynamicTest>` and **must** use
  `@TestFactory` (not `@Test`). Specs may be `suspend`.
- Markers are usable unqualified inside the `io.github.spokk` package; from elsewhere import them
  (e.g. `import io.github.spokk.given`).

## Gotchas — read before editing

- **Do not casually touch `SpokkIrGenerationExtension` / `SpokkCompilerPluginRegistrar`.** Power-assert
  behavior is delicate and tied to internal Kotlin APIs. After any change here, verify diagrams still
  render (temporarily make a spec fail, confirm the diagram, then revert it).
- **Verify assertions actually run.** A bare `==` that is *not* turned into an assertion silently
  passes. When validating the plugin, add a temporary failing spec and confirm it FAILS with a
  diagram — never trust a green build alone for assertion behavior.
- **Data-table bodies must use ordinary lambda parameters.** A *destructuring* parameter
  (`{ (a, b) -> ... }`) makes the compiler drop the leading marker statement during desugaring, which
  silently disables the assertions. Use `{ a: Int, b: Int -> ... }` or the whole-row form
  `{ row -> row[0] ... }`.
- **`suspend` specs need `kotlin-reflect` + `kotlinx-coroutines-core` on the test classpath** for
  JUnit to discover and invoke them (already wired in `lib/build.gradle.kts`).
- **Configuration cache is on.** Keep the build configuration-cache compatible; avoid reading
  mutable state at configuration time.
- Clean up any temporary debug specs / scratch files before finishing; the repo should stay green
  (`./gradlew clean build`) and free of scratch artifacts.

## Repository / git notes

- Remote: `git@github.com:T45K/spokk.git` (branch `main`).
- `.idea` and Gradle build outputs are git-ignored (see `.gitignore`); do not re-add them.
- When committing on the user's behalf, add the trailer
  `Co-authored-by: Junie <junie@jetbrains.com>`. Do not commit unless explicitly asked.
