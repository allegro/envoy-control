#!/usr/bin/env bash

docker-compose up --no-deps --build consul front-proxy jaeger s1 s2 s3 tracing flask-service
