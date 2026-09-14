# TG private WSS relay

Персональный WSS-релей для лабораторного Android-форка TG WS Proxy.

```text
Android local MTProto proxy
        │ WSS/TLS 1.2–1.3, TCP/443
        ▼
Caddy (автоматический сертификат)
        │ WebSocket внутри Docker-сети
        ▼
tg-relay
        │ raw MTProto TCP/443
        ▼
Telegram DC1–DC5/203
```

Cloudflare не используется.

## Что делает и чего не делает

- `/healthz` — обычная проверка HTTPS.
- `/probe` — безопасный публичный WebSocket handshake без подключения к Telegram. Нужен для Network Lab.
- `/apiws?dc=2` — закрытый relay endpoint; требует `Authorization: Bearer ...` или `token` в query.
- Релей принимает только заранее заданные Telegram DC, поэтому не является универсальным открытым прокси.
- MTProto-содержимое не журналируется. В лог попадают только номер сессии, DC, длительность и счётчики байт.

Текущая версия Android Network Lab умеет проверить `/probe`, но ещё не направляет через `/apiws` рабочий Telegram-трафик. Подключение relay в транспортное ядро выполняется следующим этапом после успешного теста с мобильного Билайна.

## Требования

- Ubuntu/Debian VPS;
- Docker Engine и Docker Compose v2;
- свободные TCP-порты 80 и 443;
- отдельный поддомен, например `relay.example.com`;
- A-запись поддомена напрямую на IPv4 VPS.

Если DNS управляется Cloudflare, у записи должно быть **DNS only / серое облако**. Оранжевое облако возвращает трафик в уже неработающий Cloudflare-маршрут.

Не создавайте AAAA-запись, если IPv6 на VPS не настроен и не проверен.

## 1. Подготовка DNS

Создайте запись:

```text
Type: A
Name: relay
Value: публичный IPv4 VPS
Proxy/CDN: выключен, DNS only
TTL: Auto или 300
```

Проверьте с любого компьютера:

```bash
dig +short relay.example.com A
```

Ответ должен совпасть с IP VPS, а не быть адресом Cloudflare вида `104.21.x.x` или `172.67.x.x`.

## 2. Проверка VPS

Распакуйте комплект и перейдите в каталог:

```bash
unzip tg-private-relay.zip
cd tg-private-relay
chmod +x scripts/*.sh
```

Убедитесь, что 80/443 свободны:

```bash
sudo ss -ltnp | grep -E ':(80|443)\b' || true
```

Проверьте исходящие соединения VPS к Telegram:

```bash
./scripts/check-vps.sh
```

Для каждого DC должен появиться `PASS`. Если большая часть адресов недоступна, этот VPS для relay не подходит.

## 3. Создание конфигурации

```bash
./scripts/prepare.sh relay.example.com
```

Скрипт создаст `.env` с правами `600` и случайным 256-битным токеном.

Не отправляйте `.env`, токен, пароль VPS или SSH-ключи кому-либо. Для первого `/probe` теста токен не нужен.

## 4. Запуск

```bash
docker compose up -d --build
docker compose ps
docker compose logs --tail=100 caddy
docker compose logs --tail=100 relay
```

Caddy автоматически запросит сертификат. Для этого DNS уже должен указывать на VPS, а входящие 80/443 — быть разрешены firewall/security group.

Пример UFW, только если UFW уже используется:

```bash
sudo ufw allow 22/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw status
```

Не включайте UFW по SSH, предварительно не разрешив порт SSH.

## 5. Проверка HTTPS

```bash
curl --fail --show-error https://relay.example.com/healthz
```

Ожидаемый ответ:

```json
{"status":"ok","active_sessions":0,"version":"0.1.0"}
```

## 6. Проверка с Билайна

На телефоне:

1. отключите Wi-Fi;
2. откройте `TG WS Proxy Lab` → `Тест`;
3. в поле своего endpoint введите:

```text
wss://relay.example.com/probe
```

4. запустите тест;
5. в отчёте должен появиться блок `Custom relay / Custom WSS endpoint` с `HTTP 101` и `PASS`.

URL `/probe` не содержит токена и его можно прислать вместе с отчётом.

Если TCP проходит, но TLS зависает, IP/домен этого VPS также фильтруется мобильной сетью — нужен другой VPS/IP/домен. Если TLS проходит, но ответ не 101, проверьте Caddy и relay logs.

## Обновление и остановка

```bash
docker compose pull
docker compose up -d --build
```

Остановка:

```bash
docker compose down
```

Полное удаление вместе с сертификатами Caddy:

```bash
docker compose down -v
```

## Безопасность

- наружу публикуются только Caddy 80/443;
- порт relay 8080 доступен только внутри Docker-сети;
- контейнер relay работает без root, с read-only filesystem и без Linux capabilities;
- `/apiws` использует токен и допускает только Telegram DC из фиксированного списка;
- Caddy access log выключен, чтобы query token не попадал в журнал;
- `/probe` не подключается к Telegram и закрывается примерно через две секунды;
- регулярно устанавливайте обновления ОС и Docker.
