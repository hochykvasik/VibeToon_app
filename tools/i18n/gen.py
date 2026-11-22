"""Генератор строк интерфейса: pairs.py + pairs2.py (русский|английский) -> res/values/strings.xml, res/values-en/strings.xml, ui/i18n/I18nTable.kt.
Запуск: python tools/i18n/gen.py. В pairs2 {} - подставляемое значение (шаблон), \\n - перевод строки."""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from pairs import PAIRS
from pairs2 import PAIRS2

R = os.path.join(HERE, '..', '..', 'app', 'src', 'main')

seen = {}
for line in (PAIRS.strip() + '\n' + PAIRS2.strip()).split('\n'):
    ru, en = line.split('|', 1)
    ru = ru.replace('\\n', '\n')
    en = en.replace('\\n', '\n')
    if ru not in seen:
        seen[ru] = en

keys = {}
used = set()
for ru, en in seen.items():
    k = 'ui_' + (re.sub(r'[^a-z0-9]+', '_', en.lower()).strip('_')[:36] or 'str')
    base, i = k, 2
    while k in used:
        k = f'{base}_{i}'
        i += 1
    used.add(k)
    keys[ru] = k


def esc(s):
    s = s.replace('\\', '\\\\').replace("'", "\\'").replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '\\"')
    if s.startswith(('@', '?')):
        s = '\\' + s
    return s.replace('\n', '\\n')


def write(path, items, ru_side):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    out = ['<?xml version="1.0" encoding="utf-8"?>', '<!-- ' + ('RU: основной язык интерфейса' if ru_side else 'EN: English UI strings') + ' (генерируется tools/i18n/gen.py) -->', '<resources>']
    for ru, en in items:
        out.append(f'    <string name="{keys[ru]}">{esc(ru if ru_side else en)}</string>')
    out.append('</resources>')
    open(path, 'w', encoding='utf-8', newline='\n').write('\n'.join(out) + '\n')


items = list(seen.items())
write(os.path.join(R, 'res', 'values', 'strings.xml'), items, True)
write(os.path.join(R, 'res', 'values-en', 'strings.xml'), items, False)


def kq(t):
    return '"' + t.replace('\\', '\\\\').replace('"', '\\"').replace('$', '\\$').replace('\n', '\\n') + '"'


exact = [(ru, k) for ru, k in keys.items() if '{}' not in ru]
tmpl = [(ru, k) for ru, k in keys.items() if '{}' in ru]
tmpl.sort(key=lambda t: -len(t[0].replace('{}', '')))
kt = ['package com.vibetoon.ui.i18n', '', 'import com.vibetoon.R', '',
      '/** Таблица «русский текст интерфейса -> ресурс строки». Текст в коде остаётся русским (он же ключ), перевод берётся из strings.xml. Генерируется tools/i18n/gen.py. */',
      'val I18N_IDS: Map<String, Int> = mapOf(']
for ru, k in exact:
    kt.append('    ' + kq(ru) + ' to R.string.' + k + ',')
kt.append(')')
kt += ['', '/** Шаблоны с подстановкой: регулярное выражение русской строки (группы = значения) -> ресурс; от самых конкретных к общим. */',
       'val I18N_TEMPLATES: List<Pair<String, Int>> = listOf(']
for ru, k in tmpl:
    rx = '^' + '(.+?)'.join(re.escape(p) for p in ru.split('{}')) + '$'
    kt.append('    ' + kq(rx) + ' to R.string.' + k + ',')
kt.append(')')
p = os.path.join(R, 'java', 'com', 'vibetoon', 'ui', 'i18n')
os.makedirs(p, exist_ok=True)
open(os.path.join(p, 'I18nTable.kt'), 'w', encoding='utf-8', newline='\n').write('\n'.join(kt) + '\n')
print(len(keys), 'strings,', len(tmpl), 'templates')
