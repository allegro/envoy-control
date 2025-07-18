package pl.allegro.tech.servicemesh.envoycontrol.groups

import com.google.protobuf.Struct
import com.google.protobuf.Value
import io.envoyproxy.controlplane.cache.NodeGroup
import io.envoyproxy.envoy.config.core.v3.BuildVersion
import io.envoyproxy.envoy.type.v3.SemanticVersion
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import io.envoyproxy.envoy.config.core.v3.Node as NodeV3

@Suppress("MagicNumber")
val MIN_ENVOY_VERSION_SUPPORTING_JWT_FAILURE_STATUS = envoyVersion(1, 26)
@Suppress("MagicNumber")
val MIN_ENVOY_VERSION_SUPPORTING_IGNORE_HTTP_11_UPGRADES = envoyVersion(1, 34)

class MetadataNodeGroup(
    val properties: SnapshotProperties
) : NodeGroup<Group> {
    private val logger by logger()

    override fun hash(node: NodeV3): Group {
        return createV3Group(node)
    }

    @SuppressWarnings("ReturnCount")
    private fun metadataToListenersHostPort(
        id: String,
        ingressHostValue: Value?,
        ingressPortValue: Value?,
        egressHostValue: Value?,
        egressPortValue: Value?
    ): ListenersHostPortConfig? {
        if (listOf(ingressHostValue, ingressPortValue, egressHostValue, egressPortValue).all { it == null }) {
            logger.debug("Node $id with static listener config connected.")
            return null
        }

        if (ingressHostValue == null) {
            logger.warn("Node $id has no ingress host configured, falling back to static listeners.")
            return null
        }

        if (ingressPortValue == null) {
            logger.warn("Node $id has no ingress port configured, falling back to static listeners.")
            return null
        }

        if (egressHostValue == null) {
            logger.warn("Node $id has no egress host configured, falling back to static listeners.")
            return null
        }

        if (egressPortValue == null) {
            logger.warn("Node $id has no egress port configured, falling back to static listeners.")
            return null
        }

        val ingressHost = ingressHostValue.stringValue
        val ingressPort = ingressPortValue.numberValue.toInt()

        if (ingressPort < 0 || ingressPort > MAX_PORT_VALUE) {
            logger.warn("Node $id has ingress port out of valid range [0-65535]. Falling back to static listeners.")
            return null
        }

        val egressHost = egressHostValue.stringValue
        val egressPort = egressPortValue.numberValue.toInt()

        if (egressPort < 0 || egressPort > MAX_PORT_VALUE) {
            logger.warn("Node $id has egress port out of valid range [0-65535]. Falling back to static listeners.")
            return null
        }

        return ListenersHostPortConfig(ingressHost, ingressPort, egressHost, egressPort)
    }

    private fun createListenersConfig(id: String, metadata: Struct, envoyVersion: BuildVersion): ListenersConfig? {
        val ingressHostValue = metadata.fieldsMap["ingress_host"]
        val ingressPortValue = metadata.fieldsMap["ingress_port"]
        val egressHostValue = metadata.fieldsMap["egress_host"]
        val egressPortValue = metadata.fieldsMap["egress_port"]
        val accessLogProperties = properties.dynamicListeners.httpFilters.accessLog
        val accessLogFilterSettings = AccessLogFilterSettings(
            metadata.fieldsMap["access_log_filter"],
            accessLogProperties.filters
        )

        val listenersHostPort = metadataToListenersHostPort(
            id,
            ingressHostValue,
            ingressPortValue,
            egressHostValue,
            egressPortValue
        )

        if (listenersHostPort == null) {
            return null
        }

        val useRemoteAddress = metadata.fieldsMap["use_remote_address"]?.boolValue
            ?: ListenersConfig.defaultUseRemoteAddress
        val generateRequestId = metadata.fieldsMap["generate_request_id"]?.boolValue
            ?: ListenersConfig.defaultGenerateRequestId
        val preserveExternalRequestId = metadata.fieldsMap["preserve_external_request_id"]?.boolValue
            ?: ListenersConfig.defaultPreserveExternalRequestId
        val accessLogEnabled = metadata.fieldsMap["access_log_enabled"]?.boolValue
            ?: accessLogProperties.enabled
        val enableLuaScript = metadata.fieldsMap["enable_lua_script"]?.boolValue
            ?: ListenersConfig.defaultEnableLuaScript
        val accessLogPath = metadata.fieldsMap["access_log_path"]?.stringValue
            ?: ListenersConfig.defaultAccessLogPath
        val addUpstreamExternalAddressHeader = metadata.fieldsMap["add_upstream_external_address_header"]?.boolValue
            ?: ListenersConfig.defaultAddUpstreamExternalAddressHeader

        val filtersEnabled = listOf(
            accessLogFilterSettings.statusCodeFilterSettings,
            accessLogFilterSettings.durationFilterSettings,
            accessLogFilterSettings.headerFilter,
            accessLogFilterSettings.responseFlagFilter
            ).any { it != null }

        if (accessLogEnabled && !filtersEnabled) {
            logger.warn("Node $id has access log enabled without filters configurations.")
        }

        val hasStaticSecretsDefined = metadata.fieldsMap["has_static_secrets_defined"]?.boolValue
            ?: ListenersConfig.defaultHasStaticSecretsDefined
        val useTransparentProxy = metadata.fieldsMap["use_transparent_proxy"]?.boolValue
            ?: ListenersConfig.defaultUseTransparentProxy

        val addJwtFailureStatus = envoyVersion.version >= MIN_ENVOY_VERSION_SUPPORTING_JWT_FAILURE_STATUS
        val addIgnoreHttp11Upgrades = envoyVersion.version >= MIN_ENVOY_VERSION_SUPPORTING_IGNORE_HTTP_11_UPGRADES

        return ListenersConfig(
            listenersHostPort.ingressHost,
            listenersHostPort.ingressPort,
            listenersHostPort.egressHost,
            listenersHostPort.egressPort,
            useRemoteAddress,
            generateRequestId,
            preserveExternalRequestId,
            accessLogEnabled,
            enableLuaScript,
            accessLogPath,
            addUpstreamExternalAddressHeader,
            addJwtFailureStatus,
            accessLogFilterSettings,
            hasStaticSecretsDefined,
            useTransparentProxy,
            addIgnoreHttp11Upgrades
        )
    }

    private fun createV3Group(node: NodeV3): Group {
        val nodeMetadata = NodeMetadata(node.metadata, properties)
        val serviceName = serviceName(nodeMetadata)
        val serviceId = nodeMetadata.serviceId
        val discoveryServiceName = nodeMetadata.discoveryServiceName
        val proxySettings = proxySettings(nodeMetadata)
        val listenersConfig = createListenersConfig(node.id, node.metadata, node.userAgentBuildVersion)
        return when {
            hasAllServicesDependencies(nodeMetadata) ->
                AllServicesGroup(
                    nodeMetadata.communicationMode,
                    serviceName,
                    serviceId,
                    discoveryServiceName,
                    proxySettings,
                    nodeMetadata.pathNormalizationConfig,
                    listenersConfig,
                    nodeMetadata.compressionConfig
                )
            else ->
                ServicesGroup(
                    nodeMetadata.communicationMode,
                    serviceName,
                    serviceId,
                    discoveryServiceName,
                    proxySettings,
                    nodeMetadata.pathNormalizationConfig,
                    listenersConfig,
                    nodeMetadata.compressionConfig
                )
        }
    }

    private fun hasAllServicesDependencies(metadata: NodeMetadata): Boolean {
        return !properties.outgoingPermissions.enabled ||
            metadata.proxySettings.outgoing.allServicesDependencies
    }

    private fun serviceName(metadata: NodeMetadata): String {
        return metadata.serviceName.orEmpty()
    }

    private fun proxySettings(metadata: NodeMetadata): ProxySettings {
        return when (properties.incomingPermissions.enabled) {
            true -> metadata.proxySettings
            false -> metadata.proxySettings.withIncomingPermissionsDisabled()
        }
    }

    companion object {
        private const val MAX_PORT_VALUE = 65535
    }
}

data class ListenersHostPortConfig(
    val ingressHost: String,
    val ingressPort: Int,
    val egressHost: String,
    val egressPort: Int
)

fun envoyVersion(major: Int, minor: Int): SemanticVersion =
    SemanticVersion.newBuilder().setMajorNumber(major).setMinorNumber(minor).build()

private val semanticVersionComparator = compareBy<SemanticVersion>({ it.majorNumber }, { it.minorNumber })
operator fun SemanticVersion.compareTo(other: SemanticVersion): Int = semanticVersionComparator.compare(this, other)
