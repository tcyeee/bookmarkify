# Stage 0: cargo-chef base (prebuilt image — avoids recompiling chef each build)
FROM lukemathwalker/cargo-chef:latest-rust-1.88 AS chef

WORKDIR /app

# Pass --build-arg USE_CN_MIRROR=1 to enable Alibaba/rsproxy mirrors for builds in China.
# Placed before any cargo step so dependency fetches also go through the mirror.
ARG USE_CN_MIRROR=0
RUN if [ "$USE_CN_MIRROR" = "1" ]; then \
    mkdir -p ~/.cargo && printf '[source.crates-io]\nreplace-with = "rsproxy-sparse"\n\n[source.rsproxy-sparse]\nregistry = "sparse+https://rsproxy.cn/index/"\n\n[net]\ngit-fetch-with-cli = true\n' > ~/.cargo/config.toml; \
fi

# Stage 1: planner — distill the dependency graph into recipe.json.
# This stage re-runs on every source change, but `chef prepare` is cheap and the
# recipe.json it emits is byte-identical unless Cargo.toml/Cargo.lock changed.
FROM chef AS planner
COPY Cargo.toml Cargo.lock ./
COPY crates/ crates/
RUN cargo chef prepare --recipe-path recipe.json

# Stage 2: builder
FROM chef AS builder

RUN apt-get update && apt-get install -y pkg-config libssl-dev && rm -rf /var/lib/apt/lists/*

# Compile & cache dependencies ONLY. Keyed on recipe.json, so this heavy layer is
# reused across builds until dependencies actually change — this is the whole point.
COPY --from=planner /app/recipe.json recipe.json
RUN cargo chef cook --release -p scraper-service --recipe-path recipe.json

# Now copy the real source and build just our crate (deps already compiled above).
COPY Cargo.toml Cargo.lock ./
COPY crates/ crates/
RUN cargo build -p scraper-service --release

# Stage 2b: export — lets CI extract just the compiled binary without pushing an
# image anywhere (docker buildx --target export --output type=local). Placed BEFORE
# the runtime stage so the default build target stays the runtime image (local dev /
# docker-compose unaffected). The prod deploy relays this binary through OSS and
# assembles the runtime image on the server (see deploy/Dockerfile.runtime).
FROM scratch AS export
COPY --from=builder /app/target/release/scraper-service /scraper-service

# Stage 3: Runtime
FROM debian:bookworm-slim

ARG USE_CN_MIRROR=0

RUN if [ "$USE_CN_MIRROR" = "1" ]; then \
    sed -i 's/deb.debian.org/mirrors.aliyun.com/g' /etc/apt/sources.list.d/debian.sources; \
fi && \
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
