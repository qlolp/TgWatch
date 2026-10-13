# Ужесточение workflow: точный патч

Относится к пунктам 3, 19 и 20 аудита [improvements-after-1.15.md](improvements-after-1.15.md).
Файлы под `.github/workflows/` в этой ветке не меняются (у токена нет scope `workflows`),
поэтому ниже — точные вставки для ручного применения.

## 1. Токен записи не должен лежать в рабочем каталоге

Checkout релизной джобы (`contents: write`) не задаёт `persist-credentials: false`, а
между шагом подписи и публикацией исполняются сторонние действия с эмулятором. Достаточно
не оставлять `.git/config` с токеном и проверить чистоту рабочей копии перед мутацией.

```yaml
      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4
        with:
          fetch-depth: 0
          persist-credentials: false
```

Шаг перед «Publish versioned release»:

```yaml
      - name: Refuse to publish from a modified workspace
        run: |
          set -euo pipefail
          test "$(git rev-parse HEAD)" = "$GITHUB_SHA"
          git diff --exit-code
          git diff --cached --exit-code
          test -z "$(git status --porcelain)"
```

Ограничение: если какие-то шаги сознательно пишут артефакты в корень репозитория
(сейчас так и есть — `TgWatch.apk`, отчёты), проверка должна игнорировать только
заданный список файлов, иначе шаг будет падать. Безопасный вариант с белым списком:

```yaml
          git status --porcelain | grep -vE '^(\?\?) (TgWatch\.|publish-|previous-|release-|badging|signature)' | grep . && exit 1 || true
```

## 2. Один источник версии build-tools

Шесть абсолютных путей `$ANDROID_HOME/build-tools/35.0.0/...` ломаются при смене
тулчейна (для AGP 9 минимум — 36.0.0). Заменить на переменную и раннюю проверку:

```yaml
env:
  BUILD_TOOLS: "35.0.0"

# первый шаг джобы после checkout
test -x "$ANDROID_HOME/build-tools/$BUILD_TOOLS/apksigner" || \
  yes | sdkmanager --install "build-tools;$BUILD_TOOLS" > /dev/null
test -x "$ANDROID_HOME/build-tools/$BUILD_TOOLS/apksigner"
```

и далее везде `"$ANDROID_HOME/build-tools/$BUILD_TOOLS/apksigner"` и
`"$ANDROID_HOME/build-tools/$BUILD_TOOLS/aapt2"`.

## 3. Инварианты APK внутри политики публикации

Сейчас пара APK↔checksum, подпись и версия проверяются отдельным шагом workflow, а
`scripts/release-policy.py` валидирует только непустые уникальные файлы и
JSON-доказательства. Перенести проверку в `publish()` (она уже реализована для
checksum в `verify_checksum`), добавив сверку production-сертификата и версии из
`version.properties`, чтобы защита не зависела от порядка шагов.

## 4. Пост-проверка опубликованных ассетов

```yaml
      - name: Re-download the published assets
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          set -euo pipefail
          version=$(sed -n 's/^version=//p' version.properties)
          mkdir -p published
          gh release download "v$version" --repo "$GITHUB_REPOSITORY" --dir published
          (cd published && sha256sum -c TgWatch.apk.sha256)
```

Дополнительно (по желанию) — `actions/attest-build-provenance` для APK и SBOM.
