package pl.allegro.tech.servicemesh.envoycontrol.groups

import com.google.protobuf.Struct
import io.envoyproxy.controlplane.cache.NodeGroup
import io.envoyproxy.envoy.api.v2.core.Node
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties

class MetadataNodeGroup(val properties: SnapshotProperties) : NodeGroup<Group> {
    private val logger by logger()

    override fun hash(node: Node): Group {
        val ads = node.metadata
            .fieldsMap["ads"]
            ?.boolValue
            ?: false

        return createGroup(node, ads)
    }

    private fun createListenersConfig(id: String, metadata: Struct): ListenersConfig? {
        val ingressHostValue = metadata.fieldsMap["ingress_host"]
        val ingressPortValue = metadata.fieldsMap["ingress_port"]
        val egressHostValue = metadata.fieldsMap["egress_host"]
        val egressPortValue = metadata.fieldsMap["egress_port"]

        if (listOf(ingressHostValue, ingressPortValue, egressHostValue, egressPortValue).all { it == null }) {
            logger.debug("Node $id with static listener config connected")
            return null
        }

        if (ingressHostValue == null) {
            logger.info("Node $id has no ingress host configured, falling back to static listeners.")
            return null
        }

        if (ingressPortValue == null) {
            logger.info("Node $id has no ingress port configured, falling back to static listeners.")
            return null
        }

        if (egressHostValue == null) {
            logger.info("Node $id has no egerss host configured, falling back to static listeners.")
            return null
        }

        if (egressPortValue == null) {
            logger.info("Node $id has no egress port configured, falling back to static listeners.")
            return null
        }

        val ingressHost = ingressHostValue.stringValue
        val ingressPort = ingressPortValue.numberValue.toInt()

        if (ingressPort < 0 || ingressPort > 65535) {
            logger.warn("Node $id has ingress port out of valid range [0-65535]. Falling back to static listeners.")
            return null
        }

        val egressHost = egressHostValue.stringValue
        val egressPort = egressPortValue.numberValue.toInt()

        if (egressPort < 0 || egressPort > 65535) {
            logger.warn("Node $id has egress port out of valid range [0-65535]. Falling back to static listeners.")
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

        return ListenersConfig(
                ingressHost,
                ingressPort,
                egressHost,
                egressPort,
                useRemoteAddress,
                accessLogEnabled,
                enableLuaScript,
                accessLogPath,
                resourcesDir
        )
    }

    private fun createGroup(node: Node, ads: Boolean): Group {
        val metadata = NodeMetadata(node.metadata, properties)
        val serviceName = serviceName(metadata)
        val proxySettings = proxySettings(metadata)
        val listenersConfig = createListenersConfig(node.id, node.metadata)

        return when {
            hasAllServicesDependencies(metadata) ->
                AllServicesGroup(
                        ads,
                        serviceName,
                        proxySettings,
                        listenersConfig
                )
            else ->
                ServicesGroup(
                        ads,
                        serviceName,
                        proxySettings,
                        listenersConfig
                )
        }
    }

    private fun hasAllServicesDependencies(metadata: NodeMetadata): Boolean {
        return !properties.outgoingPermissions.enabled || metadata.proxySettings.outgoing.containsDependencyForService(
            properties.outgoingPermissions.allServicesDependenciesValue
        )
    }

    private fun serviceName(metadata: NodeMetadata): String {
        return when (properties.incomingPermissions.enabled) {
            true -> metadata.serviceName.orEmpty()
            false -> ""
        }
    }

    private fun proxySettings(metadata: NodeMetadata): ProxySettings {
        return when (properties.incomingPermissions.enabled) {
            true -> metadata.proxySettings
            false -> metadata.proxySettings.withIncomingPermissionsDisabled()
        }
    }
}
