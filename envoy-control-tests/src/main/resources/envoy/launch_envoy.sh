#!/bin/sh

set -eu

HOST_IP=$(sh /usr/local/bin/host_ip.sh)
HOST_PORT=$1
HOST2_PORT=$2

CONFIG=$(cat $3)
CONFIG_DIR=$(mktemp -d)
CONFIG_FILE="$CONFIG_DIR/envoy.yaml"

LOCAL_SERVICE_IP="$4"
EXTRA_DIR="$5"

echo "${CONFIG}" | sed \
 -e "s/HOST_IP/${HOST_IP}/g" \
 -e "s/HOST_PORT/${HOST_PORT}/g" \
 -e "s/HOST2_PORT/${HOST2_PORT}/g" \
 -e "s/LOCAL_SERVICE_IP/${LOCAL_SERVICE_IP}/g" \
 -e "s|EXTRA_DIR|${EXTRA_DIR}|g" \
 > "${CONFIG_FILE}"
cat "${CONFIG_FILE}"

shift 5
/usr/local/bin/envoy --drain-time-s 1 -c "${CONFIG_FILE}" "$@"

rm -rf "${CONFIG_DIR}"
