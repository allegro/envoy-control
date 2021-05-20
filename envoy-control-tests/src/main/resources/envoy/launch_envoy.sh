#!/bin/sh

set -eu

echo server=`cat /etc/resolv.conf | grep "^nameserver" | sed -n -e 's/^nameserver //p'` >> /etc/dnsmasq.conf
cat /etc/resolv.conf | sed 's/^nameserver.*/nameserver 127.0.0.1/' > /tmp/resolv.conf
cat /tmp/resolv.conf > /etc/resolv.conf
/etc/init.d/dnsmasq restart

HOST_IP=$(bash /usr/local/bin/host_ip.sh)
HOST_PORT=$1
HOST2_PORT=$2

CONFIG=$(cat $3)
CONFIG_DIR=$(mktemp -d)
CONFIG_FILE="$CONFIG_DIR/envoy.yaml"

LOCAL_SERVICE_IP="$4"
TRUSTED_CA="$5"
CERTIFICATE_CHAIN="$6"
PRIVATE_KEY="$7"
SERVICE_NAME="$8"

echo "debug: " "$@"

echo "${CONFIG}" | sed \
 -e "s;HOST_IP;${HOST_IP};g" \
 -e "s;HOST_PORT;${HOST_PORT};g" \
 -e "s;HOST2_PORT;${HOST2_PORT};g" \
 -e "s;LOCAL_SERVICE_IP;${LOCAL_SERVICE_IP};g" \
 -e "s;TRUSTED_CA;${TRUSTED_CA};g" \
 -e "s;CERTIFICATE_CHAIN;${CERTIFICATE_CHAIN};g" \
 -e "s;PRIVATE_KEY;${PRIVATE_KEY};g" \
 -e "s;SERVICE_NAME;${SERVICE_NAME};g" \
 > "${CONFIG_FILE}"
cat "${CONFIG_FILE}"

shift 8
# todo: remove filter:trace level after development
/usr/local/bin/envoy --drain-time-s 1 -c "${CONFIG_FILE}" "$@" --component-log-level rbac:trace

rm -rf "${CONFIG_DIR}"
