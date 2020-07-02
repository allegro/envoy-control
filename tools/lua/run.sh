#!/usr/bin/env sh

docker build -q -t luatest .
cp ../../envoy-control-runner/src/main/resources/filters/handler.lua .

docker run -v $(pwd):/lua --rm luatest sh -c "find . -name '*_test.lua' -print0 | xargs -0 prove -v --color"
rm handler.lua