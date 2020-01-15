package pl.allegro.tech.servicemesh.envoycontrol.snapshot.debug

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.google.protobuf.Message
import com.google.protobuf.util.JsonFormat
import io.envoyproxy.controlplane.cache.NodeGroup
import io.envoyproxy.controlplane.cache.SnapshotCache
import io.envoyproxy.envoy.api.v2.core.Node
import org.springframework.boot.jackson.JsonComponent
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseBody
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
    fun snapshot(@RequestBody node: Node): ResponseEntity<SnapshotDebugInfo> {
        val nodeHash = nodeGroup.hash(node)
        val snapshot = cache.getSnapshot(nodeHash)
        return if (snapshot == null) {
            throw SnapshotNotFoundException()
        } else {
            ResponseEntity(
                SnapshotDebugInfo(snapshot),
                HttpStatus.OK
            )
        }
    }

    @JsonComponent
    class ProtoSerializer : JsonSerializer<Message>() {
        override fun serialize(message: Message, gen: JsonGenerator, serializers: SerializerProvider) {
            gen.writeRawValue(JsonFormat.printer().print(message))
        }
    }

    class SnapshotNotFoundException : RuntimeException("snapshot missing")

    @ExceptionHandler
    @ResponseBody
    fun handleSnapshotMissing(exception: SnapshotNotFoundException): ResponseEntity<String> = ResponseEntity
        .status(HttpStatus.NOT_FOUND)
        .body(exception.message)
}
