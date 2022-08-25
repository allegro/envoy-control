package pl.allegro.tech.servicemesh.envoycontrol.snapshot.debug

data class GlobalSnapshotInfo(val clusters: List<EndpointInfo> = emptyList())

data class EndpointInfo(val datacenter: String, val ip: String, val port: Int)
