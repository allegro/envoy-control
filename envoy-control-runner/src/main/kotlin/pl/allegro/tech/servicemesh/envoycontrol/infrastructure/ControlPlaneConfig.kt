package pl.allegro.tech.servicemesh.envoycontrol.infrastructure

import com.ecwid.consul.v1.ConsulClient
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.protobuf.ProtobufJsonFormatHttpMessageConverter
import pl.allegro.tech.discovery.consul.recipes.ConsulRecipes
import pl.allegro.tech.discovery.consul.recipes.datacenter.ConsulDatacenterReader
import pl.allegro.tech.discovery.consul.recipes.json.JacksonJsonDeserializer
import pl.allegro.tech.discovery.consul.recipes.json.JacksonJsonSerializer
import pl.allegro.tech.discovery.consul.recipes.watch.ConsulWatcher
import pl.allegro.tech.servicemesh.envoycontrol.ControlPlane
import pl.allegro.tech.servicemesh.envoycontrol.DefaultEnvoyControlMetrics
import pl.allegro.tech.servicemesh.envoycontrol.EnvoyControlMetrics
import pl.allegro.tech.servicemesh.envoycontrol.EnvoyControlProperties
import pl.allegro.tech.servicemesh.envoycontrol.chaos.domain.ChaosService
import pl.allegro.tech.servicemesh.envoycontrol.chaos.storage.ChaosDataStore
import pl.allegro.tech.servicemesh.envoycontrol.chaos.storage.SimpleChaosDataStore
import pl.allegro.tech.servicemesh.envoycontrol.consul.ConsulProperties
import pl.allegro.tech.servicemesh.envoycontrol.consul.services.ConsulLocalClusterStateChanges
import pl.allegro.tech.servicemesh.envoycontrol.consul.services.ConsulServiceChanges
import pl.allegro.tech.servicemesh.envoycontrol.consul.services.ConsulServiceMapper
import pl.allegro.tech.servicemesh.envoycontrol.consul.services.NoOpServiceWatchPolicy
import pl.allegro.tech.servicemesh.envoycontrol.consul.services.ServiceWatchPolicy
import pl.allegro.tech.servicemesh.envoycontrol.server.NoopReadinessStateHandler
import pl.allegro.tech.servicemesh.envoycontrol.server.ReadinessStateHandler
import pl.allegro.tech.servicemesh.envoycontrol.services.ClusterStateChanges
import pl.allegro.tech.servicemesh.envoycontrol.services.LocalClusterStateChanges
import pl.allegro.tech.servicemesh.envoycontrol.services.Locality
import pl.allegro.tech.servicemesh.envoycontrol.services.transformers.EmptyAddressFilter
import pl.allegro.tech.servicemesh.envoycontrol.services.transformers.InstanceMerger
import pl.allegro.tech.servicemesh.envoycontrol.services.transformers.InvalidPortFilter
import pl.allegro.tech.servicemesh.envoycontrol.services.transformers.IpAddressFilter
import pl.allegro.tech.servicemesh.envoycontrol.services.transformers.RegexServiceInstancesFilter
import pl.allegro.tech.servicemesh.envoycontrol.services.transformers.ServiceInstancesTransformer
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.EnvoyHttpFilters
import pl.allegro.tech.servicemesh.envoycontrol.synchronization.GlobalStateChanges
import pl.allegro.tech.servicemesh.envoycontrol.utils.CACHE_GROUP_COUNT_METRIC
import pl.allegro.tech.servicemesh.envoycontrol.utils.ERRORS_TOTAL_METRIC
import pl.allegro.tech.servicemesh.envoycontrol.utils.METRIC_EMITTER_TAG
import pl.allegro.tech.servicemesh.envoycontrol.utils.STATUS_TAG
import pl.allegro.tech.servicemesh.envoycontrol.utils.WATCH_METRIC
import pl.allegro.tech.servicemesh.envoycontrol.utils.WATCH_TYPE_TAG
import reactor.core.scheduler.Schedulers
import java.net.URI

@Configuration
class ControlPlaneConfig {
    init {
        Schedulers.enableMetrics()
    }

    @Bean
    @ConfigurationProperties("envoy-control")
    fun envoyControlProperties() = EnvoyControlProperties()

    @Bean
    @ConfigurationProperties("envoy-control.source.consul")
    fun consulProperties() = ConsulProperties()

    @Bean
    @ConditionalOnMissingBean(ControlPlane::class)
    @Suppress("LongParameterList")
    fun controlPlane(
        properties: EnvoyControlProperties,
        meterRegistry: MeterRegistry,
        globalStateChanges: GlobalStateChanges,
        metrics: EnvoyControlMetrics,
        envoyHttpFilters: EnvoyHttpFilters,
        consulProperties: ConsulProperties
    ): ControlPlane =
        ControlPlane.builder(properties, meterRegistry)
            .withMetrics(metrics)
            .withCurrentZone(localDatacenter(consulProperties))
            .withEnvoyHttpFilters(envoyHttpFilters)
            .build(globalStateChanges.combined())

    @Bean
    @ConditionalOnMissingBean(ConsulServiceMapper::class)
    fun consulServiceMapper(properties: ConsulProperties) = ConsulServiceMapper(
        canaryTag = properties.tags.canary,
        weightTag = properties.tags.weight,
        defaultWeight = properties.tags.defaultWeight
    )

    @Bean
    @ConditionalOnMissingBean(ServiceWatchPolicy::class)
    fun serviceWatchPolicy(): ServiceWatchPolicy = NoOpServiceWatchPolicy

    @Bean
    @Suppress("LongParameterList")
    fun consulServiceChanges(
        watcher: ConsulWatcher,
        serviceMapper: ConsulServiceMapper,
        metrics: EnvoyControlMetrics,
        objectMapper: ObjectMapper,
        consulProperties: ConsulProperties,
        readinessStateHandler: ReadinessStateHandler,
        watchPolicy: ServiceWatchPolicy,
    ) = ConsulServiceChanges(
        watcher,
        serviceMapper,
        metrics,
        objectMapper,
        consulProperties.subscriptionDelay,
        readinessStateHandler,
        watchPolicy
    )

    @Bean
    fun localClusterStateChanges(
        consulServiceChanges: ConsulServiceChanges,
        consulProperties: ConsulProperties,
        transformers: List<ServiceInstancesTransformer>
    ): LocalClusterStateChanges = ConsulLocalClusterStateChanges(
        consulServiceChanges,
        Locality.LOCAL,
        localDatacenter(consulProperties),
        transformers
    )

    @Bean
    fun consulDatacenterReader(consulProperties: ConsulProperties, objectMapper: ObjectMapper): ConsulDatacenterReader =
        ConsulRecipes.consulRecipes()
            .withJsonDeserializer(JacksonJsonDeserializer(objectMapper))
            .withJsonSerializer(JacksonJsonSerializer(objectMapper))
            .build()
            .consulDatacenterReader()
            .withAgentUri(URI("http://${consulProperties.host}:${consulProperties.port}"))
            .build()

    @Bean
    fun envoyControlMetrics(meterRegistry: MeterRegistry): EnvoyControlMetrics = controlPlaneMetrics(meterRegistry)

    @Bean
    fun emptyAddressFilter() = EmptyAddressFilter()

    @Bean
    fun invalidPortFilter() = InvalidPortFilter()

    @Bean
    fun instanceMerger() = InstanceMerger()

    @Bean
    fun ipAddressFilter() = IpAddressFilter()

    @Bean
    @ConditionalOnMissingBean(ReadinessStateHandler::class)
    fun readinessStateHandler() = NoopReadinessStateHandler

    @Bean
    @ConditionalOnProperty("envoy-control.service-filters.excluded-names-patterns")
    fun excludeServicesFilter(properties: EnvoyControlProperties) =
        RegexServiceInstancesFilter(properties.serviceFilters.excludedNamesPatterns)

    @Bean
    fun globalStateChanges(
        clusterStateChanges: Array<ClusterStateChanges>,
        meterRegistry: MeterRegistry,
        properties: EnvoyControlProperties
    ): GlobalStateChanges =
        GlobalStateChanges(clusterStateChanges, meterRegistry, properties.sync)

    @Bean
    @ConditionalOnMissingBean(EnvoyHttpFilters::class)
    fun envoyHttpFilters(
        properties: EnvoyControlProperties
    ): EnvoyHttpFilters {
        return EnvoyHttpFilters.defaultFilters(properties.envoy.snapshot)
    }

    fun localDatacenter(properties: ConsulProperties) =
        ConsulClient(properties.host, properties.port).agentSelf.value?.config?.datacenter ?: "local"

    fun controlPlaneMetrics(meterRegistry: MeterRegistry): DefaultEnvoyControlMetrics {
        return DefaultEnvoyControlMetrics(meterRegistry = meterRegistry).also {
            meterRegistry.gauge(WATCH_METRIC, Tags.of(STATUS_TAG, "added", WATCH_TYPE_TAG, "service"), it.servicesAdded)
            meterRegistry.gauge(
                WATCH_METRIC,
                Tags.of(STATUS_TAG, "removed", WATCH_TYPE_TAG, "service"),
                it.servicesRemoved
            )
            meterRegistry.gauge(
                WATCH_METRIC,
                Tags.of(STATUS_TAG, "instance-changed", WATCH_TYPE_TAG, "service"),
                it.instanceChanges
            )
            meterRegistry.gauge(
                WATCH_METRIC,
                Tags.of(STATUS_TAG, "snapshot-changed", WATCH_TYPE_TAG, "service"),
                it.snapshotChanges
            )
            meterRegistry.gauge(CACHE_GROUP_COUNT_METRIC, it.cacheGroupsCount)
            it.meterRegistry.more().counter(
                ERRORS_TOTAL_METRIC,
                Tags.of(METRIC_EMITTER_TAG, WATCH_METRIC, WATCH_TYPE_TAG, "service"),
                it.errorWatchingServices
            )
        }
    }

    @Bean
    fun protobufJsonFormatHttpMessageConverter(): ProtobufJsonFormatHttpMessageConverter {
        return ProtobufJsonFormatHttpMessageConverter()
    }

    @Bean
    fun chaosDataStore(): ChaosDataStore = SimpleChaosDataStore()

    @Bean
    @ConditionalOnMissingBean(ChaosService::class)
    fun chaosService(chaosDataStore: ChaosDataStore): ChaosService = ChaosService(chaosDataStore = chaosDataStore)
}
