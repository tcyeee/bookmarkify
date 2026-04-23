# Stage 1: Build
FROM rust:1.88-slim AS builder

WORKDIR /app

RUN apt-get update && apt-get install -y pkg-config libssl-dev && rm -rf /var/lib/apt/lists/*

RUN mkdir -p ~/.cargo && printf '[source.crates-io]\nreplace-with = "rsproxy-sparse"\n\n[source.rsproxy-sparse]\nregistry = "sparse+https://rsproxy.cn/index/"\n\n[net]\ngit-fetch-with-cli = true\n' > ~/.cargo/config.toml

COPY Cargo.toml Cargo.lock ./
COPY crates/ crates/

RUN cargo build -p scraper-service --release

# Stage 2: Runtime
FROM debian:bookworm-slim

RUN sed -i 's/deb.debian.org/mirrors.aliyun.com/g' /etc/apt/sources.list.d/debian.sources && \
    apt-get update && apt-get install -y \
    ca-certificates \
    chromium \
    libssl3 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder /app/target/release/scraper-service .

ENV CHROME_BIN=/usr/bin/chromium

EXPOSE 3000

CMD ["./scraper-service"]
