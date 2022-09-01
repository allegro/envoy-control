#!/usr/bin/env bash

docker-compose up --no-deps --build consul front-proxy jaeger service1 service2 service3 tracing flask-service
