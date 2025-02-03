package io.envoyproxy.controlplane.cache;

import io.envoyproxy.controlplane.cache.DeltaWatch;
import io.envoyproxy.controlplane.cache.MutableStatusInfo;

public class DeltaCacheStatusInfo<T> extends MutableStatusInfo<T, DeltaWatch> {

  public DeltaCacheStatusInfo(T nodeGroup) {
    super(nodeGroup);
  }
}
