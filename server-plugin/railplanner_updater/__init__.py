"""
Rail Planner 服务端自动更新插件（MCDR）

每 check_interval_minutes 分钟查一次更新：默认查内网发布站 railplannerrelease.phzt.lan 的
release 通道（source=lan），也可以在 config.json 里改成 source=github 去查 phzt-hub/railplanner。
channel=debug 时改查同一站点的 debug 通道：那边的 tag 恒为 v0.9.1-debug，同一个版本号会连发
好几个包，所以先后不比 tag，而是比包名尾巴上的构建时间戳（见 release_key）。
发现比 mods/ 里的 jar 新，就下载、按站点给的 sha256 与字节数校验、把旧 jar 挪进备份目录、
换上新 jar，然后（auto_restart 开着时）重启服务端。两个源不自动互退：内网站挂了就报错不动手，
不会悄悄转去公网装包（装了什么、从哪儿装的，日志里必须一眼能看出来）。

只认本体 jar：release 里还挂着 -sources 源码包，它同样是 .jar、sha256 与字节数也照样对得上，
但换上去服务端起不来（主类不在里面）。所以挑附件按名字精确挑，下载后开包验一遍主类与版本号，
换完第一次启动没走到 Done 就把备份滚回去。

手动部署（1.1.4 起）：check_interval_minutes 设成 0 就不轮询了，改成盯 MCDR 根目录下的
railplanner_deploy.request —— 文件一出现就拉一次（内容可带 channel / countdown），换完 jar
先广播倒计时再重启，处理结果写进 railplanner_deploy.result 给外部脚本读。tools/deploy-live.sh
就是往这个文件里丢一行 JSON，然后等结果。

命令：!!railupdate status / check / deploy / source lan|github / channel release|debug / on / off / reload
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
USER_AGENT = 'RailPlannerUpdater/1.1.3'
API_URL = 'https://api.github.com/repos/{repo}/releases?per_page=10'
LAN_LATEST_URL = '{base}/{channel}/latest.json'
SOURCES = ('lan', 'github')
CHANNELS = ('release', 'debug')
JAR_RE = re.compile(r'^railplanner-(.+)\.jar$')
# 同一个 release 里除本体外还挂着源码包，它们也以 .jar 结尾
NOT_MOD_JAR_RE = re.compile(r'-(sources?|dev|javadoc|slim|api)(-|\.|$)', re.IGNORECASE)
# 内网 debug 通道的包名：railplanner-<版本>-debug-<yyyymmddHHMM>.jar
DEBUG_JAR_RE = re.compile(r'-debug-\d{8,12}\.jar$', re.IGNORECASE)
# debug 通道的包名尾巴上挂着构建时间戳：railplanner-0.9.1-debug-202611161400.jar
BUILD_STAMP_RE = re.compile(r'-(\d{8,12})(?=-|\.|$)')
MOD_ID = 'railplanner'
MAIN_CLASS = 'com/phzt/railplanner/RailPlanner.class'


class Config(Serializable):
    enabled: bool = True
    # lan = 内网发布站（默认），github = phzt-hub/railplanner 的 release
    source: str = 'lan'
    # 内网站点：协议+域名，结尾别带斜杠
    lan_base: str = 'http://railplannerrelease.phzt.lan'
    # 内网通道：release = 正式版；debug = 调试包（别拿它上生产）
    channel: str = 'release'
    repo: str = 'phzt-hub/railplanner'
    # 0 = 不轮询，只在出现部署请求文件时拉一次（手动部署用这个）
    check_interval_minutes: int = 0
    # 部署请求文件 / 结果文件，相对 MCDR 根目录；也可以写绝对路径
    deploy_request_file: str = 'railplanner_deploy.request'
    deploy_result_file: str = 'railplanner_deploy.result'
    # 换完 jar 后广播倒计时多少秒再重启（0 按 3 秒算）
    restart_countdown_seconds: int = 60
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
    jars = [p for p in mods.glob('railplanner-*.jar')
            if p.is_file() and not NOT_MOD_JAR_RE.search(p.name)]
    if not jars:
        return None
    # 按版本号挑，不按文件名：字符串排序会把 0.9.9 排在 0.9.10 后面
    return max(jars, key=lambda p: (version_key(version_of(p) or ''), p.name))


def version_of(path: Path) -> Optional[str]:
    m = JAR_RE.match(path.name)
    return m.group(1) if m else None


def version_key(v: str) -> Tuple:
    """把 0.7.0 / v0.7.0-rc1 / 0.9.1-debug 这类版本号拍成可比较的元组。

    返回 (数字段, 正式版标记, 构建时间戳)：

        0.9.0                     → ((0, 9, 0), 1, 0)
        0.9.1-debug               → ((0, 9, 1), 0, 0)
        0.9.1-debug-202611161400  → ((0, 9, 1), 0, 202611161400)

    第二项是关键：带后缀的包（-debug / -rc1 / -beta …）排在**同名正式版之后**，
    和客户端 Version.compare 的老规矩是同一条。所以服务端装着 0.9.1-debug 时，
    0.9.1 一发布就算「更新」，会正常换过去；反过来 0.9.1-debug 也顶不掉装好的 0.9.1。
    数字段整段先比，所以 0.9.2-debug 仍然比 0.9.1 新。
    第三项只在同一个后缀内部排先后（debug 通道连发两个同版本包时）。

    后缀本身不参与数字：0.7.0-rc1 的「1」是第几个 rc，不是第四段版本号。
    """
    s = str(v or '').strip()
    if s[:1] in ('v', 'V'):
        s = s[1:]
    s = s.split('+', 1)[0]          # +build 这类元数据不参与比较
    stamp = 0
    m = BUILD_STAMP_RE.search(s)
    if m:
        stamp = int(m.group(1))
        s = s[:m.start()] + s[m.end():]
    dash = s.find('-')
    rank = 1
    if dash >= 0:
        rank = 0
        s = s[:dash]
    nums = re.findall(r'\d+', s)
    return (tuple(int(x) for x in nums[:6]) if nums else (0,), rank, stamp)


def file_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1 << 16), b''):
            h.update(chunk)
    return h.hexdigest()


def is_newer(candidate: str, current: Optional[str]) -> bool:
    if current is None:
        return True
    return version_key(candidate) > version_key(current)


def release_key(version: str, asset_name: str) -> Tuple:
    """同一版本号连发多包时，先后看包名里的构建时间戳。

    debug 通道的 tag 恒为 v0.9.1-debug：版本号本身不带时间，只有资产名
    （railplanner-0.9.1-debug-202611161605.jar）带。把时间戳顶进 version_key
    的第三项，才能和 mods/ 里装着的同名 jar 判出先后；否则只会一直报「已是最新」。
    版本号自己带时间戳时（GitHub 源的可能情形）保留原值。
    """
    key = version_key(version)
    name = (asset_name or '')
    # 内网 debug 通道的 tag 是 v0.9.1 这种不带后缀的字符串，但包是 -debug-<时间戳>.jar：
    # rank 必须跟包名走（0），否则 tag 的「正式版」标记会压过装着的 debug 包，
    # 同一个包每查一次都被判成「更新」，白下一次、还白重启一次。
    rank = 0 if '-debug' in name.lower() else key[1]
    m = BUILD_STAMP_RE.search(name)
    return (key[0], rank, (int(m.group(1)) if m else 0) or key[2])


# ---------------------------------------------------------------- 查 GitHub

def pick_asset(rel: dict, version: str, allow_debug: bool = False) -> Optional[dict]:
    """从 release 的附件里挑出本体 jar，挑不到返回 None。

    以前是「第一个以 .jar 结尾的附件」，可 release 里还挂着 -sources 源码包：
    上传顺序一变就把源码包当本体换上去（0.8.2 那次服务端就是这么起不来的）。
    现在先找名字正好是 railplanner-<版本>.jar 的，找不到再退一步排除源码包。
    内网 debug 通道的包名带 -debug-<时间戳>，只有 allow_debug 时才认。
    """
    jars = [a for a in (rel.get('assets') or []) if (a.get('name') or '').endswith('.jar')]
    exact = 'railplanner-{}.jar'.format(version)
    for asset in jars:
        if (asset.get('name') or '') == exact:
            return asset
    for asset in jars:
        name = asset.get('name') or ''
        if not JAR_RE.match(name) or NOT_MOD_JAR_RE.search(name):
            continue
        if DEBUG_JAR_RE.search(name) and not allow_debug:
            continue
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


def lan_url(cfg: Config) -> str:
    return LAN_LATEST_URL.format(base=(cfg.lan_base or '').rstrip('/'), channel=cfg.channel or 'release')


def source_label(cfg: Config) -> str:
    """日志和 status 里写清楚「这次是从哪儿查的」，省得装完之后对着日志猜。"""
    if (cfg.source or 'lan').strip().lower() == 'github':
        return 'GitHub（{}）'.format(cfg.repo)
    return '内网（{}/{}）'.format((cfg.lan_base or '').rstrip('/'), cfg.channel or 'release')


def fetch_lan(cfg: Config) -> Optional[Tuple[dict, dict]]:
    """从内网发布站拿最新一版：latest.json 是 GitHub release 对象的同构，只是少了接口字段。"""
    url = lan_url(cfg)
    req = urllib.request.Request(url, headers={'User-Agent': USER_AGENT})
    with urllib.request.urlopen(req, timeout=20) as resp:
        data = json.load(resp)
    if isinstance(data, list):
        data = next((r for r in data if isinstance(r, dict) and not r.get('draft')), None)
        if data is None:
            raise RuntimeError('{} 里没有可用的 release'.format(url))
    if not isinstance(data, dict):
        raise RuntimeError('{} 返回的不是 release 对象'.format(url))
    if data.get('prerelease') and not cfg.include_prerelease:
        return None
    version = (data.get('tag_name') or '').lstrip('vV')
    asset = pick_asset(data, version, allow_debug=(cfg.channel or 'release').strip().lower() == 'debug')
    if asset is None:
        raise RuntimeError('{} 的 assets 里没有可用的 jar'.format(url))
    link = asset.get('browser_download_url') or ''
    if link and not link.lower().startswith(('http://', 'https://')):
        asset['browser_download_url'] = '{}/{}'.format((cfg.lan_base or '').rstrip('/'), link.lstrip('/'))
    return data, asset


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

def check_and_update(server: PluginServerInterface, manual: bool = False,
                     countdown: Optional[int] = None, force_restart: bool = False) -> str:
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

    if (cfg.source or 'lan').strip().lower() == 'github':
        found = fetch_latest(cfg.repo, cfg.include_prerelease)
        if found is None:
            _last_result = 'GitHub 上没有找到带 jar 的 release'
            return _last_result
    else:
        found = fetch_lan(cfg)
        if found is None:
            _last_result = '内网站上没有可用的包：{}'.format(lan_url(cfg))
            return _last_result
    rel, asset = found
    latest = (rel.get('tag_name') or '').lstrip('vV')
    asset_name = (asset.get('name') or '').strip()
    # debug 通道的 tag 一直是 v0.9.1-debug，同一版本号会连发好几个包：
    # 只比 tag 永远判「已是最新」，得把包名里的构建时间一并比。
    if (cfg.channel or 'release').strip().lower() == 'debug':
        newer = current is None or release_key(latest, asset_name) > version_key(current)
    else:
        newer = is_newer(latest, current)

    if not newer:
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

    # 逐字节相同就当没更新：站点重发同一个包、或 tag 与包名不同步时，
    # 版本号那套比较可能判成「更新」，但换一个一模一样的 jar 再重启一次纯属折腾。
    if current_path is not None and current_path.is_file():
        try:
            same = file_sha256(current_path) == sha
        except OSError:
            same = False
        if same:
            try:
                tmp.unlink()
            except OSError:
                pass
            _last_result = '已是最新：{}（内容一致）'.format(current)
            server.logger.info('[RailPlanner] 下载的包和 mods/ 里那份逐字节相同，不换、不重启')
            return _last_result

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

    # 落地保留站点给的文件名（debug 包带 -<yyyymmddHHMM>）：下次读回来的 jar
    # 名自带时间戳，才判得出「已是最新」，不会每轮重下同一个包。外部 JSON 里的
    # 名字只取 basename，且必须仍是本体 jar 的口径，否则退回按版本号起名。
    safe_name = Path(asset_name).name
    if not JAR_RE.match(safe_name) or NOT_MOD_JAR_RE.search(safe_name):
        safe_name = 'railplanner-{}.jar'.format(latest)
    final = mods / safe_name
    tmp.replace(final)
    server.logger.info('[RailPlanner] 已换上新 jar：{}'.format(final.name))
    _last_result = '已更新到 {}'.format(latest)

    _arm_rollback(mods, final, backup_path)

    if (cfg.auto_restart or force_restart) and server.is_server_running():
        delay = max(3, int(countdown if countdown is not None else cfg.restart_delay_seconds))
        _schedule_restart(server, delay, latest)
    return _last_result


# 倒计时广播的整点：起点单独念，之后在这些秒数再念一次
_COUNTDOWN_MARKS = (60, 30, 20, 15, 10, 5, 4, 3, 2, 1)


def _schedule_restart(server: PluginServerInterface, delay: int, version: str) -> None:
    """先广播倒计时，到点再重启。自动更新和手动部署走同一条路。"""
    def run():
        # broadcast 比 say 多一步「同时打到控制台」，MCDR.log 里能查到每一次广播
        server.broadcast('§e[Rail Planner] 服务端模组更新到 {}，{} 秒后重启'.format(version, delay))
        marks = sorted((m for m in _COUNTDOWN_MARKS if m < delay), reverse=True)
        left = delay
        for mark in marks:
            if _stop.wait(max(0, left - mark)):
                return
            left = mark
            server.broadcast('§e[Rail Planner] 还有 {} 秒重启'.format(mark))
        if _stop.wait(max(0, left)):
            return
        _restart(server)

    threading.Thread(target=run, name='railplanner-countdown', daemon=True).start()


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


# ---------------------------------------------------------------- 手动部署

def _deploy_path(name: str, default: str) -> Path:
    p = Path(name or default)
    return p if p.is_absolute() else Path.cwd() / p


def request_file() -> Path:
    return _deploy_path(getattr(config, 'deploy_request_file', '') if config else '',
                        'railplanner_deploy.request')


def result_file() -> Path:
    return _deploy_path(getattr(config, 'deploy_result_file', '') if config else '',
                        'railplanner_deploy.result')


def _take_request(server: PluginServerInterface) -> Optional[dict]:
    """有请求文件就先删再处理：同一份不会被连着执行两次。"""
    path = request_file()
    if not path.is_file():
        return None
    raw = ''
    try:
        raw = path.read_text(encoding='utf-8').strip()
    except OSError:
        pass
    try:
        path.unlink()
    except OSError as e:
        server.logger.warning('[RailPlanner] 删不掉部署请求文件：{}'.format(e))
    req = {}
    if raw:
        try:
            parsed = json.loads(raw)
        except ValueError:
            parsed = {'note': raw}
        req = parsed if isinstance(parsed, dict) else {'note': raw}
    return req


def _write_result(status: str, message: str, **extra) -> None:
    payload = {
        'ok': status != 'failed',
        'status': status,
        'message': message,
        'at': int(time.time()),
        'atText': time.strftime('%Y-%m-%d %H:%M:%S'),
    }
    payload.update(extra)
    path = result_file()
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding='utf-8')
    except OSError as e:
        if _plugin_server is not None:
            _plugin_server.logger.warning('[RailPlanner] 写部署结果失败：{}'.format(e))


def deploy(server: PluginServerInterface, req: dict) -> str:
    """手动部署一遍：拉包 → 换 jar → 倒计时 → 重启，成败都落到结果文件里。"""
    channel = str(req.get('channel') or '').strip().lower()
    if channel:
        if channel in CHANNELS:
            config.channel = channel
            server.logger.info('[RailPlanner] 本次部署用 {} 通道'.format(channel))
        else:
            server.logger.warning('[RailPlanner] 请求里的通道 {} 不认识，按 {} 处理'.format(channel, config.channel))
    try:
        countdown = int(req.get('countdown'))
    except (TypeError, ValueError):
        countdown = int(getattr(config, 'restart_countdown_seconds', 60) or 60)

    mods = mods_dir(server)
    before = installed_jar(mods)
    try:
        message = check_and_update(server, manual=True, countdown=countdown, force_restart=True)
    except Exception as e:
        server.logger.error('[RailPlanner] 部署失败：{}'.format(e))
        _write_result('failed', '部署失败：{}'.format(e), channel=config.channel, countdown=countdown)
        return '部署失败：{}'.format(e)

    after = installed_jar(mods)
    updated = after is not None and (before is None or after.name != before.name)
    status = 'updated' if updated else 'uptodate'
    _write_result(
        status, message,
        channel=config.channel,
        jar=after.name if after else None,
        previous=before.name if before else None,
        countdown=countdown,
        restarting=bool(updated and server.is_server_running()),
    )
    return message


# ---------------------------------------------------------------- 定时

def _loop(server: PluginServerInterface):
    # 自动模式刚起来先等一会儿，别和开服抢资源；手动模式就是等别人喊一声，
    # 再等 60 秒去处理请求只会让部署脚本干等
    interval = int(config.check_interval_minutes) if config else 0
    if _stop.wait(60 if interval > 0 else 2):
        return
    while not _stop.is_set():
        interval = 0
        try:
            req = _take_request(server)
            if req is not None:
                server.logger.info('[RailPlanner] 收到部署请求：{}'.format(req or '（空）'))
                deploy(server, req)
                if _stop.wait(1):
                    return
                continue
            interval = int(config.check_interval_minutes) if config else 0
            if interval > 0:
                result = check_and_update(server)
                server.logger.info('[RailPlanner] 检查结果：{}'.format(result))
        except Exception as e:
            server.logger.warning('[RailPlanner] 检查更新失败：{}'.format(e))
        if _stop.wait(max(5, interval) * 60 if interval > 0 else 2):
            return


# ---------------------------------------------------------------- 命令

def _status_text(server: PluginServerInterface) -> str:
    mods = mods_dir(server)
    jar = installed_jar(mods)
    lines = [
        '§b[Rail Planner] 服务端模组更新',
        '  当前 jar：{}'.format(jar.name if jar else '（mods 里没有）'),
        '  更新源　：{}'.format(source_label(config)),
        '  自动更新：{}'.format('开' if config.enabled else '关'),
        '  预发布　：{}'.format('跟' if config.include_prerelease else '不跟'),
        '  轮询　　：{}'.format(
            '每 {} 分钟'.format(config.check_interval_minutes)
            if (config.check_interval_minutes or 0) > 0 else '关（等部署请求文件）'),
        '  请求文件：§7{}'.format(request_file()),
        '  换完　　：{}'.format(
            '广播倒计时 {} 秒后重启'.format(config.restart_countdown_seconds) if config.auto_restart else '只换 jar，不重启'),
        '  上次结果：{}'.format(_last_result),
        '  §7换源：!!railupdate source lan|github　通道：!!railupdate channel release|debug　部署：!!railupdate deploy',
    ]
    return '\n'.join(lines)


def on_load(server: PluginServerInterface, old):
    global config, _plugin_server, _thread
    _plugin_server = server
    config = server.load_config_simple('config.json', target_class=Config)
    config.source = (config.source or 'lan').strip().lower()
    config.channel = (config.channel or 'release').strip().lower()
    if config.source not in SOURCES:
        server.logger.warning('[RailPlanner] source={!r} 不认识，按 lan 处理'.format(config.source))
        config.source = 'lan'
    if config.channel not in CHANNELS:
        server.logger.warning('[RailPlanner] channel={!r} 不认识，按 release 处理'.format(config.channel))
        config.channel = 'release'
    server.register_help_message(PREFIX, 'Rail Planner 服务端模组自动更新')

    root = (
        Literal(PREFIX).
        runs(lambda src: src.reply(_status_text(server))).
        then(Literal('status').runs(lambda src: src.reply(_status_text(server)))).
        then(Literal('check').requires(lambda src: src.has_permission(PermissionLevel.ADMIN)).runs(lambda src: _manual_check(src, server))).
        then(Literal('deploy').requires(lambda src: src.has_permission(PermissionLevel.ADMIN)).runs(lambda src: _manual_deploy(src, server))).
        then(Literal('source').
             runs(lambda src: src.reply('§b[RailPlanner] 当前更新源：{}\n§7可用：!!railupdate source lan | github'.format(source_label(config)))).
             then(Literal('lan').requires(lambda src: src.has_permission(PermissionLevel.ADMIN)).runs(lambda src: _set_source(src, server, 'lan'))).
             then(Literal('github').requires(lambda src: src.has_permission(PermissionLevel.ADMIN)).runs(lambda src: _set_source(src, server, 'github')))).
        then(Literal('channel').
             runs(lambda src: src.reply('§b[RailPlanner] 内网通道：{}\n§7可用：!!railupdate channel release | debug'.format(config.channel))).
             then(Literal('release').requires(lambda src: src.has_permission(PermissionLevel.ADMIN)).runs(lambda src: _set_channel(src, server, 'release'))).
             then(Literal('debug').requires(lambda src: src.has_permission(PermissionLevel.ADMIN)).runs(lambda src: _set_channel(src, server, 'debug')))).
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
    if (config.check_interval_minutes or 0) > 0:
        server.logger.info('[RailPlanner] 自动更新已加载：源 {}，每 {} 分钟查一次，换完{}'.format(
            source_label(config), config.check_interval_minutes,
            '自动重启' if config.auto_restart else '不自动重启'))
    else:
        server.logger.info('[RailPlanner] 手动模式已加载：源 {}，不轮询，{} 出现才拉一次，换完广播 {} 秒再重启'.format(
            source_label(config), request_file().name,
            config.restart_countdown_seconds if config.auto_restart else 0))


def on_unload(server: PluginServerInterface):
    _stop.set()


def _manual_check(source, server):
    source.reply('§e[RailPlanner] 开始检查…')
    try:
        source.reply('§a[RailPlanner] ' + check_and_update(server, manual=True))
    except Exception as e:
        source.reply('§c[RailPlanner] 检查失败：{}'.format(e))


def _manual_deploy(source, server):
    source.reply('§e[RailPlanner] 开始部署…（换完广播倒计时再重启）')
    try:
        source.reply('§a[RailPlanner] ' + deploy(server, {}))
    except Exception as e:
        source.reply('§c[RailPlanner] 部署失败：{}'.format(e))


def _toggle(source, server, value: bool):
    config.enabled = value
    server.save_config_simple(config, 'config.json')
    source.reply('§a[RailPlanner] 自动更新 → {}'.format('开' if value else '关'))


def _set_source(source, server, value):
    config.source = value
    server.save_config_simple(config, 'config.json')
    source.reply('§a[RailPlanner] 更新源 → {}'.format(source_label(config)))


def _set_channel(source, server, value):
    config.channel = value
    server.save_config_simple(config, 'config.json')
    source.reply('§a[RailPlanner] 内网通道 → {}（source 是 lan 时才生效）'.format(value))


def _reload(source, server):
    global config
    config = server.load_config_simple('config.json', target_class=Config)
    config.source = (config.source or 'lan').strip().lower()
    config.channel = (config.channel or 'release').strip().lower()
    source.reply('§a[RailPlanner] 配置已重读：源 {}，间隔 {} 分钟，换完{}'.format(
        source_label(config), config.check_interval_minutes,
        '自动重启' if config.auto_restart else '不自动重启'))
