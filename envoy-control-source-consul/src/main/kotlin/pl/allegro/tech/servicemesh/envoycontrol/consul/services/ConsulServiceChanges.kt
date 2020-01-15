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
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstances
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import pl.allegro.tech.discovery.consul.recipes.watch.catalog.ServiceInstances as RecipesServiceInstances
import pl.allegro.tech.discovery.consul.recipes.watch.catalog.Services as RecipesServices

class ConsulServiceChanges(
    private val watcher: ConsulWatcher,
    private val serviceMapper: ConsulServiceMapper = ConsulServiceMapper(),
    private val metrics: EnvoyControlMetrics = DefaultEnvoyControlMetrics(),
    private val objectMapper: ObjectMapper = ObjectMapper().registerModule(KotlinModule()),
    private val subscriptionDelay: Duration = Duration.ZERO
) {
    private val logger by logger()

    fun watchState(): Flux<ServicesState> {
        val watcher = StateWatcher(watcher, serviceMapper, objectMapper, metrics, subscriptionDelay)
        return Flux.create<ServicesState>(
            { sink ->
                watcher.stateReceiver = { sink.next(it) }
            },
            FluxSink.OverflowStrategy.LATEST
        )
            .distinctUntilChanged()
            .doOnSubscribe { watcher.start() }
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
        private val subscriptionDelay: Duration
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

        private val initialLoader = InitialLoader()

        fun start() {
            if (canceller == null) {
                synchronized(StateWatcher::class.java) {
                    if (canceller == null) {
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
                watchedServices.values.forEach { canceller -> canceller.cancel() }
                watchedServices.clear()
                canceller?.cancel()
                canceller = null
            }
        }

        private fun handleServicesChange(services: RecipesServices) = synchronized(servicesLock) {
            initialLoader.update(services.serviceNames())

            val newServices = services.serviceNames() - lastServices
            newServices.forEach { service ->
                handleNewService(service)
                Thread.sleep(subscriptionDelay.toMillis())
            }

            val removedServices = lastServices - services.serviceNames()
            removedServices.forEach { handleServiceRemoval(it) }

            lastServices = services.serviceNames()
        }

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

            val newState = state.add(service)
            changeState(newState)
            metrics.serviceAdded()
        }

        private fun handleServiceInstancesChange(recipesInstances: RecipesServiceInstances) = synchronized(stateLock) {
            initialLoader.observed(recipesInstances.serviceName)

            val instances = recipesInstances.toDomainInstances()
            val newState = state.change(instances)
            if (state !== newState) {
                val addresses = instances.instances.joinToString { "[${it.id} - ${it.address}:${it.port}]" }
                logger.info("Instances for ${instances.serviceName} changed: $addresses")

                changeState(newState)
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
            val newState = state.remove(service)
            changeState(newState)
            watchedServices[service]?.cancel()
            watchedServices.remove(service)
            metrics.serviceRemoved()
        }

        private fun changeState(newState: ServicesState) {
            if (initialLoader.ready) {
                stateReceiver(newState)
            }
            state = newState
        }

        private class InitialLoader {
            private val remaining = ConcurrentHashMap.newKeySet<String>()
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
                }
            }
        }
    }
}
