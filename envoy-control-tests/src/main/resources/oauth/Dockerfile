FROM alpine
RUN apk add openjdk11-jre git
RUN git clone --depth 1 --branch 0.0.1 https://github.com/allegro/oauth-mock.git
WORKDIR ./oauth-mock
RUN ./gradlew installDist
CMD ["./build/install/oauth-mock/bin/oauth-mock"]
