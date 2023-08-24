package pl.allegro.tech.servicemesh.envoycontrol.utils

import pl.allegro.tech.servicemesh.envoycontrol.utils.ClusterConstants.AGGREGATE_CLUSTER_POSTFIX
import pl.allegro.tech.servicemesh.envoycontrol.utils.ClusterConstants.SECONDARY_CLUSTER_POSTFIX

object ClusterConstants {
    const val SECONDARY_CLUSTER_POSTFIX= "secondary"
    const val AGGREGATE_CLUSTER_POSTFIX= "aggregate"
}
fun getSecondaryClusterName(serviceName: String) :String {
    return "$serviceName-$SECONDARY_CLUSTER_POSTFIX"
}

fun getAggregateClusterName(serviceName: String) :String {
    return "$serviceName-$AGGREGATE_CLUSTER_POSTFIX"
}
