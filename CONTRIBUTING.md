# Разработка TgWatch

TgWatch наблюдает доступность Telegram без аккаунта, телефона и доступа к переписке. Перед изменением поведения прочитайте [README](README.md), [карту архитектуры](docs/superpowers/architecture.md) и [проект 1.10](docs/superpowers/specs/2026-10-10-portability-and-release-design.md).

## Сборка и локальные проверки

Нужны JDK 17, Android SDK Platform 35, Build Tools 35.0.0 для CI-команд, Python 3 и принятые лицензии Android SDK. Kotlin/AGP и Gradle закреплены в репозитории: используйте Gradle Wrapper. Укажите SDK через `ANDROID_HOME` или локальный `local.properties` с `sdk.dir`; этот файл не коммитится.

```bash
python3 -m pip install -r scripts/requirements-release.txt
python3 -m unittest discover -s scripts/tests -v
bash ./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease generateReleaseSbom
```

`assembleRelease` без signing environment создаёт неподписанный APK для проверки сборки. Локальная 1.10 имеет versionCode 11; в CI код вычисляется как `GITHUB_RUN_NUMBER + 100` с проверкой диапазона. Для production signing нужны `TGWATCH_KEYSTORE_PATH`, `TGWATCH_KEYSTORE_PASSWORD`, `TGWATCH_KEY_ALIAS`, `TGWATCH_KEY_PASSWORD`. Передавайте значения через защищённое окружение, не через историю команд или git. Debug APK предназначен для тестов.

JVM-тесты JSON используют отдельную test dependency; Android использует системный `org.json`. Проверяйте строгие типы, границы, UTF-8/JSON и повреждённые данные, а не платформенные coercions. Изменение поведения должно иметь meaningful regression: RED с ожидаемым assertion failure, затем GREEN.

## Проверки Android

Instrumentation покрывает Android-хранилище, настройки, widget state и адаптеры восстановления. Полный сценарий запускайте только на отдельном root-capable эмуляторе API 29 или 35:

```bash
bash scripts/android-smoke.sh
```

Сценарий устанавливает APK, заменяет тестовые данные, меняет подключение и режим сна, устанавливает PIN и перезагружает эмулятор. Не запускайте его на личном телефоне. Отчёты и скриншоты находятся в `app/build/reports/androidTests/`; журнал — в `smoke-logcat.txt`. Проверка свежести требует строку истории с timestamp новой опубликованной проверки, а не произвольную непустую историю.

Перед публикацией `scripts/signed-upgrade-smoke.sh <previous.apk> <current.apk>` отдельно проверяет обновление настоящего опубликованного APK, настройки, историю и свежие наблюдения. Тестовая миграция со старой 1.6 с одной тестовой подписью не восстанавливает утраченный публичный debug-ключ. Физическая батарея, ограничения OEM и успешный живой MTProto-ответ требуют отдельных наблюдений; успешная сборка не подтверждает их.

## Структура и правила данных

- `NetworkProbe`/`ProbeBatch`/`MtProto` — bounded HTTP и unauthenticated req_pq_multi/resPQ, привязка к Android Network, параллельный общий deadline и отмена с закрытием транспорта. Не добавляйте аккаунт, Telegram RPC или TLS bypass.
- `Timeline`/`HistoryStorage`/`History` — интервалы наблюдений, clock epochs, время без данных и статистика. MTTR требует OK→TG_DOWN→OK внутри окна; PARTIAL может продолжать эпизод. Пробелы, UNKNOWN/NO_NETWORK и обрезанные эпизоды не завершают MTTR.
- `CsvImport` — строгий текущий/legacy UTC-формат. UNKNOWN-строки не превращаются в checks; при отсутствии текущих retained rows старые эпохи не становятся текущими.
- `BackupSnapshot`/`BackupCodec`/`BackupCrypto` — переносимые whitelist-настройки, наблюдения и журнал. Format version 1: `TGWBKUP1`, big-endian iterations, salt16, nonce12, AES-256-GCM ciphertext/tag16; заголовок — AAD. KDF: PBKDF2-HMAC-SHA256, 600 000 iterations. Plaintext ≤8 МиБ, ≤65 000 observations, ≤150 log lines по ≤4096 символов; пароль 8–1024 символа.
- `RestoreTransaction` и Android-адаптер — подтверждённая замена через durable pending journal. Дешифрование и полная валидация предшествуют изменению данных; pending replay завершается до мониторинга, после restore/import monitoring остаётся выключенным. Cache обновляется после durable записи. Неверный пароль не меняет состояние службы.
- `Prefs`/`QuietHours` — минуты 0..1439 с fallback к legacy hour×60, локальный timezone и полуоткрытые интервалы. `LastSuccessStore` сохраняет только опубликованный OK; diagnostic/service state исключён из backup.
- `MonitorService`/`CheckSchedule`/`OfflineBackoff` — существующая native-служба, планирование и паузы без сети; `EventNotifications` и `StatusWidget` — Android-представление результатов. Переписывание на coroutines, NTP и английская локализация в 1.10 не выполнены.

UNKNOWN не доказывает отсутствие интернета или блокировку. VPN не доказывает работоспособность туннеля. История хранится семь дней, старые clock epochs сохраняются отдельно от текущей статистики. Append синхронизируется при смене статуса или при очередной записи после 60 с с прошлого sync; несинхронизированное сохранение не объявляется гарантированным при потере питания.

## SBOM и release policy

`generateReleaseSbom` разрешает `releaseRuntimeClasspath` и передаёт фактические JAR/AAR, включая transitives, в `scripts/generate-sbom.py`. CycloneDX 1.6 содержит Maven purl, resolved version и SHA-256 точных artifact bytes; локальные официальные schemas проверяют JSON. Project/file dependencies не пропускаются молча: такой inventory останавливает генерацию. Test dependencies и Android platform APIs не являются release runtime artifacts; пустой runtime classpath даёт пустой список библиотек.

Используйте официальный Trivy 0.75.0 с проверкой SHA-256 скачанного архива по опубликованному
checksum. CI закрепляет эту версию и setup action на commit SHA
`e07451d2e059ed86c2870430ea286b3a9e0bf241`:

```bash
bash scripts/scan-sbom.sh app/build/reports/sbom/TgWatch.sbom.cdx.json app/build/reports/sbom/TgWatch.trivy.json
```

Helper передаёт `--list-all-pkgs`, проверяет HIGH/CRITICAL и сохраняет отчёт, `.status.json`
и `.log`. Перед каждым scan старые outputs удаляются. Gate проверяет CycloneDX artifact name,
валидный scan timestamp и package inventory, покрывающий все runtime-библиотеки SBOM;
пустой или подменённый отчёт не заменяет scan. Ошибка, недоступная vulnerability database
или некорректный отчёт не считаются отсутствием уязвимостей. В CI артефакт `sbom-reports`
сохраняется и при сбое; публикация требует статус `passed` этого запуска и совпадение SBOM
повторной release-сборки.

Если default database mirror недоступен, используйте официальный GHCR без изменения
TLS-проверки или отключения gate:

```bash
TRIVY_DB_REPOSITORY=ghcr.io/aquasecurity/trivy-db:2 \
bash scripts/scan-sbom.sh app/build/reports/sbom/TgWatch.sbom.cdx.json app/build/reports/sbom/TgWatch.trivy.json
```

Локальный scan 10 октября 2026 года использовал checksum-verified Trivy 0.75.0 и этот GHCR
override, поскольку default mirror был заблокирован. Gate завершился `passed`, проверены
`kotlin-stdlib:2.0.21` и `annotations:13.0`, HIGH/CRITICAL не найдены. Это результат локального
запуска на соответствующем runtime SBOM, не подтверждение GitHub workflow или отсутствия
любых уязвимостей Android/platform.

Push в main и PR запускают проверки; публикация разрешена только ручным `Build APK` на `main` с `publish=true`. Группы build/Android checks отделены от repository-wide последовательной публикации с `cancel-in-progress=false`. Для версии измените `versionName`, локальный `versionCode`, [CHANGELOG](CHANGELOG.md) и `docs/releases/<versionName>.md`.

`scripts/release-policy.py` публикует тег `v<versionName>.<run_number>` на точный commit SHA. Более старый код не продвигает `latest`. Существующий release не изменяется: полностью идентичные commit и assets дают no-op; draft, неполные файлы, другой commit или bytes останавливают публикацию. Если release отсутствует, но тег уже
существует на другом commit, helper отказывается до `gh release create`. Не исправляйте это автоматическим удалением тега или заменой файлов.

На доверенной машине с JDK 17+, OpenSSL и авторизованным `gh`:

```bash
bash scripts/configure-signing.sh restore /secure/existing-backup
# Только для первоначального нового ключа:
bash scripts/configure-signing.sh init /secure/new-backup
```

`restore` требует `tgwatch-release.p12`, `tgwatch-signing-password.txt` и alias `tgwatch` типа `PrivateKeyEntry`; certificate-only entry отклоняется до изменения
Secrets. Ключи не создаёт. `init` не перезаписывает локальную копию и по умолчанию отказывается заменять signing Secrets. `--allow-replace-existing-secrets` допускается только с `init` и означает явную смену production-подписи, нарушающую обновление установок. Для обычного релиза существующий ключ сохраняйте. Тесты release helper используют mocks и не должны менять реальные Secrets или публиковать releases.

## Как предложить изменение

1. Опишите конкретную проблему, ожидаемое поведение и проверку в отдельной ветке.
2. Сохраните Android 8.0/API 26, существующие настройки и наблюдения. Для часов, malformed input, process death и cancellation добавьте регрессию.
3. Запустите unit/release-helper tests и lint; изменения Android-компонентов проверяйте на API 29/35. Запишите failed/skipped/unrun отдельно от passed.
4. В PR объясните поведение пользователя, совместимость данных и ограничения; добавьте запись в CHANGELOG. Не коммитьте ключи, пароли, локальные пути или данные устройств.

Код распространяется под [MIT](LICENSE). Вклад в репозиторий принимается на тех же условиях.
