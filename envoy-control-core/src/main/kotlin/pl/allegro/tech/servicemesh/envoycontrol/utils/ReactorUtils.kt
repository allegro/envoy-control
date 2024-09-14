package pl.allegro.tech.servicemesh.envoycontrol.utils

import io.micrometer.core.instrument.MeterRegistry
import org.reactivestreams.Subscription
import org.slf4j.LoggerFactory
import reactor.core.Disposable
import reactor.core.Fuseable
import reactor.core.Scannable
import reactor.core.publisher.Flux
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.streams.asSequence

private val logger = LoggerFactory.getLogger("pl.allegro.tech.servicemesh.envoycontrol.utils.ReactorUtils")
private val defaultScheduler by lazy { Schedulers.newSingle("reactor-utils-scheduler") }
private const val DEFAULT_CHECK_INTERVAL_SECONDS = 60L
private val defaultCheckInterval = Duration.ofSeconds(DEFAULT_CHECK_INTERVAL_SECONDS)

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

/**
 * Measures how many items are discarded by all previous operators.
 * If you want to measure how many items are discarded by specific operator only, measure it BEFORE and AFTER that
 * operator and calculate difference between them
 */
fun <T> Flux<T>.measureDiscardedItems(name: String, meterRegistry: MeterRegistry): Flux<T> = this
    .doOnDiscard(Any::class.java) { meterRegistry.counter("reactor-discarded-items.$name").increment() }

fun <T> Flux<T>.onBackpressureLatestMeasured(name: String, meterRegistry: MeterRegistry): Flux<T> =
    measureDiscardedItems("$name-before", meterRegistry)
        .onBackpressureLatest()
        .measureDiscardedItems(name, meterRegistry)

/**
 * It is possible that operator combineLatest() or maybe other operators may send cancel() to upstream publisher but
 * don't send error to a subscriber. The error is stored internally but is not dispatched due to some kind of deadlock.
 * This method can be used to log such a exception despite that.
 */
fun <T> Flux<T>.logSuppressedError(
    message: String,
    checkInterval: Duration = defaultCheckInterval,
    scheduler: Scheduler = defaultScheduler
): Flux<T> {
    var subscribtion: Subscription? = null
    var task: Disposable? = null
    return this
        .doOnSubscribe { s ->
            subscribtion = s
            task?.dispose()
            task = scheduler.schedulePeriodically(
                {
                    if (logError(s, message)) {
                        task?.dispose()
                    }
                },
                checkInterval.toMillis(),
                checkInterval.toMillis(),
                TimeUnit.MILLISECONDS
            )
        }
        .doFinally { signal ->
            task?.dispose()
            subscribtion?.let { logError(it, "$message (flux terminated with signal: $signal") }
        }
}

private fun logError(s: Subscription, message: String): Boolean = Scannable.from(s)
    .scan(Scannable.Attr.ERROR)
    ?.let {
        logger.error(message, it)
        true
    }
    ?: false

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
    for (i in 0 until innerSources) {
        meterRegistry.gauge("${bufferMetric(name)}_$i", scannable, innerBufferExtractor(i))
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

sealed class ParallelizableScheduler
object DirectScheduler : ParallelizableScheduler()
data class ParallelScheduler(
    val scheduler: Scheduler,
    val parallelism: Int
) : ParallelizableScheduler()

fun <T> Flux<T>.doOnNextScheduledOn(
    scheduler: ParallelizableScheduler,
    doOnNext: (T) -> Unit
): Flux<T> = when (scheduler) {
    is DirectScheduler -> {
        doOnNext(doOnNext)
    }
    is ParallelScheduler -> {
        this.parallel(scheduler.parallelism)
            .runOn(scheduler.scheduler)
            .doOnNext(doOnNext)
            .sequential()
    }
}
