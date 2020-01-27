package pl.allegro.tech.servicemesh.envoycontrol.utils

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import reactor.core.Scannable
import reactor.core.publisher.Flux

private val logger = LoggerFactory.getLogger("pl.allegro.tech.servicemesh.envoycontrol.utils.ReactorUtils")

/**
 * Measures buffer size of compatible reactor operators. Reports it as a metric.
 *
 * Usage:
 *   use in a Flux chain AFTER a buffering operator
 * Supported operators:
 * - Flux.create with default BUFFER OverflowStrategy
 * - onBackpressureBuffer
 * - onBackpressureLatest (buffer size is always 0 or 1)
 * - publishOn
 */
fun <T> Flux<T>.measureBuffer(name: String, meterRegistry: MeterRegistry): Flux<T> = this.doOnSubscribe { s ->
    if (s !is Scannable) {
        logger.error("is not scannable")
        return@doOnSubscribe
    }

    val buffered = s.scan(Scannable.Attr.BUFFERED)
    if (buffered == null) {
        logger.error("buffer size not available")
        return@doOnSubscribe
    }

    val supplier = { sc: Scannable -> sc.scan(Scannable.Attr.BUFFERED)?.toDouble() ?: 0.0 }

    meterRegistry.gauge("reactor-buffers.$name", s, supplier)
}

/**
 * Measures how many items were discarded by previous reactor operator. Reports it as a metric.
 *
 * Usage:
 *   use in a Flux chain after a operator, that may discard items.
 *
 * Useful for onBackpressureLatest, onBackpressureDrop
 */
fun <T> Flux<T>.measureDiscardedItems(name: String, meterRegistry: MeterRegistry): Flux<T> = this
    .doOnDiscard(Any::class.java) {
        meterRegistry.counter("reactor-discarded-items.$name").increment()
    }
