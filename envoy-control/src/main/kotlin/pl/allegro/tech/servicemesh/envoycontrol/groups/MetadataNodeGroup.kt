package pl.allegro.tech.servicemesh.envoycontrol.groups

import io.envoyproxy.controlplane.cache.NodeGroup
import io.envoyproxy.envoy.api.v2.core.Node

class MetadataNodeGroup(
    val allServicesDependenciesValue: String = "*",
    val outgoingPermissions: Boolean,
    val incomingPermissions: Boolean = false
) : NodeGroup<Group> {

    override fun hash(node: Node): Group {
        val ads = node.metadata
            .fieldsMap["ads"]
            ?.boolValue
            ?: false

        return createGroup(node, ads)
    }

    private fun createGroup(node: Node, ads: Boolean): Group {
        val metadata = NodeMetadata(node.metadata)
        val serviceName = serviceName(metadata)
        val proxySettings = proxySettings(metadata)

        return when {
            hasAllServicesDependencies(metadata) ->
                AllServicesGroup(ads, serviceName(metadata), proxySettings(metadata))
            else ->
                ServicesGroup(ads, serviceName, proxySettings)
        }
    }

    private fun hasAllServicesDependencies(metadata: NodeMetadata): Boolean {
        return !outgoingPermissions ||
            metadata.proxySettings.outgoing.containsDependencyForService(allServicesDependenciesValue)
    }

    private fun serviceName(metadata: NodeMetadata): String {
        return when (incomingPermissions) {
            true -> metadata.serviceName.orEmpty()
            false -> ""
        }
    }

    private fun proxySettings(metadata: NodeMetadata): ProxySettings {
        return when (incomingPermissions) {
            true -> metadata.proxySettings
            false -> metadata.proxySettings.withIncomingPermissionsDisabled()
        }
    }
}
