#!/usr/bin/env bash

docker-compose up --no-deps --build consul envoy http-echo
