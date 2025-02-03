package io.envoyproxy.controlplane.cache;

import io.envoyproxy.controlplane.cache.Snapshot;

/**
 * {@code SnapshotConsistencyException} indicates that resource references in a {@link Snapshot} are not consistent,
 * i.e. a resource references another resource that does not exist in the snapshot.
 */
public class SnapshotConsistencyException extends Exception {

  public SnapshotConsistencyException(String message) {
    super(message);
  }
}
