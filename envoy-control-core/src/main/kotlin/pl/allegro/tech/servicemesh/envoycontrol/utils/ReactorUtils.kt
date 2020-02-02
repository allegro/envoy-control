package pl.allegro.tech.servicemesh.envoycontrol.utils

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import reactor.core.Fuseable
import reactor.core.Scannable
import reactor.core.publisher.Flux
import kotlin.streams.asSequence

private val logger = LoggerFactory.getLogger("pl.allegro.tech.servicemesh.envoycontrol.utils.ReactorUtils")

/**
 * Measures buffer size of compatible reactor operators. Reports it as a metric.
 *
 * Usage:
 *   use in a Flux chain AFTER a buffering operator
 * Supported operators:
 * - Flux.create with default BUFFER OverflowStrategy
 * - Flux.merge (set innerSources parameter to number of sources)
 * - Flux.combineLatest
 * - onBackpressureBuffer
 * - onBackpressureLatest (buffer size is always 0 or 1)
 * - publishOn
 */
fun <T> Flux<T>.measureBuffer(
    name: String,
    meterRegistry: MeterRegistry,
    innerSources: Int = 0
): Flux<T> = this.doOnSubscribe {

    when (it) {
        is Fuseable.QueueSubscription<*> -> measureQueueSubscriptionBuffer(it, name, meterRegistry)
        else -> measureScannableBuffer(Scannable.from(it), name, innerSources, meterRegistry)
    }
}

fun <T> Flux<T>.measureDiscardedItems(name: String, meterRegistry: MeterRegistry): Flux<T> = this
    .doOnDiscard(Any::class.java) { meterRegistry.counter("reactor-discarded-items.$name").increment() }

/**
 * Flux.combineLatest() is an example of QueueSubscription
 */
private fun measureQueueSubscriptionBuffer(
    subscription: Fuseable.QueueSubscription<*>,
    name: String,
    meterRegistry: MeterRegistry
) {
    meterRegistry.gauge(bufferMetric(name), subscription, queueSubscriptionBufferExtractor)
}

private fun measureScannableBuffer(
    scannable: Scannable,
    name: String,
    innerSources: Int,
    meterRegistry: MeterRegistry
) {
    val buffered = scannable.scan(Scannable.Attr.BUFFERED)
    if (buffered == null) {
        logger.error("Cannot register metric '${bufferMetric(name)}'. Buffer size not available. " +
            "Use measureBuffer() only on supported reactor operators")
        return
    }

    meterRegistry.gauge(bufferMetric(name), scannable, scannableBufferExtractor)

    /**
     * Special case for FlatMap derived operators like merge(). The main buffer attribute doesn't return actual
     * buffer (that is controlled by `prefetch` parameter) size. Instead it returns simply number of connected sources.
     *
     * To access actual buffer size, we need to extract it from inners(). We don't know how many sources will
     * be available, so it must be stated explicitly as innerSources parameter.
     */
    (0 until innerSources).forEach {
        meterRegistry.gauge("${bufferMetric(name)}_$it", scannable, innerBufferExtractor(it))
    }
}

private val scannableBufferExtractor = { s: Scannable -> s.scan(Scannable.Attr.BUFFERED)?.toDouble() ?: -1.0 }
private fun innerBufferExtractor(index: Int) = { s: Scannable ->
    s.inners().asSequence()
        .elementAtOrNull(index)
        ?.let(scannableBufferExtractor)
        ?: -1.0
}
private val queueSubscriptionBufferExtractor = { s: Fuseable.QueueSubscription<*> -> s.size.toDouble() }

private fun bufferMetric(name: String) = "reactor-buffers.$name"
