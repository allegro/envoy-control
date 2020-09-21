package pl.allegro.tech.servicemesh.envoycontrol.snapshot.resource.listeners.filters

import io.envoyproxy.envoy.extensions.filters.http.header_to_metadata.v3.Config

class ServiceTagFilter {
    companion object {
        fun serviceTagFilterRules(
            header: String,
            tag: String
        ): List<Config.Rule> {
            return listOf(Config.Rule.newBuilder()
                    .setHeader(header)
                    .setRemove(false)
                    .setOnHeaderPresent(
                            Config.KeyValuePair.newBuilder()
                                    .setKey(tag)
                                    .setMetadataNamespace("envoy.lb")
                                    .setType(Config.ValueType.STRING)
                                    .build()
                    )
                    .build())
        }
    }
}
