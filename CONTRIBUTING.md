# Разработка TgWatch

Перед изменением поведения прочитайте [README](README.md), [архитектуру](docs/superpowers/architecture.md)
и [unified 1.11 spec](docs/superpowers/specs/2026-10-10-unified-1.11-design.md). Приложение
наблюдает доступность без аккаунта, телефона и доступа к переписке. Минимум Android API 26,
target API 35; native UI, Direct Boot, настройки и seven-day history сохраняются.

## Сборка и проверки

Нужны JDK 17, Android SDK Platform 35 и Build Tools 35.0.0, Python 3.11+, принятые лицензии SDK.
Используйте закреплённые Kotlin/AGP и Gradle Wrapper. SDK задаётся через `ANDROID_HOME` либо
неотслеживаемый `local.properties`; ключи, пароли и локальные пути не коммитятся.

```bash
python3 -m pip install --require-hashes -r scripts/requirements-release.lock
python3 -m unittest discover -s scripts/tests -v
python3 scripts/test_dependency_audit.py
python3 scripts/test_signing_keychain.py
bash ./gradlew testDebugUnitTest detekt lintDebug assembleDebug assembleRelease generateReleaseSbom dependencyInventory
```

Проверки legacy Gradle Wrapper читают неизменяемый исторический коммит: клонируйте
репозиторий с полной историей, либо выполните `git fetch --unshallow` для shallow clone.
CI запускает ту же unittest-suite через `python3 scripts/report-python-tests.py`:
код отказа сохраняется, ошибки получают ограниченные annotations GitHub,
а отсутствие обнаруженных тестов не считается успехом.

`version.properties` задаёт SemVer `1.19.1`; код `1019001` вычисляется одинаково локально и
в CI. Minor/patch ограничены 0–999; переполнение и неверный формат отклоняются.
`assembleRelease` без signing environment создаёт unsigned APK для проверки
сборки. Production build использует `TGWATCH_KEYSTORE_PATH`, `TGWATCH_KEYSTORE_PASSWORD`,
`TGWATCH_KEY_ALIAS`, `TGWATCH_KEY_PASSWORD`; не передавайте секреты в аргументах команд.
Debug APK — test-only.

Release tooling устанавливается из полного lock-файла с хешами прямых и транзитивных
зависимостей. После изменения `scripts/requirements-release.txt` обновите lock командой
`python3 scripts/lock-requirements.py scripts/requirements-release.txt scripts/requirements-release.lock`
и проверьте его diff перед установкой. Проверка соответствия прямых pins выполняется
той же командой с `--check` и не требует сети.

`.editorconfig` задаёт основной стиль. Detekt закреплён в сборке и использует
baseline существующих замечаний из исходного `main`; новые файлы туда не входят.
Успешный запуск означает отсутствие новых замечаний по выбранным правилам,
а не полное устранение старых. Не обновляйте baseline для сокрытия новых ошибок;
обоснованное локальное suppression указывается рядом с причиной.

Для changes в логике нужна meaningful regression с ожидаемым RED assertion и GREEN.
Не заменяйте malformed-input/tamper/process-death/cancellation проверки тривиальными mocks.
JVM JSON использует test dependency, Android — системный `org.json`; строгая проверка не
должна зависеть от permissive platform coercions.

Android scenarios выполняются только на отдельном disposable root-capable эмуляторе.
Матрица CI охватывает API 26/29/35/36; target приложения пока остаётся API35:

```bash
bash scripts/android-smoke.sh
```

На API 33+ smoke отдельно отзывает разрешение уведомлений перед новым процессом
инструментации: Android завершает приложение при отзыве. Две проверки подтверждают
отсутствие доставки и переход мастера из окончательного отказа в выданное разрешение.
Пропуск этих проверок считается ошибкой; после прогона флаги отказа очищаются и
разрешение восстанавливается. Мастер также проверяется при шрифте 200% и пересоздании Activity.

Сценарий устанавливает APK, заменяет тестовые данные, меняет сеть/Doze/PIN и перезагружает
эмулятор. Отчёты — `app/build/reports/androidTests/`, logcat — `smoke-logcat.txt`. Свежий
status timestamp должен иметь собственную строку history; непустая старая история этого не
доказывает. Restoration, minute/widget и independent Android event-channel tests сохраняются.

Upgrade baseline для CI — настоящий `v1.10.47` APK с фиксированным SHA-256
`efd2147dae453c12288da5b3f33840f3dfb2f6b85032411f348fabb5e8e009d1`.
Дополнительный сценарий использует последний стабильный опубликованный предшественник;
его APK сверяется с checksum, production-сертификатом и версией тега. Данные очищаются
только между двумя независимыми сценариями, без удаления между old → new внутри каждого.
`scripts/signed-upgrade-smoke.sh <previous.apk> <current.apk>` проверяет сохранение истории,
legacy hour settings и independent event options без clearing data. Локальный upgrade с общей
development-подписью проверяет миграцию, но не production certificate continuity. Батарея,
OEM background restrictions и живой Telegram ответ требуют отдельных наблюдений.

## Код и совместимость данных

- `NetworkProbe`/`ProbeBatch`/`MtProto` — bounded HTTP/unauthenticated req_pq_multi/resPQ, Android Network binding, shared deadlines и cancellation с закрытием socket/connection. Полная TL validation сохранена; не добавляйте account/RPC или TLS bypass.
- `Timeline`/`HistoryStorage`/`History` — interval observations, clock epochs, unknown gaps, per-status counts и exact latency totals. Полностью наблюдённый TG_DOWN для MTTR продолжается через PARTIAL до свежего OK; offline/gaps/clipping/clock change разрывают покрытие. Устаревшая последняя эпоха не должна оживлять старую статистику. Zero-width UNKNOWN с latency=-1 — явные epoch metadata, не checks; positive-width UNKNOWN остаётся реальной проверкой. После failed write dirty snapshot должен сохраняться целиком до возврата к append.
- `CsvImport` — strict current/legacy UTC history. Positive-width UNKNOWN rows пропускаются, чтобы generated gaps не увеличивали checks; явные zero-width epoch metadata принимаются только в текущих семи колонках. Archived epochs не становятся текущими после удаления UNKNOWN.
- `BackupSnapshot`/`BackupCodec` — legacy typed snapshot и strict JSON private journal; `TargetBackupCodec`/`TargetPortableBackup` — новые TGWB v3-копии независимых целей. Обычный экспорт пишет v3, отдельный Telegram-only экспорт — совместимый v2; чтение прежних TGWB v1/v2 и TGWBKUP1 сохраняется. Приватные адреса исключаются из обоих новых экспортов. Версии portable envelope и JSON payload не смешиваются. Подробнее: [backup](docs/backup.md).
- `BackupManager`/`RestoreTransaction`/`TgWatchApplication` — validation before changes, stop-and-await monitoring, durable journal и replay до компонентов. Оба старых private journals мигрируют. Cache меняется после durable write; monitoring после restore/import остаётся остановленным, transient status/last OK не переносятся.
- `Prefs`/`QuietHours`/`EventPreferences` — minute boundaries с legacy hour fallback и independent outage/PARTIAL/recovery notify/sound. Отсутствующие legacy flags имеют прежний fallback; PARTIAL остаётся opt-in. `LastSuccessStore` сохраняет только published OK; Android channel overrides принадлежат устройству.
- `ExtraProbes`/`HttpsUrlPolicy` — opt-in informational probes в группе `EXTRA`, которую `ProbeReport.classify` игнорирует; они не должны влиять на статус, latency, reason и тревоги и используют отдельный трёхпоточный bounded executor. `WebhookPolicy`/`WebhookGate` — чистая логика payload и rate limit; `WebhookSettings` хранит адрес как секрет (как `AutomationSettings`). `WebhookCoordinator` связывает URL/generation с admission/completion; `WebhookNotifier` сохраняет не больше одного физического запроса, включая смену настроек.
- `MonitorService`/`CheckSchedule`/`OfflineBackoff` — existing native lifecycle/scheduling; `EventNotifications`/`StatusWidget`/`MainActivity`/`BackupUi` — Android adapters/UI. Full Telegram RPC, NTP и coroutine rewrite не реализованы.

### Язык интерфейса

Интерфейс следует языку системы: по умолчанию английский, для русской локали — русский.
Тексты разметки, манифеста и виджета берутся из строковых ресурсов: английские в
`res/values/strings.xml`, русские в `res/values-ru/strings.xml`. Текст, который собирается
в коде (уведомления, виджет, плитка, журнал, сводки, диалоги), пишется как `Lang.t(ru, en)`;
`Lang.ru` выставляется из ресурса `R.string.lang` в `TgWatchApplication` и `MainActivity`,
поэтому код и ресурсы всегда выбирают один язык. JVM-тесты по умолчанию проверяют русский
текст, английский — в `LangTest`; инструментальные тесты сравнивают с `Lang.t(...)` или
ресурсом, потому что эмуляторы CI работают в en-US. Любую новую строку добавляйте сразу
на обоих языках: имена строк в `values/` и `values-ru/` должны совпадать.

UNKNOWN не доказывает отсутствие интернета или блокировку; VPN не доказывает работоспособность
туннеля. Append sync выполняется при смене статуса и на следующей записи после 60 с с прошлого
sync. Unsynced append не гарантируется при power loss; stop/replace/compaction отдельно сохраняют
историю. Семидневное хранение, observation bounds и Direct Boot сохраняются.

## Два аудита зависимостей

`generateReleaseSbom` берёт resolved `releaseRuntimeClasspath` JAR/AAR, включая transitives.
`scripts/generate-sbom.py` формирует schema-valid CycloneDX 1.6 с resolved version, Maven purl
и SHA-256 точных artifact bytes. Неизвестные/project/file dependencies не пропускаются молча.
Android platform APIs и test libraries не входят в production runtime SBOM.

С официальным checksum-verified Trivy 0.75.0:

```bash
bash scripts/scan-sbom.sh app/build/reports/sbom/TgWatch.sbom.cdx.json app/build/reports/sbom/TgWatch.trivy.json
```

Helper использует `--list-all-pkgs`, очищает stale outputs и проверяет HIGH/CRITICAL, artifact
identity, timestamp и coverage package inventory относительно runtime SBOM. Report, status и log
сохраняются при ошибках. Недоступный scanner/database и malformed report не считаются clean.
При недоступности default database mirror допустим официальный GHCR без TLS/gate bypass:

```bash
TRIVY_DB_REPOSITORY=ghcr.io/aquasecurity/trivy-db:2 \
bash scripts/scan-sbom.sh app/build/reports/sbom/TgWatch.sbom.cdx.json app/build/reports/sbom/TgWatch.trivy.json
```

OSV дополнительно проверяет **точные resolved Maven versions runtime и test classpaths**:

```bash
bash ./gradlew dependencyInventory
python3 scripts/dependency-audit.py app/build/reports/dependencies/resolved.json app/build/reports/dependencies/osv-report.json
```

Audit включает transitives, пагинацию и withdrawn records. Пустой/incomplete inventory, missing
runtime graph, неполный ответ, ошибка API и любая неотозванная OSV vulnerability останавливают
audit; это отдельный gate от Trivy HIGH/CRITICAL. CI требует evidence обоих audits этого запуска. Инвентарь `resolved.json` содержит все
три scope и dependency graphs; опубликованные audit assets — `TgWatch.dependencies.json`
и `TgWatch.osv.json` рядом с `TgWatch.sbom.cdx.json` и `TgWatch.trivy.json`.
Известные vulnerabilities проверяются по соответствующим базам; SDK/JDK/Gradle plugins и все
возможные platform defects не покрываются runtime SBOM. Здесь нет fresh scan/audit результата 1.11.

## Подпись и публикация

Signing scripts используют только существующий ключ, приватный alias `tgwatch` и ожидаемый
сертификат опубликованной линии. Пароль — macOS login Keychain или hidden terminal input;
новый ключ/password sidecar не создаются. Команды protect/verify/configure и expected fingerprint:
[signing backup](docs/signing-backup.md). Linux cloud не предоставляет login Keychain. Не меняйте
реальные Secrets/ключи в тестах; helper regressions используют fixtures/mocks.

Push/main и PR запускают checks. Публикация разрешена совпадающим с исходниками SemVer-тегом
на commit из `main` либо ручным `Build APK` на `main` с `publish=true`, после checks и двух
audits. Build/Android cancellation отделена от последовательной repository-wide publication
с `cancel-in-progress=false`. Для версии обновите `version.properties`, [CHANGELOG](CHANGELOG.md)
и `docs/releases/<versionName>.md`. Изменение поведения требует новой версии; PR-проверка
валидирует формат и однозначное вычисление кода, но не требует существования ещё не опубликованного тега.

Release policy сохраняет published tags/assets. Уже существующий выпуск нельзя перезаписывать;
draft, incomplete/different assets, другой commit или conflicting orphan tag требуют явного разбора.
Более старый code не заменяет latest. Не обходите это удалением тега или перезаписью assets.
CI должен проверить production certificate и upgrade реального 1.10.47 до публикации.

## Как предложить изменение

1. Опишите конкретный trigger, проблему, новое поведение и проверку в отдельной ветке.
2. Сохраните настройки/данные старых версий; добавьте границы, invalid input и restart regressions.
3. Запустите meaningful JVM/helper/lint и Android checks. Разделяйте passed, failed, skipped и unrun.
4. В PR объясните поведение, совместимость и ограничения. Не коммитьте secrets/device data.

Код и вклад распространяются под [MIT](LICENSE).
