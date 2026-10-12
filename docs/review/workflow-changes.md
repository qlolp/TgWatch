# Правка workflow для 1.14.0

Файл [build-apk.yml.proposed](build-apk.yml.proposed) — готовая замена файла
.github/workflows/build-apk.yml. В песочнице токен без scope workflows, поэтому
изменение не может быть отправлено в git автоматически: GitHub отклоняет push, который
меняет файлы под .github/workflows/. Примените файл вручную:

    cp docs/review/build-apk.yml.proposed .github/workflows/build-apk.yml
    git add .github/workflows/build-apk.yml
    git commit -m "ci: apply the reviewed workflow"

Тесты уже проверяют предложенный файл (scripts/tests/test_release_tooling.py),
поэтому он не «протухнет» молча: если содержимое разойдётся с правилами, проверки упадут.

## Что меняется

| Фрагмент | Было | Стало |
|---|---|---|
| Источники действий | теги v4, v2 | полные SHA коммитов с пометкой версии |
| Wrapper | не проверялся | шаг wrapper-validation и distributionSha256Sum в gradle-wrapper.properties |
| Python-зависимости | pip install по requirements-release.txt | pip install --require-hashes по requirements-release.lock |
| Baseline обновления | жёстко v1.10.47 с зашитым sha256 | scripts/upgrade-baseline.py выбирает предыдущий опубликованный релиз и сверяет его checksum |
| Проверка подписи | только сравнение с v1.10.47 | сравнение сертификатов предыдущего релиза и новой сборки; production-фингерпринт остаётся зашитым |
| Публикация | без повторной проверки артефакта | sha256sum -c, apksigner verify и сверка versionCode/versionName из aapt2 dump badging перед release-policy.py |
| Таймауты | не заданы | 45/90/60 минут для job-ов build/release/android |
| Шаг обновления | безусловный | выполняется только если baseline найден (upgrade_check) |

Остальные улучшения 1.14.0 (код, скрипты, lock-файл, Dependabot, checksum дистрибутива)
уже в ветке и не требуют прав владельца.
