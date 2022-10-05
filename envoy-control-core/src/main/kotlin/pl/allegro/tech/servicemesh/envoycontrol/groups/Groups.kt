package pl.allegro.tech.servicemesh.envoycontrol.groups

sealed interface Group<T> {
    val communicationMode: CommunicationMode
    val serviceName: String
    val discoveryServiceName: String?
    val proxySettings: ProxySettings
    val listenersConfig: ListenersConfig?
    val customData: T?
}

data class ServicesGroup<T>(
    override val communicationMode: CommunicationMode,
    override val serviceName: String = "",
    override val discoveryServiceName: String? = null,
    override val proxySettings: ProxySettings = ProxySettings(),
    override val listenersConfig: ListenersConfig? = null,
    override val customData: T?
) : Group<T>

data class AllServicesGroup<T>(
    override val communicationMode: CommunicationMode,
    override val serviceName: String = "",
    override val discoveryServiceName: String? = null,
    override val proxySettings: ProxySettings = ProxySettings(),
    override val listenersConfig: ListenersConfig? = null,
    override val customData: T?
) : Group<T>

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
    }
}
