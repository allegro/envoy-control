package pl.allegro.tech.servicemesh.envoycontrol.server

import com.google.common.cache.CacheBuilder
import com.google.protobuf.Any
import com.google.protobuf.Message
import io.envoyproxy.controlplane.server.serializer.ProtoResourcesSerializer

internal class CachedProtoResourcesSerializer : ProtoResourcesSerializer {

    private val cache = CacheBuilder.newBuilder()
        .weakValues()
        .build<Collection<Message>, MutableCollection<Any>>()

    override fun serialize(resources: MutableCollection<out Message>): MutableCollection<Any> {
        return cache.get(resources) {
            resources.asSequence()
                .map { Any.pack(it) }
                .toMutableList()
        }
    }

    @Suppress("NotImplementedDeclaration")
    override fun serialize(resource: Message?): Any {
        throw NotImplementedError("Serializing single messages is not supported")
    }
}
