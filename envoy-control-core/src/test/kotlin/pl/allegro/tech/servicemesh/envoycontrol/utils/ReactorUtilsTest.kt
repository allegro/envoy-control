package pl.allegro.tech.servicemesh.envoycontrol.utils

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.BiFunction

class ReactorUtilsTest {

    @Test
    fun `should measure buffer size of publishOn operator`() {
        // given
        val meterRegistry = SimpleMeterRegistry()

        // when
        val received = Flux.range(0, 20)
            .publishOn(Schedulers.single())
            .measureBuffer("publish", meterRegistry)
            .subscribeRequestingN(n = 5)

        // then
        assertThat(received.await(2, TimeUnit.SECONDS)).isTrue()

        val buffered = meterRegistry["reactor-buffers.publish"].gauge()
        assertThat(buffered.value()).isEqualTo(15.0)
    }

    @Test
    fun `should measure buffer size of merge operator`() {
        // given
        val meterRegistry = SimpleMeterRegistry()

        // when
        val received = Flux.merge(32, Flux.range(0, 12), Flux.range(0, 8))
            .measureBuffer("merge", meterRegistry, innerSources = 2)
            .subscribeRequestingN(n = 5)

        // then
        assertThat(received.await(2, TimeUnit.SECONDS)).isTrue()

        val sourcesCount = meterRegistry["reactor-buffers.merge"].gauge().value()
        val source0Buffered = meterRegistry["reactor-buffers.merge_0"].gauge().value()
        val source1Buffered = meterRegistry["reactor-buffers.merge_1"].gauge().value()

        assertThat(sourcesCount).isEqualTo(2.0)
        // 12 published minus 5 requested = 7
        assertThat(source0Buffered).isEqualTo(7.0)
        // all 8 published, none requested by subscriber, who already received 5 items from source0
        assertThat(source1Buffered).isEqualTo(8.0)
    }

    @Test
    fun `should measure buffer size of combineLatest operator`() {
        // given
        val meterRegistry = SimpleMeterRegistry()

        // when
        val received = Flux.combineLatest(Flux.range(0, 3), Flux.range(100, 6), BiFunction { a: Int, b: Int -> a + b })
            .measureBuffer("combine", meterRegistry)
            .subscribeRequestingN(n = 4)

        // then
        assertThat(received.await(2, TimeUnit.SECONDS)).isTrue()

        val buffered = meterRegistry["reactor-buffers.combine"].gauge().value()

        // only two last items from source1 are undelivered (6 produces - 4 requested = 2)
        assertThat(buffered).isEqualTo(2.0)
    }

    @Test
    fun `should measure discarded items of backpressureLatest`() {
        // given
        val meterRegistry = SimpleMeterRegistry()

        // when
        val received = Flux.range(0, 10)
            .filter { it % 2 == 0 } // 5 items will be discarded by filter
            .onBackpressureLatestMeasured("latest", meterRegistry)
            .subscribeRequestingN(n = 2)

        // then
        assertThat(received.await(2, TimeUnit.SECONDS)).isTrue()

        val discardedItemsBeforeBackpressure = meterRegistry["reactor-discarded-items.latest-before"].counter().count()
        val discardedItemsAfterBackpressure = meterRegistry["reactor-discarded-items.latest"].counter().count()

        /**
         * Published by range: (0..10)
         * After filtering: (0, 2, 4, 6, 8) (5 discarded)
         * Requested and dispatched to subscriber: (0, 2)
         * Not dispatched to subscriber, received by onBackpressure: (4, 6, 8)
         * Discarded by onBackpressure: (4, 6)
         */
        assertThat(discardedItemsAfterBackpressure - discardedItemsBeforeBackpressure).isEqualTo(2.0)
    }

    private fun <T> Flux<T>.subscribeRequestingN(n: Int): CountDownLatch {
        val received = CountDownLatch(n)
        subscribe(
            { i -> received.countDown() },
            { e -> fail(e) },
            {},
            { subscription ->
                subscription.request(n.toLong())
            }
        )
        return received
    }
}
