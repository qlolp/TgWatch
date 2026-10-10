# Архитектура TgWatch 1.10

Android 8.0+; native Views и существующая foreground-служба. Чистые Kotlin policies и codecs отделены от Android-хранилища, Network/SAF и UI. Карта показывает потоки данных; наличие пути не заменяет отчёт о его проверке.

```mermaid
flowchart TD
    UI["MainActivity · настройки, история, SAF preview"]
    SERVICE["MonitorService · foreground lifecycle"]
    SCHEDULE["CheckSchedule / OfflineBackoff"]
    NETWORK["Android Network adapter"]
    PROBE["NetworkProbe / ProbeBatch · shared deadline"]
    HTTP["HTTPS · Telegram и controls"]
    MT["MtProto · bounded req_pq_multi / resPQ"]
    HISTORY["History / HistoryStorage · durable-first replacement"]
    TIMELINE["Timeline · clock epochs, gaps, counts, MTTR"]
    PREFS["Prefs / QuietHours · minute settings + legacy fallback"]
    SUCCESS["LastSuccessStore / LastSuccessPolicy · published OK only"]
    PRESENT["StatusWidget / EventNotifications"]
    LOG["EventLog"]
    FILE["SAF document · encrypted backup or plaintext CSV"]
    SNAP["BackupSnapshot / BackupCodec · strict whitelist + bounds"]
    CRYPTO["BackupCrypto · PBKDF2 + AES-256-GCM"]
    CSV["CsvImport · strict UTC, omit UNKNOWN"]
    RESTORE["Android restore adapter · confirmed replace, stop monitoring"]
    TX["RestoreTransaction · durable pending journal"]
    START["Application startup · pending replay before components"]

    UI --> SERVICE
    UI --> PREFS
    SERVICE --> SCHEDULE
    SCHEDULE --> SERVICE
    SERVICE --> NETWORK
    NETWORK --> PROBE
    PROBE --> HTTP
    PROBE --> MT
    PROBE --> SERVICE
    SERVICE --> HISTORY
    HISTORY --> TIMELINE
    TIMELINE --> UI
    SERVICE --> SUCCESS
    SERVICE --> LOG
    SERVICE --> PRESENT
    SUCCESS --> PRESENT
    PREFS --> SERVICE

    UI --> FILE
    HISTORY --> SNAP
    PREFS --> SNAP
    LOG --> SNAP
    SNAP --> CRYPTO
    CRYPTO --> FILE
    FILE --> CRYPTO
    CRYPTO --> SNAP
    FILE --> CSV
    SNAP --> RESTORE
    CSV --> RESTORE
    RESTORE --> TX
    START --> TX
    TX --> HISTORY
    TX --> PREFS
    TX --> LOG
    TX --> SUCCESS
```

## Границы поведения

- MTProto — unauthenticated greeting без аккаунта и RPC. Парсер проверяет весь TL resPQ и canonical abridged frame ≤4096 bytes. Ответ с nonce подтверждает протокол, но не серверную аутентичность или доставку сообщения. NetworkProbe сохраняет общий бюджет Telegram 6 с и дополнительный бюджет controls при необходимости; отмена закрывает транспорт.
- История описывает ограниченные интервалы наблюдений. Gaps остаются UNKNOWN time; clock rollback создаёт новую эпоху. Счётчики проверок считаются по наблюдениям, MTTR — только для OK→TG_DOWN→OK в текущем окне. PARTIAL может продолжать эпизод; gaps, UNKNOWN/NO_NETWORK и clipping его исключают.
- Backup переносит только разрешённые настройки, историю и журнал. Диагностический last OK, состояние службы, cooldowns и OS permissions исключены. Криптография аутентифицирует весь header/ciphertext; codec проверяет строгие типы/числа/лимиты до применения. Расшифрование/экспорт выполняются вне UI thread.
- Restore после preview/подтверждения заменяет данные и оставляет monitoring остановленным. Pending journal replay идемпотентно завершает прерванную замену до работы компонентов; это несколько durable записей с восстановлением после сбоя, а не обещание одной общей файловой транзакции Android. Неверный пароль и malformed input не создают transaction и не останавливают действующий monitoring.
- CSV — plaintext history interchange. UNKNOWN rows опускаются, поскольку экспорт не отличает реальные UNKNOWN checks от generated gaps. При отсутствии сохранённых строк текущей эпохи возвращается пустая история. Семидневное хранение сохраняется.
- Quiet hours — local minute-of-day с legacy hour fallback; last success — сохранённый опубликованный OK, независимый от свежести текущего состояния.

## Выпуск

```mermaid
flowchart LR
    INPUT["main push / PR / manual workflow"] --> CHECKS["build + unit + lint + Android scenarios"]
    CHECKS --> INVENTORY["resolved releaseRuntimeClasspath"]
    INVENTORY --> BOM["CycloneDX 1.6 · purl / version / SHA-256"]
    BOM --> SCAN["Trivy HIGH / CRITICAL gate"]
    SCAN --> ARTIFACT["this run's sbom-reports"]
    ARTIFACT --> MANUAL["main + manual publish=true"]
    MANUAL --> SIGN["persistent signing + certificate / upgrade gate"]
    SIGN --> POLICY["serialized release-policy · immutable tag/assets"]
    POLICY --> RELEASE["APK + hash + SBOM + Trivy report"]
    POLICY --> LATEST["latest only for higher versionCode"]
```

Состояние scan должно быть `passed`, недоступность не превращается в чистый результат. SBOM отражает разрешённые JAR/AAR runtime dependencies, а не Android platform или test libraries. Публикации отделены от отменяемых checks; `restore` signing использует существующий ключ, `init` требует явного намерения создать новый.

Спецификация: [portability and release](specs/2026-10-10-portability-and-release-design.md). План: [implementation](plans/2026-10-10-portability-and-release.md). Команды и ограничения проверок: [CONTRIBUTING](../../CONTRIBUTING.md).
