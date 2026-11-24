"""Проверка словаря pairs.py + pairs2.py (спринт 30).

Запуск: python3 tools/i18n/check_pairs.py [--fix]
Ошибки (код возврата 1):
  - строка без «|» или с пустой половиной;
  - русский ключ встречается несколько раз (gen.py молча берёт первый перевод);
  - английский перевод содержит кириллицу;
  - в английском переводе «keyframe(s)»: единый термин - «key(s)».
С --fix повторные строки (дубликаты ключа) удаляются, остаётся первая - та, что уже попадает в strings.xml.
Совпадающие английские переводы у разных русских синонимов («Картинка»/«Изображение» -> Image) допустимы и выводятся как справка.
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
FILES = [('pairs.py', 'PAIRS'), ('pairs2.py', 'PAIRS2')]
CYR = re.compile('[А-Яа-яЁё]')
KEYFRAME = re.compile(r'key\s*-?frames?', re.I)
# названия языков в списке выбора пишутся на самом языке (эндонимы)
ENDONYMS = {'Русский'}


def blocks():
    """(файл, номер строки, текст) для каждой строки внутри блока PAIRS/PAIRS2."""
    for name, var in FILES:
        path = os.path.join(HERE, name)
        lines = open(path, encoding='utf-8').read().split('\n')
        inside = False
        for i, line in enumerate(lines):
            if not inside:
                if line.startswith(var + ' = """'):
                    inside = True
                continue
            if line.startswith('"""'):
                break
            yield name, i, line


def main(fix):
    errors, seen, drop = [], {}, {}
    for name, i, line in blocks():
        where = f'{name}:{i + 1}'
        if '|' not in line:
            errors.append(f'{where}: нет «|»: {line!r}')
            continue
        ru, en = line.split('|', 1)
        if not ru.strip() or not en.strip():
            errors.append(f'{where}: пустая половина: {line!r}')
            continue
        if ru.startswith('#'):
            continue  # заголовок-комментарий pairs2 (содержит «|», gen.py его пропускает как обычный ключ)
        if ru in seen:
            first_where, first_en = seen[ru]
            kind = 'дубликат' if first_en == en else f'конфликт ({first_en!r} / {en!r})'
            errors.append(f'{where}: {kind} ключа {ru!r}, первая строка {first_where}')
            drop.setdefault(name, set()).add(i)
        else:
            seen[ru] = (where, en)
        if CYR.search(en) and en not in ENDONYMS:
            errors.append(f'{where}: кириллица в переводе: {en!r}')
        if KEYFRAME.search(en):
            errors.append(f'{where}: «keyframe» в переводе - используйте «key»: {en!r}')
    by_en = {}
    for ru, (_, en) in seen.items():
        by_en.setdefault(en, []).append(ru)
    shared = {en: rus for en, rus in by_en.items() if len(rus) > 1}
    if fix and drop:
        for name, idx in drop.items():
            path = os.path.join(HERE, name)
            lines = open(path, encoding='utf-8').read().split('\n')
            open(path, 'w', encoding='utf-8', newline='\n').write('\n'.join(l for k, l in enumerate(lines) if k not in idx))
        print('удалено повторов:', sum(len(v) for v in drop.values()))
        return main(False)
    print(f'ключей: {len(seen)}, общих английских переводов у синонимов: {len(shared)}')
    for e in errors:
        print('ОШИБКА', e)
    return 1 if errors else 0


if __name__ == '__main__':
    sys.exit(main('--fix' in sys.argv))
