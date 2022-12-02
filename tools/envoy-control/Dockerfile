FROM adoptopenjdk/openjdk11:alpine-jre

RUN mkdir /tmp/envoy-control-dist /tmp/envoy-control /bin/envoy-control /etc/envoy-control /var/tmp/config
COPY tools/envoy-control/run.sh /usr/local/bin/run.sh
VOLUME /var/tmp/config
COPY ../../envoy-control-runner/build/distributions /tmp/envoy-control-dist
COPY ./envoy-control-runner/src/main/resources/application-docker.yaml /etc/envoy-control/application.yaml
RUN tar -xf /tmp/envoy-control-dist/envoy-control-runner-*.tar -C /tmp/envoy-control \
    && mv /tmp/envoy-control/envoy-control-runner*/ /bin/envoy-control/envoy-control-runner

WORKDIR /usr/local/bin/

# APP_PORT: 8080
# XDS_PORT: 50000
CMD ["sh", "run.sh"]
