FROM mendhak/http-https-echo
USER root
RUN apk add --update \
    curl \
    && rm -rf /var/cache/apk/*
WORKDIR /
COPY register_and_run.sh /register_and_run.sh
RUN chmod a+x /register_and_run.sh
ENTRYPOINT ["/register_and_run.sh"]
