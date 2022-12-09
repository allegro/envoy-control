from flask import Flask
from flask import request
import os
import requests
import socket
import sys
import json

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

app = Flask(__name__)

@app.route('/health-check')
def health_check():
    return (os.environ['SERVICE_NAME'], 200)


@app.route('/proxy/<first>/<second>')
def proxy2(first, second):
    headers = {
        'Host': first
    }
    print(request.headers, file=sys.stderr)
    for header in TRACE_HEADERS_TO_PROPAGATE:
        if header in request.headers:
            headers[header] = request.headers[header]
    resp = requests.get(f"http://{os.environ['ENVOY_HOST']}:31000/proxy/{second}", headers=headers)
    return (resp.text, 200)


@app.route('/proxy/<first>')
def proxy(first):
    headers = {
        'Host': first
    }
    print(request.headers, file=sys.stderr)
    for header in TRACE_HEADERS_TO_PROPAGATE:
        if header in request.headers:
            headers[header] = request.headers[header]
    resp = requests.get(f"http://{os.environ['ENVOY_HOST']}:31000/health-check", headers=headers)
    return (resp.text, 200)



if __name__ == "__main__":
    app.run(host='0.0.0.0', port=os.environ['PORT'])
