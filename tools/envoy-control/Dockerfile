FROM gradle:8.3-jdk17 AS builder
COPY --chown=gradle:gradle settings.gradle build.gradle gradle.properties /home/gradle/src/
COPY --chown=gradle:gradle envoy-control-core/ /home/gradle/src/envoy-control-core/
COPY --chown=gradle:gradle envoy-control-runner/ /home/gradle/src/envoy-control-runner/
COPY --chown=gradle:gradle envoy-control-services/ /home/gradle/src/envoy-control-services/
COPY --chown=gradle:gradle envoy-control-source-consul/ /home/gradle/src/envoy-control-source-consul/

WORKDIR /home/gradle/src
RUN gradle :envoy-control-runner:assemble --parallel --no-daemon

FROM eclipse-temurin:17-jre

RUN mkdir /tmp/envoy-control-dist /tmp/envoy-control /bin/envoy-control /etc/envoy-control /var/tmp/config
COPY --from=builder /home/gradle/src/envoy-control-runner/build/distributions/ /tmp/envoy-control-dist
COPY ./envoy-control-runner/src/main/resources/application-docker.yaml /etc/envoy-control/application.yaml
RUN tar -xf /tmp/envoy-control-dist/envoy-control-runner-0.1.0*.tar -C /tmp/envoy-control \
    && mv /tmp/envoy-control/envoy-control-runner*/ /bin/envoy-control/envoy-control-runner

COPY tools/envoy-control/run.sh /usr/local/bin/run.sh
VOLUME /var/tmp/config
WORKDIR /usr/local/bin/

# APP_PORT: 8080
# XDS_PORT: 50000
CMD ["sh", "run.sh"]
