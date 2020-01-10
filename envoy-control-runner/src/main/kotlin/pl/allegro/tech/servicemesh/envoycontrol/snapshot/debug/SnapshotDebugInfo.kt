package pl.allegro.tech.servicemesh.envoycontrol.snapshot.debug

import io.envoyproxy.envoy.api.v2.Cluster
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment
import io.envoyproxy.envoy.api.v2.Listener
import io.envoyproxy.envoy.api.v2.RouteConfiguration
import net.openhft.hashing.LongHashFunction

fun version(version: String) = Version(
    version,
    // we use the same method of hashing as Envoy to create comparable string
    java.lang.Long.toUnsignedString(LongHashFunction.xx().hashBytes(version.toByteArray(Charsets.US_ASCII)))
)

data class Version(
    val raw: String,
    val metric: String
)

data class Versions(
    val clusters: Version,
    val endpoints: Version,
    val routes: Version,
    val listeners: Version
)

data class Snapshot(
    val clusters: Map<String, Cluster>,
    val endpoints: Map<String, ClusterLoadAssignment>,
    val listeners: Map<String, Listener>,
    val routes: Map<String, RouteConfiguration>
)

data class SnapshotDebugInfo(
    val snapshot: Snapshot,
    val versions: Versions
)
