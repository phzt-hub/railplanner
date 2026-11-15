"""
Rail Planner 服务端自动更新插件（MCDR）

每 check_interval_minutes 分钟查一次 GitHub 上 phzt-hub/railplanner 的 release（默认连预发布一起跟），
发现比 mods/ 里的 jar 新，就下载、按 GitHub 给的 sha256 与字节数校验、把旧 jar 挪进备份目录、
换上新 jar，然后（auto_restart 开着时）重启服务端。

命令：!!railupdate status / check / on / off / reload
"""

import hashlib
import json
import re
import threading
import time
import urllib.request
import zipfile
from pathlib import Path
from typing import Optional, Tuple

from mcdreforged.api.all import *

PLUGIN_ID = 'railplanner_updater'
PREFIX = '!!railupdate'
USER_AGENT = 'RailPlannerUpdater/1.0.0'
API_URL = 'https://api.github.com/repos/{repo}/releases?per_page=10'
JAR_RE = re.compile(r'^railplanner-(.+)\.jar$')


class Config(Serializable):
    enabled: bool = True
    repo: str = 'phzt-hub/railplanner'
    check_interval_minutes: int = 30
    include_prerelease: bool = True
    auto_restart: bool = True
    restart_delay_seconds: int = 15
    keep_backup: bool = True


config: Optional[Config] = None
_plugin_server: Optional[PluginServerInterface] = None
_stop = threading.Event()
_thread: Optional[threading.Thread] = None
_last_result = '还没查过'


# ---------------------------------------------------------------- 路径与版本

def mods_dir(server: PluginServerInterface) -> Path:
    mcdr_cfg = server.get_mcdr_config()
    world_dir = mcdr_cfg.get('working_directory', 'server')
    return (Path.cwd() / world_dir / 'mods').resolve()


def installed_jar(mods: Path) -> Optional[Path]:
    jars = sorted(p for p in mods.glob('railplanner-*.jar') if p.is_file())
    return jars[-1] if jars else None


def version_of(path: Path) -> Optional[str]:
    m = JAR_RE.match(path.name)
    return m.group(1) if m else None


def version_key(v: str) -> Tuple:
    """把 0.7.0 / v0.7.0-rc1 这类版本号拍成可比较的元组。"""
    nums = re.findall(r'\d+', v or '')
    return tuple(int(x) for x in nums[:6]) if nums else (0,)


def is_newer(candidate: str, current: Optional[str]) -> bool:
    if current is None:
        return True
    a = list(version_key(candidate))
    b = list(version_key(current))
    n = max(len(a), len(b))
    a += [0] * (n - len(a))
    b += [0] * (n - len(b))
    return tuple(a) > tuple(b)


# ---------------------------------------------------------------- 查 GitHub

def fetch_latest(repo: str, include_prerelease: bool) -> Optional[Tuple[dict, dict]]:
    req = urllib.request.Request(
        API_URL.format(repo=repo),
        headers={'User-Agent': USER_AGENT, 'Accept': 'application/vnd.github+json'},
    )
    with urllib.request.urlopen(req, timeout=20) as resp:
        releases = json.load(resp)
    best = None
    for rel in releases:
        if rel.get('draft'):
            continue
        if rel.get('prerelease') and not include_prerelease:
            continue
        asset = next((a for a in rel.get('assets') or [] if (a.get('name') or '').endswith('.jar')), None)
        if asset is None:
            continue
        stamp = rel.get('published_at') or rel.get('created_at') or ''
        if best is None or stamp > best[2]:
            best = (rel, asset, stamp)
    if best is None:
        return None
    return best[0], best[1]


def download(asset: dict, dest: Path) -> str:
    req = urllib.request.Request(asset['browser_download_url'], headers={'User-Agent': USER_AGENT})
    digest = hashlib.sha256()
    size = 0
    with urllib.request.urlopen(req, timeout=180) as resp, open(dest, 'wb') as f:
        while True:
            chunk = resp.read(1 << 16)
            if not chunk:
                break
            digest.update(chunk)
            f.write(chunk)
            size += len(chunk)
    want_sha = (asset.get('digest') or '')
    if want_sha.startswith('sha256:') and digest.hexdigest() != want_sha[len('sha256:'):]:
        raise RuntimeError('sha256 校验不符')
    if asset.get('size') and size != asset['size']:
        raise RuntimeError('字节数不符（{} ≠ {}）'.format(size, asset['size']))
    if not zipfile.is_zipfile(dest):
        raise RuntimeError('下来的不是 jar/zip')
    return digest.hexdigest()


# ---------------------------------------------------------------- 更新一步

def check_and_update(server: PluginServerInterface, manual: bool = False) -> str:
    global _last_result
    cfg = config
    if cfg is None:
        return '插件还没加载完'
    if not cfg.enabled and not manual:
        _last_result = '已关闭自动更新'
        return _last_result

    mods = mods_dir(server)
    if not mods.is_dir():
        _last_result = '找不到 mods 目录：{}'.format(mods)
        return _last_result

    current_path = installed_jar(mods)
    current = version_of(current_path) if current_path else None

    found = fetch_latest(cfg.repo, cfg.include_prerelease)
    if found is None:
        _last_result = 'GitHub 上没有找到带 jar 的 release'
        return _last_result
    rel, asset = found
    latest = (rel.get('tag_name') or '').lstrip('vV')

    if not is_newer(latest, current):
        _last_result = '已是最新：{}'.format(current or '（没有 jar）')
        return _last_result

    server.logger.info('[RailPlanner] 发现新版本 {}（当前 {}），开始下载'.format(latest, current))
    tmp = mods / '.railplanner-download.tmp'
    try:
        sha = download(asset, tmp)
    except Exception:
        if tmp.exists():
            tmp.unlink()
        raise
    server.logger.info('[RailPlanner] 下载完成：{} 字节，sha256 {}'.format(asset.get('size'), sha[:12]))

    if cfg.keep_backup and current_path is not None:
        backup = mods / '.railplanner-backup'
        backup.mkdir(exist_ok=True)
        target = backup / current_path.name
        try:
            if target.exists():
                target.unlink()
            current_path.replace(target)
        except OSError as e:
            server.logger.warning('[RailPlanner] 备份旧 jar 失败：{}'.format(e))

    for old in mods.glob('railplanner-*.jar'):
        try:
            old.unlink()
        except OSError as e:
            server.logger.warning('[RailPlanner] 删旧 jar 失败：{}'.format(e))

    final = mods / 'railplanner-{}.jar'.format(latest)
    tmp.replace(final)
    server.logger.info('[RailPlanner] 已换上新 jar：{}'.format(final.name))
    _last_result = '已更新到 {}'.format(latest)

    if cfg.auto_restart and server.is_server_running():
        delay = max(3, int(cfg.restart_delay_seconds))
        server.say('§e[Rail Planner] 服务端模组更新到 {}，{} 秒后重启'.format(latest, delay))
        # MCDR 的 schedule_task 不接受 delay，这里用一个后台计时器
        timer = threading.Timer(delay, lambda: _restart(server))
        timer.daemon = True
        timer.start()
    return _last_result


def _restart(server: PluginServerInterface):
    if not server.is_server_running():
        return
    server.logger.info('[RailPlanner] 重启服务端以加载新版本')
    server.restart()


# ---------------------------------------------------------------- 定时

def _loop(server: PluginServerInterface):
    # 刚起来先等一会儿，别和开服抢资源
    if _stop.wait(60):
        return
    while not _stop.is_set():
        try:
            result = check_and_update(server)
            server.logger.info('[RailPlanner] 检查结果：{}'.format(result))
        except Exception as e:
            server.logger.warning('[RailPlanner] 检查更新失败：{}'.format(e))
        interval = max(5, int(config.check_interval_minutes)) * 60 if config else 1800
        if _stop.wait(interval):
            return


# ---------------------------------------------------------------- 命令

def _status_text(server: PluginServerInterface) -> str:
    mods = mods_dir(server)
    jar = installed_jar(mods)
    lines = [
        '§b[Rail Planner] 服务端模组更新',
        '  当前 jar：{}'.format(jar.name if jar else '（mods 里没有）'),
        '  自动更新：{}'.format('开' if config.enabled else '关'),
        '  跟的仓库：{}　预发布：{}'.format(config.repo, '跟' if config.include_prerelease else '不跟'),
        '  间隔：{} 分钟　换完：{}'.format(
            config.check_interval_minutes,
            '{} 秒后自动重启'.format(config.restart_delay_seconds) if config.auto_restart else '只换 jar，不重启'),
        '  上次结果：{}'.format(_last_result),
    ]
    return '\n'.join(lines)


def on_load(server: PluginServerInterface, old):
    global config, _plugin_server, _thread
    _plugin_server = server
    config = server.load_config_simple('config.json', target_class=Config)
    server.register_help_message(PREFIX, 'Rail Planner 服务端模组自动更新')

    root = (
        Literal(PREFIX).
        runs(lambda src: src.reply(_status_text(server))).
        then(Literal('status').runs(lambda src: src.reply(_status_text(server)))).
        then(Literal('check').requires(lambda src: src.has_permission(PermissionLevel.ADMIN)).runs(lambda src: _manual_check(src, server))).
        then(Literal('on').requires(lambda src: src.has_permission(PermissionLevel.ADMIN)).runs(lambda src: _toggle(src, server, True))).
        then(Literal('off').requires(lambda src: src.has_permission(PermissionLevel.ADMIN)).runs(lambda src: _toggle(src, server, False))).
        then(Literal('reload').requires(lambda src: src.has_permission(PermissionLevel.ADMIN)).runs(lambda src: _reload(src, server)))
    )
    server.register_command(root)

    _stop.clear()
    _thread = threading.Thread(target=_loop, args=(server,), name='railplanner-updater', daemon=True)
    _thread.start()
    server.logger.info('[RailPlanner] 自动更新已加载：仓库 {}，每 {} 分钟查一次，换完{}'.format(
        config.repo, config.check_interval_minutes,
        '自动重启' if config.auto_restart else '不自动重启'))


def on_unload(server: PluginServerInterface):
    _stop.set()


def _manual_check(source, server):
    source.reply('§e[RailPlanner] 开始检查…')
    try:
        source.reply('§a[RailPlanner] ' + check_and_update(server, manual=True))
    except Exception as e:
        source.reply('§c[RailPlanner] 检查失败：{}'.format(e))


def _toggle(source, server, value: bool):
    config.enabled = value
    server.save_config_simple(config, 'config.json')
    source.reply('§a[RailPlanner] 自动更新 → {}'.format('开' if value else '关'))


def _reload(source, server):
    global config
    config = server.load_config_simple('config.json', target_class=Config)
    source.reply('§a[RailPlanner] 配置已重读：间隔 {} 分钟，换完{}'.format(
        config.check_interval_minutes, '自动重启' if config.auto_restart else '不自动重启'))
