FROM grafana/xk6:1.4.11 AS builder

RUN xk6 build v1.2.2 \
    --with github.com/phymbert/xk6-sse@v0.1.12 \
    --output /tmp/k6

FROM grafana/k6:1.2.2

COPY --from=builder /tmp/k6 /usr/bin/k6
