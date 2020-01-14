package pl.allegro.tech.servicemesh.envoycontrol.infrastructure

import com.ecwid.consul.v1.ConsulClient
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
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
import pl.allegro.tech.servicemesh.envoycontrol.consul.ConsulProperties
import pl.allegro.tech.servicemesh.envoycontrol.consul.services.ConsulLocalServiceChanges
import pl.allegro.tech.servicemesh.envoycontrol.consul.services.ConsulServiceChanges
import pl.allegro.tech.servicemesh.envoycontrol.consul.services.ConsulServiceMapper
import pl.allegro.tech.servicemesh.envoycontrol.services.LocalServiceChanges
import pl.allegro.tech.servicemesh.envoycontrol.services.Locality
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceChanges
import pl.allegro.tech.servicemesh.envoycontrol.services.transformers.EmptyAddressFilter
import pl.allegro.tech.servicemesh.envoycontrol.services.transformers.InstanceMerger
import pl.allegro.tech.servicemesh.envoycontrol.services.transformers.IpAddressFilter
import pl.allegro.tech.servicemesh.envoycontrol.services.transformers.RegexServiceInstancesFilter
import pl.allegro.tech.servicemesh.envoycontrol.services.transformers.ServiceInstancesTransformer
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.listeners.filters.EnvoyHttpFilters
import pl.allegro.tech.servicemesh.envoycontrol.synchronization.GlobalServiceChanges
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
    fun controlPlane(
        properties: EnvoyControlProperties,
        meterRegistry: MeterRegistry,
        globalServiceChanges: GlobalServiceChanges,
        metrics: EnvoyControlMetrics,
        envoyHttpFilters: EnvoyHttpFilters
    ): ControlPlane =
        ControlPlane.builder(properties, meterRegistry)
            .withMetrics(metrics)
            .withEnvoyHttpFilters(envoyHttpFilters)
            .build(globalServiceChanges.combined())

    @Bean
    @ConditionalOnMissingBean(ConsulServiceMapper::class)
    fun consulServiceMapper(properties: ConsulProperties) = ConsulServiceMapper(
        canaryTag = properties.tags.canary,
        weightTag = properties.tags.weight,
        defaultWeight = properties.tags.defaultWeight
    )

    @Bean
    fun consulServiceChanges(
        watcher: ConsulWatcher,
        serviceMapper: ConsulServiceMapper,
        metrics: EnvoyControlMetrics,
        objectMapper: ObjectMapper,
        consulProperties: ConsulProperties
    ) = ConsulServiceChanges(watcher, serviceMapper, metrics, objectMapper, consulProperties.subscriptionDelay)

    @Bean
    fun localServiceChanges(
        consulServiceChanges: ConsulServiceChanges,
        consulProperties: ConsulProperties,
        transformers: List<ServiceInstancesTransformer>
    ): LocalServiceChanges = ConsulLocalServiceChanges(
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
    fun instanceMerger() = InstanceMerger()

    @Bean
    fun ipAddressFilter() = IpAddressFilter()

    @Bean
    @ConditionalOnProperty("envoy-control.service-filters.excluded-names-patterns")
    fun excludeServicesFilter(properties: EnvoyControlProperties) =
        RegexServiceInstancesFilter(properties.serviceFilters.excludedNamesPatterns)

    @Bean
    fun globalServiceChanges(
        serviceChanges: Array<ServiceChanges>
    ): GlobalServiceChanges =
        GlobalServiceChanges(serviceChanges)

    @Bean
    fun envoyHttpFilters(
        properties: EnvoyControlProperties
    ): EnvoyHttpFilters {
        return EnvoyHttpFilters.defaultFilters(properties.envoy.snapshot)
    }

    fun localDatacenter(properties: ConsulProperties) =
        ConsulClient(properties.host, properties.port).agentSelf.value?.config?.datacenter ?: "local"

    fun controlPlaneMetrics(meterRegistry: MeterRegistry) =
        DefaultEnvoyControlMetrics().also {
            meterRegistry.gauge("services.added", it.servicesAdded)
            meterRegistry.gauge("services.removed", it.servicesRemoved)
            meterRegistry.gauge("services.instanceChanged", it.instanceChanges)
            meterRegistry.gauge("services.snapshotChanged", it.snapshotChanges)
            meterRegistry.gauge("cache.groupsCount", it.cacheGroupsCount)
            meterRegistry.more().counter("services.watch.errors", listOf(), it.errorWatchingServices)
        }

    @Bean
    fun protobufJsonFormatHttpMessageConverter(): ProtobufJsonFormatHttpMessageConverter {
        return ProtobufJsonFormatHttpMessageConverter()
    }
}
