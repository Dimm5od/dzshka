# ClaudeBridge — Claude в Minecraft

Пишешь в чат игры `@claude построй домик` — и Claude строит.

## Установка (один раз)

1. **На VPS установи Git:** git-scm.com → Download for Windows → везде «Next».
2. **Установи Claude Code:** открой PowerShell и вставь
   `irm https://claude.ai/install.ps1 | iex`
3. **Войди в аккаунт:** в PowerShell напиши `claude` и войди тем же аккаунтом, что и на claude.ai.
   Проверка: `claude -p "привет"` — должен прийти ответ.
4. **Положи `ClaudeBridge-1.0.jar`** в папку `plugins` сервера и перезапусти сервер.

## Как пользоваться

- В чате: `@claude сделай день и построй вокруг меня стеклянный купол`
- Или командой: `/claude построй башню`
- Ответ приходит через 5–30 секунд.
- Звать Claude может только **GriGold** (меняется в `plugins/ClaudeBridge/config.yml`).
- После изменения настроек: `/claude reload`

## Если не работает

- **«не нашёл программу Claude Code»** — в PowerShell напиши `where.exe claude`,
  скопируй путь и вставь в `config.yml` в строку `claude-path`, потом `/claude reload`.
- **Другие ошибки** — смотри файл `plugins/ClaudeBridge/work/errors.txt`.
- Сервер и Claude Code должны работать от **одного и того же пользователя Windows**.

## Для разработчика

Исходники в `ClaudeBridge/`. Сборка: `ClaudeBridge/build.sh` (нужна Java 21).
