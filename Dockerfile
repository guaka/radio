FROM node:22-alpine AS frontend
WORKDIR /src
COPY package.json pnpm-lock.yaml tsconfig.json ./
RUN corepack enable && pnpm install --frozen-lockfile
COPY src/frontend ./src/frontend
RUN pnpm run build

FROM golang:1.25-alpine AS build
WORKDIR /src

COPY go.mod go.sum ./
RUN go mod download

COPY cmd ./cmd
COPY internal ./internal
COPY public ./public
COPY --from=frontend /src/public/js ./public/js
COPY LICENSE README.md CNAME ./

RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o /out/plextr-server ./cmd/server

FROM alpine:3.20
WORKDIR /app

RUN adduser -D -u 10001 appuser

COPY --from=build /out/plextr-server /app/plextr-server
COPY --from=build /src/public ./public
COPY LICENSE README.md CNAME ./
RUN mkdir -p /app/data && chown -R appuser:appuser /app

USER appuser
EXPOSE 3030

ENV ADDR=:3030
ENTRYPOINT ["/app/plextr-server"]
