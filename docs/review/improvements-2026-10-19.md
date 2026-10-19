# Аудит 1.20.1 и улучшения 2026-10-19

Проверено дерево `main` версии 1.20.1: сервис, история и её журналы, цели, вебхук,
диагностика, Wear-модуль, релизные артефакты. Ниже — что вошло в эту итерацию, что
сознательно отложено и по каким проверкам это видно.

## Вошло в эту итерацию

1. **Локальный журнал падений.** `CrashJournal` записывает класс исключения, имя потока
   и кадры стека; текст исключения не сохраняется. Последующее усиление проверяет
   метаданные кода и исключает произвольные имена потоков и пути, в том числе
   при чтении старого журнала и на границе экспорта. Журнал ограничен пятью записями, 32 КиБ и 8 КиБ
   на запись, лежит в device-protected storage, переживает смерть процесса и
   экспортируется только явным документом диагностики (схема 4). Очищается вместе
   с журналом событий.
2. **Диагностика: схема 4.** Добавлен блок `crashes` (`retained`, `last.{at_ms, thread,
   type, frames}`); allowlist и предел 16 КиБ сохранены, `LocalDiagnostics` передаёт
   срез журнала. Обновлены JVM- и instrumentation-тесты схемы.
3. **StrictMode в debug.** `DebugStrictMode` включает `detectDiskReads`,
   `detectDiskWrites`, `detectNetwork` с `penaltyLog`; release и поведение на устройстве
   не меняются. Это делает главный поток наблюдаемым до того, как ANR заметит пользователь.
4. **Размер Wear-APK.** AGP хранит dex без сжатия при `minSdk >= 28`; в `:wear`
   включён `packaging.dex.useLegacyPackaging = true`. Проверено локальной сборкой
   тем же тулчейном: часы 11 062 209 → **4 195 629 Б** (−62 %), dex 10 429 324 Б
   сжимается, а не исчезает; телефон 2 591 839 Б и не менялся по существу
   (+668 Б от журнала падений и схемы 4).
5. **Гейты качества.** `scripts/check-detekt-baseline.py` запрещает рост baseline
   (сейчас 408 записей), `scripts/check-apk-size.py` проверяет бюджет телефона (3 МиБ)
   и часов (6 МиБ — с запасом к 4,2 МиБ и заметно ниже прежних 11 МиБ). Оба покрыты
   регрессиями в `scripts/tests/test_quality_guards.py` и запускаются в `build-apk.yml`.

## Отложено осознанно

- **Долгая история и окна обслуживания.** Дизайн — в
  [спеке](../superpowers/specs/2026-10-19-retention-and-maintenance-design.md).
  Причина отсрочки: rollup меняет формат журнала и бэкап, поэтому сначала нужны
  измерения памяти и времени агрегаций на 30/90 днях и отдельный `targetBackupHeapTest`
  для v4. Менять `History.KEEP_MS` без этого — регрессия, а не улучшение.
- **R8 и baseline profile.** Ломаются не сборкой, а отражением и поведением на устройстве;
  нужен прогон release-APK на матрице 26/29/35/36, а не только `assembleRelease`.
  Порядок: сжатие dex (сделано) → R8 с keep-правилами и smoke → baseline profile.
- **Per-target вебхук.** Требует отдельного приватного хранилища на цель, UI и миграции
  формы настроек; текущая единственная точка остаётся документированной границей.
- **`USE_EXACT_ALARM` и `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.** Для GitHub/F-Droid это
  допустимо; для Google Play нужна отдельная политика разрешений и fallback на неточные
  будильники. Решение продуктовое, не кодовое.

## Проверки этой итерации

```sh
python3 -m unittest scripts.tests.test_quality_guards -v
python3 scripts/check-detekt-baseline.py config/detekt/baseline.xml --max 408
bash ./gradlew :app:detekt :app:testDebugUnitTest :app:lintDebug :app:assembleRelease \
  :wear:testDebugUnitTest :wear:lintDebug :wear:assembleRelease --no-daemon
```

В этой среде JDK и Android SDK отсутствовали по умолчанию; проверка выполнялась
локально установленным JDK17 и SDK 35/36. Прогон `:app:detekt`,
`:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleRelease` и
`:wear:assembleRelease` завершился успешно, включая новые тесты `CrashJournalTest`
и обновлённую схему диагностики.
