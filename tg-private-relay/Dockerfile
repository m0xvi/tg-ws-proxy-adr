FROM golang:1.23-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY cmd ./cmd
RUN CGO_ENABLED=0 GOOS=linux go build \
    -trimpath \
    -ldflags="-s -w -buildid=" \
    -o /out/tg-relay ./cmd/relay

FROM scratch
USER 65532:65532
COPY --from=build /out/tg-relay /tg-relay
EXPOSE 8080
ENTRYPOINT ["/tg-relay"]
