#!/bin/sh
# set -o pipefail
# set -o errexit

port="${ENVOY_INGRESS_LISTENER_PORT}"
service_name="${SERVICE_NAME}"
instance_id="${service_name}-1"

echo "Registering instance of ${service_name} in consul"
echo "============================="
echo
echo

ip="$(hostname -i)"

body='
{
  "ID": "'${instance_id}'",
  "Name": "'${service_name}'",
  "Tags": [
    "primary"
  ],
  "Address": "'${ip}'",
  "Port": '${port}',
  "Check": {
    "DeregisterCriticalServiceAfter": "90m",
    "http": "http://'${ip}:${port}${HEALTH_CHECK}'",
    "Interval": "10s"
  }
}
'

sed -i "s/{{.EgressListenerPort}}/${ENVOY_EGRESS_LISTENER_PORT}/g" /etc/envoy/envoy.yaml
sed -i "s/{{.IngressListenerPort}}/${ENVOY_INGRESS_LISTENER_PORT}/g" /etc/envoy/envoy.yaml
sed -i "s/{{.XdsHost}}/${ENVOY_XDS_HOST}/g" /etc/envoy/envoy.yaml
sed -i "s/{{.XdsPort}}/${ENVOY_XDS_PORT}/g" /etc/envoy/envoy.yaml
sed -i "s/{{.AdminPort}}/${ENVOY_ADMIN_PORT}/g" /etc/envoy/envoy.yaml
sed -i "s/{{.ServiceName}}/${SERVICE_NAME}/g" /etc/envoy/envoy.yaml
sed -i "s/{{.ServiceHost}}/${SERVICE_HOST}/g" /etc/envoy/envoy.yaml
sed -i "s/{{.ServicePort}}/${SERVICE_PORT}/g" /etc/envoy/envoy.yaml
sed -i "s/{{.DependencyService}}/${DEPENDENCY_SERVICE}/g" /etc/envoy/envoy.yaml


curl -X PUT --fail --data "${body}" -s consul:8500/v1/agent/service/register

envoy -c /etc/envoy/envoy.yaml --service-cluster "${service_name}" --service-node "${instance_id}"
