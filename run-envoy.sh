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

consul agent -server -ui -ui-content-path "/consul/ui" -dev -client 0.0.0.0
