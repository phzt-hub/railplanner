# CurseForge 发布素材

这里的东西是给 CF 项目页/文件页复制粘贴用的，不是给玩家读的仓库文档。

| CF 字段 | 用哪个文件 |
|---|---|
| Project name | `Rail Planner` |
| Summary | `summary.txt`（231 字符，一句话讲清「在地图上选线 → 得到能铺的线」） |
| Description | `description.md` 全文，从 `# Rail Planner` 到最后一行的 MIT（中间 `---` 之后是中文对照，英文用户滚到那儿也无所谓，中文用户正好接上） |
| Description（贴用版） | `RailPlanner-CurseForge-Description.docx` —— 打开、翻到第 2 页、全选、Ctrl+V，格式不用再调。第 1 页是说明，别复制 |
| Changelog（文件页） | `changelog-0.8.3.md` |
| Category | Map and Information（最贴）。CF 只让选一个主分类 |
| License | MIT（CF 内置列表里有，不要选 Custom） |
| Game version | 26.3 |
| Mod loader | Fabric |
| Release type | Release |

## 描述的结构（顺序别动）

`description.md` 的段落顺序是有意的，尤其两处：

1. 标题 → 一句话 → **它是什么** → **关掉地图还在** → 截图 → **⚠️ 这不是生存模组** → 功能 → 安装 → 按键 → 口径 → 已知限制 → 链接
2. 那个 ⚠️ 块必须留在「功能」之前、截图之后：早了读者还不知道这是什么，晚了（滚到功能列表下面）就没人看见，而这正是最该劝退的那批人

块里写的是**这个 mod 真的会做的事**（12‰、4 分钟、按 `R`），不是空玩梗——改的时候别把数字改成编的，那三个数都能在面板/手册里对上。

## 一键贴（docx）

`description.md` 是源，`RailPlanner-CurseForge-Description.docx`（约 3 MB，正文 11 页）是拿去贴的成品。直接往 CF 编辑器里贴 Markdown，`#`、`**`、`-` 会原样留在页面上，几百处得手删；docx 里是 Word 的真样式（Heading、List Bullet、List Number、超链接），CF 编辑器的粘贴处理器认它们。

改完 `description.md` 重新生成一次就行：

```sh
python3 tools/make-cf-docx.py               # 带七张截图，约 3 MB
python3 tools/make-cf-docx.py --no-images   # 只出文字，约 46 KB
```

生成器顺手做了四件人肉容易错的事：两段编号列表各自从 1 开始（不会出现 5. 6. 7.）、`*斜体*` 和 `**粗体**` 都还原成真斜体/粗体、正文里不放外链图（CF 会剥掉 raw.githubusercontent 的 `<img>`，图片走 Gallery）、七张截图以**本地副本**嵌进「Screenshots」小节下面（1600×900 重采样、JPEG 4:4:4、通栏 16.6 cm ≈ 245 dpi，每张底下一行 Gallery 标题）。

嵌图顺序和标题就是下面那张 Gallery 表，脚本顶部 `GALLERY` 一处改完就行。图是给人看和打印的：**CF 编辑器的粘贴处理器不保证接住粘贴的图片**，项目页上的图仍然走 Gallery 上传，两边顺序一致。

## 图片（Gallery，按这个顺序传）

| # | 文件 | 标题（CF 里填 caption） |
|---|---|---|
| 1 | `docs/screenshot.png` | Pick points on the world map |
| 2 | `docs/world-lines.png` | The same line standing in the 3D world |
| 3 | `docs/world-lines-xray.png` | Press X: the same line seen through terrain |
| 4 | `docs/route-editor.png` | Rename and recolour a route |
| 5 | `docs/point-y.png` | Double-click a vertex: Y, name, type |
| 6 | `docs/collapsed.png` | H collapses the panel to one line |
| 7 | `docs/settings.png` | Settings page (U, or Mod Menu) |

第一张会当封面，选 `screenshot.png`（面板 + 地图同框，信息量最大）。

七张都是 **1920×1080、英文客户端、无窗口边框、无教程浮层、无命令回显**（`run/` 里那台 1920×1080 的 X 服务 + `x11grab` 抓帧）。
换图时照这四条走，别用桌面整屏截图——上一版封面里就带进了一个浏览器窗口。

## 依赖怎么填

- Fabric API —— **Required dependency**（CF 上搜 `fabric-api`）
- Xaero's World Map —— **Required dependency**（搜 `xaeros-world-map`，版本范围 1.47.x）
- Mod Menu —— **Optional dependency**

描述里已经写了服务端只要 Fabric API、不需要 Xaero，依赖表照上面填就行，不用为服务端单独备注。

## 发布前核对

```sh
ls -l build/libs/railplanner-0.8.3.jar
md5sum build/libs/railplanner-0.8.3.jar
unzip -l build/libs/railplanner-0.8.3.jar | grep -E 'fabric.mod.json|icon.png'
```

- jar 名与 `gradle.properties` 的版本一致（0.8.3）
- 上传的是 `build/libs/` 里那个**没被 `-sources` 污染的**主 jar
- 文件页的「Game version」勾 26.3，「Mod loader」勾 Fabric，两样都勾错会直接被玩家下载到崩溃

## 三个已知的坑

1. **CF 的描述渲染不支持表格**，所以 `description.md` 里一张表都没有，全是列表。要是你以后再往里加表格，页面上会变成一片竖线。
3. **⚠️ 那段用 `##` 标题而不是引用块（`>`）**，是故意的：CF 的编辑器对引用块支持不可靠，万一不认，页面上会原样冒出四个 `>` 字符。标题在所有渲染器里都稳，效果一样能把它从正文里拔出来。想换成引用块，先在一个测试项目里贴一下看效果。
2. 描述开头的封面图用的是 raw.githubusercontent 的地址。CF 一般能显示，但万一显示成裂图，把那一行 `![...](...)` 删掉、只靠 Gallery 就行 —— 图片本身已经在 Gallery 里了。

## 改哪里

- 改语气/加卖点 → `description.md`，英文段在前、中文段在 `---` 之后
- 加新版本 → 复制一份 `changelog-0.8.3.md` 改名，按 git log 提炼（`git log --oneline <上个 tag>..HEAD`）
- 改一句话简介 → `summary.txt`，CF 的 Summary 框不长，超了会被截断
