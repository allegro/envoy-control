package pl.allegro.tech.servicemesh.envoycontrol.groups

import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.google.protobuf.util.Durations
import com.google.protobuf.util.Values
import io.envoyproxy.envoy.config.accesslog.v3.ComparisonFilter
import io.envoyproxy.envoy.config.core.v3.BuildVersion
import io.envoyproxy.envoy.config.core.v3.Node
import io.envoyproxy.envoy.type.v3.SemanticVersion
import io.grpc.Status
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode.ADS
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode.XDS
import pl.allegro.tech.servicemesh.envoycontrol.groups.ListenersConfig.AddUpstreamServiceTagsCondition
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.serviceDependencies
import io.envoyproxy.envoy.config.core.v3.Node as NodeV3

class MetadataNodeGroupTest {

    private val defaultNormalizationConfig = PathNormalizationConfig(
        normalizationEnabled = true,
        mergeSlashes = true,
        pathWithEscapedSlashesAction = "KEEP_UNCHANGED"
    )
    private val defaultCompConfig = Compressor(false, 1)
    private val compressionConfig =  CompressionConfig(defaultCompConfig, defaultCompConfig)
    @Test
    fun `should assign to group with all dependencies`() {
        // given
        val nodeGroup = MetadataNodeGroup(createSnapshotProperties(outgoingPermissions = true))
        val node = nodeV3(serviceDependencies = setOf("*", "a", "b", "c"), ads = false)

        // when
        val group = nodeGroup.hash(node)

        // then
        assertThat(group).isEqualTo(
            AllServicesGroup(
                // we have to preserve all services even if wildcard is present,
                // because service may define different settings for different dependencies (for example endpoints, which
                // will be implemented in https://github.com/allegro/envoy-control/issues/6
                communicationMode = XDS,
                pathNormalizationConfig = defaultNormalizationConfig,
                proxySettings = ProxySettings().with(
                    serviceDependencies = serviceDependencies("a", "b", "c"),
                    allServicesDependencies = true
                ),
                compressionConfig = compressionConfig
            )
        )
    }

    @Test
    fun `should assign to group with no dependencies`() {
        // given
        val nodeGroup = MetadataNodeGroup(createSnapshotProperties(outgoingPermissions = true))

        // when
        val group = nodeGroup.hash(NodeV3.newBuilder().build())

        // then
        assertThat(group).isEqualTo(
            ServicesGroup(
                proxySettings = ProxySettings().with(serviceDependencies = setOf()),
                pathNormalizationConfig = defaultNormalizationConfig,
                communicationMode = XDS,
                compressionConfig = compressionConfig
            )
        )
    }

    @Test
    fun `should assign to group with listed dependencies`() {
        // given
        val nodeGroup = MetadataNodeGroup(createSnapshotProperties(outgoingPermissions = true))
        val node = nodeV3(serviceDependencies = setOf("a", "b", "c"), ads = false)

        // when
        val group = nodeGroup.hash(node)

        // then
        assertThat(group).isEqualTo(
            ServicesGroup(
                proxySettings = ProxySettings().with(serviceDependencies = serviceDependencies("a", "b", "c")),
                pathNormalizationConfig = defaultNormalizationConfig,
                communicationMode = XDS,
                compressionConfig = compressionConfig
            )
        )
    }

    @Test
    fun `should assign to group with all dependencies on ads`() {
        // given
        val nodeGroup = MetadataNodeGroup(createSnapshotProperties(outgoingPermissions = true))
        val node = nodeV3(serviceDependencies = setOf("*"), ads = true)

        // when
        val group = nodeGroup.hash(node)

        // then
        assertThat(group).isEqualTo(
            AllServicesGroup(
                communicationMode = ADS,
                pathNormalizationConfig = defaultNormalizationConfig,
                proxySettings = ProxySettings().with(allServicesDependencies = true),
                compressionConfig = compressionConfig
            )
        )
    }

    @Test
    fun `should assign to group with listed dependencies on ads`() {
        // given
        val nodeGroup = MetadataNodeGroup(createSnapshotProperties(outgoingPermissions = true))
        val node = nodeV3(serviceDependencies = setOf("a", "b", "c"), ads = true)

        // when
        val group = nodeGroup.hash(node)

        // then
        assertThat(group).isEqualTo(
            ServicesGroup(
                proxySettings = ProxySettings().with(serviceDependencies = serviceDependencies("a", "b", "c")),
                pathNormalizationConfig = defaultNormalizationConfig,
                communicationMode = ADS,
                compressionConfig = compressionConfig
            )
        )
    }

    @Test
    fun `should assign to group with all dependencies when outgoing-permissions is not enabled`() {
        // given
        val nodeGroup = MetadataNodeGroup(createSnapshotProperties(outgoingPermissions = false))
        val node = nodeV3(serviceDependencies = setOf("a", "b", "c"), ads = true)

        // when
        val group = nodeGroup.hash(node)

        // then
        assertThat(group).isEqualTo(
            AllServicesGroup(
                // we have to preserve all services even if outgoingPermissions is disabled,
                // because service may define different settings for different dependencies (for example retry config)
                communicationMode = ADS,
                pathNormalizationConfig = defaultNormalizationConfig,
                proxySettings = ProxySettings().with(serviceDependencies = serviceDependencies("a", "b", "c")),
                compressionConfig = compressionConfig
            )
        )
    }

    @Test
    fun `should not include service settings when incoming permissions are disabled`() {
        // given
        val nodeGroup = MetadataNodeGroup(createSnapshotProperties(outgoingPermissions = true))
        val node = nodeV3(
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
                pathNormalizationConfig = defaultNormalizationConfig,
                communicationMode = XDS,
                serviceName = "app1",
                compressionConfig = compressionConfig
            )
        )
    }

    @Test
    fun `should not include service settings when incoming permissions are disabled for all dependencies`() {
        // given
        val nodeGroup = MetadataNodeGroup(createSnapshotProperties(outgoingPermissions = true))
        val node = nodeV3(serviceDependencies = setOf("*"), ads = false, serviceName = "app1", incomingSettings = true)

        // when
        val group = nodeGroup.hash(node)

        // then
        assertThat(group.proxySettings.incoming).isEqualTo(Incoming())
    }

    @Test
    fun `should include service settings when incoming permissions are enabled`() {
        // given
        val nodeGroup = MetadataNodeGroup(
            createSnapshotProperties(outgoingPermissions = true, incomingPermissions = true)
        )
        val node = nodeV3(
            serviceDependencies = setOf("a", "b"),
            ads = true,
            serviceName = "app1",
            incomingSettings = true
        )

        // when
        val group = nodeGroup.hash(node)

        // then
        assertThat(group).isEqualTo(
            ServicesGroup(
                communicationMode = ADS,
                serviceName = "app1",
                pathNormalizationConfig = defaultNormalizationConfig,
                proxySettings = addedProxySettings.with(serviceDependencies = serviceDependencies("a", "b")),
                compressionConfig = compressionConfig
            )
        )
    }

    @Test
    fun `should include service settings when incoming permissions are enabled for all dependencies`() {
        // given
        val nodeGroup = MetadataNodeGroup(
            createSnapshotProperties(outgoingPermissions = true, incomingPermissions = true)
        )
        val node = nodeV3(serviceDependencies = setOf("*"), ads = false, serviceName = "app1", incomingSettings = true)

        // when
        val group = nodeGroup.hash(node)

        // then
        assertThat(group).isEqualTo(
            AllServicesGroup(
                communicationMode = XDS,
                serviceName = "app1",
                pathNormalizationConfig = defaultNormalizationConfig,
                proxySettings = addedProxySettings.with(allServicesDependencies = true),
                compressionConfig = compressionConfig
            )
        )
    }

    @Test
    fun `should parse proto incoming timeout policy`() {
        // when
        val nodeGroup = MetadataNodeGroup(createSnapshotProperties(outgoingPermissions = true))
        val node = nodeV3(
            serviceDependencies = setOf("*"), ads = true, incomingSettings = true,
            responseTimeout = "777s", idleTimeout = "13.33s"
        )

        // when
        val group = nodeGroup.hash(node)

        // then
        assertThat(group.proxySettings.incoming.timeoutPolicy.responseTimeout?.seconds).isEqualTo(777)
        assertThat(group.proxySettings.incoming.timeoutPolicy.idleTimeout).isEqualTo(Durations.parse("13.33s"))
    }

    @Test
    fun `should parse proto with custom healthCheck definition`() {
        // when
        val nodeGroup = MetadataNodeGroup(createSnapshotProperties(incomingPermissions = true))
        val node = nodeV3(
            serviceDependencies = setOf("*"), ads = true, incomingSettings = true,
            healthCheckPath = "/status/ping", healthCheckClusterName = "local_service_health_check"
        )

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
        val nodeGroup = MetadataNodeGroup(createSnapshotProperties())
        val metadata = createMetadataBuilderWithDefaults()

        metadata!!.putFields(
            "access_log_filter",
            accessLogFilterProto(value = "EQ:400", fieldName = "status_code_filter")
        )

        // when
        val group = nodeGroup.hash(NodeV3.newBuilder().setMetadata(metadata.build()).build())

        // then
        val statusCodeFilterSettings = group.listenersConfig!!.accessLogFilterSettings.statusCodeFilterSettings!!
        assertThat(statusCodeFilterSettings.comparisonCode).isEqualTo(400)
        assertThat(statusCodeFilterSettings.comparisonOperator).isEqualTo(ComparisonFilter.Op.EQ)
    }

    @ParameterizedTest
    @CsvSource(
        "equal:400",
        "eq:40",
        "GT:1234"
    )
    fun `should throw exception and not set listeners config access log status code filter if invalid config`(config: String) {
        // given
        val nodeGroup = MetadataNodeGroup(createSnapshotProperties())
        val metadata = createMetadataBuilderWithDefaults()

        metadata!!.putFields(
            "access_log_filter",
            accessLogFilterProto(value = config, fieldName = "status_code_filter")
        )

        // expect
        val exception = assertThrows<NodeMetadataValidationException> {
            nodeGroup.hash(NodeV3.newBuilder().setMetadata(metadata.build()).build())
        }
        assertThat(exception.status.description)
            .isEqualTo("Invalid access log comparison filter. Expected OPERATOR:VALUE")
        assertThat(exception.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
    }

    @Test
    fun `should not set listeners config access log status code filter if not defined`() {
        // given
        val nodeGroup = MetadataNodeGroup(createSnapshotProperties())
        val metadata = createMetadataBuilderWithDefaults()

        // when
        val group = nodeGroup.hash(NodeV3.newBuilder().setMetadata(metadata?.build()).build())

        // then
        assertThat(group.listenersConfig!!.accessLogFilterSettings.statusCodeFilterSettings).isNull()
    }

    @Test
    fun `should correctly compare envoy version`() {
        // given
        val v1dot21 = envoyVersion(1, 21)
        val v1dot24 = envoyVersion(1, 24)
        val v1dot26 = envoyVersion(1, 26)
        val v2dot21 = envoyVersion(2, 21)

        // expect
        assertTrue(v1dot21 < v1dot24)
        assertFalse(v1dot24 < v1dot24)
        assertFalse(v1dot26 < v1dot24)
        assertFalse(v2dot21 < v1dot24)
    }

    @Test
    fun `should set addUpstreamServiceTag condition according to client flag and envoy version`() {
        // given
        val nodeGroup = MetadataNodeGroup(createSnapshotProperties())

        fun assertThat(node: Node, has: AddUpstreamServiceTagsCondition) {
            // when
            val group = nodeGroup.hash(node)

            // then
            assertThat(group.listenersConfig.orDefault().addUpstreamServiceTags).isEqualTo(has)
        }

        val emptyNode = NodeV3.newBuilder().build()
        val basicNode = NodeV3.newBuilder().setMetadata(createMetadataBuilderWithDefaults()).build()
        val newEnvoyNode = basicNode.with(envoyVersion(1, 24))
        val oldEnvoyNode = basicNode.with(envoyVersion(1, 23))
        val enabledNewEnvoyNode = newEnvoyNode.withMetadata("add_upstream_service_tags", Values.of(true))
        val enabledOldEnvoyNode = oldEnvoyNode.withMetadata("add_upstream_service_tags", Values.of(true))
        val disabledNewEnvoyNode = newEnvoyNode.withMetadata("add_upstream_service_tags", Values.of(false))
        val disabledOldEnvoyNode = oldEnvoyNode.withMetadata("add_upstream_service_tags", Values.of(false))

        // expect
        assertThat(node = emptyNode, has = AddUpstreamServiceTagsCondition.NEVER)
        assertThat(node = basicNode, has = AddUpstreamServiceTagsCondition.NEVER)
        assertThat(node = newEnvoyNode, has = AddUpstreamServiceTagsCondition.WHEN_SERVICE_TAG_PREFERENCE_IS_USED)
        assertThat(node = oldEnvoyNode, has = AddUpstreamServiceTagsCondition.NEVER)
        assertThat(node = enabledNewEnvoyNode, has = AddUpstreamServiceTagsCondition.ALWAYS)
        assertThat(node = enabledOldEnvoyNode, has = AddUpstreamServiceTagsCondition.NEVER)
        assertThat(node = disabledNewEnvoyNode, has = AddUpstreamServiceTagsCondition.NEVER)
        assertThat(node = disabledOldEnvoyNode, has = AddUpstreamServiceTagsCondition.NEVER)
    }

    private fun createMetadataBuilderWithDefaults(): Struct.Builder? {
        val metadata = NodeV3.newBuilder().metadataBuilder
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

    private fun NodeV3.with(version: SemanticVersion) = toBuilder()
        .setUserAgentBuildVersion(BuildVersion.newBuilder().setVersion(version))
        .build()

    private fun NodeV3.withMetadata(key: String, value: Value) = toBuilder()
        .apply { metadataBuilder.putFields(key, value) }
        .build()
}
