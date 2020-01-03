package pl.allegro.tech.servicemesh.envoycontrol.snapshot.debug

import io.envoyproxy.controlplane.cache.NodeGroup
import io.envoyproxy.controlplane.cache.Snapshot
import io.envoyproxy.controlplane.cache.SnapshotCache
import io.envoyproxy.envoy.api.v2.core.Node
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import pl.allegro.tech.servicemesh.envoycontrol.ControlPlane
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group

@RestController
class SnapshotDebugController(controlPlane: ControlPlane) {
    val cache: SnapshotCache<Group> = controlPlane.cache
    val nodeGroup: NodeGroup<Group> = controlPlane.nodeGroup

    /**
     * Returns a textual representation of the snapshot for debugging purposes.
     * It contains the versions of XDS resources and the contents for a provided node JSON
     * extracted from Envoy's config_dump endpoint.
     */
    @PostMapping("/snapshot")
    fun snapshot(@RequestBody node: Node): ResponseEntity<String> {
        val nodeHash = nodeGroup.hash(node)
        val snapshot = cache.getSnapshot(nodeHash)
        return if (snapshot == null) {
            return ResponseEntity("snapshot missing", HttpStatus.NOT_FOUND)
        } else {
            ResponseEntity(
                versions(snapshot) +
                    "snapshot:\n" +
                    snapshot.toString(),
                HttpStatus.OK
            )
        }
    }

    private fun versions(snapshot: Snapshot): String {
        val versions = StringBuilder()
            .append("clusters: ${snapshot.clusters().version()}\n")
            .append("endpoints: ${snapshot.endpoints().version()}\n")
            .append("routes: ${snapshot.routes().version()}\n")
            .append("listeners: ${snapshot.listeners().version()}\n")
        return versions.toString()
    }
}
