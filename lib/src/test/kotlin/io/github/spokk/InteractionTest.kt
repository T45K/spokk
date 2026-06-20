package io.github.spokk

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** A collaborator of [Publisher]; the thing we mock/stub in the specs below. */
interface Subscriber {
    fun receive(message: String): String
}

/** Subject under specification: broadcasts each message to all of its subscribers. */
class Publisher {
    private val subscribers = mutableListOf<Subscriber>()

    fun subscribe(subscriber: Subscriber) {
        subscribers += subscriber
    }

    fun send(message: String) {
        subscribers.forEach { it.receive(message) }
    }
}

/** A small, spy-able subject (an `open` class so the spy can override its methods). */
open class Greeter {
    open fun greet(name: String): String = "Hello, $name"
}

/**
 * Spock-style interaction-based specifications, mirroring
 * [Spock's mocking chapter](https://spockframework.org/spock/docs/2.4/interaction_based_testing.html).
 *
 * Collaborators are created with [Mock]/[Stub]/[Spy], stubbed with `stub { … } returns …`, and the
 * interactions are verified in a `then` block with a Spock-like cardinality (`1 * { … }`).
 */
class InteractionTest {

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
        // "When the publisher sends 'hello', both subscribers receive it exactly once."
        1 * { subscriber.receive("hello") }
        1 * { subscriber2.receive("hello") }
    }

    @Test
    fun `cardinalities count how often a call happens`() {
        given
        val subscriber = Mock<Subscriber>()
        val publisher = Publisher()
        publisher.subscribe(subscriber)

        `when`
        publisher.send("a")
        publisher.send("b")

        then
        2 * { subscriber.receive(any()) }       // exactly two calls
        (1..3) * { subscriber.receive(any()) }  // between one and three calls
        0 * { subscriber.receive("never") }     // this exact call never happened
    }

    @Test
    fun `argument matchers constrain the verified call`() {
        given
        val subscriber = Mock<Subscriber>()
        val publisher = Publisher()
        publisher.subscribe(subscriber)

        `when`
        publisher.send("hello")

        then
        1 * { subscriber.receive(match { it.length > 3 }) }
    }

    @Test
    fun `a wrong cardinality fails the interaction check`() {
        given
        val subscriber = Mock<Subscriber>()
        val publisher = Publisher()
        publisher.subscribe(subscriber)

        `when`
        publisher.send("hello") // 'receive' happens once

        // Proves verification really runs: demanding two calls when only one happened must fail.
        expect
        assertThrows<AssertionError> {
            2 * { subscriber.receive("hello") }
        }
    }

    @Test
    fun `stubbing makes a collaborator respond`() {
        given
        val subscriber = Stub<Subscriber>()
        stub { subscriber.receive(any()) } returns "ok"

        expect
        subscriber.receive("hello") == "ok"
        subscriber.receive("whatever") == "ok"
    }

    @Test
    fun `stub returns successive values`() {
        given
        val subscriber = Stub<Subscriber>()
        stub { subscriber.receive(any()) } returnsMany listOf("ok", "error", "ok")

        expect
        subscriber.receive("1") == "ok"
        subscriber.receive("2") == "error"
        subscriber.receive("3") == "ok"
        subscriber.receive("4") == "ok" // the last value keeps repeating
    }

    @Test
    fun `stub can compute the response from the arguments`() {
        given
        val subscriber = Stub<Subscriber>()
        stub { subscriber.receive(any()) } answers { firstArg<String>().uppercase() }

        expect
        subscriber.receive("hello") == "HELLO"
    }

    @Test
    fun `stub can throw`() {
        given
        val subscriber = Stub<Subscriber>()
        stub { subscriber.receive("boom") } throws IllegalStateException("nope")

        expect
        assertThrows<IllegalStateException> { subscriber.receive("boom") }
    }

    @Test
    fun `spy runs the real method unless it is stubbed`() {
        given
        val greeter = Spy(Greeter())
        stub { greeter.greet("Bob") } returns "Yo, Bob"

        expect
        greeter.greet("Bob") == "Yo, Bob"    // stubbed
        greeter.greet("Ann") == "Hello, Ann" // real implementation
    }

    // --- Closure-free stubbing (`mock.call(_) > value`) -----------------------------------------
    // The plugin rewrites a `>` whose left-hand side is a mock call using `_`/`any()` into a real
    // `every { … } returns …`, so a stub can be written without the `stub { … }` wrapper — closer to
    // Spock's `subscriber.receive(_) >> "ok"`.

    @Test
    fun `closure-free stubbing with the underscore matcher`() {
        given
        val subscriber = Stub<Subscriber>()
        subscriber.receive(`_`) > "ok" // == Spock's `subscriber.receive(_) >> "ok"`

        expect
        subscriber.receive("hello") == "ok"
        subscriber.receive("whatever") == "ok"
    }

    @Test
    fun `closure-free stubbing with the any matcher`() {
        given
        val subscriber = Stub<Subscriber>()
        subscriber.receive(any()) > "ok"

        expect
        subscriber.receive("hello") == "ok"
    }

    @Test
    fun `the underscore matcher also works inside stub and verification blocks`() {
        given
        val subscriber = Mock<Subscriber>()
        stub { subscriber.receive(`_`) } returns "stubbed"

        `when`
        val result = subscriber.receive("hi")

        then
        result == "stubbed"
        1 * { subscriber.receive(`_`) }
    }
}
