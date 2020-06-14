#!/usr/bin/dumb-init /bin/sh
set -e

sed -i "s/{{.IngressListenerPort}}/${PORT:-10000}/g" /etc/envoy-front-proxy.yaml

/usr/local/bin/envoy --base-id 1 -c /etc/envoy.yaml &
/usr/local/bin/envoy -c /etc/envoy-front-proxy.yaml &

# start EC
START_ARGUMENTS=""
CONFIG_FILE=/etc/envoy-control/application.yaml
if [ -f "$CONFIG_FILE" ]; then
    START_ARGUMENTS="--spring.config.location=file:$CONFIG_FILE "
fi
if [ ! -z "${ENVOY_CONTROL_PROPERTIES}" ]; then
    START_ARGUMENTS="$START_ARGUMENTS $ENVOY_CONTROL_PROPERTIES"
fi
echo "Launching Envoy-control with $START_ARGUMENTS"
ls /var/tmp/ &
ls /var/tmp/envoy-control-runner &
ls /var/tmp/envoy-control-runner/bin/ &
/bin/envoy-control/envoy-control-runner/bin/envoy-control-runner $START_ARGUMENTS &

sh -c 'sleep 10; curl -X PUT --data "{
  "ID": "echo1",
  "Name": "echo1",
  "Tags": [ "primary" ],
  "Address": "127.0.0.1",
  "Port": 10010,
  "Check": {
    "DeregisterCriticalServiceAfter": "90m",
    "http": "http://127.0.0.1:10010",
    "Interval": "10s"
  }
}" -s localhost:8500/v1/agent/service/register' &

consul agent -server -ui -ui-content-path "/consul/ui" -dev -client 0.0.0.0
