package pl.allegro.tech.servicemesh.envoycontrol.config.sharing

import org.testcontainers.containers.GenericContainer
import java.util.LinkedList
import java.util.Queue

class ContainerPool<OWNER, CONTAINER : GenericContainer<*>>(private val containerFactory: () -> CONTAINER) {

    private val freeContainers: Queue<CONTAINER> = LinkedList()
    private val usedContainers = mutableMapOf<OWNER, CONTAINER>()

    fun acquire(owner: OWNER): CONTAINER {
        val container = freeContainers.poll() ?: containerFactory()
        container.start()

        usedContainers[owner] = container
        return container
    }

    fun release(owner: OWNER) {
        val container = usedContainers.remove(owner) ?: throw ContainerNotFound(owner.toString())
        freeContainers.add(container)
    }

    class ContainerNotFound(owner: String) : RuntimeException("container owned by $owner not found")
}
