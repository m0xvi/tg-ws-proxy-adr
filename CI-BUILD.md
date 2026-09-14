# Сборка APK через GitHub Actions

GitHub собирает APK полностью из исходников: Rust-ядро (обе архитектуры) +
Gradle. В репозитории два workflow:

| Workflow | Файл | Что собирает | Подпись |
| --- | --- | --- | --- |
| Build APK | `.github/workflows/build-apk.yml` | `assembleRelease` × 3 флейвора (universal/arm64/arm32) | release-ключ из секретов, без них — debug-ключ раннера |
| Build Android APK (debug) | `.github/workflows/android-build.yml` | `assembleUniversalDebug` | общий debug-ключ из секрета — **ставится поверх установленного на телефоне** |

Плюс `relay.yml`: `go vet` + `go test` + `docker build` + smoke-тест серверного
релея `tg-private-relay/` (запускается при правках в нём).

Оба APK-workflow запускаются на каждый push, вручную (Actions → Run workflow)
и на теге `v*` (тогда же создаётся Release с APK).

## 1. Секреты подписи (один раз)

Без секретов сборки проходят, но APK подписываются ключом раннера и **не
встанут поверх** установленного на телефоне. Чтобы подпись совпадала:

1. Возьми свой общий `debug.keystore` (тот, которым подписан установленный
   APK; локальная копия лежит вне git) и закодируй:
   ```sh
   base64 -w0 debug.keystore > debug-keystore.b64
   ```
   (Windows PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("debug.keystore"))`)
2. GitHub → репозиторий → **Settings → Secrets and variables → Actions** →
   **New repository secret**:
   - имя `DEBUG_KEYSTORE_BASE64`, значение — содержимое `debug-keystore.b64`;
   - файл `debug-keystore.b64` после этого удали.
3. Для release-подписей в `build-apk.yml` — те же 4 секрета из README
   (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
   `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`).

Сами ключи и `.env` в git не коммитятся (см. `.gitignore`).

## 2. Запуск сборки

- Автоматически на каждый push, или вручную: **Actions** → нужный workflow →
  **Run workflow**.
- Готовый APK — внизу страницы запуска в блоке **Artifacts**
  (`TG-WS-Proxy-Lab-debug-apk` или `apk-<versionName>`), нужен вход в GitHub.
- Первая сборка занимает ~10–15 минут (Rust+Gradle без кешей).

## 3. Сверка подписи с установленной на телефоне

Отпечаток виден прямо на странице запуска (Checks → annotations,
`APK signer SHA-256: ...`) — скачивать APK не обязательно. Или скачай APK
из артефактов и сравни локально:

```sh
apksigner verify --print-certs TG-WS-Proxy-Lab-*.apk | grep SHA-256
```

Ожидается (сертификат установленного приложения):

```text
F8:7E:2B:3C:38:5C:4E:68:0B:12:E9:5E:E8:42:74:B5:D7:E7:C8:4C:1F:61:8D:B2:9D:FB:9B:C6:D4:15:F6:61
```

Совпал — APK встанет поверх без удаления данных. Не совпал — проверь, что
секрет `DEBUG_KEYSTORE_BASE64` задан и шаг «Restore shared debug keystore»
в логе отчитался об успехе.

## 4. Обновление версии

1. `app/build.gradle.kts` → `versionCode` +1, новый `versionName`.
2. Commit + push (или тег `v0.2.x`).
