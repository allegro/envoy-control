package pl.allegro.tech.servicemesh.envoycontrol.groups

import io.envoyproxy.controlplane.cache.NodeGroup
import io.envoyproxy.envoy.api.v2.core.Node
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties

class MetadataNodeGroup(val properties: SnapshotProperties) : NodeGroup<Group> {

    override fun hash(node: Node): Group {
        val ads = node.metadata
            .fieldsMap["ads"]
            ?.boolValue
            ?: false

        return createGroup(node, ads)
    }

    private fun createGroup(node: Node, ads: Boolean): Group {
        val metadata = NodeMetadata(node.metadata, properties)
        val serviceName = serviceName(metadata)
        val proxySettings = proxySettings(metadata)

        val ingressHost = node.metadata.fieldsMap["ingress_host"]?.stringValue ?: "0.0.0.0"
        val ingressPort= node.metadata.fieldsMap["ingress_port"]?.numberValue?.toInt() ?: -1
        val egressHost= node.metadata.fieldsMap["egress_host"]?.stringValue ?: "0.0.0.0"
        val egressPort= node.metadata.fieldsMap["egress_port"]?.numberValue?.toInt() ?: -1
        val useRemoteAddress= node.metadata.fieldsMap["use_remote_address"]?.boolValue ?: true
        val accessLogEnabled= node.metadata.fieldsMap["access_log_enabled"]?.boolValue ?: false
        val accessLogPath= node.metadata.fieldsMap["access_log_path"]?.stringValue ?: "/dev/stdout"
        val luaScriptDir= node.metadata.fieldsMap["lua_script_dir"]?.stringValue ?: "envoy/lua"

        return when {
            hasAllServicesDependencies(metadata) ->
                AllServicesGroup(ads, serviceName(metadata), proxySettings(metadata), ingressHost, ingressPort, egressHost, egressPort, useRemoteAddress, accessLogEnabled, accessLogPath, luaScriptDir)
            else ->
                ServicesGroup(ads, serviceName, proxySettings, ingressHost, ingressPort, egressHost, egressPort, useRemoteAddress, accessLogEnabled, accessLogPath, luaScriptDir)
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
