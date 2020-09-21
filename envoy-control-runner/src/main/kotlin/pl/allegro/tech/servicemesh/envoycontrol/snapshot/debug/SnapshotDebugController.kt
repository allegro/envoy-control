package pl.allegro.tech.servicemesh.envoycontrol.snapshot.debug

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.google.protobuf.Any
import com.google.protobuf.BoolValue
import com.google.protobuf.Duration
import com.google.protobuf.Message
import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.google.protobuf.util.JsonFormat
import com.google.protobuf.util.JsonFormat.TypeRegistry
import io.envoyproxy.controlplane.cache.NodeGroup
import io.envoyproxy.controlplane.cache.SnapshotCache
import io.envoyproxy.controlplane.cache.v3.Snapshot
import io.envoyproxy.envoy.config.core.v3.Node
import io.envoyproxy.envoy.config.rbac.v3.RBAC
import io.envoyproxy.envoy.extensions.filters.http.header_to_metadata.v3.Config
import io.envoyproxy.envoy.extensions.filters.http.lua.v3.Lua
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext
import io.envoyproxy.envoy.extensions.filters.http.rbac.v3.RBAC as RBACFilter
import io.envoyproxy.envoy.type.matcher.PathMatcher
import io.envoyproxy.envoy.type.matcher.StringMatcher
import org.springframework.boot.jackson.JsonComponent
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.RestController
import pl.allegro.tech.servicemesh.envoycontrol.ControlPlane
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotUpdater

@RestController
class SnapshotDebugController(controlPlane: ControlPlane) {
    val cache: SnapshotCache<Group, Snapshot> = controlPlane.cache
    val nodeGroup: NodeGroup<Group> = controlPlane.nodeGroup
    val snapshotUpdater: SnapshotUpdater = controlPlane.snapshotUpdater

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

    @GetMapping("/snapshot-global")
    fun globalSnapshot(@RequestParam xds: Boolean?): ResponseEntity<SnapshotDebugInfo> {
        val globalSnapshot = snapshotUpdater.getGlobalSnapshot()
        if (xds == true) {
            return if (globalSnapshot?.xdsSnapshot == null) {
                throw GlobalSnapshotNotFoundException("Xds global snapshot missing")
            } else {
                ResponseEntity(
                    SnapshotDebugInfo(globalSnapshot.xdsSnapshot!!),
                    HttpStatus.OK
                )
            }
        }
        return if (globalSnapshot?.adsSnapshot == null) {
            throw GlobalSnapshotNotFoundException("Ads global snapshot missing")
        } else {
            ResponseEntity(
                SnapshotDebugInfo(globalSnapshot.adsSnapshot!!),
                HttpStatus.OK
            )
        }
    }

    @JsonComponent
    class ProtoSerializer : JsonSerializer<Message>() {
        final val typeRegistry: TypeRegistry = TypeRegistry.newBuilder()
            .add(HttpConnectionManager.getDescriptor())
            .add(Config.getDescriptor())
            .add(BoolValue.getDescriptor())
            .add(Duration.getDescriptor())
            .add(Struct.getDescriptor())
            .add(Value.getDescriptor())
            .add(RBAC.getDescriptor())
            .add(RBACFilter.getDescriptor())
            .add(Any.getDescriptor())
            .add(PathMatcher.getDescriptor())
            .add(StringMatcher.getDescriptor())
            .add(UpstreamTlsContext.getDescriptor())
            .add(Lua.getDescriptor())
            .build()

        val printer: JsonFormat.Printer = JsonFormat.printer().usingTypeRegistry(typeRegistry)

        override fun serialize(message: Message, gen: JsonGenerator, serializers: SerializerProvider) {
            gen.writeRawValue(printer.print(message))
        }
    }

    class SnapshotNotFoundException : RuntimeException("snapshot missing")
    class GlobalSnapshotNotFoundException(message: String) : RuntimeException(message)

    @ExceptionHandler
    @ResponseBody
    fun handleSnapshotMissing(exception: SnapshotNotFoundException): ResponseEntity<String> = ResponseEntity
        .status(HttpStatus.NOT_FOUND)
        .body(exception.message)

    @ExceptionHandler
    @ResponseBody
    fun handleGlobalSnapshotMissing(exception: GlobalSnapshotNotFoundException): ResponseEntity<String> = ResponseEntity
        .status(HttpStatus.NOT_FOUND)
        .body(exception.message)
}
