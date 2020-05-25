#!/bin/bash

ROOT_DN="/C=PL/ST=Malopolska/L=Krakow/O=Envoy Control Root Authority/"
INTERMEDIATE_DN="/C=PL/ST=Malopolska/L=Krakow/O=Envoy Control Intermediate Authority/"

function gen_root_ca {
  # Root key
  openssl genrsa -out "root-ca-$1.key" 2048

  # Self-sign root cert
  openssl req \
    -x509 \
    -new \
    -nodes \
    -key "root-ca-$1.key" \
    -days 99999 \
    -out "root-ca-$1.crt" \
    -subj "$ROOT_DN"
}

function gen_intermediate {
  # Intermadiate key
  openssl genrsa -out "intermediate-ca-$1.key" 2048

  # Intermediate cert
  openssl req \
    -x509 \
    -new \
    -nodes \
    -key "intermediate-ca-$1.key" \
    -days 99999 \
    -out "intermediate-ca-$1.crt" \
    -subj "$INTERMEDIATE_DN"

  # Intermediate CA CSR
  openssl req -new -key "intermediate-ca-$1.key" -subj "$INTERMEDIATE_DN" -utf8 -out "intermediate-ca-$1.csr"

  # Sign CSR with Root CA
  openssl x509 \
    -req -in "intermediate-ca-$1.csr" \
    -CA "root-ca-$1.crt" \
    -CAkey "root-ca-$1.key" \
    -CAcreateserial \
    -extensions v3_ca \
    -days 99999 \
    -out "intermediate-ca-$1.crt"
}

function gen_client {
  # Client key
  openssl genrsa -out "privkey-echo-$1.key" 2048

  # Client CSR
  openssl req -new \
    -key "privkey-echo-$1.key" \
    -out "echo-$1.csr" \
    -subj "/CN=echo-$1/C=PL/"

  # Sign
  openssl x509 \
    -req -in "echo-$1.csr" \
    -extfile <(echo "subjectAltName=URI:spiffe://echo$1") \
    -CA "intermediate-ca-$2.crt" \
    -CAkey "intermediate-ca-$2.key" \
    -CAcreateserial \
    -days 99999 \
    -out "echo-$1-signed-by-root-ca-$2.crt" \

  cat "echo-$1-signed-by-root-ca-$2.crt" "intermediate-ca-$2.crt" > "fullchain-echo-$1-intermediate-$2.crt"
}

gen_root_ca 1
gen_root_ca 2
gen_intermediate 1
gen_intermediate 2
gen_client 1 1
gen_client 2 1
gen_client 3 1
gen_client 1 2
