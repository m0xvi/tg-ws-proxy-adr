# srv1 (130.49.141.28): relay №3 на порту 8443 — пошагово

Особенности этого VPS: 3x-ui/xray держит 443 (НЕ трогаем), nginx — 80, Docker уже работает.
Схема: телефон → `wss://relay3.ageevgroup.ru:8443` → Caddy (8443, свой сертификат) → relay:8080 → Telegram DC.

---

## Шаг 1. DNS (в панели зоны ageevgroup.ru)

- A-запись: `relay3` → `130.49.141.28`, TTL 300.
- Проверка (после публикации):
  ```sh
  dig +short relay3.ageevgroup.ru @1.1.1.1   # должно вернуть 130.49.141.28
  ```

## Шаг 2. Файлы на сервер

Со своего ПК:
```sh
scp tg-private-relay-v0.1.1-media-test-source.zip root@130.49.141.28:/root/
scp srv1-relay-deploy/compose.yaml srv1-relay-deploy/Caddyfile root@130.49.141.28:/root/
```

На сервере:
```sh
mkdir -p /root/tg-private-relay
unzip -q /root/tg-private-relay-v0.1.1-media-test-source.zip -d /root/tg-private-relay
# если zip распаковался с вложенным каталогом — перенеси файлы так, чтобы
# compose.yaml и Caddyfile лежали прямо в /root/tg-private-relay
mv /root/compose.yaml /root/Caddyfile /root/tg-private-relay/
cd /root/tg-private-relay
mkdir -p certs
# проверь, что Caddyfile ссылается на {$RELAY_DOMAIN}:8443 и сертификаты
cat Caddyfile
```

## Шаг 3. Файрвол

```sh
ss -ltnp | grep ':8443 ' || echo "8443 свободен"
ufw status 2>/dev/null | head -5
ufw allow 8443/tcp 2>/dev/null   # только если ufw активен
```

## Шаг 4. Сертификат через acme.sh (ручной DNS-01)

```sh
curl https://get.acme.sh | sh -s email=admin@ageevgroup.ru   # подставь свою почту
source ~/.bashrc
~/.acme.sh/acme.sh --issue --dns -d relay3.ageevgroup.ru --server letsencrypt
```

Команда **запнётся и напечатает TXT-запись** вида:

```
Add the following TXT record:
Domain: _acme-challenge.relay3.ageevgroup.ru
Txt value: 9ihDbjYfTExAYeDs4DBUeu...
```

Добавь эту TXT-запись в панели DNS, подожди 1–2 минуты и проверь:

```sh
dig +short TXT _acme-challenge.relay3.ageevgroup.ru @1.1.1.1
```

Затем продолжи выпуск:

```sh
~/.acme.sh/acme.sh --issue --dns -d relay3.ageevgroup.ru --server letsencrypt \
  --yes-I-know-dns-manual-mode-enough-go-ahead-please
```

Установка сертификата + авторелоад Caddy:

```sh
mkdir -p /root/tg-private-relay/certs
~/.acme.sh/acme.sh --install-cert -d relay3.ageevgroup.ru \
  --fullchain-file /root/tg-private-relay/certs/fullchain.pem \
  --key-file /root/tg-private-relay/certs/privkey.pem \
  --reloadcmd "docker restart tg-private-relay-caddy-1"
```

(имя контейнера проверь: `docker ps --format '{{.Names}}'`)

## Шаг 5. .env — домен новый, ТОКЕН общий с VPS #1

Локально у себя (не в чат):
```sh
ssh root@mg.ageevgroup.ru 'grep ^RELAY_TOKEN ~/tg-probe/tg-private-relay/.env'
```

На srv1:
```sh
cd /root/tg-private-relay
cat > .env <<'EOF'
RELAY_DOMAIN=relay3.ageevgroup.ru
RELAY_TOKEN=ВСТАВЬ_ТОКЕН_С_VPS1
MAX_CONNECTIONS=256
WS_READ_LIMIT_BYTES=2097152
EOF
chmod 600 .env
```

## Шаг 6. Запуск и проверка

```sh
cd /root/tg-private-relay
docker compose up -d --build
docker compose ps
curl -fsS https://relay3.ageevgroup.ru:8443/healthz
curl -fsS https://relay3.ageevgroup.ru:8443/readyz
```

Ожидаемо: `{"status":"ok",...,"version":"0.1.1"}` и в readyz все `ok:true` (маршруты на srv1 отличные).

## Шаг 7. Приложение v0.2.5

1. Установи `TG-WS-Proxy-Lab-v0.2.5-relay-port-universal-debug.apk` (поверх старой, версия 0.2.5).
2. Настройки → Private Relay → домены (одной строкой):
   ```
   mg.ageevgroup.ru, pokehero.ru, relay3.ageevgroup.ru:8443
   ```
3. Токен тот же. Включи прокси, проверь публичные медиа.
4. Вкладка диагностики: прогон — блок «Private relay» для `relay3.ageevgroup.ru-8443`
   покажет, работает ли порт 8443 с соты Билайн (это главный вопрос этого плана).

## Шаг 8. Продление сертификата (раз в ~80 дней)

Ручной режим acme.sh не продлевается сам. Напоминание в календарь; процедура:

```sh
~/.acme.sh/acme.sh --issue --dns -d relay3.ageevgroup.ru --server letsencrypt
# → заменит TXT-запись в DNS на новую (удали старую, добавь новую)
dig +short TXT _acme-challenge.relay3.ageevgroup.ru @1.1.1.1   # дождались новую
~/.acme.sh/acme.sh --renew -d relay3.ageevgroup.ru \
  --yes-I-know-dns-manual-mode-enough-go-ahead-please
# install-cert сработает сам: reloadcmd перезапустит caddy
openssl x509 -enddate -noout -in /root/tg-private-relay/certs/fullchain.pem
```

Позже добавим проверку срока годности этого сертификата в ntfy-монитор.
