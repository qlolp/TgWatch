# Документация TgWatch

Начните с [описания приложения](../README.md) и [последнего опубликованного выпуска](https://github.com/qlolp/TgWatch/releases/latest).
Версия исходников хранится в [version.properties](../version.properties); история изменений — в [CHANGELOG](../CHANGELOG.md).

## Пользователю

| Задача | Документ |
| --- | --- |
| Установка, батарея, уведомления, история, CSV и виджеты | [Руководство](usage.md) |
| Matrix, WhatsApp Web и свои HTTPS-цели | [Цели и условия HTTPS](multiservice.md) |
| Региональные сервисы, страна, история и сравнение A/B | [Региональные проверки](regional-services.md) |
| Webhook, Home Assistant, ntfy и MQTT | [Автоматизация](automation.md) |
| Перенос настроек и истории | [Резервные копии и совместимость](backup.md) |
| Часы | [Wear OS](wear.md) |
| Приложение для строки меню Mac, встроенный Python и подпись | [macOS](macos.md) |
| English overview | [English documentation](README.en.md) |

## Разработчику и сопровождающему

| Задача | Документ |
| --- | --- |
| Локальная сборка, тесты, стиль и правила PR | [CONTRIBUTING](../CONTRIBUTING.md) |
| Версии, CI, выпуск и проверка обновлений | [Выпуски и разработка](release-and-development.md) |
| Постоянный ключ подписи | [Резервная копия ключа](signing-backup.md) |
| Компоненты и ограничения архитектуры | [Карта архитектуры](superpowers/architecture.md) |
| Диагностика, наблюдаемость, приватность отчётов | [Диагностика](diagnostics.md) |
| Физические Android/OEM и измерения батареи | [Протокол и матрица](power-validation.md) |
| F-Droid и границы готовности | [Подготовка дистрибутива](fdroid.md) |
| Следующие шаги и отложенные архитектурные решения | [Текущий roadmap](development-roadmap.md) |
| Ветки, старые workflows и восстановление после уборки | [Сопровождение репозитория](maintenance/README.md) |

## Исторические материалы

- `releases/` — заметки о конкретных версиях; сведения старого выпуска не описывают автоматически текущий.
- `review/` — аудиты и результаты исследования на указанную дату.
- `distribution/` — подготовка и evidence конкретных дистрибутивов, включая исторические exact-tag сборки.
- `superpowers/specs/` и `superpowers/plans/` — исходные требования и планы отдельных итераций.
- `maintenance/archive/` — завершённые одноразовые сценарии; GitHub Actions их не запускает.

Статус текущих возможностей и незавершённых внешних проверок поддерживается в одном [roadmap](development-roadmap.md).
Результат эмулятора не заменяет OEM-измерения, наличие signing pipeline — Apple notarization,
а подготовка F-Droid — принятие каталогом.
