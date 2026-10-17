# G — Distribution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Подготовить достоверные материалы установки и применить согласованные metadata репозитория.

**Architecture:** Сначала обновляются исходные материалы и проверяемый F-Droid package. GitHub metadata применяются как отдельная обратимая операция с чтением результата, принятие F-Droid остаётся внешним фактом.

**Tech Stack:** Kotlin/JVM 17, Android Views, Gradle 8.14.6, AGP 8.13.2, Kotlin 2.4.21, JUnit 4; дополнения указаны в задачах.

**Spec:** [Утверждённая спецификация](../specs/2026-10-17-distribution-design.md), [общие ограничения](../specs/2026-10-17-growth-design.md).

## Global Constraints

- Телефон: Android API 26+, compile/target API 35; Kotlin и существующий Views UI.
- Один foreground-сервис; нет сервера автора, учётной записи или фоновой телеметрии.
- Дополнительные цели и внешние интеграции выключены по умолчанию.
- Недоступность измерения, отсутствие сети и измеренный сбой сервиса различаются.
- `claude/project-thread-s0ejzl` не изменяется, не удаляется и не используется как рабочая ветка.
- Существующие API v1, виджет Telegram и чтение бэкапов v1/v2 сохраняются.
- Новые пользовательские строки добавляются на русском и английском.
- Не обещать звёзды, охват, одобрение магазина или независимую reproducibility.
- F-Droid candidate не становится опубликованным приложением после одного lint.
- Existing GitHub release tags/assets immutable; production certificate не меняется.

## Review Focus

- README обещает ещё не выпущенную возможность — сверять с конкретным тегом (Task 1).
- Новый F-Droid APK подписан другим ключом — нельзя обещать обновление поверх GitHub APK (Task 2).
- Source build ошибочно назван byte reproducibility — отдельное сравнение payload (Task 2).
- Нет доступа к GitHub API — материалы готовы, применение metadata честно blocked (Task 3).
- Labels/outreach создаются без подходящих задач/адресата — нет массовых назначений и автоматической рассылки (Task 3).

---

План подготовлен для согласования; ни один checkbox не означает уже выполненную реализацию.
Пути ниже относительно корня репозитория; команды выполняются из `/workspace/TgWatch`
после `source /workspace/.tgwatch-setup/env.sh`. Android-команды требуют отдельного
тестового эмулятора/устройства; отсутствие устройства обозначается как unrun.

### Task 1: Актуальные описания, скриншоты и безопасные шаблоны

**Files:**
- Modify: `README.md`, `docs/README.en.md`, `docs/development-roadmap.md`, `docs/fdroid.md`.
- Modify: `fastlane/metadata/android/en-US/full_description.txt`, `fastlane/metadata/android/ru-RU/full_description.txt` и существующие short descriptions.
- Create: `docs/distribution/repository-metadata.json`, `docs/distribution/submission.md`.
- Create, если отсутствуют: `.github/ISSUE_TEMPLATE/bug_report.yml`, `.github/ISSUE_TEMPLATE/feature_request.yml`, `.github/pull_request_template.md`.
- Update: `docs/screenshots/main.png`, `docs/screenshots/settings.png` только после фактического UI соответствующего этапа.

**Interfaces:**
- Consumes: выпущенный код/тег, release assets, dependencyInventory, существующие store metadata.
- Produces: repository-metadata.json с description/topics/labels и отметкой релиза, которому соответствует описание.
- До C description: `Private Android reachability monitor for Telegram. Local history, alerts and diagnostics.`
- После выпуска C: `Private Android reachability monitor for Telegram, Matrix, WhatsApp Web and custom HTTPS services.`
- Topics: android, kotlin, telegram, network-monitoring, privacy, network-diagnostics, uptime-monitoring, fdroid.

- [ ] **Step 1: Сверить факты.** Прочитать latest tag/version.properties/подпись, публичные description/topics и существующие templates; проверить реальный runtime inventory, а не утверждение «нет runtime dependencies».
- [ ] **Step 2: Обновить материалы.** Фактический тулчейн 8.14.6/8.13.2/2.4.21; sourceVersion из тега; RU/EN границы протокольных проб, установка, backup перед сменой сертификата, latest APK и checksum. В templates добавить API/version/ожидаемое/наблюдаемое и безопасный export без обязательных raw logs.
- [ ] **Step 3: Подготовить скриншоты на disposable emulator.** Fixture history/targets без реальных адресов и токенов. На текущем main не показывать мультисервис до его релиза; после C/E обновить текст и изображения вместе.
- [ ] **Step 4: Проверить материалы.** `python3 -m unittest discover -s scripts/tests -p test_unified_release.py`; проверить локальные ссылки и прямые APK/checksum URLs. Параметры `full_description`/short text сверить с существующими store-limit tests. Если создан YAML template, выполнить синтаксический parse существующим YAML toolchain.
- [ ] **Step 5: Зафиксировать только фактически изменённые перечисленные пути.** Коммит `docs: explain verified installation and released monitoring capabilities`.

### Task 2: Проверяемый пакет F-Droid

**Files:**
- Modify: `metadata/ru.tgwatch.yml`, `docs/fdroid.md`, `docs/distribution/submission.md`.
- Modify: соответствующие текущему коду changelogs в `fastlane/metadata/android/{en-US,ru-RU}/changelogs/`.
- Existing checks: `scripts/tests/test_unified_release.py`, `scripts/tests/test_semver_release.py`.

**Interfaces:**
- Consumes: immutable source tag/main commit, unsigned assembly, production release APK/SBOM.
- Produces: candidate recipe и submission package с source/tag/license/foreground/exact-alarm объяснением; никакого signing secret.
- Certificate GitHub и F-Droid могут различаться: рецепт не обещает in-place upgrade без независимого upstream-signature workflow.

- [ ] **Step 1: Проверить кандидат на точном теге.** Установить поддержанный fdroidserver в isolated environment через штатный setup, не редактируя pinned product dependencies; проверить metadata schema/категории и tag commit/versionCode.
- [ ] **Step 2: Выполнить `fdroid readmeta` и `fdroid lint ru.tgwatch` в fdroiddata staging checkout.** Сохранить версии инструментов, stdout/exit status; исправить recipe, не отключать валидацию ради зелёного результата.
- [ ] **Step 3: Собрать независимый unsigned APK.** С точного тега `bash ./gradlew :app:assembleRelease --no-daemon` без signing env. Проверить applicationId/min/target/version и license/dependency inventory. При наличии fdroid build capability выполнить также build по candidate recipe.
- [ ] **Step 4: Отдельно проверить reproducibility.** Независимый source APK сравнить с upstream signed APK поддержанным F-Droid/apksigcopier workflow; source build без сравнения не означает byte reproducibility. При несовпадении записать причину, не заявлять upstream-signature поддержку. Обновить submission с реальными evidence.
- [ ] **Step 5: Зафиксировать candidate package.** `git add -- metadata/ru.tgwatch.yml docs/fdroid.md docs/distribution/submission.md` и только нужные changelogs; коммит `docs: prepare verified F-Droid submission candidate`. Заявка третьей стороне этим шагом не отправляется.

### Task 3: Применить metadata и зафиксировать границу внешней публикации

**Files:**
- Modify: `docs/distribution/repository-metadata.json`, `docs/distribution/submission.md` после фактической проверки.
- External reversible configuration: description/topics и существующие/недостающие labels репозитория qlolp/TgWatch.

**Interfaces:**
- Consumes: согласованный package Task 1, текущий release stage и доступные штатные GitHub credentials/proxy.
- Produces: применённые и повторно прочитанные description/topics; labels good first issue/help wanted при наличии подходящих задач. Статусы prepared/applied/blocked в submission.

- [ ] **Step 1: Проверить специфичный read/write доступ штатным GitHub tool/API.** Успешный git fetch не доказывает API write; не извлекать proxy credentials и не запрашивать PAT до проверки имеющихся bindings.
- [ ] **Step 2: Применить только актуальные значения.** Через structured GitHub tool либо `gh api --method PATCH repos/qlolp/TgWatch --input /tmp/tgwatch-about.json` передать точный description из Task1. Topics обновить `gh api --method PUT repos/qlolp/TgWatch/topics --input /tmp/tgwatch-topics.json`; временные JSON-файлы содержат проверенный набор approved+existing topics, без shell interpolation. Имеющиеся unrelated topics не удалять. Создать только отсутствующие labels без массового назначения.
- [ ] **Step 3: Повторно прочитать configuration.** Сравнить фактические description/topics/label names с package. API denial означает blocked application, не блокирует docs/recipe; сохранить точные unapplied values без секретов.
- [ ] **Step 4: Подготовить внешнюю заявку.** В submission указать конкретный F-Droid канал, адресата, текст и приложенные evidence для отдельного согласования отправки. Не писать авторам/сообществам и не отправлять PR/заявку третьей стороне без явной инструкции отправить.
- [ ] **Step 5: Сохранить фактический результат.** Коммит `docs: record repository metadata and submission readiness`; не записывать «F-Droid published» до подтверждённого принятия каталогом.

## Проверка этапа

Записать точный тег, Android version code, certificate, toolchain, результаты fdroid lint/build
и статус repository metadata. Различить подготовленный submission package, отправленную
заявку и фактическое принятие. Для editorial изменений не писать тесты, повторяющие текст.
Использовать существующие проверки source/version/store metadata/release policies.


После прохождения проверок записать их фактические результаты в описание PR.
Новые версии, теги и ассеты не публиковать из тестовых шагов. Интеграция и выпуск
проходят существующие проверки подписи, upgrade и source/APK/asset integrity.
