package io.envoyproxy.controlplane.cache;

import io.envoyproxy.controlplane.cache.CacheStatusInfo;
import io.envoyproxy.controlplane.cache.DeltaCacheStatusInfo;
import io.envoyproxy.controlplane.cache.Resources;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CacheStatusInfoAggregator<T> {
  private final ConcurrentMap<T, ConcurrentMap<io.envoyproxy.controlplane.cache.Resources.ResourceType, io.envoyproxy.controlplane.cache.CacheStatusInfo<T>>> statuses =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<T, ConcurrentMap<io.envoyproxy.controlplane.cache.Resources.ResourceType, io.envoyproxy.controlplane.cache.DeltaCacheStatusInfo<T>>> deltaStatuses =
      new ConcurrentHashMap<>();

  public Collection<T> groups() {
    return Stream.concat(statuses.keySet().stream(), deltaStatuses.keySet().stream()).collect(Collectors.toSet());
  }

  public void remove(T group) {
    statuses.remove(group);
    deltaStatuses.remove(group);
  }

  /**
   * Returns map of delta status infos for group identifier.
   *
   * @param group group identifier.
   */
  public Map<io.envoyproxy.controlplane.cache.Resources.ResourceType, io.envoyproxy.controlplane.cache.DeltaCacheStatusInfo<T>> getDeltaStatus(T group) {
    return deltaStatuses.getOrDefault(group, new ConcurrentHashMap<>());
  }

  /**
   * Returns map of status infos for group identifier.
   *
   * @param group group identifier.
   */
  public Map<io.envoyproxy.controlplane.cache.Resources.ResourceType, io.envoyproxy.controlplane.cache.CacheStatusInfo<T>> getStatus(T group) {
    return statuses.getOrDefault(group, new ConcurrentHashMap<>());
  }

  /**
   * Check if statuses for specific group have any watcher.
   *
   * @param group group identifier.
   * @return true if statuses for specific group have any watcher.
   */
  public boolean hasStatuses(T group) {
    Map<io.envoyproxy.controlplane.cache.Resources.ResourceType, io.envoyproxy.controlplane.cache.CacheStatusInfo<T>> status = getStatus(group);
    Map<io.envoyproxy.controlplane.cache.Resources.ResourceType, io.envoyproxy.controlplane.cache.DeltaCacheStatusInfo<T>> deltaStatus = getDeltaStatus(group);
    return status.values().stream().mapToLong(io.envoyproxy.controlplane.cache.CacheStatusInfo::numWatches).sum()
        + deltaStatus.values().stream().mapToLong(io.envoyproxy.controlplane.cache.DeltaCacheStatusInfo::numWatches).sum() > 0;
  }

  /**
   * Returns delta status info for group identifier and creates new one if it doesn't exist.
   *
   * @param group        group identifier.
   * @param resourceType resource type.
   */
  public io.envoyproxy.controlplane.cache.DeltaCacheStatusInfo<T> getOrAddDeltaStatusInfo(T group, io.envoyproxy.controlplane.cache.Resources.ResourceType resourceType) {
    return deltaStatuses.computeIfAbsent(group, g -> new ConcurrentHashMap<>())
        .computeIfAbsent(resourceType, s -> new DeltaCacheStatusInfo<>(group));
  }

  /**
   * Returns status info for group identifier and creates new one if it doesn't exist.
   *
   * @param group        group identifier.
   * @param resourceType resource type.
   */
  public io.envoyproxy.controlplane.cache.CacheStatusInfo<T> getOrAddStatusInfo(T group, Resources.ResourceType resourceType) {
    return statuses.computeIfAbsent(group, g -> new ConcurrentHashMap<>())
        .computeIfAbsent(resourceType, s -> new CacheStatusInfo<>(group));
  }
}
