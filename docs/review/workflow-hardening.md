# Ужесточение workflow: готовый патч

Относится к пунктам 3, 5, 19 и 20 аудита [improvements-after-1.15.md](improvements-after-1.15.md).
Файлы под `.github/workflows/` из этой ветки не отправляются (у токена нет scope `workflows`),
поэтому правка подготовлена как патч:

    git apply docs/review/workflow-hardening.patch
    git add .github/workflows/build-apk.yml
    git commit -m "ci: harden the release workflow"

Патч проверен: применяется к текущему `main` без конфликтов, результат разбирается как YAML,
jobs не меняют состав; в песочнице проверены все шесть замен пути и наличие новых шагов.

## Что делает патч

| Изменение | Зачем |
|---|---|
| `persist-credentials: false` у всех checkout'ов | токен с `contents: write` больше не остаётся в `.git/config` рабочей копии релизной джобы, где между подписью и публикацией работают сторонние действия |
| `env: BUILD_TOOLS: "35.0.0"` и шесть замен `$ANDROID_HOME/build-tools/35.0.0/...` на `${{ env.BUILD_TOOLS }}` | одна точка правки при миграции тулчейна (для AGP 9 минимум — 36.0.0) |
| шаг «Check the pinned build tools are present» в `build` и `release` | отказ на первом шаге с понятным сообщением, а не в середине релизной джобы |
| `--dependency-verification=strict` в сборке | сборка падает, если артефакт не совпал с `gradle/verification-metadata.xml` |
| шаг «Refuse to publish from a modified or unexpected workspace» | публикация не может идти из каталога, который изменили после подписи |
| шаг «Re-download and verify the published assets» | опубликованный APK сверяется с собственным `sha256` после создания релиза |

## Про `gradle/verification-metadata.xml`

Файл добавлен в этой ветке (312 компонентов, 557 артефактов) и покрывает набор задач CI:
`detekt`, `testDebugUnitTest`, `lintDebug`, `assembleDebug`, `assembleRelease`,
`assembleDebugAndroidTest`, `dependencyInventory`, `generateReleaseSbom`.

Важно: сам факт наличия файла включает проверку зависимостей во всех сборках. После обновления
любой зависимости (в том числе через Dependabot) метаданные нужно дополнить:

    bash ./gradlew --write-verification-metadata sha256 <задачи, которые резолвят зависимости>

Эту строку стоит добавить в [CONTRIBUTING](../../CONTRIBUTING.md), иначе первый же PR
Dependabot по Gradle-зависимостям покраснеет на проверке хэшей.
