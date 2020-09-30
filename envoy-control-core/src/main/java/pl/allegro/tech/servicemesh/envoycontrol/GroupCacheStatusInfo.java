package pl.allegro.tech.servicemesh.envoycontrol;

import io.envoyproxy.controlplane.cache.CacheStatusInfo;
import io.envoyproxy.controlplane.cache.StatusInfo;
import java.util.ArrayList;
import java.util.Collection;
import javax.annotation.concurrent.ThreadSafe;

/**
 * {@code GroupCacheStatusInfo} provides an implementation of {@link StatusInfo} for a group of {@link CacheStatusInfo}.
 * This class is copy of {@link io.envoyproxy.controlplane.cache.GroupCacheStatusInfo}
 */
@ThreadSafe
class GroupCacheStatusInfo<T> implements StatusInfo<T> {
    private final Collection<CacheStatusInfo<T>> statuses;

    public GroupCacheStatusInfo(Collection<CacheStatusInfo<T>> statuses) {
        this.statuses = new ArrayList<>(statuses);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long lastWatchRequestTime() {
        return statuses.stream().mapToLong(CacheStatusInfo::lastWatchRequestTime).max().orElse(0);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T nodeGroup() {
        return statuses.stream().map(CacheStatusInfo::nodeGroup).findFirst().orElse(null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int numWatches() {
        return statuses.stream().mapToInt(CacheStatusInfo::numWatches).sum();
    }
}