#!/usr/bin/env sh

set -o pipefail
set -o errexit

port=80
service_name=http-echo
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
    "http": "http://'${ip}:${port}'",
    "Interval": "10s"
  }
}
'
curl -X PUT --fail --data "${body}" -s consul:8500/v1/agent/service/register

cd /app
node ./index.js

