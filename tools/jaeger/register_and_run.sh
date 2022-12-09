#!/usr/bin/env sh

set -o pipefail
set -o errexit

port=9411
service_name=jaeger
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
    "http": "http://'${ip}':14269",
    "Interval": "10s"
  }
}
'
curl -X PUT --fail --data "${body}" -s consul:8500/v1/agent/service/register

/go/bin/all-in-one-linux --collector.zipkin.host-port=9411
