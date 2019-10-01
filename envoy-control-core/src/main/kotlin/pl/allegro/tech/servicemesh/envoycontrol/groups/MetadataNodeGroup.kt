package pl.allegro.tech.servicemesh.envoycontrol.groups

import io.envoyproxy.controlplane.cache.NodeGroup
import io.envoyproxy.envoy.api.v2.core.Node
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties

class MetadataNodeGroup(
    val properties: SnapshotProperties
) : NodeGroup<Group> {

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

        return when {
            hasAllServicesDependencies(metadata) ->
                AllServicesGroup(ads, serviceName(metadata), proxySettings(metadata))
            else ->
                ServicesGroup(ads, serviceName, proxySettings)
        }
    }

    private fun hasAllServicesDependencies(metadata: NodeMetadata): Boolean {
        return !outgoingPermissionsEnabled() ||
            metadata.proxySettings.outgoing.containsDependencyForService(allServicesDependenciesValue())
    }

    private fun serviceName(metadata: NodeMetadata): String {
        return when (incomingPermissionsEnabled()) {
            true -> metadata.serviceName.orEmpty()
            false -> ""
        }
    }

    private fun proxySettings(metadata: NodeMetadata): ProxySettings {
        return when (incomingPermissionsEnabled()) {
            true -> metadata.proxySettings
            false -> metadata.proxySettings.withIncomingPermissionsDisabled()
        }
    }

    private fun incomingPermissionsEnabled() = properties.incomingPermissions.enabled

    private fun outgoingPermissionsEnabled() = properties.outgoingPermissions.enabled

    private fun allServicesDependenciesValue() = properties.outgoingPermissions.allServicesDependenciesValue
}
