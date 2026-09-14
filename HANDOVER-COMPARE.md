# Сверка копий приложения: корень репозитория ↔ `tg-project-handover/tg-ws-proxy-android/`

Дата: 2026-09-14. Метод: сравнение SHA блобов git (одинаковый SHA = файлы побайтово идентичны).
Сравнивались:
- **root** — коммит `e7ca954` (корень `main`, он же корень текущей рабочей копии);
- **handover** — `main:tg-project-handover/tg-ws-proxy-android/` (коммит `f6a3bd88`).

Итог: 90+ файлов идентичны, расходятся **10 позиций**. При этом расхождения направленные:
**root новее по коду (поддержка портов v0.2.5), handover новее по упаковке (CI + keystore).**
Ни одна из копий не является полной.

---

## 1. Идентичны побайтово (можно не проверять)

| Группа | Файлы |
| --- | --- |
| Rust-ядро | `src/*.rs` (все 7: balancer, cfproxy, config, crypto, lib, proxy, ws) — дерево `bb686a25` |
| Старый Go | `old_src_golang/tg-ws-proxy.go` (`0f72cdd2`) |
| Корневые | `Cargo.lock`, `Cargo.toml`, `LICENSE`, `NETWORK_LAB.md`, `PRIVATE_RELAY.md`, `README.md`, `build.gradle.kts`, `build_apk.bat`, `build_so.bat`, `gradle.properties`, `gradlew.bat`, `settings.gradle.kts` |
| Gradle wrapper | `gradle/wrapper/gradle-wrapper.jar` (`8bdaf60c`), `…properties` (`2e111328`) |
| Kotlin (9 из 11) | `AppUpdate`, `BootReceiver`, `LogEntry`, `MainActivity`, `NativeProxy`, `ProxyService`, `ProxyTilePreferencesActivity`, `ProxyTileService`, `SettingsStore` |
| Kotlin UI (весь пакет) | `app/src/main/java/.../ui/*` — дерево `11beaaa2` |
| Прочее | `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`, `res/drawable/*`, `res/mipmap-anydpi*/*`, `res/values/colors.xml` (`927922ea`), `res/values/themes.xml` (`a9c6432f`) |

Отдельно: `gradlew` — **в обеих копиях** содержимое одинаковое (`61b77d7f`), но режим `100644`,
то есть **не исполняемый**. Правку `chmod +x` нужно применить к выбранной копии.

---

## 2. Расхождения (10 позиций)

| # | Файл | root (`e7ca954`) | handover | Что это значит |
| --- | --- | --- | --- | --- |
| 1 | `app/build.gradle.kts` | `67f14095` | `2de512b9` | В handover есть блок `signingConfigs.getByName("debug") { storeFile = rootProject.file("keystore/debug.keystore") }` — пиннинг debug-ключа. Остальное (versionCode 9, versionName `0.2.5-relay-port`, флейворы, зависимости, minify off) совпадает |
| 2 | `keystore/debug.keystore` | **нет** | есть, 2618 Б, блоб `b0cc99d3` | Ключ, обеспечивающий одинаковую подпись локальных и CI-сборок (APK ставится поверх). Без него CI подписывает своим ключом раннера |
| 3 | `app/src/main/java/.../ProxyController.kt` | `f96442dd` (7343 Б) | `0fcf44ee` | **root новее.** В root есть `relayHostPortRegex`, `normalizeRelayHostPort`, `splitRelayHostPort` и валидация порта; в handover порт отрезается `.substringBefore(':')` и не валидируется → формат `домен:8443` не поддерживается |
| 4 | `NetworkDiagnostics.kt` | `806b6504` (44 893 Б) | `cd7e9449` (44 047 Б) | **root новее.** В root `splitRelayHostPort` используется в диагностике (проба relay на его порту) |
| 5 | `res/values/strings.xml` | `6aef1248` (12 274 Б) | `386aee79` (12 253 Б) | Подсказка в root: «…optional **:port**, e.g. relay1.example.com, relay2.example.com**:8443**»; в handover порта нет |
| 6 | `res/values-ru/strings.xml` | `4fbc9d1a` (16 444 Б) | `dd48f2f9` (16 422 Б) | То же на русском (упоминания порта/домена) |
| 7 | `app/src/main/jniLibs/**` | arm64 `33a59528` (2 083 760 Б), v7a `9ca51b27` (1 360 936 Б) | arm64 `c56bb268` (2 084 048 Б), v7a `cac9f9e6` (1 356 792 Б) | **Разные сборки** ядра (размеры отличаются). Какую пару считать эталонной — вопрос; CI всё равно пересобирает из `src/` |
| 8 | `.gitignore` (корень) | `6cc7f76f` (17 Б: `.env`, `*.log`, `relay`) | `cb179c24` (430 Б) | У handover правильный набор: `.gradle/`, `build/`, `app/build/`, `local.properties`, `.idea/`, `*.iml`, `.kotlin/`, `.cxx/`, `target/`, `*.log` + комментарий про keystore. В обоих вариантах **опасное правило `relay` без слеша** отсутствует только у handover — но оно есть в корневом и в `tg-project-handover/tg-private-relay/.gitignore` |
| 9 | `CI-BUILD.md` | **нет** | есть, 2221 Б (`121f136b`) | Инструкция по пушу и запуску CI |
| 10 | `.github/workflows/android-build.yml` | **нет** (в корне вообще нет `.github/`) | есть, 2456 Б (`9509d47c`) | CI: `assembleUniversalDebug` + артефакт `TG-WS-Proxy-Lab-debug-apk`, Release по тегу |

Дополнительно только в handover: `apk/TG-WS-Proxy-Lab-v0.2.5-relay-port-universal-debug.apk` +
`apk/SHA256SUMS.txt`, `relay-versions/*.zip`, `srv1-relay-deploy/*`, `tg-private-relay/*`
(последние три в корне тоже есть — см. отдельную сверку ниже).

---

## 3. Серверная часть: где настоящие исходники

- `tg-project-handover/tg-private-relay/` — **без `cmd/`**: та же ошибка `.gitignore` (правило
  `relay` без слеша, файл `.gitignore` внутри каталога — 3 строки). Текстового `cmd/relay/main.go`
  в репозитории по-прежнему нет ни в одной копии.
- Реальные исходники лежат **только внутри zip**:
  - `relay-versions/tg-private-relay-v0.1.1-media-test-source.zip` — **развёрнута** на VPS#1/#2;
  - `relay-versions/tg-private-relay-v0.1.2-upload-test-source.zip` — 0.1.2, добавлен `/probe-upload`.
- Значит `tg-private-relay/cmd/relay/main.go` в корне — **реконструкция автора-агента**, а не оригинал.
  Известные отличия реконструкции от оригиналов: нет `/media-test`; `/readyz` отдаёт 200 при degraded
  (в оригинале — 503); в 0.1.2 есть `/probe-upload`.
  Действие: распаковать zip и положить настоящий файл, сверив с текущим.

---

## 4. Рецепт слияния (для новой сессии)

База — **root-копия** (в ней актуальный v0.2.5-код с поддержкой портов). Из handover-копии добавить:

1. `keystore/debug.keystore` + блок `getByName("debug")` в `app/build.gradle.kts` (пункты 1–2);
2. `CI-BUILD.md` (п. 9);
3. выбрать один CI: `android-build.yml` из handover (`assembleUniversalDebug`, debug-подпись,
   совместима с установленным APK) **или** `build-apk.yml` из ветки `arena/01a09fc8-tg-ws-proxy-adr`
   (`assembleRelease` × 3 флейвора, подпись release-ключом из секретов);
4. `.gitignore` из handover (п. 8) — вместо корневого, и починить правило `relay` → `/relay`
   в `tg-project-handover/tg-private-relay/.gitignore`;
5. решить судьбу бинарников в git: `apk/*.apk` (≈12 МБ), `relay-versions/*.zip`, `jniLibs/*.so`,
   `target/`. Для CI они не нужны — ядро собирается из `src/`;
6. пересобрать `.so` (или выбрать одну пару из п. 7) и проверить, что подпись APK совпадает
   с той, что стоит на телефоне (иначе установка «поверх» не пройдёт).

Также напомнить: `main` сейчас — 3 коммита (`e7ca954` → `f6a3bd88` → `b985a2f`), вся работа из
PR #1 (`.github/workflows/build-apk.yml`, `relay.yml`, `cmd/relay/main.go`, правки `.gitignore`,
`chmod +x gradlew`, раздел про CI в README) в `main` **отсутствует** и живёт в ветке
`arena/01a09fc8-tg-ws-proxy-adr`. Файл `AGENTS.md` в `main` содержит текст workflow `build-apk.yml`
(подтверждено: коммит `b985a2f` добавил 273 строки YAML) — заменить на текст документа.

---

## 5. Безопасность (проверено в этой сверке)

- Репозиторий **публичный**, а `keystore/debug.keystore` закоммичен намеренно. Следствие: любой
  может подписать APK, который Android примет как обновление установленного приложения
  (при совпадающем `applicationId` `com.tgwsproxy.networklab`). Решение: оставить (удобство),
  убрать ключ и подписывать в CI секретами, или сделать репозиторий приватным.
- В документах есть реальные IP/домены VPS и их роли — для публичного репозитория это лишнее.