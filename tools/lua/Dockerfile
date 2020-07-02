FROM akorn/luarocks:lua5.1-alpine

RUN apk add unzip perl-test-harness-utils
RUN luarocks install lua-testmore

WORKDIR /lua