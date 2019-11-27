# We use envoy version from master. This is 1.13.0-dev version with support for KEYS_SUBSET fallback policy,
# which is required for service-tags routing. More info: https://github.com/envoyproxy/envoy/pull/8890
FROM envoyproxy/envoy-dev:b7bef67c256090919a4585a1a06c42f15d640a09

ENV PORT=9999:9999
ENV PORT=80:80
ENV ENVOY_NODE_ID=front-proxy-id
ENV ENVOY_NODE_CLUSTER=front-proxy
ENV ENVOY_EGRESS_LISTENER_PORT=80
ENV ENVOY_INGRESS_LISTENER_PORT=8080
ENV ENVOY_ADMIN_PORT=9999
ENV ENVOY_XDS_PORT=50000
ENV ENVOY_XDS_HOST=host.docker.internal

ADD envoy-template.yaml /etc/envoy/envoy.yaml
RUN sed -i "s/{{.EgressListenerPort}}/${ENVOY_EGRESS_LISTENER_PORT}/g" /etc/envoy/envoy.yaml
RUN sed -i "s/{{.IngressListenerPort}}/${ENVOY_INGRESS_LISTENER_PORT}/g" /etc/envoy/envoy.yaml
RUN sed -i "s/{{.XdsHost}}/${ENVOY_XDS_HOST}/g" /etc/envoy/envoy.yaml
RUN sed -i "s/{{.XdsPort}}/${ENVOY_XDS_PORT}/g" /etc/envoy/envoy.yaml
RUN sed -i "s/{{.AdminPort}}/${ENVOY_ADMIN_PORT}/g" /etc/envoy/envoy.yaml

EXPOSE 80 443 9999

RUN mkdir envoy

CMD ["envoy", "-c", "/etc/envoy/envoy.yaml", "--service-cluster", "${ENVOY_NODE_CLUSTER}", "--service-node", "${ENVOY_NODE_ID}"]
