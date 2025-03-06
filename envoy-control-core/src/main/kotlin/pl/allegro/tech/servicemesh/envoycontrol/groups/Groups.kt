package pl.allegro.tech.servicemesh.envoycontrol.groups

import pl.allegro.tech.servicemesh.envoycontrol.snapshot.AccessLogFiltersProperties

sealed class Group {
    abstract val communicationMode: CommunicationMode
    abstract val serviceName: String
    abstract val serviceId: Int?
    abstract val discoveryServiceName: String?
    abstract val proxySettings: ProxySettings
    abstract val pathNormalizationPolicy: PathNormalizationPolicy
    abstract val listenersConfig: ListenersConfig?
    abstract val compressionConfig: CompressionConfig
}

data class ServicesGroup(
    override val communicationMode: CommunicationMode,
    override val serviceName: String = "",
    override val serviceId: Int? = null,
    override val discoveryServiceName: String? = null,
    override val proxySettings: ProxySettings = ProxySettings(),
    override val pathNormalizationPolicy: PathNormalizationPolicy = PathNormalizationPolicy(),
    override val listenersConfig: ListenersConfig? = null,
    override val compressionConfig: CompressionConfig = CompressionConfig(),
) : Group()

data class AllServicesGroup(
    override val communicationMode: CommunicationMode,
    override val serviceName: String = "",
    override val serviceId: Int? = null,
    override val discoveryServiceName: String? = null,
    override val proxySettings: ProxySettings = ProxySettings(),
    override val pathNormalizationPolicy: PathNormalizationPolicy = PathNormalizationPolicy(),
    override val listenersConfig: ListenersConfig? = null,
    override val compressionConfig: CompressionConfig = CompressionConfig(),
) : Group()

data class PathNormalizationPolicy(
    val normalizationEnabled: Boolean? = null,
    val mergeSlashes: Boolean? = null,
    val pathWithEscapedSlashesAction: String? = null
)

data class CompressionConfig(
    val gzip: Compressor? = null,
    val brotli: Compressor? = null,
)

data class Compressor(
    val enabled: Boolean? = null,
    val quality: Int? = null,
)

data class ListenersConfig(
    val ingressHost: String,
    val ingressPort: Int,
    val egressHost: String,
    val egressPort: Int,
    val useRemoteAddress: Boolean = defaultUseRemoteAddress,
    val generateRequestId: Boolean = defaultGenerateRequestId,
    val preserveExternalRequestId: Boolean = defaultPreserveExternalRequestId,
    val accessLogEnabled: Boolean = defaultAccessLogEnabled,
    val enableLuaScript: Boolean = defaultEnableLuaScript,
    val accessLogPath: String = defaultAccessLogPath,
    val addUpstreamExternalAddressHeader: Boolean = defaultAddUpstreamExternalAddressHeader,
    val addJwtFailureStatus: Boolean = true,
    val accessLogFilterSettings: AccessLogFilterSettings,
    val hasStaticSecretsDefined: Boolean = defaultHasStaticSecretsDefined,
    val useTransparentProxy: Boolean = defaultUseTransparentProxy
) {

    companion object {
        const val defaultAccessLogPath = "/dev/stdout"
        const val defaultUseRemoteAddress = false
        const val defaultGenerateRequestId = false
        const val defaultPreserveExternalRequestId = false
        const val defaultAccessLogEnabled = false
        const val defaultEnableLuaScript = false
        const val defaultAddUpstreamExternalAddressHeader = false
        const val defaultHasStaticSecretsDefined: Boolean = false
        const val defaultUseTransparentProxy: Boolean = false

        val DEFAULT = ListenersConfig(
            "",
            0,
            "",
            0,
            accessLogFilterSettings = AccessLogFilterSettings(null, AccessLogFiltersProperties())
        )
    }
}

fun ListenersConfig?.orDefault() = this ?: ListenersConfig.DEFAULT
