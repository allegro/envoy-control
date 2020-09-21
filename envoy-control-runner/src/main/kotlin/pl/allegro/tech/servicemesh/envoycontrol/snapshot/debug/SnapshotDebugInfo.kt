package pl.allegro.tech.servicemesh.envoycontrol.snapshot.debug

import io.envoyproxy.envoy.config.cluster.v3.Cluster
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment
import io.envoyproxy.envoy.config.listener.v3.Listener
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration
import net.openhft.hashing.LongHashFunction
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.GlobalSnapshot

fun version(version: String) = Version(
    version,
    // we use the same method of hashing as Envoy to create comparable string
    java.lang.Long.toUnsignedString(LongHashFunction.xx().hashBytes(version.toByteArray(Charsets.US_ASCII)))
)

data class Version(
    val raw: String,
    val metric: String
)

private val emptyVersion = Version("", "")

data class Versions(
    val clusters: Version = emptyVersion,
    val endpoints: Version = emptyVersion,
    val routes: Version = emptyVersion,
    val listeners: Version = emptyVersion
)

data class Snapshot(
    val clusters: Map<String, Cluster>,
    val endpoints: Map<String, ClusterLoadAssignment>,
    val listeners: Map<String, Listener> = emptyMap(),
    val routes: Map<String, RouteConfiguration> = emptyMap()
)

data class SnapshotDebugInfo(
    val snapshot: Snapshot,
    val versions: Versions
) {
    constructor(snapshot: io.envoyproxy.controlplane.cache.v3.Snapshot) : this(
        snapshot = Snapshot(
            clusters = snapshot.clusters().resources(),
            endpoints = snapshot.endpoints().resources(),
            listeners = snapshot.listeners().resources(),
            routes = snapshot.routes().resources()
        ),
        versions = Versions(
            clusters = version(snapshot.clusters().version()),
            endpoints = version(snapshot.endpoints().version()),
            listeners = version(snapshot.listeners().version()),
            routes = version(snapshot.routes().version())
        )
    )

    constructor(globalSnapshot: GlobalSnapshot) : this(
        snapshot = Snapshot(
            clusters = globalSnapshot.clusters.resources(),
            endpoints = globalSnapshot.endpoints.resources()
        ),
        versions = Versions()
    )
}
