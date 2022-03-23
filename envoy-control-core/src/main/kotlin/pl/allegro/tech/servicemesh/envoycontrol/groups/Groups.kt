package pl.allegro.tech.servicemesh.envoycontrol.groups

sealed class Group {
    abstract val communicationMode: CommunicationMode
    abstract val serviceName: String
    abstract val discoveryServiceName: String?
    abstract val proxySettings: ProxySettings
    abstract val listenersConfig: ListenersConfig?
}

data class ServicesGroup(
    override val communicationMode: CommunicationMode,
    override val serviceName: String = "",
    override val discoveryServiceName: String? = null,
    override val proxySettings: ProxySettings = ProxySettings(),
    override val listenersConfig: ListenersConfig? = null
) : Group()

data class AllServicesGroup(
    override val communicationMode: CommunicationMode,
    override val serviceName: String = "",
    override val discoveryServiceName: String? = null,
    override val proxySettings: ProxySettings = ProxySettings(),
    override val listenersConfig: ListenersConfig? = null
) : Group()

@SuppressWarnings("ComplexMethod")
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

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ListenersConfig

        if (ingressHost != other.ingressHost) return false
        if (ingressPort != other.ingressPort) return false
        if (egressHost != other.egressHost) return false
        if (egressPort != other.egressPort) return false
        if (useRemoteAddress != other.useRemoteAddress) return false
        if (generateRequestId != other.generateRequestId) return false
        if (preserveExternalRequestId != other.preserveExternalRequestId) return false
        if (accessLogEnabled != other.accessLogEnabled) return false
        if (enableLuaScript != other.enableLuaScript) return false
        if (accessLogPath != other.accessLogPath) return false
        if (addUpstreamExternalAddressHeader != other.addUpstreamExternalAddressHeader) return false
        if (accessLogFilterSettings != other.accessLogFilterSettings) return false
        if (hasStaticSecretsDefined != other.hasStaticSecretsDefined) return false
        if (useTransparentProxy != other.useTransparentProxy) return false

        return true
    }

    override fun hashCode(): Int {
        var result = ingressHost.hashCode()
        result = 31 * result + ingressPort
        result = 31 * result + egressHost.hashCode()
        result = 31 * result + egressPort
        result = 31 * result + useRemoteAddress.hashCode()
        result = 31 * result + generateRequestId.hashCode()
        result = 31 * result + preserveExternalRequestId.hashCode()
        result = 31 * result + accessLogEnabled.hashCode()
        result = 31 * result + enableLuaScript.hashCode()
        result = 31 * result + accessLogPath.hashCode()
        result = 31 * result + addUpstreamExternalAddressHeader.hashCode()
        result = 31 * result + accessLogFilterSettings.hashCode()
        result = 31 * result + hasStaticSecretsDefined.hashCode()
        result = 31 * result + useTransparentProxy.hashCode()
        return result
    }
}
