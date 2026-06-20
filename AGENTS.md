# AGENTS.md

Guidance for AI coding agents (and humans) working on **spokk**.

## What this project is

`spokk` is a tiny [Spock](https://spockframework.org/)-style testing framework for Kotlin, built on
the **K2 compiler**. Inside a test method, `given` / `when` / `then` (plus `expect`, `and`, `setup`)
act as Spock-like block labels, and a *bare* boolean expression after `then` / `expect` is rewritten
into a [Power-assert](https://kotlinlang.org/docs/power-assert.html)-backed `kotlin.assert(...)` call
by a custom K2 compiler plugin. A `` `|` ``-separated data table powers Spock's `where:`
(data-driven) testing, and a [MockK](https://mockk.io)-backed DSL powers Spock-style
interaction-based testing (`Mock`/`Stub`/`Spy`, stubbing, and `n * { … }` interaction verification).
Stubbing can be written closure-free as `` mock.call(`_`) > value `` (Spock's `mock.call(_) >> value`):
the plugin synthesises the `` `_` `` matcher and rewrites the `>` into a real MockK `every { … } returns …`.

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
| `:lib`             | `lib/`              | Runtime markers (`given`/`when`/`then`/…), the `where(...)` data-driven DSL, the MockK-backed mocking DSL, and example specs. |
| `:compiler-plugin` | `compiler-plugin/`  | The K2 compiler plugin that turns bare booleans after `then`/`expect` into Power-assert checks. |

Key source files:

- `lib/src/main/kotlin/io/github/spokk/Markers.kt` — the block markers, declared as top-level
  `Unit` properties so that writing one on its own line is a valid no-op statement.
- `lib/src/main/kotlin/io/github/spokk/DataDriven.kt` — the `` `|` `` data table (`TableScope`,
  `DataRow`) and the `where(...)` overloads (2–5 typed columns + a whole-`DataRow` form).
- `lib/src/main/kotlin/io/github/spokk/Mocking.kt` — the Spock-style mocking DSL wrapping MockK:
  `Mock`/`Stub`/`Spy` factories, the `stub { … }` recording alias for `every`, the top-level `any()`
  "any argument" marker, and the `Int.times`/`IntRange.times` operators that implement the
  `n * { … }` interaction cardinality (delegating to MockK `verify`).
- `compiler-plugin/src/main/kotlin/io/github/spokk/compiler/SpokkCompilerPluginRegistrar.kt` — the
  `CompilerPluginRegistrar` entry point (K2); registers the FIR extension, then the IR extensions.
- `compiler-plugin/src/main/kotlin/io/github/spokk/compiler/SpokkFirExtensions.kt` — the FIR
  extension that synthesises the top-level `` `_` `` matcher property (Spock's `_`).
- `compiler-plugin/src/main/kotlin/io/github/spokk/compiler/SpokkIrGenerationExtension.kt` — the IR
  transform that rewrites the bare booleans **and** the closure-free `` mock.call(`_`) > value ``
  stubs, and replaces `` `_` ``/`any()` markers inside mocking-scope lambdas with MockK `any()`.
- `lib/src/test/kotlin/io/github/spokk/` — the example specs that double as the test suite
  (`FooTest`, `ClosureSemanticsTest`, `WhereTest`, `InteractionTest`).

## Tech stack (pinned)

- **Kotlin `2.4.0`** (K2). The compiler plugin uses internal Power-assert APIs
  (`PowerAssertConfiguration`, `PowerAssertIrGenerationExtension`) that are version-coupled to this
  exact Kotlin version — do not bump Kotlin without re-checking those APIs.
- **JUnit Jupiter `6.0.1`** (test engine + API).
- **kotlinx-coroutines `1.10.2`** (drives `suspend` data-driven bodies via `runBlocking`).
- **MockK `1.14.11`** (backs the mocking DSL). It is an `api` dependency of `:lib` because the DSL
  exposes MockK scope types (`MockKMatcherScope`, `MockKStubScope`, `MockKVerificationScope`) in its
  public signatures.
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
5. **Closure-free stubbing.** `SpokkAssertionTransformer.tryRewriteClosureFreeStub` recognises a
   statement `mock.call(`_`/any()) > value` (a `>` whose left operand is a call carrying the
   `` `_` ``/`any()` marker) and rewrites it into `every { mock.call(any()) } returns value`,
   building the `every` lambda in IR and swapping each marker for a real `MockKMatcherScope.any()`.
   Detection is anchored on the marker, so ordinary comparisons are never touched; `> value` only
   type-checks for `Comparable` return types. `replaceMarkersInMockKScope` does the same marker→`any()`
   swap inside any lambda whose receiver is a `MockKMatcherScope` (`stub { … }`, `n * { … }`, …).
6. **The `` `_` `` matcher is FIR-synthesised** (`SpokkUnderscoreMatcherExtension`) as a top-level
   `io.github.spokk._` property of type `Nothing` (assignable to any argument). `_` is a reserved
   name, so it can only be *referenced* back-tick escaped (`` `_` ``) and never bare; a
   plugin-generated *declaration* is exempt from the reserved-name source check. Its getter is never
   executed — every use is rewritten to `any()`.

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
- **Mocking DSL — braces vs. the closure-free `>` form.** Interaction *verification* always needs the
  braces (`n * { mock.call(...) }`): Kotlin (unlike Groovy) evaluates `mock.call(...)` eagerly, so the
  lambda is what lets MockK *record* the call. Stubbing can be closure-free — `` mock.call(`_`) > value ``
  — because the IR plugin rewrites that `>` into `every { … } returns …`; this only works for
  `Comparable` return types, so `returnsMany`/`answers`/`throws` (and non-`Comparable` returns) still
  use the `stub { … }` recording. `>>`/bare `_` are impossible in Kotlin (no `>>` operator; `_` is a
  reserved identifier usable only as `` `_` ``), which is why spokk uses single `>` and `` `_` ``.
- **Touching the closure-free stub / `` `_` `` rewrite is delicate.** It builds IR by hand (a lambda for
  `every`, MockK `any()` calls) and depends on the FIR-synthesised `` `_` `` property and exact MockK
  symbols (`io.mockk.every`, `MockKMatcherScope.any`, `MockKStubScope.returns`). After changing it,
  run `InteractionTest` and confirm a closure-free stub really stubs (a relaxed mock returns a default
  otherwise) — a green build alone does not prove the rewrite fired.
- **Verify interaction checks actually fail** (same spirit as assertions). An over/under-specified
  cardinality must throw an `AssertionError`; `InteractionTest` keeps a spec that asserts this via
  `assertThrows`. Mocking a concrete/`final` class makes MockK attach a Java agent dynamically (a
  harmless JVM warning on modern JDKs); mocking interfaces does not.
- **Configuration cache is on.** Keep the build configuration-cache compatible; avoid reading
  mutable state at configuration time.
- Clean up any temporary debug specs / scratch files before finishing; the repo should stay green
  (`./gradlew clean build`) and free of scratch artifacts.

## Repository / git notes

- Remote: `git@github.com:T45K/spokk.git` (branch `main`).
- `.idea` and Gradle build outputs are git-ignored (see `.gitignore`); do not re-add them.
- When committing on the user's behalf, add the trailer
  `Co-authored-by: Junie <junie@jetbrains.com>`. Do not commit unless explicitly asked.
