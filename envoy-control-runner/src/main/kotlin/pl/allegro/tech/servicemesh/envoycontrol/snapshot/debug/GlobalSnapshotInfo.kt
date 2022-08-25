package pl.allegro.tech.servicemesh.envoycontrol.snapshot.debug


data class GlobalSnapshotInfo(val clusters: List<ClusterInfo> = emptyList(), val error: Boolean = false, val errorMessage: String? = null)

data class ClusterInfo(val datacenter: String, val endpoints: List<EndpointInfo>)

data class EndpointInfo(val ip: String, val port: Int)
