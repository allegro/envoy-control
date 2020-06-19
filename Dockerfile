FROM alpine:3.9 as consul

# This is the release of Consul to pull in.
ENV CONSUL_VERSION=1.7.4

# This is the location of the releases.
ENV HASHICORP_RELEASES=https://releases.hashicorp.com

# Set up certificates, base tools, and Consul.
# libc6-compat is needed to symlink the shared libraries for ARM builds
RUN set -eux && \
    apk add --no-cache ca-certificates curl dumb-init gnupg libcap openssl su-exec iputils jq libc6-compat && \
    gpg --keyserver keyserver.ubuntu.com --recv-keys 91A6E7F85D05C65630BEF18951852D87348FFC4C && \
    mkdir -p /tmp/build && \
    cd /tmp/build && \
    apkArch="$(apk --print-arch)" && \
    case "${apkArch}" in \
        aarch64) consulArch='arm64' ;; \
        armhf) consulArch='armhfv6' ;; \
        x86) consulArch='386' ;; \
        x86_64) consulArch='amd64' ;; \
        *) echo >&2 "error: unsupported architecture: ${apkArch} (see ${HASHICORP_RELEASES}/consul/${CONSUL_VERSION}/)" && exit 1 ;; \
    esac && \
    wget ${HASHICORP_RELEASES}/consul/${CONSUL_VERSION}/consul_${CONSUL_VERSION}_linux_${consulArch}.zip && \
    wget ${HASHICORP_RELEASES}/consul/${CONSUL_VERSION}/consul_${CONSUL_VERSION}_SHA256SUMS && \
    wget ${HASHICORP_RELEASES}/consul/${CONSUL_VERSION}/consul_${CONSUL_VERSION}_SHA256SUMS.sig && \
    gpg --batch --verify consul_${CONSUL_VERSION}_SHA256SUMS.sig consul_${CONSUL_VERSION}_SHA256SUMS && \
    grep consul_${CONSUL_VERSION}_linux_${consulArch}.zip consul_${CONSUL_VERSION}_SHA256SUMS | sha256sum -c && \
    unzip -d /bin consul_${CONSUL_VERSION}_linux_${consulArch}.zip && \
    cd /tmp && \
    rm -rf /tmp/build && \
    gpgconf --kill all && \
    apk del gnupg openssl && \
    rm -rf /root/.gnupg && \
# tiny smoke test to ensure the binary we downloaded runs
    consul version

# The /consul/data dir is used by Consul to store state. The agent will be started
# with /consul/config as the configuration directory so you can add additional
# config files in that location.
RUN mkdir -p /consul/data && \
    mkdir -p /consul/config

# set up nsswitch.conf for Go's "netgo" implementation which is used by Consul,
# otherwise DNS supercedes the container's hosts file, which we don't want.
RUN test -e /etc/nsswitch.conf || echo 'hosts: files dns' > /etc/nsswitch.conf

# Expose the consul data directory as a volume since there's mutable state in there.
VOLUME /consul/data

# Server RPC is used for communication between Consul clients and servers for internal
# request forwarding.
EXPOSE 8300

# Serf LAN and WAN (WAN is used only by Consul servers) are used for gossip between
# Consul agents. LAN is within the datacenter and WAN is between just the Consul
# servers in all datacenters.
EXPOSE 8301 8301/udp 8302 8302/udp

# HTTP and DNS (both TCP and UDP) are the primary interfaces that applications
# use to interact with Consul.
EXPOSE 8500 8600 8600/udp

# Consul doesn't need root privileges so we run it as the consul user from the
# entry point script. The entry point script also uses dumb-init as the top-level
# process to reap any zombie processes created by Consul sub-processes.

FROM gradle:5.6.2-jdk11 AS build
COPY --chown=gradle:gradle settings.gradle build.gradle /home/gradle/src/
COPY --chown=gradle:gradle envoy-control-core/ /home/gradle/src/envoy-control-core/
COPY --chown=gradle:gradle envoy-control-runner/ /home/gradle/src/envoy-control-runner/
COPY --chown=gradle:gradle envoy-control-services/ /home/gradle/src/envoy-control-services/
COPY --chown=gradle:gradle envoy-control-source-consul/ /home/gradle/src/envoy-control-source-consul/
WORKDIR /home/gradle/src
RUN gradle :envoy-control-runner:assemble --parallel --no-daemon

FROM envoyproxy/envoy-alpine-dev:6c2137468c25d167dbbe4719b0ecaf343bfb4233 as envoy
COPY heroku/envoy.yaml /etc/envoy.yaml
COPY heroku/envoy-front-proxy.yaml /etc/envoy-front-proxy.yaml
COPY --from=consul /bin/consul /bin/consul

RUN apk --no-cache add openjdk11 curl --repository=http://dl-cdn.alpinelinux.org/alpine/edge/community
RUN mkdir /tmp/envoy-control-dist /tmp/envoy-control /bin/envoy-control /etc/envoy-control
COPY --from=build /home/gradle/src/envoy-control-runner/build/distributions/ /tmp/envoy-control-dist
COPY ./envoy-control-runner/src/main/resources/application.yaml /etc/envoy-control/
COPY heroku/register-echo1.json /etc/envoy-control/
RUN tar -xf /tmp/envoy-control-dist/envoy-control-runner*.tar -C /tmp/envoy-control
RUN mv /tmp/envoy-control/envoy-control-runner*/ /bin/envoy-control/envoy-control-runner
# APP_PORT: 8080
# XDS_PORT: 50000

COPY heroku/run-envoy.sh /run-envoy.sh
CMD ["sh", "/run-envoy.sh"]
