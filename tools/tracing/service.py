from flask import Flask
from flask import request
import os
import requests
import socket
import sys

app = Flask(__name__)

TRACE_HEADERS_TO_PROPAGATE = [
    'X-Ot-Span-Context',
    'X-Request-Id',

    # Zipkin headers
    'X-B3-TraceId',
    'X-B3-SpanId',
    'X-B3-ParentSpanId',
    'X-B3-Sampled',
    'X-B3-Flags',

    # Jaeger header (for native client)
    "uber-trace-id",

    # SkyWalking headers.
    "sw8"
]

s = int(os.environ['SERVICE_NAME'])


@app.route('/service/<service_number>')
def hello(service_number):
    return (
        'Hello from behind Envoy (service {})! hostname: {} resolved'
        'hostname: {}\n'.format(
            os.environ['SERVICE_NAME'], socket.gethostname(),
            socket.gethostbyname(socket.gethostname())))


@app.route('/health-check')
def health_check():
    return ('', 200)


@app.route('/trace')
def trace():
    headers = {}

    if s == 1 or s == 2:
        for header in TRACE_HEADERS_TO_PROPAGATE:
            if header in request.headers:
                headers[header] = request.headers[header]
        requests.get("http://localhost:9000/trace", headers=headers)

    return (
        'Hello from behind Envoy (service {})! hostname: {} resolved'
        'hostname: {}\n'.format(
            os.environ['SERVICE_NAME'], socket.gethostname(),
            socket.gethostbyname(socket.gethostname())))


if __name__ == "__main__":
    app.run(host='0.0.0.0', port=os.environ['PORT'])
