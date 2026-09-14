# AGENTS.md — точка входа в проект (читать первым)

> Обновлено: 2026-09-14 по `tg-project-handover/HANDOVER.md` (первоисточник контекста).
> Если что-то ниже расходится с кодом — верьте коду и правьте этот файл.

## Что это за проект

Android-приложение **TG WS Proxy Lab** (форк `amurcanov/tg-ws-proxy-android`, upstream `Flowseal/tg-ws-proxy`)
+ **персональный WSS-релей** на своих VPS. Цель: рабочий Telegram на соте **Билайн (Калининград)**,
где прямой TCP до Telegram блокируется, а Cloudflare TLS зависает.

```text
Android (com.tgwsproxy.networklab)
  └─ локальный MTProto-прокси 127.0.0.1:1443 (Rust libtgwsproxy.so через JNI)
       └─ WSS/TLS :443 (или :8443 на srv1) ──► Caddy на VPS ──► Go relay :8080
            └─ raw MTProto TCP :443 ──► Telegram DC1..DC5 + DC203 (медиа/CDN)
```

- Релей — основной маршрут, Cloudflare — не использовать (не работает на соте).
- До 8 доменов relay через запятую (failover), общий токен; с v0.2.5 поддержан формат `домен:порт`.
- Секреты только в `.env` на VPS. В чат/отчёты токены и IP абонента не попадают.

## Порядок чтения

1. этот файл;
2. `tg-project-handover/HANDOVER.md` — полная хронология, инфраструктура, грабли, план;
3. `tg-private-relay/README.md` (сервер) и `README.md` (приложение);
4. `PRIVATE_RELAY.md`, `NETWORK_LAB.md`, `RUNBOOK-relay-na-novom-VPS.md`,
   `srv1-relay-deploy/README-SRV1.md` (решительно: деплой relay3 на srv1);
5. `.github/workflows/*.yml` — как собирается проект.

## Инфраструктура (из HANDOVER §3)

| # | Хост | IP | Роль / состояние |
|---|---|---|---|
| 1 | `mg.ageevgroup.ru` | `5.129.239.160` | relay №1, `~/tg-probe/tg-private-relay`, работает (relay **0.1.1** + Caddy 443). Маршрут к DC203 плавает |
| 2 | `pokehero.ru` | `147.45.152.7` | relay №2, работает (0.1.1 + Caddy 443). Хвост: `docker update --restart=no mtproto-proxy` |
| 3 | `srv1` | `130.49.141.28` | relay №3 (план). Маршруты 15/15 OK. 443 занят 3x-ui/xray (НЕ ТРОГАТЬ), 80 — nginx → TLS на **8443** + сертификат вручную acme.sh DNS-01 |

DNS релея — A-запись прямо на IP, в Cloudflare только **DNS only** (серая тучка).

## Версии и состояние

| Компонент | Версия / факт |
| --- | --- |
| Приложение | **v0.2.5-relay-port**, versionCode 9, `com.tgwsproxy.networklab`, minSdk 24 / target 35 |
| Сервер на VPS | **0.1.1** (`relay-versions/tg-private-relay-v0.1.1-media-test-source.zip`) |
| Сервер в handover (не развёрнут) | **0.1.2** (`tg-private-relay/`, добавлен `/probe-upload`) |
| Тулчейн приложения | AGP 9.0.1 / Gradle 9.1.0, JDK 17, compileSdk 35, NDK 27.2.12479018 |
| Токен relay | 32–256 символов, URL-safe; короче — ядро молча выключает релей |

Endpoints релея: `/healthz`, `/readyz` (TCP-проверки DC1..5+203, **503 если degraded**),
`/probe`, `/probe-stream?bytes=`, `/probe-upload?bytes=` (только 0.1.2), `/download?bytes=`,
`/media-test`, `/apiws?dc=&token=` (токен также принимается как `Authorization: Bearer`).

Приложение v0.2.5 проходит диагностику и по `/apiws` (это рабочий маршрут, не «только /probe»).
Проба «Relay WSS upload 50 MiB» против сервера 0.1.1 даёт FAIL — это **ожидаемо**, не баг.

## Сборка

- **CI:** `.github/workflows/build-apk.yml` (ядро из `src/` под оба ABI + `assembleRelease` для
  universal/arm64/arm32, артефакт `apk-<versionName>`, по тегу `v*` — Release) и
  `.github/workflows/relay.yml` (go vet/test + docker build + smoke-тест контейнера).
- **Вариант из handover:** `tg-project-handover/tg-ws-proxy-android/.github/workflows/android-build.yml`
  — `assembleUniversalDebug` + артефакт `TG-WS-Proxy-Lab-debug-apk`, сборка по тегу/вручную.
- **Локально:** `build_so.bat` → `build_apk.bat`.
- **Подпись:** чтобы APK с CI ставился **поверх** установленного, debug-сборка
  (`android-build.yml`) подписывается общим debug-ключом (SHA-256 `F8:7E:2B:3C:...:61`),
  который CI восстанавливает из секрета `DEBUG_KEYSTORE_BASE64`. В git ключ **не**
  коммитится (решение 2026-09-14: было «keystore в репо», стало «секреты»).
  Без секрета CI подписывает ключом раннера → «поверх» не встанет.
  Release (`build-apk.yml`): ключ из секретов `ANDROID_KEYSTORE_BASE64/_PASSWORD/_ALIAS/_PASSWORD`.

## Грабли (каждая уже стоила времени)

1. **`.gitignore` с правилом `relay` без слеша** молча исключает каталог `cmd/relay/` из git.
   Баг живёт в **двух** файлах: корневой `.gitignore` и `tg-project-handover/tg-private-relay/.gitignore`.
   Из-за него исходник сервера `cmd/relay/main.go` до сих пор отсутствует в репозитории как файл —
   он есть только внутри zip в `relay-versions/`. Менять на `/relay` (бинарник в корне) и
   после правки проверять `git status`/`git check-ignore`.
2. **Память Gradle:** в `gradle.properties` лимиты под слабую машину (`-Xmx1400m`,
   `MaxMetaspaceSize=384m`); на CI этого мало (`OutOfMemoryError: Metaspace`) — поднимать только в CI.
3. **`gradlew` терял бит запуска** (100644) — в CI падало на `./gradlew`.
4. **R8/minify выключены** (`isMinifyEnabled=false`) — иначе OOM; иконки только `material-icons-core`.
5. **DC203 (91.105.192.100:443) обязателен** — публичные каналы, видео, реакции, custom emoji.
   Подмена `DC203→DC2` ломала медиа; для relay нужно `let relay_dc = dc;`.
6. **Маршрут VPS→DC203 может плавать сутками** — лечится проверкой `./scripts/check-vps.sh`
   (15 прогонов) и failover-списком доменов в приложении.
7. **Порты:** srv1 использует 8443, сертификат продлевается вручную (DNS-01 acme.sh, раз в ~80 дней).
8. **Лимит workspace ~128 МБ** — лишние тулчейны/APK молча выкидываются из снапшота.
9. **Публичный репозиторий:** ключи подписи (`keystore/`, `*.keystore`, `release.keystore`)
   в git не коммитятся — в CI они восстанавливаются из секретов
   (`DEBUG_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_BASE64`). Не возвращать keystore в индекс.
   В доках есть реальные IP/домены VPS — для публичного репозитория это лишнее.

## Не коммитить / следить

`.env` (в нём `RELAY_TOKEN`), пароли и SSH-ключи, токены ntfy, личные IP абонента.
`target/`, `app/build/`, `local.properties` — только через `.gitignore`.

## Расхождение копий приложения (проверено 2026-09-14)

В репозитории две копии исходников приложения и они **не эквивалентны** (детали — в
`HANDOVER-COMPARE.md`): корневая копия новее по коду (поддержка `домен:порт`, v0.2.5),
`tg-project-handover/tg-ws-proxy-android/` новее по упаковке (CI `android-build.yml`,
`CI-BUILD.md`, `keystore/debug.keystore` + пиннинг debug-подписи в `app/build.gradle.kts`).
Расходятся всего 10 позиций, остальное совпадает побайтово.

В частности: `ProxyController.kt`, `NetworkDiagnostics.kt`, `res/values/strings.xml`,
`res/values-ru/strings.xml` — новее в корне; `app/build.gradle.kts`, `.gitignore`, keystore,
`CI-BUILD.md`, `.github/workflows/android-build.yml` — только/новее в handover.
Исходник сервера `cmd/relay/main.go` в текстовом виде отсутствует в обеих копиях —
он есть только внутри zip в `relay-versions/`.

## Статус и что дальше

- CI возвращён в репозиторий мержем ветки `arena/01a09fc8-tg-ws-proxy-adr`
  (`.github/workflows/build-apk.yml` + `relay.yml`, исполняемый `gradlew`,
  `target/` убран из индекса). Реконструкция `tg-private-relay/cmd/relay/main.go`
  заменена настоящим исходником **0.1.2** из `relay-versions/*.zip`
  (`/probe-upload`, `/readyz`→503 при degraded, `/media-test`). Debug-подпись —
  через секрет `DEBUG_KEYSTORE_BASE64`, keystore из git убран.
- Ближайшие шаги (HANDOVER §7): проверить, что CI-APK ставится поверх (совпадение подписи);
  тест v0.2.5 на телефоне с тремя доменами и failover; деплой relay3 на srv1; проверка порта 8443
  с соты; хвосты `.env` VPS#1 и `mtproto-proxy` на VPS#2; пересборка ntfy-монитора.
- Бэклог: пары домен+токен, классификация логов relay (`client_closed/upstream_closed/timeout`),
  встроенные логи в приложение (на Vivo вкладка логов пуста из-за logcat), развернуть сервер 0.1.2.

## Правила для агента

- Отвечать по-русски, давать точные команды и конфиги; секреты не запрашивать и не выводить.
- Перед правкой ядра (Rust) или релея (Go) свериться с `tg-project-handover/` и zip-версиями,
  не переписывать по догадкам.
- После значимых изменений обновлять этот файл и `HANDOVER.md`.