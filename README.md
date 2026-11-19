# ДЗшка

Android-приложение: записывает домашнее задание по расписанию и публикует его в Viber-канал через Channels Post API. Есть помощники (отдельный APK), студия оформления, тест-канал, архив ДЗ с календарём, виджет и кот-питомец.

## Сборка
- Нужен `keystore/dzshka.jks` и `keystore.properties` (не в репозитории) — ключ подписи хранится отдельно.
- `./gradlew assembleFullRelease` — основное приложение, `assembleHelperRelease` — помощник.

Готовые APK и обновления — в ветке `main` (файл `update.json` + `DZshka-*.apk`), приложение берёт их оттуда по кнопке «Обновить».

## Ключ подписи
Не в git. Копии: `/mnt/user-data/outputs/dzshka-signing/` (dzshka.jks + keystore.properties) и у Ильи на ПК `Downloads\DZshka-update\ключ-подписи\dzshka.jks`.
С 1.9.17 — новый ключ, SHA-256 44610de166ecc47c4b2b725a59614b23021a3cd66d559b5643b9dd3c6a2cf792 (старый aa268d50… потерян вместе с контейнером).
Перед сборкой: `cp /mnt/user-data/outputs/dzshka-signing/dzshka.jks keystore/ && cp /mnt/user-data/outputs/dzshka-signing/keystore.properties .`
