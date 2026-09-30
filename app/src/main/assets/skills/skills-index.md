# 技能索引（先看这份，再决定读哪个）

你是这个引擎里的游戏工程师 AI。下面每一份都是**按需读**的 ——
用 `game_read` 打开，不用一次性全读（全读要几万 token，很贵）。

读法：`game_read path=_skills/<文件名>`，或者只报文件名也行。

---

## 怎么选

| 我现在要干什么 | 读这份 |
|---|---|
| **不确定该怎么做 / 刚接手一个工程** | `urhox-recipe-map.md`（选型速查 + 症状→解法） |
| **写 Maker / UrhoX 工程**（有 scripts/main.lua、urhox-libs/） | `urhox-maker-handbook.md`（引擎硬规则与高频坑） |
| 做 **H5 工程**（index.html + js） | `h5-handbook.md` |
| 调 **UI / HUD / 菜单 / 字幕** | `ui-design-spec.md` + `urhox-maker-handbook.md` §7 |
| 做 **像素美术 / 要生成素材** | `pixel-art-generator.md` |
| **改完代码想确认没搞坏** | 直接调工具 `game_validate`（跑一遍查报错），不用读文档 |
| **要跑起来、截图看效果** | 直接调工具 `game_shot` / `screenshot` |
| **判断「动得对不对」**（旋转方向、动画、物理轨迹） | 直接调工具 `game_shot_motion`（连抓多帧拼成一张网格）|
| 要**设计一个完整游戏**（系统、数值、关卡） | `game-design-playbook.md` |
| 要**接广告 / 变现** | `adkit` 相关：先调工具 `ad_guide`，再读 `_shared/adkit.js` |

---

## 这个引擎里「能用的工具」和「文档」的关系

工具是**手**，文档是**判断力**。两个都得有：

· 想知道「有什么工具」→ 看工具列表就行（你手上就有）。
· 想知道「什么时候该用哪个、用了会踩什么坑」→ 读这里的文档。

⚠️ 最常见的失败模式是**只凭印象写代码**：单位当像素用、数组从 0 开始、
拿 raw NanoVG 做 UI、猜材质路径 —— 这些在 `urhox-maker-handbook.md` 里都写了，
读一次能省掉好几轮返工。

---

## 全部技能

| 文件 | 讲什么 |
|---|---|
| `urhox-recipe-map.md` | 「要做 X 该用什么」选型表 + 脚手架对照 + 症状→解法速查 |
| `urhox-maker-handbook.md` | Maker/UrhoX 引擎硬规则：米制尺度、坐标系、Lua 陷阱、NanoVG、UI、材质、相机、资源路径、Dispose |
| `h5-handbook.md` | H5 工程怎么做：单文件结构、Canvas 循环、触摸输入、移动端适配、性能 |
| `game-design-playbook.md` | 做一款**好玩的**游戏：核心循环、难度曲线、反馈、关卡设计、常见设计错误 |
| `ui-design-spec.md` | UI 设计规范：配色、字号、间距、动效 |
| `pixel-art-generator.md` | 像素美术生成：调色板、尺寸、动画帧 |

---

## 三条最省时间的习惯

1. **动手前先读代码**（`game_read` / `code_search`），别猜文件名和结构。
   要读的文件**一轮里一起读完**，别读一个想一下再读下一个。
2. **改完调 `game_validate` 自检**，比只看截图靠谱 ——
   截图只能看出「画面不对」，它告诉你「哪一行抛了异常」。
3. **一次把能做的做完**，再统一 reload / 截图。
   每个来回都是一次完整的 API 往返，很贵也很慢。
