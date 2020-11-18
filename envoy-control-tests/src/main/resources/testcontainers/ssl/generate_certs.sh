#!/bin/bash

ROOT_DN="/C=US/ST=Utah/L=Provo/O=ACME Signing Authority Inc/CN=example.com"
INTERMEDIATE_DN="/C=US/ST=Utah/L=Provo/O=ACME Signing Authority Inc/CN=example.com"

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
    -subj "$INTERMEDIATE_DN"
#    -subj "/C=US/ST=Utah/L=Provo/O=ACME Tech Inc/CN=example.com"

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

function gen_clientMulti {
  # Client key
  openssl genrsa -out "privkey-echo-$1.key" 2048

  # Client CSR
  openssl req -new \
    -key "privkey-echo-$1.key" \
    -out "echo-$1.csr" \
    -subj "/C=US/ST=Utah/L=Provo/O=ACME Tech Inc/CN=example.com"

  # Sign
  openssl x509 \
    -req -in "echo-$1.csr" \
    -extfile <(echo "subjectAltName=URI.1:spiffe://echo$1,URI.2:spiffe://echo$1-special,URI.3:spiffe://echo$1-admin") \
    -CA "intermediate-ca-$2.crt" \
    -CAkey "intermediate-ca-$2.key" \
    -CAcreateserial \
    -days 99999 \
    -out "echo-$1-signed-by-root-ca-$2.crt" \

  cat "echo-$1-signed-by-root-ca-$2.crt" "intermediate-ca-$2.crt" > "fullchain-echo-$1-intermediate-$2.crt"
}
echo "# remove used"
rm root-ca-3.crt root-ca3.key.pem
rm fullchain_echo4.pem privkey_echo4.pem
rm fullchain_echo5.pem privkey_echo5.pem

gen_root_ca 3
gen_intermediate 1
gen_client 4 1
gen_clientMulti 5 1

openssl x509 -inform PEM -in fullchain-echo-4-intermediate-1.crt > fullchain_echo4.pem
openssl rsa -in privkey-echo-4.key -text > privkey_echo4.pem

openssl x509 -inform PEM -in fullchain-echo-5-intermediate-1.crt > fullchain_echo5.pem
openssl rsa -in privkey-echo-5.key -text > privkey_echo5.pem

echo "# post clenaup"
rm intermediate-ca-1.crt intermediate-ca-1.csr intermediate-ca-1.key intermediate-ca-1.srl root-ca-1.key root-ca-1.srl
rm  echo-4.csr echo-4-signed-by-root-ca-1.crt fullchain-echo-4-intermediate-1.crt privkey-echo-4.key
rm  echo-5.csr echo-5-signed-by-root-ca-1.crt fullchain-echo-5-intermediate-1.crt privkey-echo-5.key