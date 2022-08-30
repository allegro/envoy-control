package pl.allegro.tech.servicemesh.envoycontrol.snapshot.debug

import io.envoyproxy.controlplane.cache.NodeGroup
import io.envoyproxy.controlplane.cache.SnapshotCache
import io.envoyproxy.controlplane.cache.v3.Snapshot
import io.envoyproxy.envoy.config.core.v3.Node
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment
import org.springframework.stereotype.Component
import pl.allegro.tech.servicemesh.envoycontrol.ControlPlane
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.GlobalSnapshot
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotUpdater

@Component
class SnapshotDebugService(
    controlPlane: ControlPlane
) {
    val cache: SnapshotCache<Group, Snapshot> = controlPlane.cache
    val nodeGroup: NodeGroup<Group> = controlPlane.nodeGroup
    val snapshotUpdater: SnapshotUpdater = controlPlane.snapshotUpdater

    fun snapshot(node: Node): SnapshotDebugInfo {
        val nodeHash = nodeGroup.hash(node)
        val snapshot = cache.getSnapshot(nodeHash)
        return if (snapshot == null) {
            throw SnapshotNotFoundException()
        } else {
            SnapshotDebugInfo(snapshot)
        }
    }

    fun globalSnapshot(xds: Boolean): SnapshotDebugInfo {
        val globalSnapshot = snapshotUpdater.getGlobalSnapshot()
        if (xds) {
            return if (globalSnapshot?.xdsSnapshot == null) {
                throw GlobalSnapshotNotFoundException("Xds global snapshot missing")
            } else {
                SnapshotDebugInfo(globalSnapshot.xdsSnapshot!!)
            }
        }
        return if (globalSnapshot?.adsSnapshot == null) {
            throw GlobalSnapshotNotFoundException("Ads global snapshot missing")
        } else {
            SnapshotDebugInfo(globalSnapshot.adsSnapshot!!)
        }
    }

    fun globalSnapshot(service: String, dc: String?, xds: Boolean): EndpointInfoList {
        val updateResult = snapshotUpdater.getGlobalSnapshot()
        val globalSnapshot = if (xds) {
            updateResult?.xdsSnapshot
        } else {
            updateResult?.adsSnapshot
        }
        val endpoints = extractEndpoints(globalSnapshot, service)
        val endpointInfos = getEndpointsInfo(endpoints, dc)
        return EndpointInfoList(endpointInfos)
    }

    private fun getEndpointsInfo(
        endpoints: ClusterLoadAssignment,
        dc: String?
    ) = endpoints.endpointsList
        .filter { dc == null || it.locality.zone.endsWith(dc, true) }
        .flatMap { locality ->
            locality.lbEndpointsList.map {
                val socketAddress = it.endpoint.address.socketAddress
                EndpointInfo(locality.locality.zone, socketAddress.address, socketAddress.portValue)
            }
        }

    private fun extractEndpoints(globalSnapshot: GlobalSnapshot?, service: String): ClusterLoadAssignment {
        if (globalSnapshot == null) {
            logger.warn("Global snapshot is missing")
            throw GlobalSnapshotNotFoundException("Global snapshot is missing")
        }
        val endpoints = globalSnapshot.endpoints
            .resources()[service]
        if (endpoints == null) {
            logger.warn("Can not find $service in global snapshot")
            throw GlobalSnapshotNotFoundException("Service $service not found in global snapshot")
        }
        return endpoints
    }

    private companion object {
        val logger by logger()
    }

}

class SnapshotNotFoundException : RuntimeException("snapshot missing")

class GlobalSnapshotNotFoundException(message: String) : RuntimeException(message)
