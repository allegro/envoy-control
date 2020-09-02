package pl.allegro.tech.servicemesh.envoycontrol.groups

import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.google.protobuf.util.Durations
import io.envoyproxy.envoy.api.v2.core.Node
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode.ADS
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode.XDS
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.serviceDependencies
import io.envoyproxy.envoy.config.filter.accesslog.v2.ComparisonFilter.Op.EQ
import io.grpc.Status
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.AccessLogFilterFactory

class MetadataNodeGroupTest {
    val accessLogFilterFactory = AccessLogFilterFactory()

    @Test
    fun `should assign to group with all dependencies`() {
        // given
        val nodeGroup = MetadataNodeGroup(createSnapshotProperties(outgoingPermissions = true), accessLogFilterFactory)
        val node = node(serviceDependencies = setOf("*", "a", "b", "c"), ads = false)

        // when
        val group = nodeGroup.hash(node)

        // then
        assertThat(group).isEqualTo(AllServicesGroup(
            // we have to preserve all services even if wildcard is present,
            // because service may define different settings for different dependencies (for example endpoints, which
            // will be implemented in https://github.com/allegro/envoy-control/issues/6
                communicationMode = XDS,
                proxySettings = ProxySettings().with(serviceDependencies = serviceDependencies("a", "b", "c"),
                    allServicesDependencies = true
                )
        ))
    }

    @Test
    fun `should assign to group with no dependencies`() {
        // given
        val nodeGroup = MetadataNodeGroup(createSnapshotProperties(outgoingPermissions = true), accessLogFilterFactory)

        // when
        val group = nodeGroup.hash(Node.newBuilder().build())

        // then
        assertThat(group).isEqualTo(
            ServicesGroup(
                proxySettings = ProxySettings().with(serviceDependencies = setOf()),
                communicationMode = XDS
            )
        )
    }

    @Test
    fun `should assign to group with listed dependencies`() {
        // given
        val nodeGroup = MetadataNodeGroup(createSnapshotProperties(outgoingPermissions = true), accessLogFilterFactory)
        val node = node(serviceDependencies = setOf("a", "b", "c"), ads = false)

        // when
        val group = nodeGroup.hash(node)

        // then
        assertThat(group).isEqualTo(
            ServicesGroup(
                proxySettings = ProxySettings().with(serviceDependencies = serviceDependencies("a", "b", "c")),
                communicationMode = XDS
            )
        )
    }

    @Test
    fun `should assign to group with all dependencies on ads`() {
        // given
        val nodeGroup = MetadataNodeGroup(createSnapshotProperties(outgoingPermissions = true), accessLogFilterFactory)
        val node = node(serviceDependencies = setOf("*"), ads = true)

        // when
        val group = nodeGroup.hash(node)

        // then
        assertThat(group).isEqualTo(
            AllServicesGroup(
                    communicationMode = ADS,
                    proxySettings = ProxySettings().with(allServicesDependencies = true)
            )
        )
    }

    @Test
    fun `should assign to group with listed dependencies on ads`() {
        // given
        val nodeGroup = MetadataNodeGroup(createSnapshotProperties(outgoingPermissions = true), accessLogFilterFactory)
        val node = node(serviceDependencies = setOf("a", "b", "c"), ads = true)

        // when
        val group = nodeGroup.hash(node)

        // then
        assertThat(group).isEqualTo(
            ServicesGroup(
                proxySettings = ProxySettings().with(serviceDependencies = serviceDependencies("a", "b", "c")),
                communicationMode = ADS
            )
        )
    }

    @Test
    fun `should assign to group with all dependencies when outgoing-permissions is not enabled`() {
        // given
        val nodeGroup = MetadataNodeGroup(createSnapshotProperties(outgoingPermissions = false), accessLogFilterFactory)
        val node = node(serviceDependencies = setOf("a", "b", "c"), ads = true)

        // when
        val group = nodeGroup.hash(node)

        // then
        assertThat(group).isEqualTo(AllServicesGroup(
            // we have to preserve all services even if outgoingPermissions is disabled,
            // because service may define different settings for different dependencies (for example retry config)
                communicationMode = ADS,
                proxySettings = ProxySettings().with(serviceDependencies = serviceDependencies("a", "b", "c"))
        ))
    }

    @Test
    fun `should not include service settings when incoming permissions are disabled`() {
        // given
        val nodeGroup = MetadataNodeGroup(createSnapshotProperties(outgoingPermissions = true), accessLogFilterFactory)
        val node = node(
            serviceDependencies = setOf("a", "b", "c"),
            ads = false, serviceName = "app1",
            incomingSettings = true
        )

        // when
        val group = nodeGroup.hash(node)

        // then
        assertThat(group).isEqualTo(
            ServicesGroup(
                proxySettings = ProxySettings().with(serviceDependencies = serviceDependencies("a", "b", "c")),
                communicationMode = XDS
            )
        )
    }

    @Test
    fun `should not include service settings when incoming permissions are disabled for all dependencies`() {
        // given
        val nodeGroup = MetadataNodeGroup(createSnapshotProperties(outgoingPermissions = true), accessLogFilterFactory)
        val node = node(serviceDependencies = setOf("*"), ads = false, serviceName = "app1", incomingSettings = true)

        // when
        val group = nodeGroup.hash(node)

        // then
        assertThat(group.proxySettings.incoming).isEqualTo(Incoming())
    }

    @Test
    fun `should include service settings when incoming permissions are enabled`() {
        // given
        val nodeGroup = MetadataNodeGroup(createSnapshotProperties(outgoingPermissions = true, incomingPermissions = true), accessLogFilterFactory)
        val node = node(serviceDependencies = setOf("a", "b"), ads = true, serviceName = "app1", incomingSettings = true)

        // when
        val group = nodeGroup.hash(node)

        // then
        assertThat(group).isEqualTo(ServicesGroup(
            communicationMode = ADS,
            serviceName = "app1",
            proxySettings = addedProxySettings.with(serviceDependencies = serviceDependencies("a", "b"))
        ))
    }

    @Test
    fun `should include service settings when incoming permissions are enabled for all dependencies`() {
        // given
        val nodeGroup = MetadataNodeGroup(createSnapshotProperties(outgoingPermissions = true, incomingPermissions = true), accessLogFilterFactory)
        val node = node(serviceDependencies = setOf("*"), ads = false, serviceName = "app1", incomingSettings = true)

        // when
        val group = nodeGroup.hash(node)

        // then
        assertThat(group).isEqualTo(AllServicesGroup(
                communicationMode = XDS,
                serviceName = "app1",
                proxySettings = addedProxySettings.with(allServicesDependencies = true)
        ))
    }

    @Test
    fun `should parse proto incoming timeout policy`() {
        // when
        val nodeGroup = MetadataNodeGroup(createSnapshotProperties(outgoingPermissions = true), accessLogFilterFactory)
        val node = node(serviceDependencies = setOf("*"), ads = true, incomingSettings = true,
            responseTimeout = "777s", idleTimeout = "13.33s")

        // when
        val group = nodeGroup.hash(node)

        // then
        assertThat(group.proxySettings.incoming.timeoutPolicy.responseTimeout?.seconds).isEqualTo(777)
        assertThat(group.proxySettings.incoming.timeoutPolicy.idleTimeout).isEqualTo(Durations.parse("13.33s"))
    }

    @Test
    fun `should parse proto with custom healthCheck definition`() {
        // when
        val nodeGroup = MetadataNodeGroup(createSnapshotProperties(incomingPermissions = true), accessLogFilterFactory)
        val node = node(serviceDependencies = setOf("*"), ads = true, incomingSettings = true,
            healthCheckPath = "/status/ping", healthCheckClusterName = "local_service_health_check")

        // when
        val group = nodeGroup.hash(node)

        // then
        assertThat(group.proxySettings.incoming.healthCheck.path).isEqualTo("/status/ping")
        assertThat(group.proxySettings.incoming.healthCheck.clusterName).isEqualTo("local_service_health_check")
        assertThat(group.proxySettings.incoming.healthCheck.hasCustomHealthCheck()).isTrue()
    }

    @Test
    fun `should set listeners config access log status code filter according to metadata`() {
        // given
        val nodeGroup = MetadataNodeGroup(createSnapshotProperties(), accessLogFilterFactory)
        val metadata = createMetadataBuilderWithDefaults()

        metadata!!.putFields("access_log_filter", accessLogFilterProto("EQ:400"))

        // when
        val group = nodeGroup.hash(Node.newBuilder().setMetadata(metadata.build()).build())

        // then
        assertThat(group.listenersConfig!!.accessLogFilterSettings.statusCodeFilterSettings!!.comparisonCode).isEqualTo(400)
        assertThat(group.listenersConfig!!.accessLogFilterSettings.statusCodeFilterSettings!!.comparisonOperator).isEqualTo(EQ)
    }

    @ParameterizedTest
    @CsvSource(
        "equal:400",
        "eq:40",
        "GT:1234"
    )
    fun `should throw exception and not set listeners config access log status code filter if invalid config`(config: String) {
        // given
        val nodeGroup = MetadataNodeGroup(createSnapshotProperties(), accessLogFilterFactory)
        val metadata = createMetadataBuilderWithDefaults()

        metadata!!.putFields("access_log_filter", accessLogFilterProto(config))

        // expect
        val exception = assertThrows<NodeMetadataValidationException> {
            nodeGroup.hash(Node.newBuilder().setMetadata(metadata.build()).build())
        }
        assertThat(exception.status.description)
            .isEqualTo("Invalid access log status code filter. Expected OPERATOR:STATUS_CODE")
        assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    @Test
    fun `should not set listeners config access log status code filter if not defined`() {
        // given
        val nodeGroup = MetadataNodeGroup(createSnapshotProperties(), accessLogFilterFactory)
        val metadata = createMetadataBuilderWithDefaults()

        // when
        val group = nodeGroup.hash(Node.newBuilder().setMetadata(metadata?.build()).build())

        // then
        assertThat(group.listenersConfig!!.accessLogFilterSettings.statusCodeFilterSettings).isNull()
    }

    private fun createMetadataBuilderWithDefaults(): Struct.Builder? {
        val metadata = Node.newBuilder().metadataBuilder
        metadata.putFields("ingress_host", Value.newBuilder().setStringValue("127.0.0.1").build())
        metadata.putFields("ingress_port", Value.newBuilder().setStringValue("6001").build())
        metadata.putFields("egress_host", Value.newBuilder().setStringValue("127.0.0.1").build())
        metadata.putFields("egress_port", Value.newBuilder().setStringValue("6003").build())
        return metadata
    }

    private fun createSnapshotProperties(
        allServicesDependenciesValue: String = "*",
        outgoingPermissions: Boolean = false,
        incomingPermissions: Boolean = false
    ): SnapshotProperties {
        val snapshotProperties = SnapshotProperties()
        snapshotProperties.outgoingPermissions.enabled = outgoingPermissions
        snapshotProperties.outgoingPermissions.allServicesDependencies.identifier = allServicesDependenciesValue
        snapshotProperties.incomingPermissions.enabled = incomingPermissions
        return snapshotProperties
    }
}
