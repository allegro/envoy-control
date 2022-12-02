#!/usr/bin/env sh

set -o pipefail
set -o errexit

port=80
service_name1=http-echo1
service_name2=http-echo2
service_name3=http-echo3

ip="$(hostname -i)"

body1='
{
  "ID": "'${service_name1}'-1",
  "Name": "'${service_name1}'",
  "Tags": [
    "primary",
    "hermes-consumers"
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
body2='
{
  "ID": "'${service_name2}'-1",
  "Name": "'${service_name2}'",
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
body3='
{
  "ID": "'${service_name3}'-1",
  "Name": "'${service_name3}'",
  "Tags": [
    "primary",
    "hermes-consumers"
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

sleep 15

echo "Registering instance of ${service_name1} in consul"
curl -X PUT --data "${body1}" consul:8500/v1/agent/service/register
echo "Registering instance of ${service_name2} in consul"
curl -X PUT --data "${body2}" consul:8500/v1/agent/service/register
echo "Registering instance of ${service_name3} in consul"
curl -X PUT --data "${body3}" consul:8500/v1/agent/service/register

cd /app
node ./index.js

