FROM adoptopenjdk/openjdk11:alpine-jre

COPY tools/envoy-control/run.sh /usr/local/bin/run.sh
ADD envoy-control-runner/build/distributions/envoy-control-runner-*.tar /var/tmp/

RUN mv /var/tmp/envoy-control-runner* /var/tmp/envoy-control-runner
RUN mkdir /var/tmp/config

VOLUME /var/tmp/config

WORKDIR /usr/local/bin/

# APP_PORT: 8080
# XDS_PORT: 50000
EXPOSE 8080 50000

CMD ["sh", "run.sh"]