import subprocess, os, shutil, re
# Запуск: python tools/gen_commits.py  (пересоздаёт docs/commits из git log)
repo = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
out = os.path.join(repo, 'docs', 'commits')
shutil.rmtree(out, ignore_errors=True)
os.makedirs(out)

def git(*a):
    return subprocess.run(['git', '-C', repo, *a], capture_output=True, text=True, encoding='utf-8').stdout

log = git('log', '--format=%H|%h|%ad|%s', '--date=format:%Y-%m-%d %H:%M').strip().splitlines()
index = ['# Коммиты VibeToon', '', 'Один файл на коммит: что изменено и какие файлы затронуты. Новое сверху.',
         'Описание простыми словами - в `../CHANGELOG_RU.md`.', '', '| Дата | Коммит | Что |', '|---|---|---|']
for line in log:
    full, short, date, subj = line.split('|', 3)
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
    index.append(f'| {date} | [{short}]({name}) | {subj[:110]} |')
open(os.path.join(out, 'INDEX.md'), 'w', encoding='utf-8').write('\n'.join(index) + '\n')
print(len(log))
