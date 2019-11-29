package pl.allegro.tech.servicemesh.envoycontrol.groups

sealed class Group {
    abstract val ads: Boolean
    abstract val serviceName: String
    abstract val proxySettings: ProxySettings
    abstract val ingressHost: String
    abstract val ingressPort: Int
    abstract val egressHost: String
    abstract val egressPort: Int
    abstract val useRemoteAddress: Boolean
    abstract val accessLogEnabled: Boolean
    abstract val accessLogPath: String
    abstract val luaScriptDir: String
    open fun isGlobalGroup() = false
}

data class ServicesGroup(
        override val ads: Boolean,
        override val serviceName: String = "",
        override val proxySettings: ProxySettings = ProxySettings(),
        override val ingressHost: String = "0.0.0.0",
        override val ingressPort: Int = -1,
        override val egressHost: String = "0.0.0.0",
        override val egressPort: Int = -1,
        override val useRemoteAddress: Boolean = true,
        override val accessLogEnabled: Boolean = false,
        override val accessLogPath: String = "/dev/stdout",
        override val luaScriptDir: String = "envoy/lua"
) : Group()

data class AllServicesGroup(
        override val ads: Boolean,
        override val serviceName: String = "",
        override val proxySettings: ProxySettings = ProxySettings(),
        override val ingressHost: String = "0.0.0.0",
        override val ingressPort: Int = -1,
        override val egressHost: String = "0.0.0.0",
        override val egressPort: Int = -1,
        override val useRemoteAddress: Boolean = true,
        override val accessLogEnabled: Boolean = false,
        override val accessLogPath: String = "/dev/stdout",
        override val luaScriptDir: String = "envoy/lua"
) : Group() {
    /**
     * Global group is a base group for all other groups. First we generate the global groups from a snapshot,
     * then generate all other groups using data from global groups.
     */
    override fun isGlobalGroup() = serviceName == "" && proxySettings.isEmpty()
}
