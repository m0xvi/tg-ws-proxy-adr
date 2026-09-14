# HANDOVER — TG-WS-Proxy Lab + приватные Telegram-relay

> Документ для нового чата/ассистента. Содержит всё о проекте: контекст, инфраструктуру,
> хронологию запросов пользователя и выполненных работ, текущее состояние, планы и
> рецепты сборки. Дата последнего обновления: **2026-09-14**.

---

## 1. Суть проекта

Цель: стабильный Telegram на мобильном операторе **Билайн (Калининград)**, где прямой
доступ к Telegram заблокирован/деградирован на соте. Решение — форк Android-приложения
(оригинал: https://github.com/amurcanov/tg-ws-proxy-android, upstream: https://github.com/Flowseal/tg-ws-proxy),
превращённый в «Network Lab»: локальный MTProto-proxy на телефоне, который ходит в Telegram
**через собственные VPS-relay по WSS/TLS**.

Схема трафика:

```
Android-приложение (com.tgwsproxy.networklab)
  └─ локальный прокси 127.0.0.1:1443 (Rust native lib, JNI)
       └─ WSS/TLS :443 (или :8443 для relay3) ──► Caddy на VPS ──► Go relay (tg-private-relay:8080)
            └─ raw MTProto TCP :443 ──► Telegram DC1..DC5 + DC203 (медиа/CDN)
```

Приложение поддерживает **failover по списку доменов relay** (до 8, через запятую) и
(с v0.2.5) **нестандартные порты** в формате `домен:порт`. Токен — один общий на все relay.

## 2. Правила работы с пользователем

- Общаться **по-русски**. Пользователь технически грамотный, root на VPS, копирует команды.
- **НИКОГДА не запрашивать и не показывать секреты**: `.env`, `RELAY_TOKEN`, пароли, SSH-ключи,
  ntfy-URL. Если токен нужен — говорить «скопируй локально по SSH», не присылать в чат.
- Приватность: диагностические отчёты не должны содержать публичные/локальные IP абонента
  (в `NetworkDiagnostics.kt` timeout-сообщения редактируются регексом в `[local-address-redacted]`,
  токен в отчётах маскируется `(?i)(token=)[^&\s]+` → `$1[redacted]`).
- **Не использовать Cloudflare** (CDN/proxy) для рабочего трафика: CF TLS зависает на соте Билайн.
- Давать точные команды/конфиги; длинные инструкции — файлами в workspace.

## 3. Инфраструктура

| # | Хост | IP | Роль | Состояние |
|---|------|----|------|-----------|
| 1 | `mg.ageevgroup.ru` (hostname ams-1-vm-1ksc) | `5.129.239.160` | relay №1, путь `~/tg-probe/tg-private-relay` (root) | Работает (relay 0.1.1 + Caddy на 443). Есть посторонние сервисы (amnezia-dns/awg). Cert LE до 2026-10-29. **Маршрут к DC203 нестабилен (плавает FAIL/OK)** |
| 2 | `pokehero.ru` (hostname x3u) | `147.45.152.7` | relay №2 | Работает (relay 0.1.1 + Caddy 443). Есть `compose.override.yaml` (dns 1.1.1.1/8.8.8.8 + ротация логов json-file 10m×3). `mtproto-proxy` контейнер остановлен, **restart policy не отключён** — нужно `docker update --restart=no mtproto-proxy` |
| 3 | `srv1` (в работе) | `130.49.141.28` | relay №3 (план) | Маршруты до ВСЕХ DC идеальны (15/15 OK). **3x-ui панель — НЕ ТРОГАТЬ** (xray держит `*:443`, nginx — 80). Docker работает. Один IPv4 → TLS на **8443**. Домена нет → A-запись `relay3.ageevgroup.ru` → 130.49.141.28 |

- Токен relay: один и тот же на VPS#1 и VPS#2 (лежит в `.env` на серверах; при деплое srv1 — скопировать с VPS#1). В чате не светить.
- Сертификаты: VPS#1/#2 — Let's Encrypt автоматически (Caddy, HTTP-01 на 443). srv1 — **ручной DNS-01 через acme.sh** (порты 80/443 заняты), продление вручную раз в ~80 дней (процедура в `srv1-relay-deploy/README-SRV1.md`).
- Мониторинг ntfy (был на VPS#1): **выключен** (`systemctl disable --now tg-relay-monitor.timer`), спамил из-за плавающих DC; `relay-watch.sh` возможно битый (^C при записи). Требуется пересборка на 3 endpoint'а с антиспамом (план: ALERT_AFTER=3, ALERT_COOLDOWN_SEC=1800, warning если один relay умер, critical если оба/все; DC203 critical если недоступен отовсюду; + контроль срока cert relay3).

## 4. Хронология: запросы пользователя → выполненные работы

### Ранние сессии (до текущего чата)
1. **«Telegram не работает по мобильному Билайну, хочу своё решение»**
   → Форк приложения, подняты приватные relay (Go `tg-private-relay` за Caddy) на своих VPS.
2. **«Добавь тесты приватного relay в приложение»**
   → v0.2.0–v0.2.1: `NetworkDiagnostics.kt` переписан (Format 2), пробы: `/healthz`, `/readyz`,
   `/probe` (WSS), `/apiws?dc=1..203`, HTTPS download, WSS stream 1/10/50 MiB.
3. **«Перестали грузиться медиа в публичных каналах»** (первый случай)
   → Найден корень: в native `config.rs` подмена `DC203 -> DC2` (DC_OVERRIDES) ломала медиа-маршрут
   (публичные видео, реакции, custom emoji идут через DC203/CDN). Фикс `DC203 -> DC203`
   (v0.2.2 patch, затем чистая v0.2.3). Подтверждено логами relay (появились dc=203/4/5/1) и
   сообщением пользователя «да! все прекрасно загружается!».
4. **«Хочу failover на второй VPS»**
   → v0.2.4 `relay-failover` (versionCode 8): домены relay как список (`, ; пробел`), до 8 штук,
   цикл перебора в `private_relay_acquire_ws` (`src/proxy.rs`), валидация в Kotlin/Rust,
   строка-подсказка в настройках. Токен пока один на все домены.

### Текущая сессия (2026-09-14)
5. **«Снова отвалились медиа на обоих VPS, хочу запустить службу на другом VPS — что делать?»**
   → Диагноз: это НЕ приложение, а плавающий TCP-маршрут VPS→DC203 (91.105.192.100:443) у хостеров
   (`/readyz` на VPS#1 периодически 503, DC203 FAIL/OK). Написан `RUNBOOK-relay-na-novom-VPS.md`:
   сначала проверка маршрутов на новом VPS (15 прогонов), затем деплой.
6. **«На этом VPS развёрнута 3x-ui панель, её трогать нельзя. Домена нет»** + выводы: все DC OK 15/15,
   443 занят `xray-linux-amd64`, 80 — nginx, docker при установке «daemon not running»
   → Решение: (а) TLS-фронт relay вынести на порт **8443**; (б) домен не нужен «на VPS» —
   A-запись `relay3.ageevgroup.ru` → 130.49.141.28 в DNS-зоне; (в) сертификат — ручной DNS-01
   (acme.sh), т.к. 80/443 заняты; (г) собрана **v0.2.5-relay-port** (versionCode 9) с поддержкой
   `домен:порт` в списке relay (Rust `ws.rs` split_host_port, валидация `lib.rs`/`ProxyController.kt`,
   диагностика зондирует relay на его порту); (д) подготовлен бандл `srv1-relay-deploy/`
   (compose с Caddy только на 8443 + Caddyfile с `tls /etc/caddy/certs/...` + README-SRV1.md).
7. **«Не могу скачать APK из workspace. Что закинуть на GitHub, чтобы GitHub сам собирал APK? Заархивируй исходники»**
   → Причина пропажи: лимит снапшота workspace ~128 МБ (накопились старые APK + тулчейны).
   APK пересобран и выложен заново. Сделан **github-ready** репозиторий:
   - `.github/workflows/android-build.yml` — CI: JDK17 + SDK + NDK 27.2 + Rust + cargo-ndk →
     сборка `.so` (обе архитектуры) → `assembleUniversalDebug` → артефакт; на теге `v*` — Release с APK.
   - `keystore/debug.keystore` закоммичен в репо + `build.gradle.kts` использует его для debug-подписи:
     CI-сборки подписаны тем же сертификатом (SHA-256 `F8:7E:2B:3C:38:5C:4E:68:0B:12:E9:5E:E8:42:74:B5:D7:E7:C8:4C:1F:61:8D:B2:9D:FB:9B:C6:D4:15:F6:61`),
     что и локальные → APK с GitHub ставится поверх установленного без удаления данных.
   - `CI-BUILD.md` — инструкция по пушу и запуску. `.gitignore` обновлён (keystore НЕ игнорируется — так задумано).
   - Старые APK (v0.1.2–v0.2.3) из workspace удалены для экономии лимита.
8. **«Планирую новый чат для работы с GitHub-репозиторием. Заархивируй всё, что нужно новому чату,
   перепиши все мои запросы и работы в ридми»**
   → Этот архив `tg-project-handover.zip` (состав в §11).

## 5. Ключевые технические выводы (проверено)

- **Сота Билайн**: прямой TCP к Telegram (149.154.167.x) — блокируется (SocketTimeout);
  Cloudflare TLS (в т.ч. speed.cloudflare.com) — зависает; обычный HTTPS (ya.ru) и приватный
  VPS по WSS/TLS 443 — работают. Вывод: только собственный relay на «обычном» TLS.
- **DC203 (91.105.192.100:443)** обязателен: публичные каналы/видео/реакции/custom emoji идут
  через него. Подмена DC203→DC2 ломает медиа (в `config.rs` DC_OVERRIDES остаётся только для
  legacy CF/direct веток, для relay — `let relay_dc = dc;`).
- **Нестабильность хостеров**: маршрут VPS→DC203 может плавать сутками (FAIL/OK) — лечится
  выбором VPS с чистым маршрутом (srv1 проверен: 15/15) и failover-списком в приложении.
- **Порт в relay-домене**: поддержан с v0.2.5 (`домен:порт`), SNI/Host без порта, коннект на порт.
- Логи relay `result=error` — грубая классификация (часто это нормальное закрытие соединения).
  Улучшение (client_closed/upstream_closed/timeout) — в бэклоге.
- На Vivo вкладка логов приложения не работает (logcat pid-фильтр пуст) — в бэклоге встроенные логи.

## 6. Текущее состояние (на 2026-09-14)

- **Приложение**: актуальная версия **v0.2.5-relay-port** (versionCode 9), package
  `com.tgwsproxy.networklab`, minSdk 24 / target 35, arm64-v8a + armeabi-v7a, debug-подпись
  (ключ в `keystore/debug.keystore`). APK: `apk/TG-WS-Proxy-Lab-v0.2.5-relay-port-universal-debug.apk`.
- **Репозиторий**: готов к пушу на GitHub (`tg-ws-proxy-android/` в архиве, без `.git` — сделать
  `git init`), CI настроен и самодостаточен. Релизы: пуш тега `vX.Y.Z` → Release с APK.
- **Сервер relay**: в архиве две версии —
  - `tg-private-relay/` — исходники **0.1.2** (добавлен `/probe-upload`, версия строк 0.1.2). НЕ развёрнута.
  - `relay-versions/tg-private-relay-v0.1.1-media-test-source.zip` — **та, что развёрнута на VPS#1/#2**.
  Endpoints сервера: `/healthz`, `/readyz` (TCP-проверки DC1/2/3/4/5/203, 503 если degraded),
  `/probe` (WSS), `/probe-stream?bytes=` (WSS download), `/probe-upload?bytes=` (только 0.1.2),
  `/download?bytes=`, `/media-test` (HTML), `/apiws?dc=&token=` (основной relay).
- **Пользователь ещё не сделал**: пуш репо на GitHub; установку/тест v0.2.5; деплой relay3 на srv1
  (DNS → acme.sh DNS-01 → Caddy:8443 → домен в приложении `mg.ageevgroup.ru, pokehero.ru, relay3.ageevgroup.ru:8443`).
- **Известные незакрытые хвосты**: `.env` на VPS#1 мог быть испорчен sed-ом (проверить
  `grep ^RELAY_DOMAIN ~/tg-probe/tg-private-relay/.env` → должно быть `mg.ageevgroup.ru`);
  `docker update --restart=no mtproto-proxy` на VPS#2; ntfy-мониторинг выключен и требует пересборки.

## 7. Ближайшие шаги (приоритет)

1. Пуш репо на GitHub (приватный), первая CI-сборка, скачивание артефакта — убедиться, что APK
   с CI ставится поверх текущего (подпись совпадает).
2. Тест v0.2.5 на телефоне: домены `mg.ageevgroup.ru, pokehero.ru, relay3.ageevgroup.ru:8443`,
   медиа в публичных каналах, аварийный тест failover (`docker compose stop caddy` на VPS#1 →
   прокси должен переехать на следующий домен → `start caddy`).
3. Деплой relay3 на srv1 по `srv1-relay-deploy/README-SRV1.md` (DNS → cert → compose → readyz → домен в приложении).
4. Проверить порт 8443 с соты Билайн (вкладка диагностики v0.2.5, блок «Private relay»
   `relay3.ageevgroup.ru-8443`). Если порт душится — перевесить на 2053/2083/5443 (правка
   compose+Caddyfile+строки в настройках приложения).
5. Хвосты: `.env` VPS#1, `docker update --restart=no mtproto-proxy` на VPS#2.
6. Пересобрать ntfy-монитор: 3 endpoint'а (`/readyz`), антиспам (ALERT_AFTER=3,
   ALERT_COOLDOWN_SEC=1800), DC203-critical, алерт на срок сертификата relay3 (<14 дней).
7. Бэклог: пары домен+токен в приложении (разные токены на relay); классификация логов relay;
   встроенные логи в приложение (без logcat, Vivo); развернуть server 0.1.2 (нужен для пробы
   «Relay WSS upload 50 MiB» в диагностике — против 0.1.1 эта проба будет FAIL, это не ошибка).

## 8. Сборка APK

### Основной путь — GitHub Actions (рекомендуется)
Пуш в репо → Actions → «Build Android APK» → Run workflow → артефакт `TG-WS-Proxy-Lab-debug-apk`.
Или тег `vX.Y.Z` → Release. Подробности в `tg-ws-proxy-android/CI-BUILD.md`.
Версия меняется в `app/build.gradle.kts` (`versionCode`/`versionName`).

### Резервный путь — сборка в песочнице (проверенный рецепт)
Память песочницы ~1.9 ГБ, swap нет, /tmp — tmpfs 993 МБ. Порядок:

```sh
# JDK17 (в /tmp, удалить после сборки!)
curl -L --fail -o /tmp/jdk17.tar.gz 'https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse'
mkdir -p /tmp/jdk17 && tar -xzf /tmp/jdk17.tar.gz -C /tmp/jdk17 --strip-components=1 && rm /tmp/jdk17.tar.gz

# Android SDK — ТОЛЬКО в /home/user (не в /tmp!)
curl -L --fail -o /tmp/ct.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
mkdir -p /home/user/android-sdk-build/cmdline-tools && cd /home/user/android-sdk-build/cmdline-tools
unzip -q /tmp/ct.zip && mv cmdline-tools latest && rm /tmp/ct.zip
export JAVA_HOME=/tmp/jdk17 PATH=/tmp/jdk17/bin:$PATH
SDKM=/home/user/android-sdk-build/cmdline-tools/latest/bin/sdkmanager
yes | $SDKM --sdk_root=/home/user/android-sdk-build --licenses
$SDKM --sdk_root=/home/user/android-sdk-build "platforms;android-35" "build-tools;35.0.0" "platform-tools" "ndk;27.2.12479018"
echo "sdk.dir=/home/user/android-sdk-build" > local.properties   # в корне репо

# Rust + cargo-ndk (артефакты сборки вынести из /tmp!)
curl -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal
source ~/.cargo/env
rustup target add aarch64-linux-android armv7-linux-androideabi
export CARGO_BUILD_BUILD_DIR=/home/user/cargo-build   # иначе /tmp переполнится
cargo install cargo-ndk --locked

# Native библиотеки
cd <репо>
export ANDROID_NDK_HOME=/home/user/android-sdk-build/ndk/27.2.12479018
cargo ndk -t arm64-v8a --platform 24 -o app/src/main/jniLibs build --release
cargo ndk -t armeabi-v7a --platform 21 -o app/src/main/jniLibs build --release

# Gradle (демон может падать по OCM/OOM — ПОВТОРЯТЬ 2–3 раза, инкрементально дотягивает)
chmod +x ./gradlew
JAVA_HOME=/tmp/jdk17 ANDROID_HOME=/home/user/android-sdk-build ANDROID_SDK_ROOT=/home/user/android-sdk-build \
  PATH=/tmp/jdk17/bin:$PATH ./gradlew :app:assembleUniversalDebug --no-daemon --no-configuration-cache \
  -Dorg.gradle.jvmargs='-Xmx900m -Dfile.encoding=UTF-8 -XX:+UseSerialGC -XX:MaxMetaspaceSize=512m' \
  -Dorg.gradle.workers.max=1
# APK: app/build/outputs/apk/universal/debug/app-universal-debug.apk
```

Грабли (уже учтены в репо, но помни):
- `gradle.properties` — low-memory (daemon off, parallel off, workers 1, in-process Kotlin).
- `app/build.gradle.kts`: `isMinifyEnabled=false` (R8 падает по OOM).
- `material-icons-extended` заменён на `material-icons-core` (иконки в UI — только из core).
- Не держать большие тулчейны/APK в `/home/user` постоянно: лимит снапшота workspace ~128 МБ —
  лишнее выкидывается МОЛЧА (однажды из-за этого «пропал» готовый APK).
- Подпись: debug-ключ хранится в `keystore/debug.keystore` (storepass `android`, alias
  `androiddebugkey`) — в репо НАМЕРЕННО, обеспечивает одинаковую подпись CI/локальных сборок.

## 9. Сервер tg-private-relay

- Go, один файл `cmd/relay/main.go` (+ `compose.yaml`, `Caddyfile`, `Dockerfile`, `.env.example`,
  `scripts/check-vps.sh`, `scripts/prepare.sh`). Секреты только в `.env` (RELAY_DOMAIN, RELAY_TOKEN).
- Деплой: `scp` zip → `unzip` в `~/tg-private-relay` → создать `.env` → `docker compose up -d --build`
  → `curl https://<домен>/healthz` и `/readyz`. На VPS#1 путь `~/tg-probe/tg-private-relay`.
- Проверка маршрутов до Telegram с любого VPS: `scripts/check-vps.sh` (TCP DC1..DC5+203).
- compose: relay read_only, cap_drop ALL, no-new-privileges; caddy 443 (srv1: 8443) + монтирует Caddyfile.
- Логи сессий: `session=... dc=... duration=... up=... down=... result=...`.

## 10. Состав архива

```
tg-project-handover/
├── HANDOVER.md                          ← этот документ
├── apk/
│   ├── TG-WS-Proxy-Lab-v0.2.5-relay-port-universal-debug.apk   (актуальный, versionCode 9)
│   └── SHA256SUMS.txt                   ← хеши всех файлов архива
├── tg-ws-proxy-android/                 ← репо для GitHub (готов к git init/push, CI внутри)
├── tg-private-relay/                    ← исходники сервера 0.1.2 (на VPS сейчас 0.1.1)
├── relay-versions/
│   ├── tg-private-relay-v0.1.1-media-test-source.zip   (развёрнутая версия)
│   └── tg-private-relay-v0.1.2-upload-test-source.zip
├── srv1-relay-deploy/
│   ├── compose.yaml                     (Caddy только на 8443, серт из ./certs)
│   ├── Caddyfile                        (tls /etc/caddy/certs/..., reverse_proxy relay:8080)
│   └── README-SRV1.md                   ← пошаговый деплой relay3 (DNS→cert→.env→run→приложение)
└── RUNBOOK-relay-na-novom-VPS.md        ← общий чеклист «новый VPS под relay»
```

## 11. Версии приложения и артефакты

| Версия | versionCode | Что сделано | Статус |
|--------|-------------|-------------|--------|
| 0.1.2 | 2 | базовая лаборатория сети | история |
| 0.2.0 | 3 | Private Relay (WSS к своему серверу) | история |
| 0.2.1 | 5 | тесты relay в диагностике (healthz/readyz/probe/apiws/download/stream) | история |
| 0.2.2 | (patch) | DC203→DC203 фикс, so пересобран | история |
| 0.2.3 | 7 | чистая стабильная после DC203-фикса | история |
| 0.2.4 | 8 | failover: список доменов до 8, общий токен | установлена у пользователя |
| **0.2.5** | **9** | **домен:порт в списке relay + диагностика на порту + GitHub CI + keystore в репо** | **актуальная** |

SHA-256 актуального APK и всего архива — в `apk/SHA256SUMS.txt`.

## 12. С чего начать новому чату

1. Прочитать этот файл и `srv1-relay-deploy/README-SRV1.md`.
2. Спросить у пользователя статус: пушнут ли репо, стоит ли v0.2.5, сделан ли relay3.
3. Дальше — по чеклисту §7.
