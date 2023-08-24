package pl.allegro.tech.servicemesh.envoycontrol.config.envoy

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.KotlinModule
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.HttpResponseCloser.addToCloseableResponses
import pl.allegro.tech.servicemesh.envoycontrol.config.service.EchoContainer

class EnvoyAdmin(
    private val address: String,
    private val objectMapper: ObjectMapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
) {

    fun cluster(name: String): ClusterStatus? =
        clusters()
            .firstOrNull { it.name == name }

    fun numOfEndpoints(clusterName: String): Int =
        cluster(clusterName)
            ?.hostStatuses
            ?.size
            ?: 0

    fun endpointsAddress(clusterName: String): List<SocketAddress> =
        cluster(clusterName)
            ?.hostStatuses
            ?.mapNotNull { it.address }
            ?.mapNotNull { it.socketAddress }
            ?: emptyList()

    fun hostStatus(clusterName: String, ip: String): HostStatus? =
        cluster(clusterName)
            ?.hostStatuses
            ?.find {
                it.address?.socketAddress?.address == ip
            }

    fun isEndpointHealthy(clusterName: String, ip: String) = hostStatus(clusterName, ip)
        ?.healthStatus
        ?.edsHealthStatus == "HEALTHY"

    fun isIngressReady() = statValue("http.ingress_http.rq_total") != "-1"

    fun statValue(statName: String): String? = get("stats?filter=^$statName$").body?.use {
        val splitedStats = it.string().lines().first().split(":")
        if (splitedStats.size != 2) {
            return "-1"
        }
        return splitedStats[1].trim()
    }

    fun resetCounters() {
        post("reset_counters")
    }

    private fun clusters(): List<ClusterStatus> {
        val response = get("clusters?format=json")
        return response.body.use {
            objectMapper.readValue(it?.string(), ClusterStatuses::class.java).clusterStatuses
        }
    }

    fun configDump(): String {
        val response = get("config_dump", mapOf("include_eds" to "on"))
        return response.body.use { it!!.string() }
    }

    fun nodeInfo(): String {
        val configDump = configDump()
        val bootstrapConfigDump = bootstrapConfigDump(configDump)
        val node = bootstrapConfigDump.at("/bootstrap/node")
        (node as ObjectNode).remove("hidden_envoy_deprecated_build_version")
        return objectMapper.writeValueAsString(node)
    }

    private fun bootstrapConfigDump(configDump: String) =
        objectMapper.readTree(configDump).at("/configs/0")

    fun circuitBreakerSetting(
        cluster: String,
        setting: String,
        priority: String = "default_priority"
    ): Int {
        val regex = "$cluster::$priority::$setting::(.+)".toRegex()
        val response = get("clusters")
        return response.body?.use { it.string().lines() }
            ?.find { it.matches(regex) }
            ?.let { regex.find(it)!!.groupValues[1].toInt() }!!
    }

    fun cluster(cluster: String, ip: String): AdminInstance? {
        val regex = "$cluster::$ip:${EchoContainer.PORT}::zone::(.+)".toRegex()
        val response = get("clusters")
        return response.body?.use { it.string().lines() }
            ?.find { it.matches(regex) }
            ?.let { AdminInstance(ip, cluster = regex.find(it)!!.groupValues[1]) }
    }

    private val client = OkHttpClient.Builder()
        .build()

    private fun get(path: String, queryParams: Map<String, String> = mapOf()): Response {
        val params = queryParams.entries
            .joinToString(prefix = "?", separator = "&") {
                "${it.key}=${it.value}"
            }
        return client.newCall(
            Request.Builder()
                .get()
                .url("$address/$path$params")
                .build()
        )
            .execute().addToCloseableResponses()
    }

    private fun post(path: String): Response =
        client.newCall(
            Request.Builder()
                .post(RequestBody.create("application/json".toMediaType(), "{}"))
                .url("$address/$path")
                .build()
        ).execute().addToCloseableResponses()

    data class AdminInstance(val ip: String, val cluster: String)
}

data class ClusterStatuses(
    @JsonAlias("cluster_statuses") val clusterStatuses: List<ClusterStatus>
)

data class ClusterStatus(
    val name: String?,
    @JsonAlias("host_statuses") val hostStatuses: List<HostStatus>?,
    @JsonAlias("added_via_api") val addedViaApi: Boolean?
)

data class HostStatus(
    val address: Address?,
    val stats: List<Stats>,
    @JsonAlias("health_status") val healthStatus: HealthStatus,
    val weight: Int?
)

data class Stats(
    val name: String?,
    val type: String?,
    val value: String?
)

data class Address(
    @JsonAlias("socket_address") val socketAddress: SocketAddress?
)

data class SocketAddress(
    val address: String?,
    @JsonAlias("port_value") val portValue: Int?
)

data class HealthStatus(
    @JsonAlias("eds_health_status") val edsHealthStatus: String?,
    @JsonAlias("failed_outlier_check") val failedOutlierCheck: Boolean?
)
