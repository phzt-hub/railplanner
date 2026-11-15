# CurseForge 发布素材

这里的东西是给 CF 项目页/文件页复制粘贴用的，不是给玩家读的仓库文档。

| CF 字段 | 用哪个文件 |
|---|---|
| Project name | `Rail Planner` |
| Summary | `summary.txt`（231 字符，一句话讲清「在地图上选线 → 得到能铺的线」） |
| Description | `description.md` 全文，从 `# Rail Planner` 到最后一行的 MIT（中间 `---` 之后是中文对照，英文用户滚到那儿也无所谓，中文用户正好接上） |
| Changelog（文件页） | `changelog-0.8.0.md` |
| Category | Map and Information（最贴）。CF 只让选一个主分类 |
| License | MIT（CF 内置列表里有，不要选 Custom） |
| Game version | 26.3 |
| Mod loader | Fabric |
| Release type | Release |

## 图片（Gallery，按这个顺序传）

| # | 文件 | 标题（CF 里填 caption） |
|---|---|---|
| 1 | `docs/screenshot.png` | Pick points on the world map |
| 2 | `docs/world-lines.png` | The same line standing in the 3D world |
| 3 | `docs/world-lines-xray.png` | X: pillars through terrain — blue is dig, orange is build up |
| 4 | `docs/route-editor.png` | Rename and recolour a route |
| 5 | `docs/point-y.png` | Double-click a vertex: Y, name, type |
| 6 | `docs/collapsed.png` | H collapses the panel to one line |
| 7 | `docs/settings.png` | Settings page (U, or Mod Menu) |

第一张会当封面，选 `screenshot.png`（面板 + 地图同框，信息量最大）。

## 依赖怎么填

- Fabric API —— **Required dependency**（CF 上搜 `fabric-api`）
- Xaero's World Map —— **Required dependency**（搜 `xaeros-world-map`，版本范围 1.47.x）
- Mod Menu —— **Optional dependency**

描述里已经写了服务端只要 Fabric API、不需要 Xaero，依赖表照上面填就行，不用为服务端单独备注。

## 发布前核对

```sh
ls -l build/libs/railplanner-0.8.0.jar
md5sum build/libs/railplanner-0.8.0.jar
unzip -l build/libs/railplanner-0.8.0.jar | grep -E 'fabric.mod.json|icon.png'
```

- jar 名与 `gradle.properties` 的版本一致（0.8.0）
- 上传的是 `build/libs/` 里那个**没被 `-sources` 污染的**主 jar
- 文件页的「Game version」勾 26.3，「Mod loader」勾 Fabric，两样都勾错会直接被玩家下载到崩溃

## 两个已知的坑

1. **CF 的描述渲染不支持表格**，所以 `description.md` 里一张表都没有，全是列表。要是你以后再往里加表格，页面上会变成一片竖线。
2. 描述开头的封面图用的是 raw.githubusercontent 的地址。CF 一般能显示，但万一显示成裂图，把那一行 `![...](...)` 删掉、只靠 Gallery 就行 —— 图片本身已经在 Gallery 里了。

## 改哪里

- 改语气/加卖点 → `description.md`，英文段在前、中文段在 `---` 之后
- 加新版本 → 复制一份 `changelog-0.8.0.md` 改名，按 git log 提炼（`git log --oneline <上个 tag>..HEAD`）
- 改一句话简介 → `summary.txt`，CF 的 Summary 框不长，超了会被截断
