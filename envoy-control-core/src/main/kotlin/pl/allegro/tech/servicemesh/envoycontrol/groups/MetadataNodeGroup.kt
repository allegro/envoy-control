package pl.allegro.tech.servicemesh.envoycontrol.groups

import io.envoyproxy.controlplane.cache.NodeGroup
import io.envoyproxy.envoy.api.v2.core.Node
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import java.lang.Exception

class MetadataNodeGroup(val properties: SnapshotProperties) : NodeGroup<Group> {
    private val logger by logger()

    override fun hash(node: Node): Group {
        val ads = node.metadata
            .fieldsMap["ads"]
            ?.boolValue
            ?: false

        return createGroup(node, ads)
    }

    private fun createListenersConfig(node: Node): ListenersConfig? {
        val ingressHost: String
        val ingressPort: Int
        val egressHost : String
        val egressPort : Int
        try {
            ingressHost = node.metadata.fieldsMap["ingress_host"]!!.stringValue
            ingressPort = node.metadata.fieldsMap["ingress_port"]!!.numberValue.toInt()
            egressHost = node.metadata.fieldsMap["egress_host"]!!.stringValue
            egressPort = node.metadata.fieldsMap["egress_port"]!!.numberValue.toInt()
        } catch (e: Exception) {
            logger.warn("Node ${node.id} does not have properly configured ingress / egress listeners. " +
                    "This is normal during the migration from static listeners to dynamic, " +
                    "but should not occur after that.", e)

            return null
        }

        val useRemoteAddress = node.metadata.fieldsMap["use_remote_address"]?.boolValue ?: ListenersConfig.defaultUseRemoteAddress
        val accessLogEnabled = node.metadata.fieldsMap["access_log_enabled"]?.boolValue ?: ListenersConfig.defaultAccessLogEnabled
        val accessLogPath = node.metadata.fieldsMap["access_log_path"]?.stringValue ?: ListenersConfig.defaultAccessLogPath
        val luaScriptDir = node.metadata.fieldsMap["lua_script_dir"]?.stringValue ?: ListenersConfig.defaultLuaScriptDir

        return ListenersConfig(ingressHost, ingressPort, egressHost, egressPort, useRemoteAddress, accessLogEnabled, accessLogPath, luaScriptDir)
    }

    private fun createGroup(node: Node, ads: Boolean): Group {
        val metadata = NodeMetadata(node.metadata, properties)
        val serviceName = serviceName(metadata)
        val proxySettings = proxySettings(metadata)
        val listenersConfig = createListenersConfig(node)

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
