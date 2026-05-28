# syntax=docker/dockerfile:1.6
FROM golang:1.26.3-alpine AS builder
RUN apk add --no-cache git ca-certificates
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/transactions-service ./cmd/server && \
    CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/healthcheck ./cmd/healthcheck

FROM gcr.io/distroless/static:nonroot
WORKDIR /app
COPY --from=builder /out/transactions-service /app/transactions-service
COPY --from=builder /out/healthcheck /app/healthcheck
COPY --from=builder /src/migrations /app/migrations
USER nonroot:nonroot
EXPOSE 8083
ENTRYPOINT ["/app/transactions-service"]
