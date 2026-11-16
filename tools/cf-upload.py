#!/usr/bin/env python3
"""cf-upload.py — 用 CurseForge 官方上传 API 发一个 release 文件。

token 来源（按顺序）：$CF_TOKEN -> ~/.config/dsh-publish/curseforge-token
token 绝不走命令行参数（会进 shell 历史、会出现在 ps 里）。

用法：
  # 0) 造 dist：把 build/libs 里的产物按 CF 命名收进 curseforge/dist/<ver>/
  #    默认只收本体 jar。源码包加 --with-sources（CF 会把它判 Rejected，
  #    0.8.3 的 9641964 就是先例，所以默认不收）。
  ./tools/cf-upload.py --stage 0.8.3

  # 1) 验 token（只读；项目 ID 没有只读验法，见下）
  ./tools/cf-upload.py --project 123456 --probe

  # 2) 干跑：只打印将要发送的 metadata 与文件，不上传
  ./tools/cf-upload.py --project 123456 --stage 0.8.3 --dry-run

  # 3) 真发（每个 --file 各自成为一个 CF 文件条目）
  ./tools/cf-upload.py --project 123456 --stage 0.8.3 \
      --name "Rail Planner 0.8.3" --release-type release \
      --changelog curseforge/changelog-0.8.3.md
"""
import argparse
import json
import mimetypes
import os
import secrets
import sys
import urllib.error
import urllib.request

API = "https://minecraft.curseforge.com/api"
# 版本表只能从这个域取：legacy.curseforge.com/api/game/versions 同一个 token
# 实测返回空表（200 + "[]"），会让人误以为 token 失效。
TOKEN_FILE = os.path.expanduser("~/.config/dsh-publish/curseforge-token")
# CF 强制要求 environment 组（type 75208）：只有 Client 9638 / Server 9639。
# 本 mod 的 fabric.mod.json 写的是 environment=*，两侧都勾。
# Java 25（type 2，id 14454）：fabric.mod.json 与 build.gradle 都要 java>=25，
# 标上帮用户筛；0.8.0/0.8.1 有，0.8.3 漏了。
DEFAULT_GAME_VERSIONS = ["26.3", "Fabric", "Java 25", "Client", "Server"]


def die(msg, code=1):
    print("错误: " + msg, file=sys.stderr)
    sys.exit(code)


def read_token(required=True):
    tok = os.environ.get("CF_TOKEN", "").strip()
    if tok:
        return tok, "$CF_TOKEN"
    if os.path.exists(TOKEN_FILE):
        tok = open(TOKEN_FILE, "r", encoding="utf-8").read().strip()
        if tok:
            st = os.stat(TOKEN_FILE)
            if st.st_mode & 0o077:
                print("警告: %s 权限是 %o，建议 chmod 600" % (TOKEN_FILE, st.st_mode & 0o777),
                      file=sys.stderr)
            return tok, TOKEN_FILE
    if not required:
        return None, "（无 token）"
    die("找不到 token。放到 %s（chmod 600），或设环境变量 CF_TOKEN。" % TOKEN_FILE)


def request(url, token=None, data=None, headers=None, method=None):
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("User-Agent", "railplanner-cf-upload/1.0")
    if token:
        req.add_header("X-Api-Token", token)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except urllib.error.URLError as e:
        die("连不上 %s: %s" % (url, e.reason), 3)


def multipart(fields, files):
    """fields: {name: str}；files: [(name, filename, bytes, content_type)]"""
    boundary = "----cf-upload-" + secrets.token_hex(12)
    out = []
    for name, value in fields.items():
        out.append(("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n"
                    % (boundary, name)).encode())
        out.append(value.encode("utf-8"))
        out.append(b"\r\n")
    for name, filename, blob, ctype in files:
        out.append(("--%s\r\nContent-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\n"
                    "Content-Type: %s\r\n\r\n"
                    % (boundary, name, filename, ctype)).encode())
        out.append(blob)
        out.append(b"\r\n")
    out.append(("--%s--\r\n" % boundary).encode())
    return b"".join(out), "multipart/form-data; boundary=" + boundary


def resolve_game_versions(token, wanted):
    """把 ['26.3','Fabric'] 解析成 CF 的数字 id。解析不了就原样返回名字并警告。"""
    status, body = request(API + "/game/versions", token)
    if status != 200:
        return None, "取 /api/game/versions 失败: HTTP %d %s" % (status, body[:200])
    try:
        table = json.loads(body)
    except ValueError:
        return None, "版本表不是 JSON: %s" % body[:200]
    by_name = {}
    for row in table:
        by_name.setdefault(str(row.get("name", "")).lower(), []).append(row)
    ids, problems = [], []
    for name in wanted:
        hits = by_name.get(name.lower(), [])
        if len(hits) == 1:
            ids.append(hits[0]["id"])
            continue
        if not hits:
            problems.append("%r 在版本表里没有" % name)
            continue
        # 同名多义（不同 gameVersionType）：现代分组那条才是准的，type 1 是
        # 1.12 时代的遗留平表，同一个版本在那张表里另有一条同名记录。
        canonical = [h for h in hits if h.get("gameVersionTypeID") != 1]
        if len(canonical) == 1:
            print("提示: %r 有 %d 条同名记录，取 id=%s (type %s)"
                  % (name, len(hits), canonical[0]["id"], canonical[0].get("gameVersionTypeID")),
                  file=sys.stderr)
            ids.append(canonical[0]["id"])
            continue
        cand = ", ".join("%s=%s(type %s)" % (h["id"], h.get("name"), h.get("gameVersionTypeID")) for h in hits)
        problems.append("%r 有 %d 个候选: %s" % (name, len(hits), cand))
    if problems:
        return None, "; ".join(problems)
    return ids, None


def display_name(base, path, index):
    """第一个文件用 base；其余按文件名加后缀（sources-* -> ' Sources'）。

    CF 每次 POST 生成一个独立文件条目，同一个 release 里两个条目同名会让人分不清
    哪个是本体（0.8.2 事故就是两个 .jar 分不清）。"""
    if index == 1:
        return base
    name = os.path.basename(path)
    if name.startswith("sources-"):
        return base + " Sources"
    return base + " " + os.path.splitext(name)[0]


def stage(version, with_sources=False):
    """把 build/libs 的产物按 CF 命名收进 curseforge/dist/<ver>/。

    默认只收本体 jar。源码包要显式 --with-sources：CF 审核对它判过 Rejected
    （0.8.3 的 9641964），推上去只是多一条红点。"""
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    src = os.path.join(root, "build", "libs")
    dst = os.path.join(root, "curseforge", "dist", version)
    plan = [("railplanner-%s.jar" % version, "railplanner-%s.jar" % version)]
    if with_sources:
        # 源码包必须是 .zip：一个 release 里不能再出现第二个 .jar（0.8.2 事故）
        plan.append(("railplanner-%s-sources.jar" % version,
                     "sources-railplanner-%s.zip" % version))
    os.makedirs(dst, exist_ok=True)
    made = []
    for s, d in plan:
        sp = os.path.join(src, s)
        if not os.path.exists(sp):
            die("缺产物 %s（先跑 ./gradlew25 build）" % sp)
        open(os.path.join(dst, d), "wb").write(open(sp, "rb").read())
        made.append(os.path.join(dst, d))
    print("dist: %s" % dst)
    for p in made:
        print("  %-42s %10d 字节  sha256=%s"
              % (os.path.basename(p), os.path.getsize(p), _sha256(p)[:16] + "…"))
    if not with_sources:
        print("  （源码包默认不传；要传加 --with-sources，但 CF 会判 Rejected）")
    return made


def _sha256(path):
    import hashlib
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser(description="上传一个 release 文件到 CurseForge")
    ap.add_argument("--project", help="CurseForge 项目 ID（纯数字）")
    ap.add_argument("--stage", metavar="VER",
                    help="先从 build/libs 收出 dist/<VER>/（只收本体 jar）并打印摘要")
    ap.add_argument("--with-sources", action="store_true",
                    help="连源码包一起收/传（默认关：CF 审核判过 Rejected）")
    ap.add_argument("--file", action="append", default=[], help="要上传的文件，可重复")
    ap.add_argument("--name", help="文件显示名，如 'Rail Planner 0.8.3'")
    ap.add_argument("--release-type", default="release", choices=["release", "beta", "alpha"])
    ap.add_argument("--changelog", help="changelog 文件路径（markdown）")
    ap.add_argument("--game-version", action="append", default=[], help="可重复；默认 26.3 + Fabric + Java 25 + Client + Server")
    ap.add_argument("--probe", action="store_true", help="只验 token（只读，不上传）")
    ap.add_argument("--game-version-id", action="append", default=[],
                    help="直接给版本数字 id（可重复），跳过按名字解析")
    ap.add_argument("--dry-run", action="store_true", help="只打印，不上传")
    args = ap.parse_args()

    files = list(args.file)
    if args.stage:
        files += stage(args.stage, with_sources=args.with_sources)
    if not args.project and (args.stage or args.dry_run):
        print("(没有 --project，只做到这里)")
        return
    if not args.project:
        die("要 --project <项目ID>")
    if not args.probe and not files:
        die("没有要传的文件（--file / --stage）")

    token, src = read_token(required=not args.dry_run)
    print("token: 来自 %s（%d 字符）  项目: %s"
          % (src, len(token or ""), args.project))
    url = "%s/projects/%s/upload-file" % (API, args.project)

    if args.probe:
        # 只读判据：/api/game/versions 只对有效 token 返 200（无 token 实测 401）。
        status, body = request(API + "/game/versions", token)
        if status != 200:
            die("token 无效（GET /api/game/versions -> HTTP %d）%s" % (status, body[:300]), 2)
        print("probe: token 有效 —— /api/game/versions 返回 200，%d 字节" % len(body))
        print("probe: 项目 ID %s 没有只读验法 —— CF 在校验项目归属之前就要求请求体"
              "（POST 空身实测 411，与项目 ID 无关）。第一次真发就是第一次验证。" % args.project)
        return

    wanted = args.game_version or DEFAULT_GAME_VERSIONS
    if args.game_version_id:
        gv_ids, problem = [int(x) for x in args.game_version_id], None
        print("版本: 用 --game-version-id 指定的数字 id %s" % gv_ids)
    elif token:
        gv_ids, problem = resolve_game_versions(token, wanted)
    else:
        gv_ids, problem = None, "没有 token，无法解析版本 ID"
    if gv_ids is None:
        if args.dry_run:
            print("警告: 版本名没能解析成 id: %s" % problem, file=sys.stderr)
            gv_ids = wanted
        else:
            die("版本 ID 解析失败: " + problem)

    metadata = {
        "changelog": open(args.changelog, encoding="utf-8").read() if args.changelog else "",
        "changelogType": "markdown",
        "displayName": args.name or os.path.basename(files[0]),
        "releaseType": args.release_type,
        "gameVersions": gv_ids,
        "isMarkedForManualRelease": False,
    }
    print("metadata: " + json.dumps({k: (v[:60] + "…" if isinstance(v, str) and len(v) > 60 else v)
                                     for k, v in metadata.items()}, ensure_ascii=False))
    for i, p in enumerate(files, 1):
        print("file[%d]: %s  %d 字节  sha256=%s  显示名=%r"
              % (i, p, os.path.getsize(p), _sha256(p)[:16] + "…",
                 display_name(metadata["displayName"], p, i)))
    if args.dry_run:
        print("(dry-run，未上传)")
        return

    # CF 一次请求一个文件
    for i, p in enumerate(files, 1):
        blob = open(p, "rb").read()
        ctype = mimetypes.guess_type(p)[0] or "application/octet-stream"
        if p.endswith(".jar"):
            ctype = "application/java-archive"
        md = dict(metadata)
        md["displayName"] = display_name(metadata["displayName"], p, i)
        print("  显示名: %s" % md["displayName"])
        body, ct = multipart({"metadata": json.dumps(md, ensure_ascii=False)},
                             [("file", os.path.basename(p), blob, ctype)])
        print("[%d/%d] 上传 %s (%d 字节)…" % (i, len(files), os.path.basename(p), len(blob)))
        status, resp = request(url, token, data=body, headers={"Content-Type": ct}, method="POST")
        print("  HTTP %d  %s" % (status, resp[:600]))
        if status != 200:
            die("上传 %s 失败（HTTP %d）" % (os.path.basename(p), status), 4)
    print("全部完成。")


if __name__ == "__main__":
    main()
