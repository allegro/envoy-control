package pl.allegro.tech.servicemesh.envoycontrol.utils

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
const val MAIN_CLUSTER_NAME = "cluster-1"
const val SECONDARY_CLUSTER_NAME = "cluster-1-secondary"
const val AGGREGATE_CLUSTER_NAME = "cluster-1-aggregate"
const val TRAFFIC_SPLITTING_FORCE_TRAFFIC_ZONE = "dc2"
const val CURRENT_ZONE = "dc1"

val DEFAULT_CLUSTER_WEIGHTS = zoneWeights(50, 50)

fun zoneWeights(main: Int, secondary: Int) = ZoneWeights().also {
    it.main = main
    it.secondary = secondary
}

