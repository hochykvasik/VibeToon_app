import subprocess, os, shutil, re
# Запуск: python tools/gen_commits.py  (пересоздаёт docs/commits из git log)
repo = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
out = os.path.join(repo, 'docs', 'commits')
# Не удаляем старые файлы: история до импорта в этот репозиторий (коммиты форка) есть только в docs/commits.
os.makedirs(out, exist_ok=True)
old_rows = []
idx_path = os.path.join(out, 'INDEX.md')
if os.path.exists(idx_path):
    old_rows = [l for l in open(idx_path, encoding='utf-8').read().splitlines() if l.startswith('| 20')]

def git(*a):
    return subprocess.run(['git', '-C', repo, *a], capture_output=True, text=True, encoding='utf-8').stdout

log = git('log', '--format=%H|%h|%ad|%s', '--date=format:%Y-%m-%d %H:%M').strip().splitlines()
index = ['# Коммиты VibeToon', '', 'Один файл на коммит: что изменено и какие файлы затронуты. Новое сверху.',
         'Описание простыми словами - в `../CHANGELOG_RU.md`.', '', '| Дата | Коммит | Что |', '|---|---|---|']
new_rows = []
for line in log:
    full, short, date, subj = line.split('|', 3)
    if subj.startswith('Import VibeToon from fork'):
        continue   # снимок всего форка: его история уже лежит отдельными файлами
    name = f"{date[:10]}_{date[11:].replace(':','')}_{short}.md"
    body = git('show', '-s', '--format=%b', full).strip()
    body = re.sub(r'Co-Authored-By:.*', '', body).strip()
    stat = git('show', '--stat', '--format=', full).strip()
    txt = [f'# {short}: {subj}', '', f'Дата: {date}', f'Полный хеш: `{full}`', '',
           'Посмотреть код: `git show ' + short + '`', '']
    if body:
        txt += ['## Описание', '', body, '']
    txt += ['## Изменённые файлы', '', '```', stat, '```', '']
    open(os.path.join(out, name), 'w', encoding='utf-8').write('\n'.join(txt))
    new_rows.append(f'| {date} | [{short}]({name}) | {subj[:110]} |')
seen = {r.split('|')[2].strip() for r in new_rows}
index += new_rows + [r for r in old_rows if r.split('|')[2].strip() not in seen]
open(idx_path, 'w', encoding='utf-8').write('\n'.join(index) + '\n')
print(len(log))
