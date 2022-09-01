#!/bin/sh
# set -o pipefail
# set -o errexit

port="${PORT}"
service_name="service${SERVICE_NAME}"
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
    "http": "http://'${ip}:${port}'/health-check",
    "Interval": "10s"
  }
}
'

curl -X PUT --fail --data "${body}" -s consul:8500/v1/agent/service/register

python3 /code/service.py #&
# jak nazwać node'a?
# envoy -c /etc/service-envoy.yaml --service-cluster "${service_name}" --service-node "${instance_id}"
