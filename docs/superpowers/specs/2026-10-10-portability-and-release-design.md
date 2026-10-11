# TgWatch 1.10: точность диагностики и переносимость

Пользователь поручил реализовать предложения из проверки актуального main 4d33b26. Цель: исправить подтверждённые ошибки, переносить историю и настройки между установками и расширить полезную статистику. Android 8+, native View UI, текущие профили и существующие настройки сохраняются. Работа выполняется в облачном checkout на отдельной ветке; исходная работа1.2 сохранена в проверенном архиве и stash. Публикация и изменение реальных GitHub Secrets не входят в локальную реализацию.

## Диагностика и пользовательские настройки

MTProto принимает только полный структурно корректный resPQ с ожидаемым nonce: ограниченный abridged frame, нулевой auth_key_id, корректный server message id, body size, constructor, server_nonce, TL pq, vector fingerprints. Все чтения ограничены рамкой и существующим deadline. Это остаётся unauthenticated reachability probe.

Тихие часы хранят минуты 0..1439. Старые часовые ключи читаются как hour*60; старые public hour APIs остаются совместимы. Интервал полуоткрытый, равные границы отключают его; локальный часовой пояс и DND сохраняются. UI сохраняет выбранные минуты.

Виджет показывает отдельное время последнего опубликованного OK, которое сохраняется при ошибках и перезапуске. PARTIAL не считается успешным. Смена часов назад не должна давать отрицательный возраст. Диагностическое состояние устройства не переносится в backup.

## Статистика и история

В TimeStats добавляются счётчики OK/TG_DOWN/PARTIAL/NO_NETWORK/UNKNOWN, completedOutageCount и mttrMs (-1 при отсутствии завершённых эпизодов). MTTR учитывает только полностью наблюдённый TG_DOWN-эпизод, закрытый свежим OK; PARTIAL может продолжать подтверждённый эпизод, UNKNOWN/NO_NETWORK, границы окна и смена clock_epoch исключают незавершённый/обрезанный эпизод. Доступность по времени и существующие поля не меняют смысл.

History предоставляет snapshot():List<Observation>, replaceSnapshot(context,rows):Unit с валидацией и атомарным сохранением до обновления cache. EventLog получает replace для восстановленного журнала. CSV importer читает существующий UTC формат, проверяет duration/status/epoch и ограничивает число/размер записей; импорт не превращает сгенерированные UNKNOWN gaps в число реально выполненных проверок.

Обычные append синхронизируются минимум раз в60с elapsed time и при переходе статуса; stop/restore/compaction используют существующий fsync+atomic move. Последние несинхронизированные append не объявляются гарантированно сохранёнными после потери питания.

## Переносимый backup/restore

Формат: versioned JSON snapshot внутри AES-256-GCM. PBKDF2-HMAC-SHA256,600000 iterations, случайные salt16 и nonce12, тег16. Envelope: ASCII TGWBKUP1 (8 bytes), big-endian iteration count4, salt16, nonce12, ciphertext+tag. Header передаётся как AAD. Для version1 iterations должны точно совпадать; неизвестный формат отклоняется. Пароль 8..1024 символа, не сохраняется и не выводится; ввод подтверждается при экспорте. Максимум plaintext8MiB,65000 observations,150 journal lines, каждая не более4096 символов. Неправильный пароль, tampering, лишние/обрезанные данные, неверные типы и границы отклоняются до изменения данных приложения.

BackupSnapshot(createdAt:Long,settings:Map<String,Any>,observations:List<Observation>,log:List<String>). Settings whitelist: power_profile(ECONOMY/BALANCED/FREQUENT/CUSTOM),interval_sec(10..120),keep_awake,vibrate,vibrate_partial,vibrate_offline,vibrate_recovery,notify_recovery,event_sound,vibration_pattern(enum),quiet_hours,quiet_start_hour/quiet_end_hour(0..23),quiet_start_minute/quiet_end_minute(0..1439). bool keys require Boolean, numeric keys require integral Int; unknown keys reject. Не переносить enabled,last_state,alarm cooldowns/boot ids и разрешения ОС. Observation: at>=0,until>at,duration<=600000,known kind,latency>=-1,clockEpoch>=0; JSON numbers must be integral and representable Long. JSON formatVersion1,createdAt,settings,observations,log. Прикладной код использует системный org.json; JVM tests используют отдельную test dependency org.json.

SAF выбирает пользовательский файл без storage permissions. Экспорт и расшифровка выполняются вне UI thread. Восстановление сначала показывает дату/объём snapshot и запрашивает замену текущих данных. Затем мониторинг останавливается и остаётся выключен до ручного запуска; restore не сообщает старый статус как свежий. Применение выполняется через валидированный журнал транзакции в app-private device-protected storage; Application.onCreate завершает pending transaction после сбоя процесса. Неправильный пароль/повреждённый файл не создают transaction и не меняют настройки/историю. CSV-import имеет отдельный preview и безопасное применение истории; ограничение семидневного хранения сохраняется.

## Выпуск и supply chain

Проверки PR/main можно отменять; публикации последовательные в отдельной concurrency group с cancel-in-progress=false. Более старый code не продвигается в latest; существующий опубликованный тег остаётся неизменным, незавершённый draft сообщается явно. Локальный versionName1.10,code11; CI run_number+100 валидируется без silent fallback/overflow. Настройка подписи требует явного init либо restore: restore не генерирует новый ключ, init не заменяет существующие GitHub Secrets без явного разрешения режима. Секретные значения не выводятся/не коммитятся.

SBOM отражает разрешённый releaseRuntimeClasspath, содержит purl/version/SHA-256 и сохраняется как CI artifact; CycloneDX JSON валидируется и сканируется Trivy sbom с HIGH/CRITICAL gate. Сканирование недоступно/offline не объявляется отсутствием уязвимостей. Реальные signing credentials не создавать и не менять. Подписанный upgrade gate сохраняется; smoke проверяет соответствующую свежему timestamp строку истории.

## Верификация

Для протокола, crypto/codec, границ quiet hours, MTTR и release policy — meaningful regression tests, RED→GREEN. Полная Gradle проверка unit/lint/debug/release assembly; APK metadata/signature. Android instrumentation проверяет восстановление, настройки с минутами и настоящий widget state. Физическая батарея, OEM background и успешный live Telegram ответ проверяются отдельно, без выдуманных результатов.
