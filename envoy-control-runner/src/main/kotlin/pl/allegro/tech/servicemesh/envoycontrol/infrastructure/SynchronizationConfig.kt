package pl.allegro.tech.servicemesh.envoycontrol.infrastructure

import com.ecwid.consul.v1.ConsulClient
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.SimpleAsyncTaskExecutor
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.AsyncRestTemplate
import pl.allegro.tech.discovery.consul.recipes.datacenter.ConsulDatacenterReader
import pl.allegro.tech.servicemesh.envoycontrol.EnvoyControlProperties
import pl.allegro.tech.servicemesh.envoycontrol.consul.ConsulProperties
import pl.allegro.tech.servicemesh.envoycontrol.consul.synchronization.SimpleConsulInstanceFetcher
import pl.allegro.tech.servicemesh.envoycontrol.synchronization.AsyncControlPlaneClient
import pl.allegro.tech.servicemesh.envoycontrol.synchronization.AsyncRestTemplateControlPlaneClient
import pl.allegro.tech.servicemesh.envoycontrol.synchronization.ControlPlaneInstanceFetcher
import pl.allegro.tech.servicemesh.envoycontrol.synchronization.CrossDcServiceChanges
import pl.allegro.tech.servicemesh.envoycontrol.synchronization.CrossDcServices

@Configuration
@ConditionalOnProperty(name = ["envoy-control.sync.enabled"], havingValue = "true", matchIfMissing = false)
class SynchronizationConfig {

    @Bean
    fun asyncRestTemplate(envoyControlProperties: EnvoyControlProperties): AsyncRestTemplate {
        val requestFactory = SimpleClientHttpRequestFactory()
        requestFactory.setTaskExecutor(SimpleAsyncTaskExecutor())
        requestFactory.setConnectTimeout(envoyControlProperties.sync.connectionTimeout.toMillis().toInt())
        requestFactory.setReadTimeout(envoyControlProperties.sync.readTimeout.toMillis().toInt())

        return AsyncRestTemplate(requestFactory)
    }

    @Bean
    fun controlPlaneClient(asyncRestTemplate: AsyncRestTemplate, meterRegistry: MeterRegistry) =
        AsyncRestTemplateControlPlaneClient(asyncRestTemplate, meterRegistry)

    @Bean
    fun crossDcServices(
        controlPlaneClient: AsyncControlPlaneClient,
        meterRegistry: MeterRegistry,
        controlPlaneInstanceFetcher: ControlPlaneInstanceFetcher,
        consulDatacenterReader: ConsulDatacenterReader,
        properties: EnvoyControlProperties
    ): CrossDcServiceChanges {

        val remoteDcs = consulDatacenterReader.knownDatacenters() - consulDatacenterReader.localDatacenter()
        val service = CrossDcServices(controlPlaneClient, meterRegistry, controlPlaneInstanceFetcher, remoteDcs)

        return CrossDcServiceChanges(properties, service)
    }

    @Bean
    fun instanceFetcher(
        consulProperties: ConsulProperties,
        envoyControlProperties: EnvoyControlProperties
    ) = SimpleConsulInstanceFetcher(
        ConsulClient(consulProperties.host, consulProperties.port),
        envoyControlProperties.sync.envoyControlAppName
    )
}
