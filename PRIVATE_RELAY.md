# TG WS Proxy Lab 0.2.0 — Private Relay

Экспериментальная Android-сборка, направляющая реальный MTProto-трафик Telegram через персональный WSS relay без Cloudflare.

```text
Telegram
  → MTProto 127.0.0.1:1443
  → TG WS Proxy Lab
  → verified TLS/WSS :443
  → mg.ageevgroup.ru
  → tg-private-relay
  → Telegram DC
```

## Совместимость

- Android 7.0+ (minSdk 24)
- arm64-v8a и armeabi-v7a
- package ID: `com.tgwsproxy.networklab`
- обновляется поверх Network Lab 0.1.2

## Настройка

1. На VPS в каталоге `tg-private-relay` посмотрите токен:

   ```bash
   grep '^RELAY_TOKEN=' .env
   ```

   Не присылайте токен в чаты, issue или отчёты.

2. Установите `TG-WS-Proxy-Lab-v0.2.0-private-relay-universal.apk`.
3. Откройте **Настройки → Private Relay**.
4. Включите переключатель.
5. В поле домена укажите только:

   ```text
   mg.ageevgroup.ru
   ```

   Не добавляйте `https://`, `wss://`, `/probe` или `/apiws`.

6. В поле `RELAY_TOKEN` вставьте значение после знака `=` из VPS `.env`.
7. Подождите секунду, чтобы настройки сохранились.
8. Перейдите на вкладку **Прокси**, запустите прокси и повторно примените его в Telegram.

При включении Private Relay приложение автоматически отключает Cloudflare и автоматическую отправку на ручные IP DC. Если Telegram запрашивает DC203, клиент направляет его через доступный DC2.

## Ожидаемые логи Android

```text
DC2 подключен через Private Relay
```

В статистике появится:

```text
relay:1
```

или большее значение.

## Проверка VPS

```bash
cd ~/tg-probe/tg-private-relay
docker compose ps
docker compose logs -f --tail=50 relay
```

При подключении Telegram должны появляться строки без IP клиента и без токена:

```text
session=1 dc=2 duration=... up=... down=... result=closed
```

## Если Telegram не подключается

1. Соберите отчёт в **Информация → Собрать отчёт**. Токен туда не включается.
2. Скопируйте последние строки вкладки **Логи**.
3. На VPS выполните:

   ```bash
   docker compose logs --tail=100 relay
   docker compose logs --tail=100 caddy
   curl --fail --show-error https://mg.ageevgroup.ru/healthz
   ```

4. Не публикуйте `.env` и `RELAY_TOKEN`.

Типовые признаки:

- `Private relay DC2: http 401` — неверный токен;
- `Private relay DC2: timeout` — TLS/WSS route или DNS перестал проходить;
- `Private relay DC2: http 502` — VPS не смог подключиться к выбранному Telegram DC;
- на VPS нет новых `session=` — Android не дошёл до `/apiws`;
- `session=` есть, но Telegram не подключается — проблема в MTProto bridge, приложите оба лога.

## Безопасность

- сертификат relay проверяется по WebPKI; режим `InsecureSkipVerify` для Private Relay не используется;
- токен передаётся только внутри TLS;
- Android хранит его в закрытом хранилище настроек приложения, но не в аппаратном Keystore;
- токен не включается в Network Lab или support report;
- Caddy access log в серверном комплекте отключён;
- relay принимает только фиксированный набор Telegram DC и не является универсальным прокси.

Сборка экспериментальная. Сначала проверьте текстовые сообщения, затем небольшие изображения, голосовые сообщения и крупные файлы.
