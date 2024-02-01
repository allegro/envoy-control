package pl.allegro.tech.servicemesh.envoycontrol.utils

import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SnapshotProperties
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.ZoneWeights

const val INGRESS_HOST = "ingress-host"
const val INGRESS_PORT = 3380
const val EGRESS_HOST = "egress-host"
const val EGRESS_PORT = 3380
const val DEFAULT_IDLE_TIMEOUT = 100L
const val DEFAULT_SERVICE_NAME = "service-name"
const val DEFAULT_DISCOVERY_SERVICE_NAME = "discovery-service-name"
const val CLUSTER_NAME = "cluster-name"
const val CLUSTER_NAME1 = "cluster-1"
const val CLUSTER_NAME2 = "cluster-2"
const val TRAFFIC_SPLITTING_ZONE = "dc2"
const val CURRENT_ZONE = "dc1"

val DEFAULT_CLUSTER_WEIGHTS = zoneWeights(mapOf(CURRENT_ZONE to 60, TRAFFIC_SPLITTING_ZONE to 40))

val SNAPSHOT_PROPERTIES_WITH_WEIGHTS = SnapshotProperties().also {
    it.dynamicListeners.enabled = false
    it.loadBalancing.trafficSplitting.weightsByService = mapOf(
        DEFAULT_SERVICE_NAME to DEFAULT_CLUSTER_WEIGHTS
    )
    it.loadBalancing.trafficSplitting.zoneName = TRAFFIC_SPLITTING_ZONE
}

fun zoneWeights(weightByZone: Map<String, Int>) = ZoneWeights().also {
    it.weightByZone = weightByZone
}
