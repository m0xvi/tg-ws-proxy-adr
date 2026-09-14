#!/usr/bin/env sh
set -eu

check_tcp() {
    label="$1"
    host="$2"
    port="$3"
    if command -v bash >/dev/null 2>&1 && timeout 6 bash -c "exec 3<>/dev/tcp/$host/$port" 2>/dev/null; then
        printf 'PASS  %-12s %s:%s\n' "$label" "$host" "$port"
    elif command -v nc >/dev/null 2>&1 && nc -z -w 6 "$host" "$port" >/dev/null 2>&1; then
        printf 'PASS  %-12s %s:%s\n' "$label" "$host" "$port"
    else
        printf 'FAIL  %-12s %s:%s\n' "$label" "$host" "$port"
        return 1
    fi
}

fail=0
check_tcp 'Telegram DC1' 149.154.175.50 443 || fail=1
check_tcp 'Telegram DC2' 149.154.167.51 443 || fail=1
check_tcp 'Telegram DC3' 149.154.175.100 443 || fail=1
check_tcp 'Telegram DC4' 149.154.167.91 443 || fail=1
check_tcp 'Telegram DC5' 149.154.171.5 443 || fail=1
check_tcp 'Telegram 203' 91.105.192.100 443 || fail=1

if [ "$fail" -ne 0 ]; then
    echo 'One or more Telegram routes are unavailable from this VPS.' >&2
    exit 1
fi

echo 'All configured Telegram TCP routes are reachable.'
