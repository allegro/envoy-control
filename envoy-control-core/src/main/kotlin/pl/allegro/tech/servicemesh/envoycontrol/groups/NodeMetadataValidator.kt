package pl.allegro.tech.servicemesh.envoycontrol.groups

import io.envoyproxy.controlplane.server.DiscoveryServerCallbacks
import io.envoyproxy.envoy.api.v2.DiscoveryRequest
import io.envoyproxy.envoy.api.v2.DiscoveryResponse
import io.envoyproxy.envoy.api.v2.core.Node
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties

class AllDependenciesValidationException(serviceName: String?)
    : NodeMetadataValidationException(
    "Blocked service $serviceName from using all dependencies. Only defined services can use all dependencies"
)

class NodeMetadataValidator(
    val properties: SnapshotProperties
) : DiscoveryServerCallbacks {
    override fun onStreamClose(streamId: Long, typeUrl: String?) {}

    override fun onStreamCloseWithError(streamId: Long, typeUrl: String?, error: Throwable?) {}

    override fun onStreamOpen(streamId: Long, typeUrl: String?) {}

    override fun onStreamRequest(streamId: Long, request: DiscoveryRequest?) {
        request?.node?.let { validateMetadata(it) }
    }

    override fun onStreamResponse(
        streamId: Long,
        request: DiscoveryRequest?,
        response: DiscoveryResponse?
    ) {
    }

    private fun validateMetadata(node: Node) {
        // Some validation logic is executed when NodeMetadata is created.
        // This may throw NodeMetadataValidationException
        val metadata = NodeMetadata(node.metadata, properties)

        validateDependencies(metadata)
    }

    private fun validateDependencies(metadata: NodeMetadata) {
        if (!properties.outgoingPermissions.enabled) {
            return
        }
        if (hasAllServicesDependencies(metadata) && !isAllowedToHaveAllServiceDependencies(metadata)) {
            throw AllDependenciesValidationException(metadata.serviceName)
        }
    }

    private fun hasAllServicesDependencies(metadata: NodeMetadata) =
        metadata.proxySettings.outgoing.containsDependencyForService(
            properties.outgoingPermissions.allServicesDependenciesValue
        )

    private fun isAllowedToHaveAllServiceDependencies(metadata: NodeMetadata) = properties
        .outgoingPermissions.servicesAllowedToUseWildcard.contains(metadata.serviceName)
}
