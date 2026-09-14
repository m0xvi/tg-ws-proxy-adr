# Runbook: развёртывание tg-private-relay на новом (третьем) VPS

Цель: третья точка relay для failover (приложение v0.2.4 умеет список доменов до 8, токен общий).
Версия сервера: та же, что работает на двух текущих VPS (0.1.1, zip `tg-private-relay-v0.1.1-media-test-source.zip`).

---

## Шаг 0. Проверить маршруты до Telegram ДО развёртывания (критично!)

Проблема обоих текущих VPS — нестабильный TCP до DC203. Если на новом хостере так же — разворачивать бессмысленно.

```sh
for i in $(seq 1 15); do
  date '+%H:%M:%S'
  for dc in "DC1 149.154.175.50" "DC2 149.154.167.51" "DC3 149.154.175.100" "DC4 149.154.167.91" "DC5 149.154.171.5" "DC203 91.105.192.100"; do
    set -- $dc
    timeout 5 bash -c "exec 3<>/dev/tcp/$2/443" 2>/dev/null && echo "  OK   $1 $2" || echo "  FAIL $1 $2"
  done
  sleep 10
done
```

Крутится ~4 минуты. Требование: **DC203 — все OK** (допустимо 0-1 FAIL за 15 прогонов; DC5 второстепенен).

## Шаг 1. DNS

- Завести поддомен, напр. `relay3.ageevgroup.ru`, **A-запись напрямую на IP нового VPS**.
- Никаких прокси (Cloudflare — только DNS only / серая тучка).
- TTL 300, чтобы потом быстро переводить.

## Шаг 2. Docker и свободные порты

```sh
curl -fsSL https://get.docker.com | sh
ss -ltnp | grep -E ':(80|443)\b' || echo "80/443 свободны"
```

Если 443 занят (панель 3x-ui, xray, mtproto-proxy и т.п. — как было на pokehero.ru):

```sh
docker ps                     # найти контейнер
docker stop <name>
docker update --restart=no <name>   # ОБЯЗАТЕЛЬНО, иначе поднимется после ребута
```

Если активен ufw/firewall: `ufw allow 80,443/tcp` (и ssh).

## Шаг 3. Загрузить исходники relay

Со своего ПК:

```sh
scp tg-private-relay-v0.1.1-media-test-source.zip root@<НОВЫЙ_IP>:/root/
```

На новом VPS:

```sh
apt-get -y update && apt-get -y install unzip
mkdir -p /root/tg-private-relay && unzip -q /root/tg-private-relay-v0.1.1-media-test-source.zip -d /root/tg-private-relay
cd /root/tg-private-relay
```

(если unzip ругается на лишний корневой каталог — файлы должны лежать прямо в /root/tg-private-relay, рядом с compose.yaml)

## Шаг 4. .env — домен новый, ТОКЕН ОБЩИЙ

Приложение v0.2.4 использует один токен на все домены → на новом VPS должен стоять **тот же RELAY_TOKEN, что на mg.ageevgroup.ru**.

Локально на своём ПК (не в чат!):

```sh
ssh root@mg.ageevgroup.ru 'grep ^RELAY_TOKEN ~/tg-probe/tg-private-relay/.env'
```

На новом VPS:

```sh
cd /root/tg-private-relay
cat > .env <<EOF
RELAY_DOMAIN=relay3.ageevgroup.ru
RELAY_TOKEN=<вставить_сюда_значение_с_VPS1>
MAX_CONNECTIONS=256
WS_READ_LIMIT_BYTES=2097152
EOF
chmod 600 .env
```

## Шаг 5. Запуск

```sh
docker compose up -d --build
docker compose ps
docker compose logs --tail 20 relay
```

## Шаг 6. Проверка

```sh
curl -fsS https://relay3.ageevgroup.ru/healthz
# {"status":"ok","active_sessions":0,"version":"0.1.1"}

curl -fsS https://relay3.ageevgroup.ru/readyz
# все "ok":true, особенно "name":"dc203"
```

Сертификат Let's Encrypt Caddy выпустит сам при первом запросе (нужна уже работающая DNS A-запись).

## Шаг 7. Добавить домен в приложение

Настройки → Private Relay → домены (одной строкой, через запятую):

```
mg.ageevgroup.ru, pokehero.ru, relay3.ageevgroup.ru
```

Перезапустить прокси в приложении, проверить публичные медиа (каналы/видео/реакции/custom emoji — это всё DC203).

---

## Диагностика текущих двух VPS (пока медиа сломаны)

```sh
curl -fsS https://mg.ageevgroup.ru/readyz; echo
curl -fsS https://pokehero.ru/readyz; echo
```

Если `"name":"dc203","ok":false` — подтверждён плавающий маршрут у хостера, это лечится только сменой хостера/IP или третьим VPS.

Заодно на VPS #1 проверить, что .env не испорчен прошлым sed:

```sh
grep ^RELAY_DOMAIN ~/tg-probe/tg-private-relay/.env   # должно быть mg.ageevgroup.ru
docker compose logs --tail 30 caddy                   # нет ли ошибок выпуска сертификата
```
