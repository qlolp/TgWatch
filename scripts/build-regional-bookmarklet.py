#!/usr/bin/env python3
"""Reproducible local bookmarklet installer; no remote scripts or runtime permissions."""
from pathlib import Path
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[1]


def render():
    source = (ROOT / 'tools/regional-browser-check.js').read_text()
    url = 'javascript:' + quote(source, safe='')
    return '''<!doctype html>
<html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>TgWatch — проверка открытого сервиса</title>
<style>body{font:18px/1.6 system-ui;max-width:720px;margin:3em auto;padding:0 1em}
a{display:inline-block;padding:.7em 1em;background:#225bcc;color:white;border-radius:8px}
li{margin:.7em 0}code{font-size:.9em}</style>
<h1>Проверка открытого сервиса</h1>
<ol><li>Перетащите кнопку на панель закладок браузера.</li>
<li>Откройте YouTube, Gemini, Notebook, Antigravity или ChatGPT/dots и войдите в свой аккаунт.</li>
<li>Нажмите сохранённую закладку. Она проверит видимое сообщение в этой вкладке.</li></ol>
<p><a href="''' + url + '''">TgWatch: проверить сервис</a></p>
<p>Проверка запускается только по нажатию. Она не делает сетевых запросов, не читает cookies
и не отправляет содержимое вкладки. Обычная страница не доказывает, что работают видео,
генерация или функции IDE: выполните нужное действие в самом сервисе.</p>
<p>Если браузер запрещает выполнение закладки, результат остаётся неподтверждённым.
Настройки защиты браузера менять не нужно.</p>
<h2>English</h2><p>Drag the button to the bookmarks bar, open a supported service, then click the saved
bookmark. This is one local visible-error check in the current session, without requests, cookies,
storage or page export. Normal content does not certify playback, generation or IDE readiness.</p>
</html>
'''


if __name__ == '__main__':
    (ROOT / 'tools/regional-browser-bookmarklet.html').write_text(render())
