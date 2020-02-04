package pl.allegro.tech.servicemesh.envoycontrol;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.protobuf.Message;
import io.envoyproxy.controlplane.cache.CacheStatusInfo;
import io.envoyproxy.controlplane.cache.NodeGroup;
import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.controlplane.cache.Response;
import io.envoyproxy.controlplane.cache.Snapshot;
import io.envoyproxy.controlplane.cache.SnapshotCache;
import io.envoyproxy.controlplane.cache.StatusInfo;
import io.envoyproxy.controlplane.cache.Watch;
import io.envoyproxy.controlplane.cache.WatchCancelledException;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.annotation.concurrent.GuardedBy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is copy of {@link io.envoyproxy.controlplane.cache.SimpleCache}
 */
public class SimpleCache<T> implements SnapshotCache<T> {

    private static final Logger LOGGER = LoggerFactory.getLogger(SimpleCache.class);

    private final NodeGroup<T> groups;
    private final boolean shouldSendMissingEndpoints;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Lock readLock = lock.readLock();
    private final Lock writeLock = lock.writeLock();

    @GuardedBy("lock")
    private final Map<T, Snapshot> snapshots = new HashMap<>();
    private final ConcurrentMap<T, CacheStatusInfo<T>> statuses = new ConcurrentHashMap<>();

    private AtomicLong watchCount = new AtomicLong();

    /**
     * Constructs a simple cache.
     *
     * @param groups maps an envoy host to a node group
     * @param shouldSendMissingEndpoints if set to true it will respond with empty endpoints if there is no in snapshot
     */
    public SimpleCache(NodeGroup<T> groups, boolean shouldSendMissingEndpoints) {
        this.groups = groups;
        this.shouldSendMissingEndpoints = shouldSendMissingEndpoints;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean clearSnapshot(T group) {
        // we take a writeLock to prevent watches from being created
        writeLock.lock();
        try {
            CacheStatusInfo<T> status = statuses.get(group);

            // If we don't know about this group, do nothing.
            if (status != null && status.numWatches() > 0) {
                LOGGER.warn("tried to clear snapshot for group with existing watches, group={}", group);

                return false;
            }

            statuses.remove(group);
            snapshots.remove(group);

            return true;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Watch createWatch(
            boolean ads,
            DiscoveryRequest request,
            Set<String> knownResourceNames,
            Consumer<Response> responseConsumer) {

        T group = groups.hash(request.getNode());
        // even though we're modifying, we take a readLock to allow multiple watches to be created in parallel since it
        // doesn't conflict
        readLock.lock();
        try {
            CacheStatusInfo<T> status = statuses.computeIfAbsent(group, g -> new CacheStatusInfo<>(group));
            status.setLastWatchRequestTime(System.currentTimeMillis());

            Snapshot snapshot = snapshots.get(group);
            String version = snapshot == null ? "" : snapshot.version(request.getTypeUrl(), request.getResourceNamesList());

            Watch watch = new Watch(ads, request, responseConsumer);

            if (snapshot != null) {
                Set<String> requestedResources = ImmutableSet.copyOf(request.getResourceNamesList());

                // If the request is asking for resources we haven't sent to the proxy yet, see if we have additional resources.
                if (!knownResourceNames.equals(requestedResources)) {
                    Sets.SetView<String> newResourceHints = Sets.difference(requestedResources, knownResourceNames);

                    // If any of the newly requested resources are in the snapshot respond immediately. If not we'll fall back to
                    // version comparisons.
                    if (snapshot.resources(request.getTypeUrl())
                            .keySet()
                            .stream()
                            .anyMatch(newResourceHints::contains)) {
                        respond(watch, snapshot, group);

                        return watch;
                    }
                }
            }

            // If the requested version is up-to-date or missing a response, leave an open watch.
            if (snapshot == null || request.getVersionInfo().equals(version)) {
                long watchId = watchCount.incrementAndGet();

                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("open watch {} for {}[{}] from node {} for version {}",
                            watchId,
                            request.getTypeUrl(),
                            String.join(", ", request.getResourceNamesList()),
                            group,
                            request.getVersionInfo());
                }

                status.setWatch(watchId, watch);

                watch.setStop(() -> status.removeWatch(watchId));

                return watch;
            }

            // Otherwise, the watch may be responded immediately
            boolean responded = respond(watch, snapshot, group);

            if (!responded) {
                long watchId = watchCount.incrementAndGet();

                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("did not respond immediately, leaving open watch {} for {}[{}] from node {} for version {}",
                            watchId,
                            request.getTypeUrl(),
                            String.join(", ", request.getResourceNamesList()),
                            group,
                            request.getVersionInfo());
                }

                status.setWatch(watchId, watch);

                watch.setStop(() -> status.removeWatch(watchId));
            }

            return watch;
        } finally {
            readLock.unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Snapshot getSnapshot(T group) {
        readLock.lock();

        try {
            return snapshots.get(group);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<T> groups() {
        return ImmutableSet.copyOf(statuses.keySet());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void setSnapshot(T group, Snapshot snapshot) {
        // we take a writeLock to prevent watches from being created while we update the snapshot
        CacheStatusInfo<T> status;
        writeLock.lock();
        try {
            // Update the existing snapshot entry.
            snapshots.put(group, snapshot);
            status = statuses.get(group);
        } finally {
            writeLock.unlock();
        }

        if (status == null) {
            return;
        }

        for (String typeUrl : Resources.TYPE_URLS) {
            status.watchesRemoveIf((id, watch) -> {
                if (!watch.request().getTypeUrl().equals(typeUrl)) {
                    return false;
                }
                String version = snapshot.version(watch.request().getTypeUrl(), watch.request().getResourceNamesList());

                if (!watch.request().getVersionInfo().equals(version)) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("responding to open watch {}[{}] with new version {}",
                                id,
                                String.join(", ", watch.request().getResourceNamesList()),
                                version);
                    }

                    respond(watch, snapshot, group);

                    // Discard the watch. A new watch will be created for future snapshots once envoy ACKs the response.
                    return true;
                }

                // Do not discard the watch. The request version is the same as the snapshot version, so we wait to respond.
                return false;
            });
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StatusInfo statusInfo(T group) {
        readLock.lock();

        try {
            return statuses.get(group);
        } finally {
            readLock.unlock();
        }
    }

    private Response createResponse(DiscoveryRequest request, Map<String, ? extends Message> resources, String version) {
        Collection<? extends Message> filtered = request.getResourceNamesList().isEmpty()
                ? resources.values()
                : request.getResourceNamesList().stream()
                .map(resources::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return Response.create(request, filtered, version);
    }

    private boolean respond(Watch watch, Snapshot snapshot, T group) {
        Map<String, ? extends Message> snapshotResources = snapshot.resources(watch.request().getTypeUrl());
        Map<String, ClusterLoadAssignment> snapshotForMissingResources = Collections.emptyMap();

        if (!watch.request().getResourceNamesList().isEmpty() && watch.ads()) {
            Collection<String> missingNames = watch.request().getResourceNamesList().stream()
                    .filter(name -> !snapshotResources.containsKey(name))
                    .collect(Collectors.toList());

            // We are not removing Clusters just making them no instances so it might happen that Envoy asks for instance
            // which we don't have in cache. In that case we want to send empty endpoint to Envoy.
            if (shouldSendMissingEndpoints
                    && watch.request().getTypeUrl().equals(Resources.ENDPOINT_TYPE_URL)
                    && !missingNames.isEmpty()) {
                LOGGER.info("adding missing resources [{}] to response for {} in ADS mode from node {} at version {}",
                        String.join(", ", missingNames),
                        watch.request().getTypeUrl(),
                        group,
                        snapshot.version(watch.request().getTypeUrl(), watch.request().getResourceNamesList())
                );
                snapshotForMissingResources = new HashMap<>(missingNames.size());
                for (String missingName : missingNames) {
                    snapshotForMissingResources.put(
                            missingName,
                            ClusterLoadAssignment.newBuilder().setClusterName(missingName).build()
                    );
                }
            } else if (!missingNames.isEmpty()) {
                LOGGER.info(
                        "not responding in ADS mode for {} from node {} at version {} for request [{}] since [{}] not in snapshot",
                        watch.request().getTypeUrl(),
                        group,
                        snapshot.version(watch.request().getTypeUrl(), watch.request().getResourceNamesList()),
                        String.join(", ", watch.request().getResourceNamesList()),
                        String.join(", ", missingNames));

                return false;
            }
        }

        String version = snapshot.version(watch.request().getTypeUrl(), watch.request().getResourceNamesList());

        LOGGER.debug("responding for {} from node {} at version {} with version {}",
                watch.request().getTypeUrl(),
                group,
                watch.request().getVersionInfo(),
                version);
        Response response;
        if (!snapshotForMissingResources.isEmpty()) {
            snapshotForMissingResources.putAll((Map<? extends String, ? extends ClusterLoadAssignment>) snapshotResources);
            response = createResponse(
                    watch.request(),
                    snapshotForMissingResources,
                    version);
        } else {
            response = createResponse(
                    watch.request(),
                    snapshotResources,
                    version);
        }

        try {
            watch.respond(response);
            return true;
        } catch (WatchCancelledException e) {
            LOGGER.error(
                    "failed to respond for {} from node {} at version {} with version {} because watch was already cancelled",
                    watch.request().getTypeUrl(),
                    group,
                    watch.request().getVersionInfo(),
                    version);
        }

        return false;
    }
}