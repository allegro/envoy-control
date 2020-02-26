package pl.allegro.tech.servicemesh.envoycontrol.groups

sealed class Group {
    abstract val communicationMode: CommunicationMode
    abstract val serviceName: String
    abstract val proxySettings: ProxySettings
    abstract val listenersConfig: ListenersConfig?
    open fun isGlobalGroup() = false
}

data class ServicesGroup(
    override val communicationMode: CommunicationMode,
    override val serviceName: String = "",
    override val proxySettings: ProxySettings = ProxySettings(),
    override val listenersConfig: ListenersConfig? = null
) : Group()

data class AllServicesGroup(
    override val communicationMode: CommunicationMode,
    override val serviceName: String = "",
    override val proxySettings: ProxySettings = ProxySettings(),
    override val listenersConfig: ListenersConfig? = null
) : Group() {
    /**
     * Global group is a base group for all other groups. First we generate the global groups from a snapshot,
     * then generate all other groups using data from global groups.
     */
    override fun isGlobalGroup() = serviceName == "" && proxySettings.isEmpty() && listenersConfig == null
}

data class ListenersConfig(
    val ingressHost: String,
    val ingressPort: Int,
    val egressHost: String,
    val egressPort: Int,
    val useRemoteAddress: Boolean = defaultUseRemoteAddress,
    val accessLogEnabled: Boolean = defaultAccessLogEnabled,
    val enableLuaScript: Boolean = defaultEnableLuaScript,
    val accessLogPath: String = defaultAccessLogPath,
    val resourcesDir: String = defaultResourcesDir,
    val addUpstreamExternalAddressHeader: Boolean = defaultAddUpstreamExternalAddressHeader
) {
    companion object {
        const val defaultAccessLogPath = "/dev/stdout"
        const val defaultUseRemoteAddress = false
        const val defaultAccessLogEnabled = false
        const val defaultEnableLuaScript = false
        const val defaultAddUpstreamExternalAddressHeader = false
        const val defaultResourcesDir = "envoy"
    }
}
