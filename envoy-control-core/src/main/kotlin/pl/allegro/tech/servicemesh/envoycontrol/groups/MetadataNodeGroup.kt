package pl.allegro.tech.servicemesh.envoycontrol.groups

import com.google.protobuf.Struct
import com.google.protobuf.Value
import io.envoyproxy.controlplane.cache.NodeGroup
import io.envoyproxy.envoy.api.v2.core.Node

import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters.AccessLogFilterFactory

class MetadataNodeGroup(
    val properties: SnapshotProperties,
    val accessLogFilterFactory: AccessLogFilterFactory
) : NodeGroup<Group> {
    private val logger by logger()

    override fun hash(node: Node): Group = createGroup(node)

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
            logger.warn("Node $id has no egerss host configured, falling back to static listeners.")
            return null
        }

        if (egressPortValue == null) {
            logger.warn("Node $id has no egress port configured, falling back to static listeners.")
            return null
        }

        val ingressHost = ingressHostValue.stringValue
        val ingressPort = ingressPortValue.numberValue.toInt()

        if (ingressPort < 0 || ingressPort > Companion.MAX_PORT_VALUE) {
            logger.warn("Node $id has ingress port out of valid range [0-65535]. Falling back to static listeners.")
            return null
        }

        val egressHost = egressHostValue.stringValue
        val egressPort = egressPortValue.numberValue.toInt()

        if (egressPort < 0 || egressPort > Companion.MAX_PORT_VALUE) {
            logger.warn("Node $id has egress port out of valid range [0-65535]. Falling back to static listeners.")
            return null
        }

        return ListenersHostPortConfig(ingressHost, ingressPort, egressHost, egressPort)
    }

    private fun createListenersConfig(id: String, metadata: Struct): ListenersConfig? {
        val ingressHostValue = metadata.fieldsMap["ingress_host"]
        val ingressPortValue = metadata.fieldsMap["ingress_port"]
        val egressHostValue = metadata.fieldsMap["egress_host"]
        val egressPortValue = metadata.fieldsMap["egress_port"]
        val accessLogFilterSettings = AccessLogFilterSettings(
            metadata.fieldsMap["access_log_filter"], accessLogFilterFactory
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
        val accessLogEnabled = metadata.fieldsMap["access_log_enabled"]?.boolValue
                ?: ListenersConfig.defaultAccessLogEnabled
        val enableLuaScript = metadata.fieldsMap["enable_lua_script"]?.boolValue
                ?: ListenersConfig.defaultEnableLuaScript
        val accessLogPath = metadata.fieldsMap["access_log_path"]?.stringValue
                ?: ListenersConfig.defaultAccessLogPath
        val resourcesDir = metadata.fieldsMap["resources_dir"]?.stringValue
                ?: ListenersConfig.defaultResourcesDir
        val addUpstreamExternalAddressHeader = metadata.fieldsMap["add_upstream_external_address_header"]?.boolValue
            ?: ListenersConfig.defaultAddUpstreamExternalAddressHeader
        val hasStaticSecretsDefined = metadata.fieldsMap["has_static_secrets_defined"]?.boolValue
            ?: ListenersConfig.defaultHasStaticSecretsDefined

        return ListenersConfig(
                listenersHostPort.ingressHost,
                listenersHostPort.ingressPort,
                listenersHostPort.egressHost,
                listenersHostPort.egressPort,
                useRemoteAddress,
                accessLogEnabled,
                enableLuaScript,
                accessLogPath,
                resourcesDir,
                addUpstreamExternalAddressHeader,
                accessLogFilterSettings,
                hasStaticSecretsDefined
        )
    }

    private fun createGroup(node: Node): Group {
        val metadata = NodeMetadata(node.metadata, properties)
        val serviceName = serviceName(metadata)
        val proxySettings = proxySettings(metadata)
        val listenersConfig = createListenersConfig(node.id, node.metadata)

        return when {
            hasAllServicesDependencies(metadata) ->
                AllServicesGroup(
                        metadata.communicationMode,
                        serviceName,
                        proxySettings,
                        listenersConfig
                )
            else ->
                ServicesGroup(
                        metadata.communicationMode,
                        serviceName,
                        proxySettings,
                        listenersConfig
                )
        }
    }

    private fun hasAllServicesDependencies(metadata: NodeMetadata): Boolean {
        return !properties.outgoingPermissions.enabled ||
            metadata.proxySettings.outgoing.allServicesDependencies
    }

    private fun serviceName(metadata: NodeMetadata): String {
        return when (properties.incomingPermissions.enabled) {
            true -> metadata.serviceName.orEmpty()
            // TODO: https://github.com/allegro/envoy-control/issues/91
            false -> ""
        }
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
