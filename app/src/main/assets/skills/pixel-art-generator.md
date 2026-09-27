---
name: pixel-art-generator
description: |
  Pixel art asset generator for retro-style games. Generate pixel-style characters, items,
  scenes, tiles, and UI elements with consistent retro aesthetics using AI image generation.
  Supports multiple pixel sizes (16x16, 32x32, 64x64, 128x128) and animation frame sequences
  (walk, attack, idle). Provides assets for both horizontal (side-scrolling) and vertical
  (top-down/portrait) game layouts.
  Use when users need to: (1) generate pixel art characters or sprites, (2) create pixel-style
  item/weapon/equipment icons, (3) generate retro game scene backgrounds, (4) create tile/tilemap
  assets, (5) generate pixel animation frame sequences (walk cycle, attack, idle), (6) create
  pixel-style UI elements (buttons, panels, health bars), (7) batch-generate a complete set of
  pixel game assets, or any other pixel art generation tasks for game development.
---

# Pixel Art Generator

Generate pixel-style game assets using `generate_image` and `batch_generate_images` tools.

## Workflow

1. Determine asset type (character / item / scene / tile / UI / animation)
2. Determine pixel size based on game style — see [references/size-guide.md](references/size-guide.md)
3. Build prompt from templates — see [references/prompt-templates.md](references/prompt-templates.md)
4. Call `generate_image` (single) or `batch_generate_images` (batch/animation frames)

## Quick Reference

### Prompt Construction

Every prompt follows: `{style prefix},{asset description},{view/pose}`

**Style prefix** (always include): `像素风格,pixel art,复古游戏风格,清晰像素边缘,无抗锯齿`

Add `透明背景` for characters/items. Omit for scene backgrounds.

### Common Sizes

| Asset | Size | aspect_ratio | transparent |
|-------|------|-------------|-------------|
| Character sprite | `"32x32"` or `"64x64"` | `"1:1"` | true |
| Tall character | `"32x48"` or `"48x64"` | `"2:3"` or `"3:4"` | true |
| Item/weapon icon | `"32x32"` or `"64x64"` | `"1:1"` | true |
| Scene background (horizontal) | `"320x180"` or `"480x270"` | `"16:9"` | false |
| Scene background (vertical) | `"180x320"` or `"270x480"` | `"9:16"` | false |
| Tile | `"16x16"` or `"32x32"` | `"1:1"` | false |
| UI element | `"64x64"` or `"128x128"` | `"1:1"` | true |

### Tool Call Examples

**Single character:**

```
generate_image(
  prompt="像素风格,pixel art,复古游戏风格,清晰像素边缘,无抗锯齿,透明背景,手持长剑的骑士,银色铠甲,蓝色披风,侧面朝右,全身像",
  name="knight",
  target_size="64x64",
  aspect_ratio="1:1",
  transparent=true
)
```

**Batch item icons:**

```
batch_generate_images(images=[
  { prompt="像素风格,pixel art,复古游戏风格,清晰像素边缘,无抗锯齿,透明背景,游戏道具图标,红色生命药水瓶,玻璃瓶,红色液体,木塞", name="potion_hp", target_size="32x32", transparent=true },
  { prompt="像素风格,pixel art,复古游戏风格,清晰像素边缘,无抗锯齿,透明背景,游戏道具图标,蓝色魔法药水瓶,玻璃瓶,蓝色液体发光", name="potion_mp", target_size="32x32", transparent=true },
  { prompt="像素风格,pixel art,复古游戏风格,清晰像素边缘,无抗锯齿,透明背景,游戏道具图标,金币,圆形,金色光泽", name="coin", target_size="32x32", transparent=true },
])
```

**Walk animation (4 frames, use same seed):**

```
batch_generate_images(images=[
  { prompt="像素风格,pixel art,复古游戏风格,清晰像素边缘,无抗锯齿,透明背景,手持长剑的骑士,银色铠甲,侧面朝右,站立姿势,双脚并拢", name="knight_walk_1", target_size="48x64", aspect_ratio="3:4", transparent=true, seed=42 },
  { prompt="像素风格,pixel art,复古游戏风格,清晰像素边缘,无抗锯齿,透明背景,手持长剑的骑士,银色铠甲,侧面朝右,行走姿势,左腿向前迈出,右腿在后", name="knight_walk_2", target_size="48x64", aspect_ratio="3:4", transparent=true, seed=42 },
  { prompt="像素风格,pixel art,复古游戏风格,清晰像素边缘,无抗锯齿,透明背景,手持长剑的骑士,银色铠甲,侧面朝右,行走姿势,双腿交叉中间过渡", name="knight_walk_3", target_size="48x64", aspect_ratio="3:4", transparent=true, seed=42 },
  { prompt="像素风格,pixel art,复古游戏风格,清晰像素边缘,无抗锯齿,透明背景,手持长剑的骑士,银色铠甲,侧面朝右,行走姿势,右腿向前迈出,左腿在后", name="knight_walk_4", target_size="48x64", aspect_ratio="3:4", transparent=true, seed=42 },
])
```

## Decision Guide

```
User request
  |
  ├─ "生成角色" → character sprite
  │   ├─ 需要动画? → batch_generate_images (多帧, 同 seed)
  │   └─ 单张? → generate_image (transparent=true)
  │
  ├─ "生成道具/武器/装备" → item icon
  │   ├─ 多个道具? → batch_generate_images
  │   └─ 单个? → generate_image (transparent=true)
  │
  ├─ "生成场景/背景" → scene background
  │   ├─ 横版? → aspect_ratio="16:9", transparent=false
  │   └─ 竖版? → aspect_ratio="9:16", transparent=false
  │
  ├─ "生成地块/Tile" → tile asset
  │   └─ generate_image (1:1, transparent=false)
  │
  └─ "生成UI" → UI element
      └─ generate_image (transparent=true)
```

## Key Rules

1. **Prompt language**: Always use Chinese prompts — the image generation API optimized for Chinese input.
2. **Style prefix is mandatory**: Every prompt must start with `像素风格,pixel art,复古游戏风格,清晰像素边缘,无抗锯齿`.
3. **Animation consistency**: Use the **same seed** across all frames of one animation sequence. Keep the character description identical, only change pose/action.
4. **Small sizes need emphasis**: For 16x16 targets, add `低像素数,粗大像素块,清晰方形像素` to prompt.
5. **Transparent backgrounds**: Characters and items need `transparent=true`. Scenes and tiles do not.
6. **Batch for efficiency**: Use `batch_generate_images` when generating 2+ related assets.
7. **Naming convention**: Use `{type}_{variant}` format, e.g. `knight_walk_1`, `potion_hp`, `bg_forest`.

## References

- **Prompt templates**: [references/prompt-templates.md](references/prompt-templates.md) — full prompt patterns for all asset types
- **Size guide**: [references/size-guide.md](references/size-guide.md) — pixel sizes, aspect ratios, and game style recommendations
