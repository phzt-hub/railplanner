## 0.8.3 — Updates only take the mod jar

**Fixed**

- **The auto-updater could install the sources jar.** The v0.8.2 release listed `railplanner-0.8.2-sources.jar` before the mod jar, and both the in-game updater and the server-side MCDR plugin picked "the first asset ending in `.jar`". A sources jar ends in `.jar` too and unpacks fine, so the size and sha256 checks passed — and the server then died on the next boot with `ClassNotFoundException: com.phzt.railplanner.RailPlanner`.
- Both updaters now resolve the asset by its exact name, `railplanner-<version>.jar`, and skip `-sources` / `-dev` / `-javadoc` / `-slim` / `-api` copies. No exact match means "no update this round", never a guess.
- Before anything is swapped in, both now open the downloaded jar and check that `fabric.mod.json` is present, that the entrypoint class is really inside, and that the id and version in it match the release. A jar that fails any of those is deleted and the old one stays where it is.
- The source archive is now published as `sources-railplanner-<version>.zip` instead of `-sources.jar`, so a release can no longer contain a second `.jar` at all; v0.8.2's asset was renamed the same way.
- The MCDR updater (plugin 1.0.2) additionally watches the new jar's first boot: if the server stops before `Done` within three minutes of the swap, it puts the backup back in `mods/` and starts the server again, inside the same stop event (MCDR exits before a timer would fire).

**Unchanged**

- No gameplay, save-format or protocol change. 0.8.2 and this version still talk to each other (protocol 3), and the map, the keys and the world point editing are untouched.

---

## 0.8.3 —— 更新只认本体 jar

**修复**

- **自动更新可能把源码包装进 `mods/`。** v0.8.2 的 release 里 `railplanner-0.8.2-sources.jar` 排在模组本体前面，游戏内更新器和服务端 MCDR 插件都取「第一个以 `.jar` 结尾的附件」。源码包同样以 `.jar` 结尾、同样能解开，字节数与 sha256 校验照样通过——直到下次启动服务端报 `ClassNotFoundException: com.phzt.railplanner.RailPlanner`。
- 两端现在都按准确文件名 `railplanner-<版本>.jar` 找附件，跳过 `-sources` / `-dev` / `-javadoc` / `-slim` / `-api` 这些副本；找不到本体就这一轮不更新，绝不猜。
- 换任何东西之前，两端都会先打开下载到的 jar：`fabric.mod.json` 在不在、入口主类在不在、里面的 id 与版本号对不对。任何一条不合就整包丢掉，原来的 jar 一个字节都不动。
- 源码包从 `-sources.jar` 改成 `sources-railplanner-<版本>.zip`，一个 release 里再不可能出现第二个 `.jar`；v0.8.2 的附件也照此改名重挂了一遍。
- MCDR 插件（1.0.2 版）另外盯新 jar 的第一次启动：换完三分钟内没走到 `Done` 就停了，它把备份放回 `mods/` 并重新拉起服务端，而且是在那个 stop 事件里同步做——MCDR 的 `EXIT_AFTER_STOP` 等不到定时器。

**不变**

- 玩法、存档格式、同步协议都没动。0.8.2 与本版照样互通（协议 3），地图、键位、世界里改点位全部保持原样。
