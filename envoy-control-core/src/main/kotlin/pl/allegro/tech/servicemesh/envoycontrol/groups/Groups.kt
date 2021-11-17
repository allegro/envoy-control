package pl.allegro.tech.servicemesh.envoycontrol.groups

sealed class Group {
    abstract val communicationMode: CommunicationMode
    abstract val serviceName: String
    abstract val proxySettings: ProxySettings
    abstract val listenersConfig: ListenersConfig?
    abstract val version: ResourceVersion
}

enum class ResourceVersion {
    V2, V3
}

data class ServicesGroup(
    override val communicationMode: CommunicationMode,
    override val serviceName: String = "",
    override val proxySettings: ProxySettings = ProxySettings(),
    override val listenersConfig: ListenersConfig? = null,
    override val version: ResourceVersion = ResourceVersion.V3
) : Group()

data class AllServicesGroup(
    override val communicationMode: CommunicationMode,
    override val serviceName: String = "",
    override val proxySettings: ProxySettings = ProxySettings(),
    override val listenersConfig: ListenersConfig? = null,
    override val version: ResourceVersion = ResourceVersion.V3
) : Group()

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
    val resourcesDir: String = defaultResourcesDir,
    val addUpstreamExternalAddressHeader: Boolean = defaultAddUpstreamExternalAddressHeader,
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
        const val defaultResourcesDir = "envoy"
        const val defaultHasStaticSecretsDefined: Boolean = false
        const val defaultUseTransparentProxy: Boolean = false
    }
}
