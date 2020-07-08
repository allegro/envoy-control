#!/usr/bin/env sh

#docker build -q -t andrzejwaw/testlua .

rm *.lua 2>/dev/null
cp ../../envoy-control-tests/src/main/resources/filters/ingress_handler.lua .
cp ../../envoy-control-tests/src/main/resources/testcontainers/handler_test.lua .

echo "\nTests:\n"
docker run -v $(pwd):/lua --rm andrzejwaw/testlua sh -c "find . -name '*_test.lua' -print0 | xargs -0 prove -v --color"

rm *.lua 2>/dev/null