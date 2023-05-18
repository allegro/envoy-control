package pl.allegro.tech.servicemesh.envoycontrol.infrastructure

import com.ecwid.consul.v1.ConsulClient
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.SimpleAsyncTaskExecutor
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestTemplate
import pl.allegro.tech.discovery.consul.recipes.datacenter.ConsulDatacenterReader
import pl.allegro.tech.servicemesh.envoycontrol.EnvoyControlProperties
import pl.allegro.tech.servicemesh.envoycontrol.consul.ConsulProperties
import pl.allegro.tech.servicemesh.envoycontrol.consul.synchronization.SimpleConsulInstanceFetcher
import pl.allegro.tech.servicemesh.envoycontrol.synchronization.ControlPlaneClient
import pl.allegro.tech.servicemesh.envoycontrol.synchronization.ControlPlaneInstanceFetcher
import pl.allegro.tech.servicemesh.envoycontrol.synchronization.GzipHttpRequestInterceptor
import pl.allegro.tech.servicemesh.envoycontrol.synchronization.RemoteClusterStateChanges
import pl.allegro.tech.servicemesh.envoycontrol.synchronization.RemoteServices
import pl.allegro.tech.servicemesh.envoycontrol.synchronization.RestTemplateControlPlaneClient
import java.lang.Integer.max
import java.util.concurrent.Executors

@Configuration
@ConditionalOnProperty(name = ["envoy-control.sync.enabled"], havingValue = "true", matchIfMissing = false)
class SynchronizationConfig {

    @Bean
    fun restTemplate(
        envoyControlProperties: EnvoyControlProperties,
        interceptors: List<ClientHttpRequestInterceptor>
    ): RestTemplate {
        val requestFactory = SimpleClientHttpRequestFactory()
        requestFactory.setTaskExecutor(SimpleAsyncTaskExecutor())
        requestFactory.setConnectTimeout(envoyControlProperties.sync.connectionTimeout.toMillis().toInt())
        requestFactory.setReadTimeout(envoyControlProperties.sync.readTimeout.toMillis().toInt())

        return RestTemplate(requestFactory)
            .apply { this.interceptors = interceptors }
    }

    @Bean
    @ConditionalOnProperty(name = ["server.compression.enabled"], havingValue = "true", matchIfMissing = false)
    fun gzipInterceptor(): GzipHttpRequestInterceptor = GzipHttpRequestInterceptor()

    @Bean
    fun controlPlaneClient(restTemplate: RestTemplate, meterRegistry: MeterRegistry, remoteClusters: RemoteClusters) =
        RestTemplateControlPlaneClient(
            restTemplate = restTemplate,
            meterRegistry = meterRegistry,
            executors = Executors.newFixedThreadPool(max(remoteClusters.clusters.size, 1))
        )

    @Bean
    fun remoteClusterStateChanges(
        controlPlaneClient: ControlPlaneClient,
        meterRegistry: MeterRegistry,
        controlPlaneInstanceFetcher: ControlPlaneInstanceFetcher,
        properties: EnvoyControlProperties,
        remoteClusters: RemoteClusters
    ): RemoteClusterStateChanges {
        val service = RemoteServices(
            controlPlaneClient = controlPlaneClient,
            meterRegistry = meterRegistry,
            controlPlaneInstanceFetcher = controlPlaneInstanceFetcher,
            remoteClusters = remoteClusters.clusters
        )
        return RemoteClusterStateChanges(properties, service)
    }

    @Bean
    fun remoteClusters(consulDatacenterReader: ConsulDatacenterReader) =
        RemoteClusters(consulDatacenterReader.knownDatacenters() - consulDatacenterReader.localDatacenter())

    @Bean
    fun instanceFetcher(
        consulProperties: ConsulProperties,
        envoyControlProperties: EnvoyControlProperties
    ) = SimpleConsulInstanceFetcher(
        ConsulClient(consulProperties.host, consulProperties.port),
        envoyControlProperties.sync.envoyControlAppName
    )
}

data class RemoteClusters(val clusters: List<String>)
