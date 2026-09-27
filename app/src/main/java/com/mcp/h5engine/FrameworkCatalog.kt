package com.mcp.h5engine

/**
 * 内置 H5 游戏框架的目录表。
 *
 * 真实文件由 CI 构建时下载到 assets/lib/，运行时通过
 * https://appassets.androidplatform.net/lib/<file> 提供，
 * 所以离线也能用（没网也能做游戏）。
 */
data class LibSpec(
    val key: String,
    val label: String,
    /** assets/lib 下的文件名 */
    val file: String,
    /** 一句话适用场景 */
    val purpose: String,
    /** 典型用法 */
    val snippet: String
)

object Frameworks {

    val ALL: List<LibSpec> = listOf(
        LibSpec(
            "phaser", "Phaser 3", "phaser.min.js",
            "2D 全能引擎：平台跳跃 / 塔防 / RPG / 弹幕。自带物理、Tween、瓦片地图、粒子、音频。做「像样的小游戏」首选。",
            "const g = new Phaser.Game({type: Phaser.AUTO, width: 480, height: 800, " +
                "physics: {default: 'arcade'}, scene: {create, update}});"
        ),
        LibSpec(
            "pixi", "PixiJS", "pixi.min.js",
            "高性能 2D 渲染：几千个精灵、粒子特效、滤镜、遮罩。逻辑要自己写，只负责画得快。",
            "const app = new PIXI.Application({resizeTo: window}); document.body.appendChild(app.view);"
        ),
        LibSpec(
            "three", "Three.js", "three.min.js",
            "3D：第一人称 / 体素 / 低多边形。做 3D 游戏或炫酷 3D 背景用它。",
            "const scene = new THREE.Scene(); const cam = new THREE.PerspectiveCamera(60, " +
                "innerWidth/innerHeight, 0.1, 1000);"
        ),
        LibSpec(
            "matter", "Matter.js", "matter.min.js",
            "2D 刚体物理：弹球、堆叠、ragdoll、桥梁。轻量（约 90KB），只管物理不管渲染。",
            "const engine = Matter.Engine.create(); Matter.Composite.add(engine.world, " +
                "Matter.Bodies.rectangle(200, 0, 400, 40));"
        ),
        LibSpec(
            "p5", "p5.js", "p5.min.js",
            "创意编程 / 视觉实验 / 交互艺术。上手最快，适合做抽象玩法和小体量互动作品。",
            "function setup() { createCanvas(innerWidth, innerHeight); } " +
                "function draw() { background(250, 247, 242); }"
        ),
        LibSpec(
            "howler", "Howler.js", "howler.min.js",
            "音频：背景音乐、音效、音量控制。比原生 Audio 稳。",
            "const s = new Howl({src: ['jump.mp3']}); s.play();"
        ),
        LibSpec(
            "matter_tools", "Matter.js 调试", "matter.min.js",
            "同上（别名），需要调试渲染时用 Matter.Render。",
            "Matter.Render.create({element: document.body, engine: engine});"
        )
    )

    /** 给 AI 看的清单文字 */
    fun catalogText(): String = buildString {
        append("内置框架（已随 App 打包，离线可用）：\n")
        for (l in ALL) {
            append("\n· ").append(l.label).append("  →  ").append(l.file)
            append("\n  ").append(l.purpose)
            append("\n  引用：<script src=\"https://appassets.androidplatform.net/lib/")
                .append(l.file).append("\"></script>")
            append("\n  或动态：Engine.lib('").append(l.key).append("').then(() => { /* 开始写游戏 */ })")
            append("\n  示例：").append(l.snippet)
        }
        append("\n\n选择建议：\n")
        append("- 平台跳跃 / 塔防 / RPG / 弹幕 → Phaser 3\n")
        append("- 超多精灵、粒子特效 → PixiJS\n")
        append("- 3D / 第一人称 / 体素 → Three.js\n")
        append("- 只要物理玩具 → Matter.js\n")
        append("- 创意交互 / 视觉实验 → p5.js\n")
        append("- 贪吃蛇 / 2048 这种极简玩法 → 直接原生 Canvas，不要引库（启动最快、包最小）\n")
    }
}