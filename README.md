<div align="center">
  
  # Telegram WS Proxy Android
<br>
  <img src="https://img.shields.io/badge/Android-SDK_24--36-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android SDK">
  <img src="https://img.shields.io/badge/Rust-1.70+-000000?style=for-the-badge&logo=rust&logoColor=white" alt="Rust Version">
  <img src="https://img.shields.io/badge/Kotlin-Native-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin">
  <a href="https://github.com/amurcanov/tg-ws-proxy-android/stargazers">
    <img src="https://img.shields.io/github/stars/amurcanov/tg-ws-proxy-android?style=for-the-badge&logo=github&color=ffca28&labelColor=24292e" alt="Stars">
  </a>
</div>
<br>

**TG WS Proxy Android** — это локальный **MTProto-прокси** для Telegram на Android. Приложение помогает частично решать проблемы и в ряде сценариев ускоряет работу мессенджера, перенаправляя трафик через защищённые CloudFlare WebSocket-соединения или напрямую к датацентрам Telegram.

---

<img width="972" height="696" alt="MyCollages (5)" src="https://github.com/user-attachments/assets/7c9b9f2a-fc60-4aee-b93d-db950e24555c" />

## Возможности Android-версии

- **Современный UI/UX:** приложение полностью адаптировано под актуальный Android-интерфейс на базе Material 3 и Jetpack Compose. Основные действия доступны быстро и без перегруженных экранов.
- **Интеграция с Telegram:** кнопка **«Применить в Telegram»** автоматически передаёт прокси в совместимые клиенты через `tg://proxy` (AyuGram, Plus Messenger, NekoGram и другие).
- **Фоновый режим:** используется `Foreground Service`, уведомление о работе сервиса и дополнительная логика удержания соединения, чтобы Android не выгружал прокси слишком агрессивно.
- **Лог-вьюер:** встроенный просмотр событий в реальном времени помогает быстро понять, что происходит с подключением, маршрутом и пулом соединений.
- **Темы и палитры:** поддерживаются Dynamic Colors на Android 12+, а также встроенные палитры для более старых устройств.
- **Авто-обновления внутри приложения:** вручную проверять релизы больше не нужно — когда выйдет новая версия, приложение само покажет уведомление об обновлении.
- **Раздел «Информация»:** внутри приложения есть расширенная справка по настройкам, особенностям CloudFlare, пулу WS-соединений и ручной конфигурации датацентров.

---

## Как это работает

```text
Telegram Android → Локальный MTProto (по умолчанию 127.0.0.1:1443) → TG WS Proxy → WSS (через CloudFlare или напрямую) → Telegram DC
```

1. Приложение поднимает локальный MTProto-прокси средствами нативного движка на языке **Rust**.
2. Перехватывает подключения Telegram через локальный порт и сгенерированный секретный ключ.
3. Извлекает `DC ID` из исходного пакета и устанавливает защищённое WebSocket (`TLS`) соединение с нужным датацентром, при необходимости проксируя трафик через CloudFlare.
4. Использует пул соединений, keepalive-механику и fallback-сценарии для более устойчивой работы в реальных сетевых условиях.

## Быстрый старт

1. Скачайте актуальный `APK` со **[страницы релизов](https://github.com/amurcanov/tg-ws-proxy-android/releases)**.
2. Установите приложение на ваш Android-смартфон.
3. Откройте **TG WS Proxy Android**.
4. Ознакомьтесь со справкой внутри приложения.
5. Нажмите **«Запустить прокси»** — появится уведомление о работе в фоновом режиме.
6. Нажмите **«Применить в Telegram»** — откроется Telegram-клиент, где останется только подтвердить подключение.

---

# 🎦 Видео гайд по установке и использованию

<div align="center">

<img width="1376" height="768" alt="578516258-6b2df494-de8d-44a2-a281-389fc7551a7c" src="https://github.com/user-attachments/assets/ed1449d4-0a14-4b46-8f35-b787bdee3e32" />

<br><br>

[**Смотреть на YouTube**](https://youtu.be/RP4RwyEHpwc) | [**Смотреть в Telegram**](https://t.me/avencoreschat/506796)

</div>

---


* **Краши и проблемы с установкой:** если у вас возникают сбои, вылеты или ошибки при установке, пожалуйста, сохраняйте отчёты и ссылки на них. Также ознакомьтесь с блоком `NOTE` ниже и поднимайте полноценные `issue` с полезной технической информацией.


> [!NOTE]
> ### Отчёты об ошибках
> Приложение адаптировано под мобильные сети, однако проблемы с фоновой работой всё ещё возможны из-за системных ограничений или сети.
>
> Если у вас возникла проблема, сбой или вопрос, пожалуйста, нажмите кнопку **«Собрать отчёт»** внутри приложения и приложите полученные данные к вашему `issue`. Мелкие ошибки в логах при нормально работающем прокси можно игнорировать.

---

## Сборка APK на GitHub Actions

В репозитории есть workflow [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml) — GitHub собирает APK полностью из исходников, без локального окружения.

Когда запускается:

- на каждый `push` в любую ветку;
- на `push` тега вида `v*` (например `v0.2.5-relay-port`);
- вручную: вкладка **Actions** → **Build APK** → **Run workflow**;
- на pull request.

Что делает workflow:

1. `native-lib` — ставит Rust-тулчейн и Android NDK, собирает `src/` в `libtgwsproxy.so` под `arm64-v8a` (minSdk 24) и `armeabi-v7a` (minSdk 21) через `cargo-ndk`, проверяет архитектуру через `readelf` и отдаёт библиотеки артефактом `jniLibs`.
2. `apk` — ставит JDK 17 и Android SDK (platform `android-35`, build-tools `36.0.0`), подкладывает собранные `.so` в `app/src/main/jniLibs`, подписывает (если настроены секреты) и запускает `./gradlew assembleRelease`.

Результат: артефакт `apk-<versionName>` с тремя файлами, имена как у локального скрипта `build_apk.bat`:

```text
v<versionName>-android-universal.apk     все архитектуры
v<versionName>-android-v8a-minsdk24.apk  только 64-bit ARM
v<versionName>-android-v7a-minsdk21.apk  только 32-bit ARM
```

Плюс `SHA256SUMS.txt`. При пуше тега `v*` эти же файлы автоматически прикладываются к GitHub Release.

### Подпись release-ключом (необязательно)

Без секретов APK подписывается debug-ключом: он ставится на телефон, но не подойдёт для обновления поверх сборки с другим ключом. Чтобы подписывать своим ключом, создайте ключ и добавьте в **Settings → Secrets and variables → Actions** четыре секрета:

```bash
keytool -genkeypair -v -keystore release.keystore -alias tgwsproxy \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.keystore > keystore.b64   # содержимое файла -> секрет
```

| Секрет | Значение |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | содержимое `keystore.b64` |
| `ANDROID_KEYSTORE_PASSWORD` | пароль keystore |
| `ANDROID_KEY_ALIAS` | `tgwsproxy` (или свой alias) |
| `ANDROID_KEY_PASSWORD` | пароль ключа |

### Debug-сборка поверх установленной (android-build.yml)

Второй workflow [`.github/workflows/android-build.yml`](.github/workflows/android-build.yml)
собирает один `assembleUniversalDebug` и подписывает его **общим debug-ключом**
из секрета `DEBUG_KEYSTORE_BASE64` — такой APK ставится поверх установленного
на телефоне без удаления данных. Без секрета сборка тоже проходит, но подпись
будет чужой (ключ раннера). Подробности и сверка отпечатка — в
[`CI-BUILD.md`](CI-BUILD.md). Ключи в git не коммитятся.

Локальная сборка (`build_so.bat` + `build_apk.bat`) продолжает работать как раньше: workflow читает те же `versionName` из `app/build.gradle.kts` и раскладывает APK по той же схеме имён.

## Лицензия

Этот форк распространяется под лицензией **GPLv3**. Оригинальный код `tg-ws-proxy` от [Flowseal](https://github.com/Flowseal) доступен под лицензией **MIT**.
