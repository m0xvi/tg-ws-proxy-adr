#!/usr/bin/env sh
set -eu

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 relay.example.com" >&2
    exit 2
fi

if ! command -v openssl >/dev/null 2>&1; then
    echo "openssl is required" >&2
    exit 1
fi

umask 077
domain="$1"
token="$(openssl rand -hex 32)"
cat > .env <<EOF
RELAY_DOMAIN=$domain
RELAY_TOKEN=$token
MAX_CONNECTIONS=256
WS_READ_LIMIT_BYTES=2097152
EOF
chmod 600 .env

echo "Created .env for $domain"
echo "The relay token is stored in .env. Do not paste it into chats or commit it."
echo "Probe URL (contains no secret): wss://$domain/probe"
