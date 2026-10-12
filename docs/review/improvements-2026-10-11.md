# Аудит TgWatch и предложения по улучшению

> Исторический аудит версии 1.12. Часть замечаний уже исправлена в 1.13 и
> [1.13.1](../releases/1.13.1.md); приведённые ниже номера строк, состояние PR
> и примеры патчей относятся к исходному снимку, а не к текущему `main`.

Ревью коммита `b95e63d` (`main`, версия 1.12, последний опубликованный релиз — `v1.12.59`).
Отчёт основан только на чтении кода, манифеста, CI и скриптов: в песочнице нет JDK и Android SDK,
поэтому сборка и тесты не запускались, а правки в код не вносились.

Ссылки вида `файл:строка` — точки, которые нужно посмотреть. Приоритеты:

- **P0** — влияет на достоверность результата мониторинга или блокирует публикацию.
- **P1** — заметно вредит батарее, доступности, безопасности релиза или надёжности.
- **P2** — качество кода, UX, документация, ускорение CI.

Уже в работе на момент ревью был draft-PR #14 («1.13: Tasker API, extra probes, Glance,
webhooks, targetSdk 35»); он закрыт и частично перенесён в 1.13.0. Статус предложений
после выхода 1.13.0 и 1.13.1 — ниже, в начале отчёта.

---

## Статус после 1.13.0 и 1.13.1 (обновлено 12 октября 2026)

После ревью смержены три PR: #16 («1.13: Android 15, automation, diagnostics + best of #14»,
релиз `v1.13.0`), #17 (**«1.13.1: fixes from the Cline code audit»** — исправления по этому
отчёту) и #18 (CI-отчётность по androidTest, чистка завершённых планов). Часть предложений
принята как есть, часть решена иначе и с аргументацией, часть остаётся открытой.

| Пункт | Статус | Что сделано |
|---|---|---|
| 1. Подмена ответов, контр-сайты | 🟡 иначе | Аргумент принят: все пробы — HTTPS с проверкой хоста и системным trust store, перехватчик без сертификата Telegram падает в TLS до чтения HTTP-кода. Закреплено тестом `interceptedTelegramHostsCannotConfirmWebReachability` (`EndpointFallbackTest`). Остаточный риск — только MITM с установленным CA (pinning нет) |
| 2. IPv6-адреса DC | ⬜ | Каталог остался из пяти IPv4 (`MtProtoEndpoints.kt`); в `docs/development-roadmap.md` отклонён лишь удалённый `dc.json`, про IPv6 не сказано |
| 3. `USE_EXACT_ALARM` | ⬜ | Разрешение в манифесте прежнее; на API 33+ `canScheduleExactAlarms()` всегда `true`, пункт чеклиста недостижим, риск для Play сохраняется |
| 4. `isStale` | ✅ иначе | Мёртвые параметры убраны вместо прокидывания: результат судится по интервалу, который он сам назначил (профиль + сон + backoff). Monotonic clock внедряемый, есть `StateStalenessTest` |
| 5. Запись на каждую проверку | ⬜ | `Prefs.saveLastState` и `LastSuccessStore.record` (синхронный `commit()`) вызываются на каждом результате |
| 6. Миграция настроек | ✅ | Успешный `moveSharedPreferencesFrom` больше не повторяется (`OneTimeMigrationTest`) |
| 7. Watchdog каждые 3 минуты | ✅ иначе | После каждой завершённой проверки будильник отодвигается за следующую проверку: будит только когда проверки перестали приходить |
| 8. Контраст и обрезка | ✅ | Плашки затемнены до 4,9–5,9:1 (`#0878AD`, `#B45300`, `#596673`), `maxLines=1`/`ellipsize` убраны, тач-таргеты `minHeight=48dp` |
| 9. Тикер 1 Гц | ⬜ | `render()` остался с 1 Гц; дешевле (`DailyStatsCache`), но `ACTION_STATE_CHANGED` UI не слушает |
| 10. Baseline обновления `v1.10.47` | ⬜ | Workflow по-прежнему качает `v1.10.47` с зашитыми sha256/фингерпринтом (`.github/workflows/build-apk.yml:168-179`), хотя вышли `v1.13.0` и `v1.13.1` |
| 11. Секреты, права, actions | 🟡 | Добавлены preflight до подписи и публикация по тегу `v*`; SHA-пиннинг (кроме `setup-trivy`), GitHub Environment и Dependabot не добавлены |
| 12. Перепроверка APK при публикации | 🟡 | `release-policy.py` существенно строже (SemVer-код, ancestry, монотонность, повторный preflight перед единственной мутацией), но `TgWatch.apk.sha256` не сверяется с APK внутри политики |
| 13. Wrapper и зависимости | ⬜ | Нет `distributionSha256Sum`, `verification-metadata.xml`, wrapper-validation, `--require-hashes` для pip |
| 14. Экспортированные получатели | ✅ | `BootReceiver` не экспортируется, клик виджета ушёл в неэкспортируемый `WidgetClickReceiver` |
| 15. Проверка при включении экрана | ✅ | `ACTION_SCREEN_ON` → проверка, если последний результат в процессе старше 30 с |
| 16. `User-Agent: TgWatch/1.9` | ⬜ | Строка прежняя (`NetworkProbe.kt`) |
| 17. R8 | ⬜ | Вместо него добавлен Detekt с baseline; minify не включён |
| 18. Версия в нескольких местах | ✅ | `version.properties`, детерминированный код `major*1000000 + minor*1000 + patch`, теги `v1.13.0`/`v1.13.1`, публикация по тегу |
| 19. Кэш CI | ⬜ | `gradle.properties` без caching/configuration cache, `--no-daemon` сохранён |
| 20. Robolectric, фаззинг, покрытие | 🟡 | Robolectric осознанно отложен (`docs/development-roadmap.md`), зато добавлены `ResourceTranslationTest`, `UiAccessibilityTest`, `StateStalenessTest`, `WebhookPolicyTest` и другие |
| 21. Воспроизводимость аудита | ⬜ | OSV и Trivy по-прежнему ходят в сеть в момент проверки |
| 22. Документация | 🟡 | `docs/README.en.md`, `docs/diagnostics.md`, `docs/development-roadmap.md`, удалены завершённые планы, версии в README выровнены |
| 23. Сводка при выключенном мониторинге | 🟡 | Добавлены dedupe по последней доставке и учёт тихих часов/DST; `Prefs.isEnabled` по-прежнему не проверяется |
| 24. Действия в тревожных уведомлениях | ⬜ | `EventNotifications` по-прежнему без `addAction` |
| 25. Deprecated API | ⬜ | `ProgressDialog` и `startActivityForResult` на месте |
| 26. Парность строк `Lang.t` | 🟡 | `ResourceTranslationTest` проверяет парность `values`/`values-ru` и формат-аргументы; строки, собираемые в коде через `Lang.t`, не покрыты |
| 27. `fontScale` графика | ✅ | `textSize` через `COMPLEX_UNIT_SP` |
| 28. Мелочи (`SecureRandom` и пр.) | ⬜ | — |

### Обновлённый приоритет того, что осталось

1. **P0: `USE_EXACT_ALARM`** (п.3) — прямо может заблокировать публикацию в Google Play.
2. **P0: IPv6-адреса DC** (п.2) — точность на IPv6-only сетях; это единственное из открытого,
   что влияет на достоверность результата.
3. **P1: baseline обновления `v1.10.47`** (п.10) — с новыми SemVer-тегами заметнее: проверка
   обновления не покрывает реальный предыдущий релиз.
4. **P1: запись на каждую проверку** (п.5) — батарея и износ флеша.
5. **P1: supply chain** (п.11–13) — SHA-пиннинг actions, GitHub Environment, `distributionSha256Sum`,
   dependency verification, Dependabot.
6. **P2:** п.9 (событийный UI), 16 (User-Agent), 17 (R8), 19 (кэш CI), 21, 23–25, 28.

---

## P0 — достоверность и блокеры публикации

### 1. Сеть с подменой ответов не может быть классифицирована как `TG_DOWN`

`ProbeRules.isReachableHttpCode` считает «сервер ответил» любой код `100..499`
(`app/src/main/java/ru/tgwatch/ProbeRules.kt:17`), а `NetworkProbe.probe` делает `HEAD`
с `instanceFollowRedirects = false` и не проверяет ни тело, ни заголовки
(`app/src/main/java/ru/tgwatch/NetworkProbe.kt:170-187`). При этом контрольные сайты
опрашиваются **только** если ни один адрес Telegram не ответил
(`app/src/main/java/ru/tgwatch/NetworkProbe.kt:154`).

Следствие: captive portal, прозрачный прокси провайдера или DNS-хайджек, отвечающий `200/302`
на всё, даёт `web = true`. Если при этом MTProto не подтверждается, состояние будет `PARTIAL`,
а не `TG_DOWN`, и диагностика («контрольные сайты отвечают, Telegram — нет») вообще не запустится.
Капабилити `NET_CAPABILITY_CAPTIVE_PORTAL` (`MonitorService.kt:397`) ловит только случай,
который распознал сам Android.

Что сделать:

1. Считать веб-ресурс подтверждённым только по валидному ответу: `api.telegram.org` — JSON
   с `"ok"`, `web.telegram.org` — HTML Telegram; `3xx` без редиректа считать неопределённым,
   а не подтверждением.
2. Запускать контрольные сайты всегда, когда итоговая классификация не `OK`
   (`if (results.none { it.reachable })` → `if (classify(results) != "OK")`): это 3 лишних
   `HEAD` в уже существующем шестисекундном бюджете, зато `PARTIAL` перестаёт маскировать сбой.
3. Опционально: для HTTPS-проверок добавить проверку ожидаемого сертификата (`HttpsURLConnection`
   + `getServerCertificates`/SPKI pin) — тогда пользовательский MITM-CA не даст ложный «доступен».

### 2. Только IPv4-адреса DC → ложный `PARTIAL`/`TG_DOWN` в IPv6-only сетях

`MtProtoEndpoints.all` — пять IPv4-литералов (`app/src/main/java/ru/tgwatch/MtProtoEndpoints.kt:9-15`).
В IPv6-only сетях (некоторые мобильные операторы, Wi-Fi без CLAT) TCP-проверка к ним не проходит,
хотя Telegram работает: `web = true`, `mt = false` → `PARTIAL`, а с учётом п.1 ещё и без контроля.

Что сделать: добавить официальные IPv6-адреса DC (`2001:b28:f23d::/48` и др. из
`mtproto_dc_options.cpp`), проверять семейства параллельно в том же бюджете, а в диагностике
явно показывать «MTProto недоступен, HTTPS к Telegram отвечает — похоже на блокировку DC/DPI».
Каталог адресов стоит держать в `assets/dc.json` с датой ревизии (в PR #14 это уже начато)
и добавить CI-проверку «каталог не старше N дней».

### 3. `USE_EXACT_ALARM` — риск отклонения в Google Play и мёртвый чеклист разрешений

В манифесте объявлены и `USE_EXACT_ALARM` (без ограничений), и `SCHEDULE_EXACT_ALARM`
с `maxSdkVersion="32"` (`app/src/main/AndroidManifest.xml:15-18`). На API 33+ `USE_EXACT_ALARM`
выдаётся автоматически, поэтому `Prefs.exactAlarmsAllowed` всегда `true`
(`Prefs.kt:69-76`), а пункт чеклиста и кнопка «Разрешить точные будильники»
(`MainActivity.kt:501,611,629-634`) недостижимы. Политика Play разрешает `USE_EXACT_ALARM`
только приложениям, чья основная функция — будильник или календарь.

Что сделать: убрать `USE_EXACT_ALARM`, оставить `SCHEDULE_EXACT_ALARM` без `maxSdkVersion`,
запрашивать разрешение через `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` из существующей кнопки,
сохранив фолбэк `setAndAllowWhileIdle` (`MonitorService.kt:341-345`) — код уже готов к обоим путям.

---

## P1 — батарея, корректность, релизная безопасность

### 4. `State.isStale` игнорирует оба аргумента (реальный баг индикатора свежести)

```kotlin
// app/src/main/java/ru/tgwatch/MonitorService.kt:51-55
fun isStale(now: Long, intervalSec: Int, slowExpected: Boolean = false): Boolean =
    checkedAt > 0L && (now < checkedAt || if (checkedElapsed > 0L)
        ProbeRules.isStale(checkedElapsed, SystemClock.elapsedRealtime(), expectedIntervalSec, false)
        else ProbeRules.isStale(checkedAt, now, expectedIntervalSec, false))
```

`slowExpected` не используется, `intervalSec` тоже (берётся `expectedIntervalSec` из сохранённого
состояния), оба вызова `ProbeRules.isStale` жёстко передают `false`. Все четыре вызывающих места
передают `Prefs.keepAwake(...)` (`MonitorService.kt:581`, `MainActivity.kt:498`,
`StatusWidget.kt:84`, `StatusTileService.kt:99`) — то есть настройка «будить процессор» на индикатор
не влияет, а после смены профиля при остановленной службе порог «устарело» остаётся старым
(до 3 × 300 с). Починить: передавать фактически ожидаемый интервал и прокидывать `slowExpected`
в оба вызова; добавить юнит-тест на смену интервала и на screen-off.

### 5. Запись настроек и `fsync` на каждую проверку

- `Prefs.saveLastState` пишет 8 ключей и вызывает `apply()` при каждом результате
  (`MonitorService.kt:453-456`, `Prefs.kt:235-256`).
- `LastSuccessStore.record` при каждом `OK` делает синхронный `commit()`
  (`LastSuccessStore.kt:9-18`) — то есть блокирующую запись с ожиданием диска.
- `History.record` добавляет строку и раз в 60 с делает `fd.sync()` (`History.kt:96-107`).

При профиле 10–30 с это десятки тысяч операций записи в сутки. Предложение: писать
`last_state` только при смене статуса/`since` либо не чаще раза в минуту (актуальные значения
и так живут в `MonitorService.state`); `last_success` держать в памяти и сбрасывать тем же
троттлингом; объединять оба обновления в одну `edit()`-транзакцию.

### 6. `Prefs.store()` пытается мигрировать настройки при каждом обращении

`Prefs.store` на каждый `sp(ctx)` создаёт device-protected контекст и вызывает
`moveSharedPreferencesFrom` (`Prefs.kt:54-66`), а `sp()` вызывается десятки раз в секунду
из `render()` и на каждой проверке. Предложение: кэшировать результат в `@Volatile`-флаге
процесса и повторять попытку только пока она неуспешна.

### 7. Watchdog будит устройство точным будильником каждые 3 минуты

`WatchdogReceiver` планирует `setExactAndAllowWhileIdle` на 3 минуты
(`WatchdogReceiver.kt:42,52-56`) и перепланирует себя из `onReceive`, `MonitorService.onCreate`
и `onDestroy` (`MonitorService.kt:224,285`) — ~480 точных пробуждений в сутки, даже когда
foreground-служба жива и проверяет сама; Doze всё равно откладывает такие будильники.
Предложение: планировать watchdog только когда служба не запущена, а при живом FGS использовать
нечёткий `setAndAllowWhileIdle` с интервалом ≥15 минут.

### 8. Контраст и обрезка подписей на главном экране

- Белый `on_status` на `status_ok #229ED9` = **3,02:1**, на `status_offline #F57C00` = **2,70:1**,
  на `status_idle #8A96A3` = **3,01:1** (`values/colors.xml:20`), при тексте 14sp внутри плашки
  (`activity_main.xml:124-141`) — норма WCAG для мелкого текста 4,5:1.
- `StatValue` — `maxLines=1` + `ellipsize=end`, `StatLabel` — `maxLines=1` без ellipsize
  (`values/styles.xml:48-49,58`). На `docs/screenshots/main.png` видно «2 с на…» и «Telegram за».

Предложение: затемнить статусные цвета (или использовать `text_primary` на плашке и дублировать
статус иконкой), разрешить 2 строки, сократить подписи («Проверка», «За сутки»), добавить
в CI проверку контраста (Accessibility Test Framework).

### 9. Тикер 1 Гц и расчёт статистики на главном потоке

`MainActivity` каждую секунду вызывает `render()` (`MainActivity.kt:78-83`), который читает
`History.uptimePercent`/`dayStats` → `History.stats` → `Timeline.stats` по всем наблюдениям
(`History.kt:120-128`, `Timeline.kt:72-120`), а за 7 дней при 15 с это ~40 тыс. записей; кэш
инвалидируется раз в минуту и при каждой новой записи. Событие `ACTION_STATE_CHANGED` уже
рассылается (`MonitorService.kt:508`), но его слушает только плитка (`StatusTileService.kt:44`).
Предложение: подписать статусные поля на broadcast (`RECEIVER_NOT_EXPORTED`), оставить 1 Гц
только для счётчика «N с назад», а `stats` считать вне главного потока.


### 10. CI-проверка обновления привязана к устаревшему релизу `v1.10.47`

Workflow всегда скачивает `v1.10.47`, сверяет её sha256 и сертификат
(`.github/workflows/build-apk.yml:158-173`), хотя актуальный релиз — `v1.12.59`
(`gh release list`). Значит, upgrade-совместимость проверяется не с предыдущей версией,
а с версией трёх релизов назад, и каждая новая версия требует ручной правки workflow.
Предложение: выбирать предыдущий релиз автоматически (последний тег с меньшим `versionCode`),
а эталонные sha256/фингерпринт держать в отдельном файле, который обновляет скрипт;
дополнительно прогонять матрицу N-1 и N-2.

### 11. Секреты и права в workflow

Job `release` имеет `contents: write` и внутри него сторонние действия подключаются
по мутабельным тегам, включая `reactivecircus/android-emulator-runner@v2`
(`.github/workflows/build-apk.yml:101-102,180`); SHA-пиннинг есть только у `setup-trivy`.
Шаг «Signing readiness» в job `build`, который запускается и на `pull_request`, читает
`secrets.*` (`.github/workflows/build-apk.yml:84-92`): значение только сравнивается с пустой
строкой, но сам доступ к секретам из PR-ветки того же репозитория — лишняя поверхность.

Предложение: вынести публикацию в отдельный workflow с GitHub Environment (required reviewers,
environment-секреты), запинить все `uses:` полным SHA и добавить Dependabot для их обновления,
не использовать `pull_request_target`, а проверку готовности подписи заменить на статическую.

### 12. APK и checksum не перепроверяются в момент публикации

`sha256sum` и сверка сертификата выполняются в предыдущих шагах, а `scripts/release-policy.py`
проверяет только непустоту и уникальность имён ассетов (`scripts/release-policy.py:127-146`),
не сверяя `TgWatch.apk.sha256` с `TgWatch.apk`. Между проверкой и публикацией остаются
несколько шагов, включая сторонние action. Предложение: в шаге публикации повторить
`sha256sum -c TgWatch.apk.sha256`, `apksigner verify --print-certs`, сверить
`versionCode`/`versionName` из манифеста APK (`aapt2 dump badging`) с ожидаемыми и добавить
`actions/attest-build-provenance` для APK/SBOM.

### 13. Нет верификации Gradle wrapper и зависимостей

`gradle-wrapper.properties` без `distributionSha256Sum`, нет `gradle/verification-metadata.xml`
и шага `gradle/actions/wrapper-validation`, `pip install` без `--require-hashes`
(`scripts/requirements-release.txt` — 2 пакета, транзитивные не зафиксированы), нет
`.github/dependabot.yml`. SBOM хэширует то, что уже скачалось, но не подтверждает доверие к источнику.
Предложение: добавить `distributionSha256Sum`, wrapper-validation, `--write-verification-metadata sha256`
и `--dependency-verification=strict`, lock-файл pip с хэшами, Dependabot для gradle и actions.

### 14. Экспортированные получатели с самодельными action

`BootReceiver` объявлен `exported="true"` и слушает незащищённые `QUICKBOOT_POWERON` /
`com.htc.intent.action.QUICKBOOT_POWERON` (`AndroidManifest.xml:89-104`, `BootReceiver.kt:19-20`),
`StatusWidget` — `exported="true"` с action `ru.tgwatch.action.WIDGET_CLICK`
(`AndroidManifest.xml:75-87`, `StatusWidget.kt:42`). Любое приложение может отправить такой
broadcast и заставить приложение поднять foreground-службу и запустить проверку.
Предложение: для `BootReceiver` поставить `exported="false"` (защищённые системные broadcast
доставляются и неэкспортируемым получателям), кастомные QUICKBOOT-action убрать или проверять
отправителя; клик виджета вынести в отдельный неэкспортируемый receiver.

### 15. Возврат в приложение не инициирует проверку

`MainActivity.onResume` только запускает службу (`MainActivity.kt:344-350`), реакции на
`ACTION_SCREEN_ON`/`USER_PRESENT` нет. При профиле `BALANCED` asleep = 300 с
(`PowerProfile.kt:4`) пользователь после разблокировки до пяти минут видит устаревший статус
в приложении, виджете и плитке. Предложение: по `onResume` и `ACTION_SCREEN_ON` отправлять
`ACTION_CHECK_NOW` с троттлингом (например, не чаще 30 с).

---

## P2 — качество кода, UX, CI, документация

### 16. Устаревший `User-Agent`

`NetworkProbe.kt:180` отправляет `TgWatch/1.9 (Android)`, хотя версия — 1.12. Взять значение
из `BuildConfig.VERSION_NAME` (потребуется `buildFeatures { buildConfig = true }` в AGP 8)
или из одного константного объекта, чтобы строка не отставала от релиза.

### 17. R8/shrinkResources выключены

`app/build.gradle.kts:44-49`: `isMinifyEnabled = false`, `isShrinkResources` не задан, baseline
profile отсутствует. Код свободен от рефлексии, а в CI уже есть инструментальные тесты на API 29/35 —
включить R8 с точечными `-keep` для `StatusWidget`, `MonitorService`, `StatusTileService`,
`BootReceiver` и проверить смоук-тестами.

### 18. Версия живёт в нескольких местах

`versionName = "1.12"` и `versionCode = ciVersionCode ?: 13` (`app/build.gradle.kts:12-31`)
дублируются в README, CHANGELOG, `docs/releases/`, а тег собирается как `v{version}.{run_number}`
(`scripts/release-policy.py`). Локальная сборка получает `versionCode 13`, CI — `run_number + 100`:
воспроизводимость теряется, а коллизия после форка/сброса счётчика приведёт к отказу установки.
Предложение: один источник версии (`gradle.properties` или version catalog), вычисляемый
`versionCode = major*10000 + minor*100 + patch`, а run-number — только как суффикс сборки.

### 19. CI не использует кэш сборки и configuration cache

В `gradle.properties` нет `org.gradle.caching`, `org.gradle.configuration-cache`,
`org.gradle.parallel`, оба job собираются с `--no-daemon`
(`.github/workflows/build-apk.yml:35,143`), у job-ов нет `timeout-minutes`, а `scripts/android-smoke.sh`
собирает ещё и legacy-коммит внутри emulator-job. Включить кэши и таймауты — самый дешёвый
способ сократить время проверок.

### 20. Тесты: нет Robolectric, фаззинга парсеров и покрытия

Парсеры, которые принимают недоверенные данные (`MtProto.validResponse`,
`EventLogStorage.decode`, `PortableBackupCodec.decodePublishedPayload`, `CsvImport.parse`),
покрыты примерами, но не фаззингом; Android-зависимая логика (`Prefs`, `History`,
`MonitorService`) проверяется только инструментальными тестами на эмуляторе, а
`testOptions { unitTests.isReturnDefaultValues = true }` (`app/build.gradle.kts:56`) маскирует
ошибки обращения к Android API. Предложение: добавить Robolectric для быстрых тестов
`Prefs`/`History`/`MainActivity.render`, property/fuzz-тесты парсеров с бюджетом времени,
Kover с гейтом покрытия на пакет `ru.tgwatch` (policy-объекты).

### 21. Аудит-гейты невоспроизводимы

`scripts/dependency-audit.py` обращается к живому `api.osv.dev`, `scan-sbom.sh` тянет БД Trivy
в момент скана: один и тот же коммит может пройти и не пройти проверку, а «зелёный» релиз нельзя
воспроизвести. Фиксировать снапшот БД (кэш по дате или digest) и сохранять версию БД в отчёте-ассете.

### 22. Документация: версии и структура

README начинается с «TG Монитор 1.12», но первым же абзацем описывает объединение 1.11, а
`docs/superpowers/architecture.md` озаглавлен «Архитектура TgWatch 1.11». README — 28 КБ.
Предложение: индекс `docs/README.md`, актуализировать заголовки архитектуры, переименовать
`docs/superpowers/{plans,specs}` в `docs/plans`/`docs/specs`, вынести из README детали
в тематические страницы, добавить `ROADMAP.md` со ссылкой на открытые PR.

### 23. Сводка за день отправляется и при выключенном мониторинге

`DailySummaryReceiver.post` проверяет только настройку сводки
(`DailySummaryReceiver.kt:26-37`), но не `Prefs.isEnabled` и не свежесть данных: после
«Остановить» пользователь может получить уведомление с цифрами прошлых суток.
Добавить проверку `Prefs.isEnabled` и возраста последней проверки.

### 24. У тревожных уведомлений нет действий

`EventNotifications.notification()` (`EventNotifications.kt:84-99`) не добавляет `addAction`,
хотя у foreground-уведомления есть «Проверить» и «Остановить» (`MonitorService.kt:633-642`).
Добавить в outage-уведомление «Проверить сейчас» и «Стоп» — это экономит пользователю
открытие приложения.


### 25. Устаревшие API и отсутствующие UX-мелочи

`ProgressDialog` для бэкапа (`BackupUi.kt:6,136`), `startActivityForResult`/`onActivityResult`
(`MainActivity.kt:649-676`), `requestPermissions` — заменить на `ActivityResultLauncher`
(одна зависимость `androidx.activity`) и неблокирующий индикатор прогресса.
Нет `ShortcutManager` («Проверить сейчас», «Стоп»), нет отмены для «Очистить журнал»,
нет настроек таймаутов и антидребезга (`AlertRules.GAP_MS` зашит), срок хранения истории
жёстко 7 дней (`History.kt:41`), авто-бэкап по расписанию отсутствует.

### 26. Локализация и строки в коде

`Lang.ru` — глобальный `@Volatile`, строки собираются в коде (`Lang.kt:4-8`,
`MonitorService.kt:683-691`), lint-правило `HardcodedText` отключено (`app/build.gradle.kts:60`),
а после смены языка уведомление и плитка обновляются только при следующей проверке
(`TgWatchApplication.kt:21-26` обновляет лишь виджет). Предложение: тест-«страж», проверяющий,
что у каждого `Lang.t(...)` ровно один аргумент с кириллицей, обновление уведомления/плитки
в `onConfigurationChanged`, возврат `HardcodedText` для layout-файлов (сейчас в разметке
захардкоженного текста нет — правило можно включить без правок).

### 27. График игнорирует масштаб шрифта

`ChartView.kt:41` задаёт `textSize = 11f * density`, то есть в dp: при системном
`fontScale` 1.3–2.0 подписи графика не увеличиваются. Использовать `scaledDensity`
(или `TypedValue.applyDimension(COMPLEX_UNIT_SP, 11f, ...)`), а также поднять тач-таргеты
`Segment` (40dp) и `ButtonText` (44dp) до 48dp (`values/styles.xml:62-98`).

### 28. Мелкие улучшения производительности

`MtProto.probe` создаёт новый `SecureRandom` на каждую проверку (`MtProto.kt:49`) — вынести
в общий экземпляр; `ProbeBatch.occupied` использует `WeakHashMap` без очистки набора для
умершего пула (`NetworkProbe.kt:81-86`); `History.lastMinutes` пересобирает агрегаты для графика
при каждом вызове `render()` (`History.kt:138`, `MainActivity.kt:579`).

---

## Что уже сделано хорошо (не ломать при рефакторинге)

- Чистые policy-объекты без Android (`ProbeRules`, `AlertRules`, `OfflineBackoff`,
  `CheckSchedule`, `DailySummary`, `LastSuccessPolicy`, `Timeline`) — их удобно тестировать;
  это же направление стоит продолжить, вынося из `MonitorService` и `MainActivity` остатки логики.
- Нулевые сторонние runtime-зависимости (только `junit`/`androidx.test` для тестов) и отсутствие
  телеметрии — сильное конкурентное преимущество для такого приложения.
- Бэкап: AES-256-GCM с AAD по всему заголовку, строгие whitelist-схемы, лимиты размеров,
  проверка до применения, атомарная замена с durable-журналом восстановления, чтение двух
  legacy-форматов — сделано аккуратно.
- Парсер MTProto действительно проверяет всю структуру `resPQ` и кадр, а не только «есть байты».
- История: атомарная запись через `.tmp` + `ATOMIC_MOVE`, защита от отката часов эпохами,
  ограниченное хранение, честные gaps вместо выдуманных проверок.
- Релиз: immutable-теги, SBOM CycloneDX 1.6 с SHA-256 артефактов, OSV + Trivy как обязательные
  гейты, проверка совпадения сертификата с опубликованным релизом.
- Доступность и локализация: TalkBack-подписи у значка/виджета/графика, `values-ru`, per-app язык.


---

## Предлагаемый порядок работ

| Шаг | Что | Пункты | Ожидаемый эффект |
|---|---|---|---|
| 1.13.1 | Точность: контр-сайты при `PARTIAL`, валидация веб-ответов, IPv6 DC, фикс `isStale` | 1, 2, 4 | Меньше ложных «частично доступен», корректный индикатор свежести |
| 1.13.2 | Батарея: троттлинг записи настроек, watchdog без точных будильников, отказ от `USE_EXACT_ALARM` | 3, 5, 6, 7, 15 | Заметный выигрыш по пробуждениям и записи на флеш |
| 1.13.3 | Релиз: актуальный baseline обновления, повторная проверка APK, environment + SHA-пиннинг, wrapper/dependency verification | 10-13 | Проверяемый и воспроизводимый релизный процесс |
| 1.13.4 | UI/доступность: контраст, обрезка подписей, `fontScale`, тач-таргеты, действия в уведомлениях | 8, 24, 25, 27 | Соответствие WCAG и меньше ручных действий |
| 1.14 | Инфраструктура: событийный UI вместо тикера, R8, единый источник версии, кэш CI, Robolectric + фаззинг, Kover | 9, 17-21 | Быстрее CI, меньше регрессий, меньше APK |
| 1.14 | Продукт: настраиваемый срок хранения и авто-бэкап, настройка таймаутов/антидребезга, шорткаты | 23, 25, 28 | Больше контроля у пользователя |

---

## Приложение: готовые патчи для быстрых правок

### A. `isStale` — учесть переданные параметры (пункт 4)

```kotlin
// MonitorService.State
fun isStale(now: Long, intervalSec: Int, slowExpected: Boolean = false): Boolean {
    if (checkedAt <= 0L) return false
    if (now < checkedAt) return true
    return if (checkedElapsed > 0L)
        ProbeRules.isStale(checkedElapsed, SystemClock.elapsedRealtime(), expectedIntervalSec, slowExpected)
    else
        ProbeRules.isStale(checkedAt, now, expectedIntervalSec, slowExpected)
}
```

Вызывающим местам вместо `Prefs.intervalSec(...)` передавать `expectedIntervalSec` состояния
(он уже учитывает профиль, screen-off и backoff), а `slowExpected` — `Prefs.keepAwake(...)`.

### B. Контрольные сайты при любом неполном подтверждении (пункт 1)

```kotlin
// NetworkProbe.check()
val telegram = listOf(...) + MtProtoEndpoints.all
val results = ProbeBatch(pool).run(telegram, ::probe).toMutableList()
if (ProbeReport.classify(results) != "OK") {   // было: results.none { it.reachable }
    results += ProbeBatch(pool).run(controls, ::probe)
}
```

### C. `User-Agent` из версии сборки (пункт 16)

```kotlin
// app/build.gradle.kts, android { }
buildFeatures { buildConfig = true }

// NetworkProbe.kt
connection.setRequestProperty("User-Agent", "TgWatch/${BuildConfig.VERSION_NAME} (Android)")
```

### D. Кэш миграции настроек (пункт 6)

```kotlin
private val moved = java.util.concurrent.atomic.AtomicBoolean(false)
private fun store(ctx: Context): Context {
    val app = ctx.applicationContext
    if (Build.VERSION.SDK_INT < 24) return app
    val dp = app.createDeviceProtectedStorageContext()
    if (!moved.get()) {
        try {
            val um = app.getSystemService(UserManager::class.java)
            if (um == null || um.isUserUnlocked) {
                if (dp.moveSharedPreferencesFrom(app, FILE)) moved.set(true)
            }
        } catch (_: Exception) { }
    }
    return dp
}
```

Каждый патч стоит закрывать тестом: для A — юнит-тест на смену интервала и screen-off,
для B — тест `ProbeReport.classify` с «подменёнными» ответами, для D — инструментальный тест
на перенос настроек из обычного хранилища после разблокировки.

---

## Как проверялись выводы

- Все ссылки на строки — из текущего дерева (`b95e63d`); список релизов и тегов получен через
  `gh release list` / `git tag -l` (`v1.12.59` — latest).
- Контраст посчитан по WCAG 2.1 для фактических значений `values/colors.xml`.
- Обрезка подписей подтверждена по `docs/screenshots/main.png` («2 с на…», «Telegram за»).
- Сборка и тесты не запускались: в песочнице нет JDK и Android SDK, поэтому патчи выше
  приведены как предложение и требуют прогона `./gradlew testDebugUnitTest lintDebug assembleDebug`
  и эмуляторных сценариев перед слиянием.
