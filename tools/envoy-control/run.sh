#!/usr/bin/env sh

START_ARGUMENTS=""
CONFIG_FILE=/var/tmp/config/application.yaml

if [ -f "$CONFIG_FILE" ]; then
    START_ARGUMENTS="--spring.config.location=file:$CONFIG_FILE "
fi

if [ ! -z "${ENVOY_CONTROL_PROPERTIES}" ]; then
    START_ARGUMENTS="$START_ARGUMENTS $ENVOY_CONTROL_PROPERTIES"
fi

echo "Launching Envoy-control with $START_ARGUMENTS"

/var/tmp/envoy-control-runner/bin/envoy-control-runner $START_ARGUMENTS