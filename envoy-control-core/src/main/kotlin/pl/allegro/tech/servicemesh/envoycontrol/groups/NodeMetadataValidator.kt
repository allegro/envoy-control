package pl.allegro.tech.servicemesh.envoycontrol.groups

import io.envoyproxy.controlplane.server.DiscoveryServerCallbacks
import io.envoyproxy.envoy.api.v2.DiscoveryRequest
import io.envoyproxy.envoy.api.v2.DiscoveryResponse
import io.envoyproxy.envoy.api.v2.core.Node
import pl.allegro.tech.servicemesh.envoycontrol.protocol.HttpMethod
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode.ADS
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode.XDS
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties

class AllDependenciesValidationException(serviceName: String?)
    : NodeMetadataValidationException(
    "Blocked service $serviceName from using all dependencies. Only defined services can use all dependencies"
)

class InvalidHttpMethodValidationException(serviceName: String?, method: String)
    : NodeMetadataValidationException(
        "Service: $serviceName defined an unknown method: $method in endpoint permissions."
)

class ConfigurationModeNotSupportedException(serviceName: String?, mode: String)
    : NodeMetadataValidationException(
    "Blocked service $serviceName from receiving updates. $mode is not supported by server."
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
        validateConfigurationMode(metadata)
    }

    private fun validateDependencies(metadata: NodeMetadata) {
        if (!properties.outgoingPermissions.enabled) {
            return
        }
        validateEndpointPermissionsMethods(metadata)
        if (hasAllServicesDependencies(metadata) && !isAllowedToHaveAllServiceDependencies(metadata)) {
            throw AllDependenciesValidationException(metadata.serviceName)
        }
    }

    /**
     * Exception is logged in DiscoveryRequestStreamObserver.onNext()
     * @see io.envoyproxy.controlplane.server.DiscoveryRequestStreamObserver.onNext()
     */
    @Suppress("SwallowedException")
    private fun validateEndpointPermissionsMethods(metadata: NodeMetadata) {
        metadata.proxySettings.incoming.endpoints.forEach { incomingEndpoint ->
            incomingEndpoint.methods.forEach { method ->
                try {
                    HttpMethod.valueOf(method)
                } catch (e: Exception) {
                    throw InvalidHttpMethodValidationException(metadata.serviceName, method)
                }
            }
        }
    }

    private fun hasAllServicesDependencies(metadata: NodeMetadata) =
        metadata.proxySettings.outgoing.allServicesDependencies

    private fun isAllowedToHaveAllServiceDependencies(metadata: NodeMetadata) = properties
        .outgoingPermissions.servicesAllowedToUseWildcard.contains(metadata.serviceName)

    private fun validateConfigurationMode(metadata: NodeMetadata) {
        if (metadata.communicationMode == ADS && !properties.enabledCommunicationModes.ads) {
            throw ConfigurationModeNotSupportedException(metadata.serviceName, "ADS")
        }
        if (metadata.communicationMode == XDS && !properties.enabledCommunicationModes.xds) {
            throw ConfigurationModeNotSupportedException(metadata.serviceName, "XDS")
        }
    }
}
