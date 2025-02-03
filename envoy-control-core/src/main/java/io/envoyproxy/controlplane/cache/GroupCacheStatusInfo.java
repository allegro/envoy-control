package io.envoyproxy.controlplane.cache;

import io.envoyproxy.controlplane.cache.CacheStatusInfo;
import io.envoyproxy.controlplane.cache.StatusInfo;

import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayList;
import java.util.Collection;

/**
 * {@code GroupCacheStatusInfo} provides an implementation of {@link StatusInfo} for a group of {@link CacheStatusInfo}.
 */
@ThreadSafe
public class GroupCacheStatusInfo<T> implements StatusInfo<T> {
  private final Collection<StatusInfo<T>> statuses;

  public GroupCacheStatusInfo(Collection<StatusInfo<T>> statuses) {
    this.statuses = new ArrayList<>(statuses);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long lastWatchRequestTime() {
    return statuses.stream().mapToLong(StatusInfo::lastWatchRequestTime).max().orElse(0);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public T nodeGroup() {
    return statuses.stream().map(StatusInfo::nodeGroup).findFirst().orElse(null);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int numWatches() {
    return statuses.stream().mapToInt(StatusInfo::numWatches).sum();
  }

}
