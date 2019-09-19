# Quickstart

## Requirements
* Java 8+
* Docker & Docker Compose

## Setting up an environment

### Dependencies
At this moment, Envoy Control requires Consul to run.
In the future, there will be support for other discovery service systems.
Additionally, to test the system it's convenient to have Envoy that connects to
Envoy Control and a service registered in Consul that will be propagated to Envoy.

You can run all dependencies with docker-compose
```
git clone https://github.com/allegro/envoy-control.git
cd tools
docker-compose up
```

To check the environment, go to Consul UI: [http://localhost:18500](http://localhost:18500) and see whether there is
a registered _http-echo_ service.

Additionally, you can check Envoy Admin at [http://localhost:9999](http://localhost:9999)

_http-echo_ service is not available outside of docker's network.

Envoy listener is available on [http://localhost:31000](http://localhost:31000) but the _http-echo_ service location
is not yet propagated to Envoy.

### Run Envoy Control
Run Envoy Control `./gradlew run` in a cloned catalog.

## Test the system
After a while, Envoy Control should read the state of Consul and propagate it to Envoy.

You can check it by sending curl request to _http-echo_ through a proxy.
The request will be sent to Envoy which will be redirected to the _http-echo_ service.
```
curl -x localhost:31000 http://http-echo/test -v
```

Instead of using the proxy feature you can also send a request to Envoy with a Host header. 
```
curl -H "Host: http-echo" http://localhost:31000/status/info
```