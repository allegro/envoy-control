package pl.allegro.tech.servicemesh.envoycontrol

import com.google.protobuf.Any
import io.envoyproxy.controlplane.cache.ConfigWatcher
import io.envoyproxy.controlplane.server.DiscoveryServerCallbacks
import io.envoyproxy.controlplane.server.ExecutorGroup
import io.envoyproxy.controlplane.server.V3DiscoveryServer
import io.envoyproxy.controlplane.server.serializer.ProtoResourcesSerializer
import io.envoyproxy.envoy.config.core.v3.ControlPlane
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse

class V3DiscoveryServerWithIdentifier(
    callbacks: List<DiscoveryServerCallbacks>,
    configWatcher: ConfigWatcher,
    executorGroup: ExecutorGroup?,
    protoResourcesSerializer: ProtoResourcesSerializer,
    private val identifier: String?
) : V3DiscoveryServer(
    callbacks,
    configWatcher,
    executorGroup,
    protoResourcesSerializer
) {

    override fun makeResponse(
        version: String, resources: Collection<Any>,
        typeUrl: String,
        nonce: String
    ): DiscoveryResponse? {
        return DiscoveryResponse.newBuilder()
            .setNonce(nonce)
            .setVersionInfo(version)
            .apply { identifier?.let { this.setControlPlane(controlPlane(it)) } }
            .addAllResources(resources)
            .setTypeUrl(typeUrl)
            .build()
    }

    private fun controlPlane(identifier: String) = ControlPlane.newBuilder().setIdentifier(identifier).build()
}
