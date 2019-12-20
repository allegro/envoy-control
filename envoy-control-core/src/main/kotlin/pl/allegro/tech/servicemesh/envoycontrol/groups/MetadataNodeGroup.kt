package pl.allegro.tech.servicemesh.envoycontrol.groups

import com.google.protobuf.Struct
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

    private fun createListenersConfig(id: String, metadata: Struct): ListenersConfig? {
        val ingressHost: String
        val ingressPort: Int
        val egressHost: String
        val egressPort: Int
        try {
            ingressHost = metadata.fieldsMap["ingress_host"]!!.stringValue
            ingressPort = metadata.fieldsMap["ingress_port"]!!.numberValue.toInt()
            egressHost = metadata.fieldsMap["egress_host"]!!.stringValue
            egressPort = metadata.fieldsMap["egress_port"]!!.numberValue.toInt()
        } catch (e: Exception) {
            logger.debug("Node $id does not have properly configured ingress / egress listeners. " +
                    "This is normal during the migration from static listeners to dynamic, " +
                    "but should not occur after that.", e)

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
