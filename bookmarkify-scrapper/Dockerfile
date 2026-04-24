# Stage 1: Build
FROM rust:1.88-slim AS builder

WORKDIR /app

RUN apt-get update && apt-get install -y pkg-config libssl-dev && rm -rf /var/lib/apt/lists/*

COPY Cargo.toml Cargo.lock ./
COPY crates/ crates/
COPY vendor/ vendor/
COPY .cargo/ .cargo/

RUN cargo build -p scraper-service --release --offline

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
