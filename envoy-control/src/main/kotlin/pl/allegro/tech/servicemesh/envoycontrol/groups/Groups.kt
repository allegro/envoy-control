package pl.allegro.tech.servicemesh.envoycontrol.groups

sealed class Group {
    abstract val ads: Boolean
    abstract val serviceName: String
    abstract val proxySettings: ProxySettings

    open fun isGlobalGroup() = false
}

data class ServicesGroup(
    override val ads: Boolean,
    override val serviceName: String = "",
    override val proxySettings: ProxySettings = ProxySettings()
) : Group()

data class AllServicesGroup(
    override val ads: Boolean,
    override val serviceName: String = "",
    override val proxySettings: ProxySettings = ProxySettings()
) : Group() {
    /**
     * Global group is a base group for all other groups. First we generate the global groups from a snapshot,
     * then generate all other groups using data from global groups.
     */
    override fun isGlobalGroup() = serviceName == "" && proxySettings.isEmpty()
}
