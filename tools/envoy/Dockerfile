FROM envoyproxy/envoy:v1.21.0

ENV ENVOY_EGRESS_LISTENER_PORT=31000
ENV ENVOY_INGRESS_LISTENER_PORT=31001
ENV ENVOY_ADMIN_PORT=9999
ENV ENVOY_XDS_PORT=50000
ENV ENVOY_XDS_HOST=host.docker.internal
ENV SERVICE_NAME=abc
ENV SERVICE_HOST=abc
ENV SERVICE_PORT=123

RUN apt update && apt install curl -y

ADD envoy-template.yaml /etc/envoy/envoy.yaml
ADD ingress-access.log /home/envoy/ingress-access.log

EXPOSE 80 443 9999

RUN mkdir envoy

ADD ./start_envoy.sh /usr/local/bin/start_envoy.sh
RUN chmod u+x /usr/local/bin/start_envoy.sh
ENTRYPOINT ["/usr/local/bin/start_envoy.sh"]
