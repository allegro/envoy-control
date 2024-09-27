package pl.allegro.tech.servicemesh.envoycontrol.consul.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import pl.allegro.tech.discovery.consul.recipes.json.JacksonJsonDeserializer
import pl.allegro.tech.discovery.consul.recipes.watch.Canceller
import pl.allegro.tech.discovery.consul.recipes.watch.ConsulWatcher
import pl.allegro.tech.discovery.consul.recipes.watch.catalog.ServicesWatcher
import pl.allegro.tech.discovery.consul.recipes.watch.health.HealthServiceInstancesWatcher
import pl.allegro.tech.servicemesh.envoycontrol.DefaultEnvoyControlMetrics
import pl.allegro.tech.servicemesh.envoycontrol.EnvoyControlMetrics
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.server.ReadinessStateHandler
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstances
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import pl.allegro.tech.servicemesh.envoycontrol.utils.measureDiscardedItems
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import pl.allegro.tech.discovery.consul.recipes.watch.catalog.ServiceInstances as RecipesServiceInstances
import pl.allegro.tech.discovery.consul.recipes.watch.catalog.Services as RecipesServices

@Suppress("LongParameterList")
class ConsulServiceChanges(
    private val watcher: ConsulWatcher,
    private val serviceMapper: ConsulServiceMapper = ConsulServiceMapper(),
    private val metrics: EnvoyControlMetrics = DefaultEnvoyControlMetrics(),
    private val objectMapper: ObjectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build()),
    private val subscriptionDelay: Duration = Duration.ZERO,
    private val readinessStateHandler: ReadinessStateHandler,
    private val serviceWatchPolicy: ServiceWatchPolicy = NoOpServiceWatchPolicy,
) {
    private val logger by logger()

    fun watchState(): Flux<ServicesState> {
        val watcher =
            StateWatcher(
                watcher,
                serviceMapper,
                objectMapper,
                metrics,
                subscriptionDelay,
                readinessStateHandler,
                serviceWatchPolicy,
            )
        return Flux.create<ServicesState>(
            { sink ->
                watcher.start { state: ServicesState -> sink.next(state) }
            },
            FluxSink.OverflowStrategy.LATEST
        )
            .measureDiscardedItems("consul-service-changes-emitted", metrics.meterRegistry)
            .checkpoint("consul-service-changes-emitted")
            .name("consul-service-changes-emitted").metrics()
            .checkpoint("consul-service-changes-emitted-distinct")
            .name("consul-service-changes-emitted-distinct").metrics()
            .doOnCancel {
                logger.warn("Cancelling watching consul service changes")
                watcher.close()
            }
    }

    private class StateWatcher(
        private val watcher: ConsulWatcher,
        private val serviceMapper: ConsulServiceMapper,
        private val objectMapper: ObjectMapper,
        private val metrics: EnvoyControlMetrics,
        private val subscriptionDelay: Duration,
        private val readinessStateHandler: ReadinessStateHandler,
        private val serviceWatchPolicy: ServiceWatchPolicy,
    ) : AutoCloseable {
        lateinit var stateReceiver: (ServicesState) -> (Unit)

        private val logger by logger()

        @Volatile
        private var canceller: Canceller? = null

        @Volatile
        private var state = ServicesState()
        private val stateLock = Any()
        private val watchedServices = mutableMapOf<String, Canceller>()

        @Volatile
        private var lastServices = setOf<String>()
        private val servicesLock = Any()

        private val initialLoader = InitialLoader(readinessStateHandler, metrics)

        fun start(stateReceiver: (ServicesState) -> Unit) {
            if (canceller == null) {
                synchronized(StateWatcher::class.java) {
                    if (canceller == null) {
                        this.stateReceiver = stateReceiver
                        canceller = ServicesWatcher(watcher, JacksonJsonDeserializer(objectMapper))
                            .watch(
                                { servicesResult -> handleServicesChange(servicesResult.body) },
                                { error ->
                                    metrics.errorWatchingServices()
                                    logger.warn(
                                        "Error while watching services list",
                                        error
                                    )
                                }
                            )
                    }
                }
            }
        }

        override fun close() {
            synchronized(stateLock) {
                readinessStateHandler.unready()
                watchedServices.values.forEach { canceller -> canceller.cancel() }
                watchedServices.clear()
                canceller?.cancel()
                canceller = null
            }
        }

        private fun handleServicesChange(services: RecipesServices) = synchronized(servicesLock) {
            val serviceNames = services.serviceNames()
                .filterTo(HashSet()) { shouldBeWatched(it, services.tagsForServiceOrNull(it)) }
            initialLoader.update(serviceNames)

            val newServices = serviceNames - lastServices
            newServices.forEach { service ->
                handleNewService(service)
                Thread.sleep(subscriptionDelay.toMillis())
            }

            val removedServices = lastServices - serviceNames
            removedServices.forEach { handleServiceRemoval(it) }

            lastServices = serviceNames
        }

        private fun shouldBeWatched(service: String, tags: List<String>?): Boolean =
            serviceWatchPolicy.shouldBeWatched(service, tags ?: emptyList())

        private fun handleNewService(service: String) = synchronized(stateLock) {
            val instancesWatcher = HealthServiceInstancesWatcher(
                service, watcher, JacksonJsonDeserializer(objectMapper)
            )
            logger.info("Start watching $service on ${instancesWatcher.endpoint()}")
            val canceller = instancesWatcher.watch(
                { instances -> handleServiceInstancesChange(instances.body) },
                { error -> logger.warn("Error while watching service $service", error) }
            )
            val oldCanceller = watchedServices.put(service, canceller)
            oldCanceller?.cancel()

            val stateChanged = state.add(service)
            if (stateChanged) publishState()
            metrics.serviceAdded()
        }

        private fun handleServiceInstancesChange(recipesInstances: RecipesServiceInstances) = synchronized(stateLock) {
            initialLoader.observed(recipesInstances.serviceName)
            val instances = recipesInstances.toDomainInstances()
            val stateChanged = state.change(instances)
            if (stateChanged) {
                val addresses = instances.instances.joinToString { "[${it.id} - ${it.address}:${it.port}]" }
                logger.info("Instances for ${instances.serviceName} changed: $addresses")

                publishState()
                metrics.instanceChanged()
            }
        }

        private fun RecipesServiceInstances.toDomainInstances(): ServiceInstances =
            ServiceInstances(
                serviceName,
                instances.asSequence()
                    .map { serviceMapper.toDomainInstance(it) }
                    .toSet()
            )

        private fun handleServiceRemoval(service: String) = synchronized(stateLock) {
            logger.info("Stop watching $service")
            val stateChanged = state.remove(service)
            if (stateChanged) publishState()
            watchedServices[service]?.cancel()
            watchedServices.remove(service)
            metrics.serviceRemoved()
        }

        private fun publishState() {
            if (initialLoader.ready) {
                stateReceiver(state)
            }
        }

        private class InitialLoader(
            private val readinessStateHandler: ReadinessStateHandler,
            private val metrics: EnvoyControlMetrics
        ) {
            private val remaining = ConcurrentHashMap.newKeySet<String>()
            private var startTimer: Long = 0

            init {
                startTimer = System.currentTimeMillis()
                readinessStateHandler.unready()
            }

            @Volatile
            private var initialized = false

            @Volatile
            var ready = false
                private set

            fun update(services: Collection<String>) {
                if (!initialized) {
                    remaining.addAll(services)
                    initialized = true
                }
            }

            fun observed(service: String) {
                if (!ready) {
                    remaining.remove(service)
                    ready = remaining.isEmpty()
                    if (ready) {
                        val stopTimer = System.currentTimeMillis()
                        readinessStateHandler.ready()
                        metrics.meterRegistry.timer("envoy-control.warmup.seconds")
                            .record(
                                stopTimer - startTimer,
                                TimeUnit.SECONDS
                            )
                    }
                }
            }
        }
    }
}
