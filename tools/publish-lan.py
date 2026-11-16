#!/usr/bin/env python3
"""把 Rail Planner 的 jar 发到内网发布站（railplannerrelease.phzt.lan）。

默认只发内网：不碰 GitHub、不碰 CurseForge。要发公网是另一条路（见 tools/ 里的发版记录），
而且发 GitHub 时记得同一版也跑一遍本脚本，别让内网 release 通道落在后面。

站点结构（静态目录 /home/phzt/main/railplanner-release，8788 端口，railplanner-release.service）：

    index.html            两通道入口
    release/latest.json   正式通道：最新一版（GitHub release 对象同构，改源只换 base URL）
    release/index.json    正式通道清单
    release/railplanner-<版本>.jar
    debug/latest.json     debug 通道：最新一版
    debug/index.json      debug 通道清单
    debug/railplanner-<版本>-debug-<yyyymmddHHMM>.jar

用法：
    tools/publish-lan.py                        # debug 通道，发 build/libs 里最新的包
    tools/publish-lan.py --build                # 先 ./gradlew25 build 再发
    tools/publish-lan.py --channel release      # 正式通道（服务端默认从这里拉）
    tools/publish-lan.py --jar /path/x.jar --channel debug
    tools/publish-lan.py --list                 # 只看站上现在有什么
"""

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.request
import zipfile
from datetime import datetime
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_ROOT = Path('/home/phzt/main/railplanner-release')
DEFAULT_BASE = 'http://railplannerrelease.phzt.lan'
MOD_ID = 'railplanner'
MAIN_CLASS = 'com/phzt/railplanner/RailPlanner.class'
CHANNELS = ('release', 'debug')
DEFAULT_KEEP = 5


# ------------------------------------------------------------------ 小工具

def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def human(n: int) -> str:
    for unit in ('B', 'KB', 'MB'):
        if n < 1024 or unit == 'MB':
            return '{} {}'.format(n if unit == 'B' else round(n, 1), unit)
        n /= 1024.0
    return str(n)


def now_local(ts: float = None) -> str:
    return datetime.fromtimestamp(time.time() if ts is None else ts).astimezone().isoformat(timespec='seconds')


def load_json(path: Path, default):
    if not path.is_file():
        return default
    try:
        with open(path, encoding='utf-8') as f:
            return json.load(f)
    except (json.JSONDecodeError, OSError):
        return default


def save_json(path: Path, data) -> None:
    tmp = path.with_suffix(path.suffix + '.tmp')
    with open(tmp, 'w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False, indent=2, sort_keys=False)
        f.write('\n')
    os.replace(tmp, path)


# ------------------------------------------------------------------ 包检查

def read_mod_version(jar: Path) -> str:
    """开包读 fabric.mod.json：只认本体（有主类、id 对、版本号不是占位符）。

    和两端更新器用的是同一套判据 —— sha256 只能证明「下到的东西跟站上那份一样」，
    证明不了那份是本体，所以发布前先把关节卡在入口。
    """
    if not zipfile.is_zipfile(jar):
        raise SystemExit('{} 不是 jar/zip'.format(jar))
    with zipfile.ZipFile(jar) as z:
        names = set(z.namelist())
        if 'fabric.mod.json' not in names:
            raise SystemExit('{} 里没有 fabric.mod.json，不是模组'.format(jar.name))
        if MAIN_CLASS not in names:
            raise SystemExit('{} 里没有主类，多半是 -sources 源码包'.format(jar.name))
        meta = json.loads(z.read('fabric.mod.json').decode('utf-8'))
    if meta.get('id') != MOD_ID:
        raise SystemExit('{} 的模组 id 是 {}，不是 {}'.format(jar.name, meta.get('id'), MOD_ID))
    version = str(meta.get('version') or '').strip()
    if not version or '${' in version:
        raise SystemExit('{} 里的版本号是 {!r}，这是没替换占位符的构建'.format(jar.name, version))
    return version


def newest_jar() -> Path:
    jars = [p for p in (REPO_ROOT / 'build' / 'libs').glob('railplanner-*.jar')
            if p.is_file() and '-sources' not in p.name and '-dev' not in p.name]
    if not jars:
        raise SystemExit('build/libs 里没有 railplanner 本体 jar，先跑 ./gradlew25 build')
    return max(jars, key=lambda p: p.stat().st_mtime)


# ------------------------------------------------------------------ 清单与页面

def channel_dir(root: Path, channel: str) -> Path:
    return root / channel


def entries_of(root: Path, channel: str) -> list:
    data = load_json(channel_dir(root, channel) / 'index.json', {'files': []})
    files = data.get('files') if isinstance(data, dict) else None
    return files if isinstance(files, list) else []


def write_channel_pages(root: Path, channel: str, base: str) -> dict:
    files = sorted(entries_of(root, channel), key=lambda e: e.get('added_at', ''), reverse=True)
    save_json(channel_dir(root, channel) / 'index.json',
              {'channel': channel, 'generated_at': now_local(), 'files': files,
               'count': len(files), 'total_bytes': sum(e.get('size', 0) for e in files)})

    keep = files[:1]
    latest = None
    if keep:
        e = keep[0]
        latest = {
            'tag_name': 'v' + e['version'],
            'name': 'Rail Planner {}'.format(e['version']),
            'channel': channel,
            'html_url': '{}/{}/'.format(base, channel),
            'published_at': e.get('added_at', ''),
            'assets': [{
                'name': e['name'],
                'size': e['size'],
                'digest': 'sha256:' + e['sha256'],
                'browser_download_url': '{}/{}/{}'.format(base, channel, e['name']),
                'updated_at': e.get('added_at', ''),
            }],
        }
        save_json(channel_dir(root, channel) / 'latest.json', latest)
    elif (channel_dir(root, channel) / 'latest.json').is_file():
        (channel_dir(root, channel) / 'latest.json').unlink()

    rows = []
    for e in files:
        rows.append(
            '<tr><td><a href="{name}">{name}</a></td><td>{ver}</td><td>{size}</td>'
            '<td class="sha">{sha}</td><td>{at}</td></tr>'.format(
                name=e['name'], ver=e['version'], size=human(e['size']),
                sha=e['sha256'][:16], at=e.get('added_at', '')[:19].replace('T', ' ')))
    if not rows:
        rows.append('<tr><td colspan="5" class="empty">这一通道还没有包</td></tr>')

    html = CHANNEL_HTML.format(
        channel=channel,
        title='正式版' if channel == 'release' else 'debug 版',
        count=len(files),
        total=human(sum(e.get('size', 0) for e in files)),
        generated=now_local(),
        rows='\n'.join(rows),
        latest=('<p class="latest">最新：<code>{}</code>（{}）</p>'.format(
            latest['assets'][0]['name'], latest['published_at'][:19].replace('T', ' '))
            if latest else '<p class="latest empty">还没有包</p>'),
    )
    (channel_dir(root, channel) / 'index.html').write_text(html, encoding='utf-8')
    return latest or {}


def notes_card(root: Path) -> str:
    """首页的「待查清单」入口：站根有 待查清单.html 就挂上，没有就不挂。"""
    page = root / '待查清单.html'
    if not page.exists():
        return ''
    n, updated = 0, ''
    md = root / '待查清单.md'
    if md.exists():
        try:
            text = md.read_text(encoding='utf-8')
            head = text.split('## 已结案')[0]
            n = sum(1 for line in head.splitlines() if line.startswith('## R-'))
            updated = now_local(md.stat().st_mtime)
        except OSError:
            pass
    return ('<div class="card">\n'
            '  <h2><a href="待查清单.html">待查清单</a> <span class="count">待查 {} 条</span></h2>\n'
            '  <p class="hint">已知问题与实测排除记录，只收 railplanner 的</p>\n'
            '  <a class="pkg" href="待查清单.html">待查清单.html</a>'
            '<div class="meta">原文 待查清单.md · 更新于 {}</div>\n'
            '</div>').format(n, updated)


def write_root_page(root: Path, base: str) -> None:
    cards = []
    for channel in CHANNELS:
        entries = sorted(entries_of(root, channel), key=lambda e: e.get('added_at', ''), reverse=True)
        if entries:
            e = entries[0]
            body = ('<a class="pkg" href="{}/{}">{}</a>'
                    '<div class="meta">{} · {} · sha256 {}… · {}</div>').format(
                channel, e['name'], e['name'], human(e['size']), e['version'], e['sha256'][:16],
                e.get('added_at', '')[:19].replace('T', ' '))
        else:
            body = '<div class="meta empty">还没有包</div>'
        cards.append(ROOT_CARD.format(
            channel=channel,
            title='正式版 release' if channel == 'release' else 'debug 版',
            hint=('服务端默认从这里拉（channel=release）' if channel == 'release'
                  else '自己手动下载的调试包，只留最近 5 个'),
            body=body, count=len(entries)))
    cards.append(notes_card(root))
    (root / 'index.html').write_text(ROOT_HTML.format(
        base=base, cards='\n'.join(cards), generated=now_local()), encoding='utf-8')


ROOT_CARD = '''<div class="card">
  <h2><a href="{channel}/">{title}</a> <span class="count">{count} 个</span></h2>
  <p class="hint">{hint}</p>
  {body}
</div>'''

ROOT_HTML = '''<!doctype html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Rail Planner 内网发布站</title>
<style>
  /* 配色/字体/圆角取自 release.phzt.lan 的 apk 下载页（同一套 CSS 变量），改一处要同步三页 */
  :root{{ color-scheme: light dark; --fg:#111; --dim:#666; --bg:#f4f5f7; --card:#fff;
         --line:#e3e5e8; --ok:#0a7a3d; --okbg:#e7f6ed; --warn:#9a2b00; --warnbg:#fdeee8;
         --accent:#2f6fed; --code:#f2f4f7; --th:#f7f8fa; }}
  @media (prefers-color-scheme: dark) {{
    :root{{ --fg:#e8e8ea; --dim:#9aa0a6; --bg:#16181c; --card:#1e2126; --line:#2e3238;
           --ok:#6ee7a0; --okbg:#12331f; --warn:#ffa07a; --warnbg:#3a1d12;
           --accent:#7cb0ff; --code:#262a31; --th:#23272d; }}
  }}
  *{{ box-sizing:border-box; }}
  body{{ margin:0; padding:16px; background:var(--bg); color:var(--fg);
        font:15px/1.55 -apple-system,"Segoe UI","Noto Sans CJK SC","PingFang SC","Microsoft YaHei",sans-serif; }}
  .wrap{{ max-width:720px; margin:0 auto; }}
  h1{{ font-size:20px; margin:0 0 4px; }}
  .sub{{ margin:0 0 14px; color:var(--dim); font-size:13px; }}
  .sub a{{ color:var(--accent); text-decoration:none; }}
  .card{{ background:var(--card); border:1px solid var(--line); border-radius:10px;
         padding:13px 14px 14px; margin-bottom:12px; }}
  .card h2{{ font-size:16px; margin:0 0 2px; display:flex; flex-wrap:wrap; align-items:baseline; gap:8px; }}
  .card h2 a{{ color:var(--accent); text-decoration:none; font-size:19px; font-weight:650; }}
  .card h2 a:hover{{ text-decoration:underline; }}
  .hint{{ margin:0 0 8px; color:var(--dim); font-size:13px; }}
  .pkg{{ font-family:ui-monospace,Menlo,Consolas,monospace; font-size:12.5px; color:var(--accent);
        text-decoration:none; word-break:break-all; }}
  .pkg:hover{{ text-decoration:underline; }}
  .meta{{ margin-top:3px; color:var(--dim); font-size:11.5px;
         font-family:ui-monospace,Menlo,Consolas,monospace; }}
  .count{{ color:var(--dim); font-weight:400; font-size:12.5px; }}
  .empty{{ color:var(--dim); }}
  footer{{ color:var(--dim); font-size:12px; text-align:center; margin:18px 0 8px; }}
</style></head><body>
<div class="wrap">
<h1>Rail Planner 内网发布站</h1>
<div class="sub">{base} —— 默认只发这里；GitHub / CurseForge 要你点头才推</div>
{cards}
<footer>生成于 {generated} · 发布工具 tools/publish-lan.py</footer>
</div>
</body></html>
'''


CHANNEL_HTML = '''<!doctype html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Rail Planner {channel} 通道</title>
<style>
  /* 配色/字体/圆角取自 release.phzt.lan 的 apk 下载页（同一套 CSS 变量），改一处要同步三页 */
  :root{{ color-scheme: light dark; --fg:#111; --dim:#666; --bg:#f4f5f7; --card:#fff;
         --line:#e3e5e8; --ok:#0a7a3d; --okbg:#e7f6ed; --warn:#9a2b00; --warnbg:#fdeee8;
         --accent:#2f6fed; --code:#f2f4f7; --th:#f7f8fa; }}
  @media (prefers-color-scheme: dark) {{
    :root{{ --fg:#e8e8ea; --dim:#9aa0a6; --bg:#16181c; --card:#1e2126; --line:#2e3238;
           --ok:#6ee7a0; --okbg:#12331f; --warn:#ffa07a; --warnbg:#3a1d12;
           --accent:#7cb0ff; --code:#262a31; --th:#23272d; }}
  }}
  *{{ box-sizing:border-box; }}
  body{{ margin:0; padding:16px; background:var(--bg); color:var(--fg);
        font:15px/1.55 -apple-system,"Segoe UI","Noto Sans CJK SC","PingFang SC","Microsoft YaHei",sans-serif; }}
  .wrap{{ max-width:980px; margin:0 auto; }}
  h1{{ font-size:20px; margin:0 0 4px; }}
  .sub{{ margin:0 0 14px; color:var(--dim); font-size:13px; }}
  .sub a{{ color:var(--accent); text-decoration:none; }}
  .latest{{ background:var(--card); border:1px solid var(--line); border-radius:10px;
           padding:11px 14px; margin:0 0 14px; font-size:13px; }}
  .latest code{{ font-family:ui-monospace,Menlo,Consolas,monospace; font-size:12.5px; }}
  table{{ border-collapse:collapse; width:100%; font-size:13px; }}
  th,td{{ text-align:left; padding:9px 10px; border-bottom:1px solid var(--line); }}
  th{{ color:var(--dim); font-weight:500; font-size:12px; }}
  td.sha,.meta{{ font-family:ui-monospace,Menlo,Consolas,monospace; color:var(--dim); font-size:12px; }}
  a{{ color:var(--accent); text-decoration:none; }}
  a:hover{{ text-decoration:underline; }}
  .empty{{ color:var(--dim); }}
  footer{{ color:var(--dim); font-size:12px; margin-top:18px; text-align:center; }}
</style></head><body>
<div class="wrap">
<h1>{title} · {channel} 通道</h1>
<div class="sub"><a href="../">← 回首页</a>　共 {count} 个文件 / {total}</div>
{latest}
<table><tr><th>文件</th><th>版本</th><th>大小</th><th>sha256</th><th>发布时间</th></tr>
{rows}
</table>
<footer>生成于 {generated}</footer>
</div>
</body></html>
'''


# ------------------------------------------------------------------ 发布

def publish(args) -> int:
    root = Path(args.root)
    base = args.base.rstrip('/')
    channel = args.channel
    for ch in CHANNELS:
        channel_dir(root, ch).mkdir(parents=True, exist_ok=True)

    if args.build:
        print('== 构建 ==')
        subprocess.run([str(REPO_ROOT / 'gradlew25'), 'build'], cwd=str(REPO_ROOT), check=True)

    jar = Path(args.jar).resolve() if args.jar else newest_jar()
    version = read_mod_version(jar)
    stamp = datetime.now().strftime('%Y%m%d%H%M')
    if channel == 'debug':
        # 版本号本身带 -debug 时（gradle mod_version=0.9.1-debug）别再叠一个
        core = version[:-len('-debug')] if version.lower().endswith('-debug') else version
        name = 'railplanner-{}-debug-{}.jar'.format(core, stamp)
    else:
        if version.lower().endswith('-debug'):
            raise SystemExit('{} 是 debug 构建，别发 release 通道（那会让正式版的人装到调试包）；用 --channel debug'.format(version))
        name = 'railplanner-{}.jar'.format(version)

    dest = channel_dir(root, channel) / name
    shutil.copy2(jar, dest)
    size = dest.stat().st_size
    sha = sha256_of(dest)
    entry = {
        'name': name, 'version': version, 'channel': channel,
        'size': size, 'sha256': sha, 'added_at': now_local(),
        'source_jar': jar.name,
    }

    files = [e for e in entries_of(root, channel) if e.get('name') != name]
    files.append(entry)
    files.sort(key=lambda e: e.get('added_at', ''), reverse=True)

    keep = [e for e in files[:max(1, args.keep)]]
    dropped = files[len(keep):]
    for e in dropped:
        p = channel_dir(root, channel) / e['name']
        if p.is_file() and p.name != name:
            p.unlink()

    save_json(channel_dir(root, channel) / 'index.json',
              {'channel': channel, 'generated_at': now_local(), 'files': keep})
    latest = write_channel_pages(root, channel, base)
    write_root_page(root, base)

    url = '{}/{}/{}'.format(base, channel, name)
    print('== 发布 ==')
    print('  通道　：{}'.format(channel))
    print('  版本　：{}'.format(version))
    print('  文件　：{}（{} 字节）'.format(name, size))
    print('  sha256：{}'.format(sha))
    print('  地址　：{}'.format(url))
    if dropped:
        print('  清掉　：{}'.format(', '.join(e['name'] for e in dropped)))

    if args.verify:
        return verify(base, channel, name, size, sha, latest)
    return 0


def verify(base: str, channel: str, name: str, size: int, sha: str, latest: dict) -> int:
    """从站上把刚发的包拉回来逐字节核对 —— 发版不核 sha256 等于没发。"""
    print('== 核对 ==')
    ok = True
    try:
        with urllib.request.urlopen('{}/{}/latest.json'.format(base, channel), timeout=15) as r:
            code = r.status
            data = json.load(r)
        asset = (data.get('assets') or [{}])[0]
        print('  latest.json HTTP {}，tag {}，asset {}'.format(code, data.get('tag_name'), asset.get('name')))
        if code != 200 or asset.get('name') != name:
            print('  !! latest.json 指向的不是刚发的包')
            ok = False
    except Exception as e:
        print('  !! latest.json 拉不到：{}'.format(e))
        return 1

    try:
        url = '{}/{}/{}'.format(base, channel, name)
        h = hashlib.sha256()
        got = 0
        with urllib.request.urlopen(url, timeout=60) as r:
            for chunk in iter(lambda: r.read(1 << 16), b''):
                h.update(chunk)
                got += len(chunk)
        same = (got == size and h.hexdigest() == sha)
        print('  下载　HTTP {}，{} 字节，sha256 {} {}'.format(
            r.status, got, h.hexdigest()[:16], '一致' if same else '不一致'))
        ok = ok and same
    except Exception as e:
        print('  !! 下载失败：{}'.format(e))
        ok = False
    return 0 if ok else 1


def list_site(args) -> int:
    root = Path(args.root)
    base = args.base.rstrip('/')
    for channel in CHANNELS:
        files = entries_of(root, channel)
        print('== {} 通道（{} 个）'.format(channel, len(files)))
        for e in sorted(files, key=lambda x: x.get('added_at', ''), reverse=True):
            print('  {:<44} {:>9} B  {}  {}'.format(
                e['name'], e['size'], e['sha256'][:12],
                e.get('added_at', '')[:19].replace('T', ' ')))
        print('  最新：{}/{}/latest.json'.format(base, channel))
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description='发 Rail Planner 包到内网发布站（默认 debug 通道）')
    p.add_argument('--channel', choices=CHANNELS, default='debug', help='release=正式（服务端默认从这里拉）/ debug=调试包')
    p.add_argument('--jar', help='要发的 jar，默认 build/libs 里最新那个本体')
    p.add_argument('--build', action='store_true', help='先跑 ./gradlew25 build')
    p.add_argument('--keep', type=int, default=DEFAULT_KEEP, help='每个通道保留几个（默认 5）')
    p.add_argument('--root', default=os.environ.get('RR_LAN_ROOT', str(DEFAULT_ROOT)), help='站点的静态目录')
    p.add_argument('--base', default=os.environ.get('RR_LAN_BASE', DEFAULT_BASE), help='站点对外地址')
    p.add_argument('--no-verify', dest='verify', action='store_false', help='跳过发完后的 HTTP 核对')
    p.add_argument('--list', action='store_true', help='只看站上有什么')
    args = p.parse_args()
    return list_site(args) if args.list else publish(args)


if __name__ == '__main__':
    sys.exit(main())
