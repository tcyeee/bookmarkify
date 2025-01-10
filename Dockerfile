FROM node:latest
WORKDIR /app
COPY .output /app
CMD ["node", "/app/server/index.mjs"]