# Service Transformers

Service Transformers are a way to filter out and modify services received from the discovery service before sending it to Envoy.
Transformers are only applied to the local state of discovery. Remote state of discovery is already transformed by other
instance of Envoy Control.

## Available Transformers

There are couple of available transformers

### Empty Address Filter

Exclude instances that have an empty address.

### IP Address Filter

Exclude instances that contain hostname. Envoy does not support endpoints sent via EDS that has a hostname.

### Regex Service Instances Filter

Exclude services with a given name using defined regex.

## Custom Transformers

To provide custom Transformer implement `ServiceInstancesTransformer` interface. With Envoy Control Runner, every
transformer available in Spring Context will be picked up and used. With pure Envoy Control, you have to provide
a list of transformers to `LocalClusterStateChanges` class.

## Configuration

You can see a list of settings [here](../configuration.md#service-filters)

