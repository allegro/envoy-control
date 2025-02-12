#!/bin/sh

set -eu

# Figuring out the IP of the docker host machine is a convoluted process. On Mac we pull that value from the special
# host.docker.internal hostname. Linux does not support that yet, so we have to use the routing table.
#
# See https://github.com/docker/for-linux/issues/264 to track host.docker.internal support on linux.
# Update 2025-02: don't use `getent hosts` as it started returning ipv6 address that don't work (at least on macos)
HOST_DOMAIN_IP="$(getent ahostsv4 host.docker.internal | head -n 1 | awk '{ print $1 }')"

if [[ ! -z "${HOST_DOMAIN_IP}" ]]; then
    printf "${HOST_DOMAIN_IP}"
else
    printf "$(ip route | awk '/default/ { print $3 }')"
fi
