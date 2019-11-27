package pl.allegro.tech.servicemesh.envoycontrol.config.envoy

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import pl.allegro.tech.servicemesh.envoycontrol.config.containers.EchoContainer

class EnvoyAdmin(
    val address: String,
    val objectMapper: ObjectMapper = ObjectMapper()
        .registerModule(KotlinModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
) {

    fun cluster(name: String): ClusterStatus? =
        clusters()
            .filter { it.name == name }
            .firstOrNull()

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

    fun statValue(statName: String): String? = get("stats?filter=$statName").body()?.use {
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
        return response.body().use {
            objectMapper.readValue(it?.string(), ClusterStatuses::class.java).clusterStatuses
        }
    }

    fun circuitBreakerSetting(
        cluster: String,
        setting: String,
        priority: String = "default_priority"
    ): Int {
        val regex = "$cluster::$priority::$setting::(.+)".toRegex()
        val response = get("clusters")
        return response.body()?.use { it.string().lines() }
            ?.find { it.matches(regex) }
            ?.let { regex.find(it)!!.groupValues[1].toInt() }!!
    }

    fun zone(cluster: String, ip: String): AdminInstance? {
        val regex = "$cluster::$ip:${EchoContainer.PORT}::zone::(.+)".toRegex()
        val response = get("clusters")
        return response.body()?.use { it.string().lines() }
            ?.find { it.matches(regex) }
            ?.let { AdminInstance(ip, zone = regex.find(it)!!.groupValues[1]) }
    }

    private val client = OkHttpClient.Builder()
        .build()

    private fun get(path: String): Response =
        client.newCall(
            Request.Builder()
                .get()
                .url("$address/$path")
                .build()
        )
            .execute()

    private fun post(path: String): Response =
        client.newCall(
            Request.Builder()
                .post(RequestBody.create(MediaType.get("application/json"), "{}"))
                .url("$address/$path")
                .build()
        ).execute()

    data class AdminInstance(val ip: String, val zone: String)
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
