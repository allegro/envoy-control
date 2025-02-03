package io.envoyproxy.controlplane.cache;

import io.envoyproxy.controlplane.cache.Cache;
import io.envoyproxy.controlplane.cache.MutableStatusInfo;
import io.envoyproxy.controlplane.cache.StatusInfo;
import io.envoyproxy.controlplane.cache.Watch;

import javax.annotation.concurrent.ThreadSafe;

/**
 * {@code CacheStatusInfo} provides a default implementation of {@link StatusInfo} for use in {@link Cache}
 * implementations.
 */
@ThreadSafe
public class CacheStatusInfo<T> extends MutableStatusInfo<T, Watch> {
  public CacheStatusInfo(T nodeGroup) {
    super(nodeGroup);
  }
}
