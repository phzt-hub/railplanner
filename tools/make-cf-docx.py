
# -*- coding: utf-8 -*-
"""Generate the CurseForge paste-ready DOCX from curseforge/description.md.

Page 1: 使用说明（不要复制）。Page 2+: the description itself, styled so that
copy -> paste into the CurseForge editor keeps headings, bold, lists and links.
"""
import io, re, sys, shutil, pathlib, tempfile
from docx import Document
from docx.shared import Pt, Cm, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK
from docx.oxml.ns import qn
from docx.oxml import OxmlElement

BT_CH = chr(96)
ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "curseforge" / "description.md"
OUT = ROOT / "curseforge" / "RailPlanner-CurseForge-Description.docx"
DOCS = ROOT / "docs"

# The seven gallery shots, in the same order as the upload table in
# curseforge/README.md; the caption is exactly what goes in CF's Gallery.
GALLERY = [
    ("screenshot.png", "Pick points on the world map"),
    ("world-lines.png", "The same line standing in the 3D world"),
    ("world-lines-xray.png", "Press X: the same line seen through terrain"),
    ("route-editor.png", "Rename and recolour a route"),
    ("point-y.png", "Double-click a vertex: coordinates, name, kind"),
    ("collapsed.png", "H collapses the panel to one line"),
    ("settings.png", "Settings page (U, or Mod Menu)"),
]
NO_IMAGES = "--no-images" in sys.argv

doc = Document()

sec = doc.sections[0]
sec.page_width, sec.page_height = Cm(21.0), Cm(29.7)
sec.left_margin = sec.right_margin = Cm(2.2)
sec.top_margin = sec.bottom_margin = Cm(2.0)

normal = doc.styles["Normal"]
normal.font.name = "Calibri"
normal.font.size = Pt(11)
normal._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
normal.paragraph_format.space_after = Pt(8)
normal.paragraph_format.line_spacing = 1.15

def add_hyperlink(paragraph, url, text):
    part = paragraph.part
    r_id = part.relate_to(
        url,
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink",
        is_external=True,
    )
    hl = OxmlElement("w:hyperlink")
    hl.set(qn("r:id"), r_id)
    r = OxmlElement("w:r")
    rPr = OxmlElement("w:rPr")
    color = OxmlElement("w:color")
    color.set(qn("w:val"), "1155CC")
    u = OxmlElement("w:u")
    u.set(qn("w:val"), "single")
    rPr.append(color)
    rPr.append(u)
    r.append(rPr)
    t = OxmlElement("w:t")
    t.set(qn("xml:space"), "preserve")
    t.text = text
    r.append(t)
    hl.append(r)
    paragraph._p.append(hl)

TOKEN = re.compile(r"(\*\*.+?\*\*|\*[^*\n]+\*|`[^`]+`|\[[^\]]+\]\([^)]+\))")

def add_inline(paragraph, text):
    for piece in TOKEN.split(text):
        if not piece:
            continue
        if piece.startswith("**") and piece.endswith("**") and len(piece) > 4:
            paragraph.add_run(piece[2:-2]).bold = True
        elif piece.startswith("*") and piece.endswith("*") and len(piece) > 2:
            paragraph.add_run(piece[1:-1]).italic = True
        elif piece.startswith(BT_CH) and piece.endswith(BT_CH) and len(piece) > 2:
            run = paragraph.add_run(piece[1:-1])
            run.font.name = "Consolas"
            run._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
        else:
            m = re.fullmatch(r"\[([^\]]+)\]\(([^)]+)\)", piece)
            if m:
                add_hyperlink(paragraph, m.group(2), m.group(1))
            else:
                paragraph.add_run(piece)

def make_restarted_list(doc, paragraphs):
    """Give this numbered list its own numbering, starting again at 1."""
    import copy
    numbering = doc.part.numbering_part.element
    style = doc.styles["List Number"]
    num_pr = style.element.find(qn("w:pPr")).find(qn("w:numPr"))
    num_id = int(num_pr.find(qn("w:numId")).get(qn("w:val")))
    nums = numbering.findall(qn("w:num"))
    src = [n for n in nums if int(n.get(qn("w:numId"))) == num_id][0]
    new_num_id = max(int(n.get(qn("w:numId"))) for n in nums) + 1
    new_num = copy.deepcopy(src)
    new_num.set(qn("w:numId"), str(new_num_id))
    for ov in new_num.findall(qn("w:lvlOverride")):
        new_num.remove(ov)
    ov = OxmlElement("w:lvlOverride")
    ov.set(qn("w:ilvl"), "0")
    so = OxmlElement("w:startOverride")
    so.set(qn("w:val"), "1")
    ov.append(so)
    new_num.append(ov)
    numbering.append(new_num)
    for p in paragraphs:
        pPr = p._p.get_or_add_pPr()
        numPr = OxmlElement("w:numPr")
        ilvl = OxmlElement("w:ilvl")
        ilvl.set(qn("w:val"), "0")
        nid = OxmlElement("w:numId")
        nid.set(qn("w:val"), str(new_num_id))
        numPr.append(ilvl)
        numPr.append(nid)
        pPr.append(numPr)

def rule(paragraph):
    pPr = paragraph._p.get_or_add_pPr()
    borders = OxmlElement("w:pBdr")
    bottom = OxmlElement("w:bottom")
    bottom.set(qn("w:val"), "single")
    bottom.set(qn("w:sz"), "6")
    bottom.set(qn("w:space"), "1")
    bottom.set(qn("w:color"), "BFBFBF")
    borders.append(bottom)
    pPr.append(borders)

def add_gallery(doc):
    """The seven screenshots, full text width, each with its Gallery caption.

    The 1920x1080 PNGs are 1 MB apiece; resampled to 1600 px and encoded as
    JPEG (4:4:4, so text keeps its edges) the whole set is ~3 MB inside the
    docx instead of ~8 MB, still 245 dpi at 16.6 cm.
    """
    from PIL import Image
    tmpdir = pathlib.Path(tempfile.mkdtemp(prefix="rpgallery-"))
    try:
        for name, caption in GALLERY:
            src = DOCS / name
            if not src.is_file():
                raise SystemExit("missing gallery image: " + str(src))
            im = Image.open(src).convert("RGB")
            im = im.resize((1600, round(1600 * im.height / im.width)), Image.LANCZOS)
            enc = tmpdir / (pathlib.Path(name).stem + ".jpg")
            im.save(enc, "JPEG", quality=88, subsampling=0, optimize=True, progressive=True)
            p = doc.add_paragraph()
            p.alignment = WD_ALIGN_PARAGRAPH.CENTER
            p.paragraph_format.space_before = Pt(6)
            p.paragraph_format.space_after = Pt(2)
            p.paragraph_format.keep_with_next = True
            p.add_run().add_picture(str(enc), width=Cm(16.6))
            c = doc.add_paragraph()
            c.alignment = WD_ALIGN_PARAGRAPH.CENTER
            c.paragraph_format.space_after = Pt(12)
            run = c.add_run(caption)
            run.italic = True
            run.font.size = Pt(9)
            run.font.color.rgb = RGBColor(0x59, 0x59, 0x59)
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)

h = doc.add_paragraph()
r = h.add_run("使用说明 — 这一页不要复制")
r.bold = True
r.font.size = Pt(18)

p = doc.add_paragraph()
p.add_run("这份 docx 只解决一件事：把 CurseForge 的 Project Description 整段贴进去，格式不用再调。").bold = True

steps = [
    "打开 CurseForge 项目后台 → Description，先把编辑器里原有的内容全选删掉。",
    "翻到第 2 页，从第一行「Rail Planner」开始，一直选到最后一行「许可：MIT」。",
    "Ctrl+V 直接贴，不要选「只保留文本」——标题、粗体、圆点列表、编号步骤和超链接都是真格式，贴过去就是原样。",
    "正文「Screenshots」下面那 7 张图是给你核对的：CF 编辑器的粘贴处理器不保证接住图片，项目页上的图仍然照下面 Gallery 那行单独上传，图序与本文一致。",
    "贴完抽查四处：三个 GitHub 链接能不能点、⚠️ 还在不在、列表还是圆点、中英文之间有一条浅灰分隔线。",
]
for t in steps:
    doc.add_paragraph(t, style="List Number")

doc.add_paragraph()
p = doc.add_paragraph()
p.add_run("其余字段不在本文里，另贴：").bold = True
extra = [
    ("Project name", "Rail Planner"),
    ("Summary", "见 curseforge/summary.txt（231 字符，一句话：在地图上选线 → 得到能铺的线）"),
    ("Changelog", "见 curseforge/changelog-0.8.2.md，贴在文件页的 Changelog 里"),
    ("Category", "Map and Information"),
    ("Gallery", "七张图按顺序上传，与本文「Screenshots」里那 7 张同序同标题；本文里的图是本地副本，不是外链（CF 会剥掉正文里的 raw.githubusercontent 图片，画廊单独传不受影响）"),
]
for k, v in extra:
    p = doc.add_paragraph(style="List Bullet")
    p.add_run(k + "：").bold = True
    p.add_run(v)

doc.add_paragraph()
p = doc.add_paragraph()
p.add_run("为什么从 md 转成 docx：").bold = True
p.add_run("直接贴 Markdown 进 CF 编辑器，`#`、`**`、`-` 会原样留在页面上，得手动删几百处；docx 里的格式是 Word 的真样式，CF 编辑器的粘贴处理器认它们。")

doc.add_paragraph().add_run().add_break(WD_BREAK.PAGE)

src = SRC.read_text(encoding="utf-8").splitlines()
install_paras = []
in_install = False
pending_gallery = False

def flush_gallery():
    """Drop the screenshots in once the ## Screenshots prose is done."""
    global pending_gallery
    if pending_gallery:
        add_gallery(doc)
        pending_gallery = False

i = 0
while i < len(src):
    line = src[i].rstrip()
    i += 1
    if not line.strip():
        continue
    if line.startswith("!["):
        continue
    if line.strip() == "---":
        flush_gallery()
        rule(doc.add_paragraph())
        continue
    if line.startswith("### "):
        doc.add_heading(line[4:], level=3)
        continue
    if line.startswith("## "):
        flush_gallery()
        title = line[3:].strip()
        in_install = title.lower() == "install"
        doc.add_heading(title, level=2)
        if title == "Screenshots" and not NO_IMAGES:
            pending_gallery = True
        continue
    if line.startswith("# "):
        flush_gallery()
        doc.add_heading(line[2:], level=1)
        continue
    if re.match(r"^\d+\.\s", line):
        p = doc.add_paragraph(style="List Number")
        add_inline(p, re.sub(r"^\d+\.\s+", "", line))
        if in_install:
            install_paras.append(p)
        continue
    if line.startswith("- "):
        p = doc.add_paragraph(style="List Bullet")
        add_inline(p, line[2:])
        continue
    p = doc.add_paragraph()
    add_inline(p, line)

if install_paras:
    make_restarted_list(doc, install_paras)

cp = doc.core_properties
cp.title = "Rail Planner — CurseForge Project Description"
cp.subject = "Paste-ready description for CurseForge"
cp.author = "Rail Planner"
cp.comments = "Page 1 is instructions (do not copy). Page 2+ is the description."

flush_gallery()

doc.save(OUT)
print("wrote", OUT, OUT.stat().st_size, "bytes", "(no images)" if NO_IMAGES else "(with 7 screenshots)")
