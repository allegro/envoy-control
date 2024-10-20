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
    .doOnDiscard(Any::class.java) {
        meterRegistry.counter(
            REACTOR_METRIC,
            METRIC_TYPE_TAG, "discarded-items",
            METRIC_EMITTER_TAG, name
        ).increment()
    }

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
    logger.info("subscription $subscription name: $name meterRegistry: $meterRegistry")
}

private fun measureScannableBuffer(
    scannable: Scannable,
    name: String,
    innerSources: Int,
    meterRegistry: MeterRegistry
) {
  logger.info("scannable $scannable name: $name innerSources: $innerSources meterRegistry: $meterRegistry")
}

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
