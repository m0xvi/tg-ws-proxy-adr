# Сборка APK через GitHub Actions

GitHub сам собирает APK: Rust (обе архитектуры) + Gradle + подпись тем же
debug-ключом из `keystore/debug.keystore` (он лежит в репо нарочно, это не секрет).

## 1. Создать репозиторий и запушить

На https://github.com/new создай **приватный** репозиторий (например
`tg-ws-proxy-android`), БЕЗ README/.gitignore/licence.

```sh
unzip TG-WS-Proxy-Lab-v0.2.5-github-ready-source.zip -d tg-ws-proxy-android
cd tg-ws-proxy-android
git init -b main
git add -A
git commit -m "v0.2.5-relay-port: relay домены с портом + GitHub Actions CI"
git remote add origin https://github.com/ВАШ_ЛОГИН/tg-ws-proxy-android.git
git push -u origin main
```

## 2. Запустить сборку

- Вручную: GitHub → твой репо → **Actions** → **Build Android APK** →
  **Run workflow** → ветка `main` → Run.
- Готовый APK появится на странице запуска внизу в блоке **Artifacts**
  (`TG-WS-Proxy-Lab-debug-apk`), скачивается по клику (нужен вход в GitHub).

Или через тег (тогда ещё создастся Release с APK в разделе Releases):

```sh
git tag v0.2.5
git push origin v0.2.5
```

## 3. Обновление версии

1. `app/build.gradle.kts` → `versionCode` +1, новый `versionName`.
2. Commit + push (или тег `v0.2.x`).

## Примечания

- Подпись CI-сборок совпадает с локальными: debug-ключ один и тот же
  (Cert SHA-256 `F8:7E:2B:3C:38:5C:4E:68:0B:12:E9:5E:E8:42:74:B5:D7:E7:C8:4C:1F:61:8D:B2:9D:FB:9B:C6:D4:15:F6:61`),
  поэтому APK с GitHub ставится поверх установленного приложения без удаления.
- Репозиторий держи приватным: он содержит твои рабочие правки приложения.
- Первая сборка занимает ~10–15 минут (Rust+Gradle без кешей), последующие ~5–8.
