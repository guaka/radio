FROM golang:1.22-alpine AS build
WORKDIR /src

COPY go.mod go.sum ./
RUN go mod download

COPY cmd ./cmd
COPY internal ./internal
COPY public ./public
COPY LICENSE README.md CNAME ./

RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o /out/plextr-server ./cmd/server

FROM alpine:3.20
WORKDIR /app

RUN adduser -D -u 10001 appuser

COPY --from=build /out/plextr-server /app/plextr-server
COPY public ./public
COPY LICENSE README.md CNAME ./

USER appuser
EXPOSE 8080

ENV ADDR=:8080
ENTRYPOINT ["/app/plextr-server"]
