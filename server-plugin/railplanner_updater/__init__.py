"""
Rail Planner 服务端自动更新插件（MCDR）

每 check_interval_minutes 分钟查一次 GitHub 上 phzt-hub/railplanner 的 release（默认连预发布一起跟），
发现比 mods/ 里的 jar 新，就下载、按 GitHub 给的 sha256 与字节数校验、把旧 jar 挪进备份目录、
换上新 jar，然后（auto_restart 开着时）重启服务端。

只认本体 jar：release 里还挂着 -sources 源码包，它同样是 .jar、sha256 与字节数也照样对得上，
但换上去服务端起不来（主类不在里面）。所以挑附件按名字精确挑，下载后开包验一遍主类与版本号，
换完第一次启动没走到 Done 就把备份滚回去。

命令：!!railupdate status / check / on / off / reload
"""

import hashlib
import json
import re
import shutil
import threading
import time
import urllib.request
import zipfile
from pathlib import Path
from typing import Optional, Tuple

from mcdreforged.api.all import *

PLUGIN_ID = 'railplanner_updater'
PREFIX = '!!railupdate'
USER_AGENT = 'RailPlannerUpdater/1.0.2'
API_URL = 'https://api.github.com/repos/{repo}/releases?per_page=10'
JAR_RE = re.compile(r'^railplanner-(.+)\.jar$')
# 同一个 release 里除本体外还挂着源码包，它们也以 .jar 结尾
NOT_MOD_JAR_RE = re.compile(r'-(sources?|dev|javadoc|slim|api)(-|\.|$)', re.IGNORECASE)
MOD_ID = 'railplanner'
MAIN_CLASS = 'com/phzt/railplanner/RailPlanner.class'


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
    jars = sorted(p for p in mods.glob('railplanner-*.jar')
                  if p.is_file() and not NOT_MOD_JAR_RE.search(p.name))
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

def pick_asset(rel: dict, version: str) -> Optional[dict]:
    """从 release 的附件里挑出本体 jar，挑不到返回 None。

    以前是「第一个以 .jar 结尾的附件」，可 release 里还挂着 -sources 源码包：
    上传顺序一变就把源码包当本体换上去（0.8.2 那次服务端就是这么起不来的）。
    现在先找名字正好是 railplanner-<版本>.jar 的，找不到再退一步排除源码包。
    """
    jars = [a for a in (rel.get('assets') or []) if (a.get('name') or '').endswith('.jar')]
    exact = 'railplanner-{}.jar'.format(version)
    for asset in jars:
        if (asset.get('name') or '') == exact:
            return asset
    for asset in jars:
        name = asset.get('name') or ''
        if JAR_RE.match(name) and not NOT_MOD_JAR_RE.search(name):
            return asset
    return None


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
        asset = pick_asset(rel, (rel.get('tag_name') or '').lstrip('vV'))
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
    validate_jar(dest)
    return digest.hexdigest()


def validate_jar(path: Path) -> str:
    """确认真的是能跑的模组本体，返回包里的版本号。

    sha256 和字节数只证明「下到的东西跟 GitHub 上那个附件一模一样」，证明不了那个
    附件是本体 —— -sources 源码包同样一个字不差。所以还得开包看：有没有
    fabric.mod.json、主类在不在、版本号是不是还留着 ${version} 占位符。
    """
    with zipfile.ZipFile(path) as jar:
        names = set(jar.namelist())
        if 'fabric.mod.json' not in names:
            raise RuntimeError('包里没有 fabric.mod.json，不是模组')
        if MAIN_CLASS not in names:
            raise RuntimeError('包里没有主类 {}（多半下到了 -sources 源码包）'.format(MAIN_CLASS))
        meta = json.loads(jar.read('fabric.mod.json').decode('utf-8'))
    if meta.get('id') != MOD_ID:
        raise RuntimeError('包的模组 id 是 {}，不是 {}'.format(meta.get('id'), MOD_ID))
    version = str(meta.get('version') or '')
    if not version or '${' in version:
        raise RuntimeError('包里的版本号是 {!r}，这是没替换过占位符的构建'.format(version))
    return version


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
    server.logger.info('[RailPlanner] 下载完成：{} 字节，sha256 {}，包内版本 {}'.format(
        asset.get('size'), sha[:12], validate_jar(tmp)))

    backup_path: Optional[Path] = None
    if cfg.keep_backup and current_path is not None:
        backup = mods / '.railplanner-backup'
        backup.mkdir(exist_ok=True)
        target = backup / current_path.name
        try:
            if target.exists():
                target.unlink()
            current_path.replace(target)
            backup_path = target
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

    _arm_rollback(mods, final, backup_path)

    if cfg.auto_restart and server.is_server_running():
        delay = max(3, int(cfg.restart_delay_seconds))
        server.say('§e[Rail Planner] 服务端模组更新到 {}，{} 秒后重启'.format(latest, delay))
        # MCDR 的 schedule_task 不接受 delay，这里用一个后台计时器
        timer = threading.Timer(delay, lambda: _restart(server))
        timer.daemon = True
        timer.start()
    return _last_result


def _restart(server: PluginServerInterface):
    global _expect_restart
    if not server.is_server_running():
        return
    server.logger.info('[RailPlanner] 重启服务端以加载新版本')
    _expect_restart = True
    server.restart()


# ---------------------------------------------------------------- 起没起来

# 换完 jar 到看到 Done 之间，服务端停一次就说明新 jar 有问题。
# 必须在这个 stop 事件里同步回滚：MCDR 的 EXIT_AFTER_STOP 在事件派发结束后就会让
# MCDR 自己退出（0.8.2 那次，服务端一起不来 MCDR 就 bye 了），等定时器再回滚已经晚了。
_armed: Optional[dict] = None      # 刚换上的 jar，等着看它能不能起来
_expect_restart = False            # 这次 stop 是我们自己重启引起的，不是崩
ROLLBACK_WINDOW = 180              # 只在换完 jar 的头三分钟里认「没起来」


def _arm_rollback(mods: Path, jar: Path, backup: Optional[Path]) -> None:
    global _armed
    if backup is None or not backup.is_file():
        _armed = None
        return
    _armed = {
        'mods': mods,
        'jar': jar,
        'backup': backup,
        'version': version_of(jar) or jar.name,
        'at': time.time(),
    }


def on_server_start(server: PluginServerInterface):
    global _expect_restart
    _expect_restart = False


def on_server_startup(server: PluginServerInterface):
    global _armed
    if _armed is not None:
        server.logger.info('[RailPlanner] {} 已启动完成，不回滚'.format(_armed['version']))
        _armed = None


def on_server_stop(server: PluginServerInterface, return_code: int):
    global _armed
    armed, _armed = _armed, None
    if armed is None or _expect_restart:
        return
    waited = int(time.time() - armed['at'])
    if waited > ROLLBACK_WINDOW:
        server.logger.warning('[RailPlanner] 服务端停了（离换 jar 已经 {} 秒），不动 jar'.format(waited))
        return
    server.logger.error('[RailPlanner] {} 没能启动（返回码 {}），回滚到 {}'.format(
        armed['version'], return_code, armed['backup'].name))
    try:
        for old in armed['mods'].glob('railplanner-*.jar'):
            if old.is_file() and not NOT_MOD_JAR_RE.search(old.name):
                old.unlink()
        shutil.copy2(armed['backup'], armed['jar'])
    except OSError as e:
        server.logger.error('[RailPlanner] 回滚失败：{}'.format(e))
        return
    server.logger.warning('[RailPlanner] 已换回 {}，重新拉起服务端'.format(armed['jar'].name))
    try:
        server.start()
    except Exception as e:
        server.logger.error('[RailPlanner] 回滚后拉起服务端失败：{}'.format(e))


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

    mods = mods_dir(server)
    jar = installed_jar(mods)
    if jar is not None:
        try:
            validate_jar(jar)
        except Exception as e:
            server.logger.error('[RailPlanner] mods 里的 {} 有问题：{}'.format(jar.name, e))

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
