package io.envoyproxy.controlplane.cache.v3;

import io.envoyproxy.controlplane.cache.NodeGroup;
import io.envoyproxy.controlplane.cache.v3.Snapshot;

public class SimpleCache<T> extends io.envoyproxy.controlplane.cache.SimpleCache<T, Snapshot> {
  public SimpleCache(NodeGroup<T> nodeGroup) {
    super(nodeGroup);
  }
}
