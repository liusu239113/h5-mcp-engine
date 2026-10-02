package com.mcp.h5engine

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContentValues
import android.app.Dialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import org.mozilla.geckoview.GeckoView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 宿主：手机上的 AI 游戏工作台。
 *
 *   [对话]  和 AI 聊天，可带图 / 带当前画面截图
 *   [预览]  看游戏画面（带轻量工具条）
 *   [发布]  导出工程 / 看文件 / 看 console / 分享
 *
 * 界面风格：手绘简洁风，浅色为默认，可在设置里切深色。
 * 所有可点区域都有按下反馈；导出结果会明确告诉用户文件在哪、并能一键分享。
 */
/**
 * 预览静音闸门（注入到每一个游戏页面的最前面）。
 *
 * 为什么必须注入、且必须注入在游戏脚本之前：
 * 预览 WebView 是常驻的 —— 切到「对话」只是改 visibility，页面和游戏循环都还活着，
 * 所以游戏里的 BGM / 音效会一直响下去（用户切出去还在听，纯扰民）。
 * 而 AudioContext 是游戏启动时建的，等 onPageFinished 再注入就晚了（抓不到已建的实例），
 * 因此在资源层把这段脚本插到 HTML 最前面。
 *
 * 两条路都堵：
 *   1) <audio>/<video>/new Audio() —— 静音期间 play() 直接拒绝，并把正在播的 pause 掉；
 *   2) Web Audio —— 把 AudioContext.prototype.destination 换成自己控制的 GainNode，
 *      「已经建好的」AudioContext 也照样能压（下一次取 destination 时就接上了），
 *      所以切换页签是立刻生效，不需要重载页面。
 *
 * 对外只暴露 window.__hexoraSetMuted(bool)，由 MainActivity 按当前页签调用。
 * 默认 muted = true：页面一进来就是安静的，只有停在「预览」页才放开。
 */
private val GAME_MUTE_BOOTSTRAP = """
<script>(function(){
  if (window.__hexoraMuteInstalled) return;
  window.__hexoraMuteInstalled = true;
  var muted = true, ctxs = [], gains = [];
  function pauseMedia(){
    try {
      var els = document.querySelectorAll('audio,video');
      for (var i = 0; i < els.length; i++) { try { els[i].pause(); } catch (e) {} }
    } catch (e) {}
  }
  try {
    var proto = window.HTMLMediaElement && HTMLMediaElement.prototype;
    if (proto && proto.play) {
      var _play = proto.play;
      proto.play = function(){
        // 静音期间：暂停并「装作播放成功」。故意不 reject —— 否则游戏里没 catch 的
        // play() 会往 console 里刷 Uncaught (in promise) 错误，AI 查 console_logs 时
        // 会以为音效坏了，转头去改游戏代码。
        if (muted) { try { this.pause(); } catch (e) {} return Promise.resolve(); }
        return _play.apply(this, arguments);
      };
    }
  } catch (e) {}
  var AC = window.AudioContext || window.webkitAudioContext;
  if (AC && AC.prototype) {
    try {
      var d = Object.getOwnPropertyDescriptor(AC.prototype, 'destination');
      if (d && d.get) {
        Object.defineProperty(AC.prototype, 'destination', {
          configurable: true,
          get: function(){
            var real = d.get.call(this);
            var g = this.__hexoraGain;
            if (!g) {
              try { g = this.createGain(); g.connect(real); } catch (e) { return real; }
              this.__hexoraGain = g;
              gains.push(g);
            }
            g.gain.value = muted ? 0 : 1;
            return g;
          }
        });
      }
    } catch (e) {}
    try {
      var OrigAC = AC;
      function PatchedAC(){
        var c = new OrigAC(...Array.prototype.slice.call(arguments));
        try { ctxs.push(c); if (muted) c.suspend(); } catch (e) {}
        return c;
      }
      PatchedAC.prototype = OrigAC.prototype;
      Object.setPrototypeOf(PatchedAC, OrigAC);
      window.AudioContext = PatchedAC;
      if (window.webkitAudioContext) window.webkitAudioContext = PatchedAC;
    } catch (e) {}
  }
  // 兜底：有些播放是内核按 autoplay 属性自己触发的，根本不经过我们补丁过的
  // HTMLMediaElement.prototype.play()。监听 play 事件（捕获阶段），静音期间一律按停。
  try {
    document.addEventListener('play', function(ev){
      if (!muted) return;
      var t = ev.target;
      try { t.muted = true; } catch (e) {}
      try { t.pause(); } catch (e) {}
    }, true);
  } catch (e) {}
  // 让引擎自己以为「切后台了」：Godot / Cocos / Phaser 这类引擎在 document.hidden
  // 为 true 时会主动暂停音频 —— 比自己挨个去按 <audio> 稳得多。
  try {
    Object.defineProperty(document, 'hidden', {
      configurable: true,
      get: function(){ return !!muted; }
    });
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: function(){ return muted ? 'hidden' : 'visible'; }
    });
  } catch (e) {}
  window.__hexoraMuteNotify = function(){
    try { document.dispatchEvent(new Event('visibilitychange')); } catch (e) {}
    try { window.dispatchEvent(new Event('blur')); } catch (e) {}
    try { window.dispatchEvent(new Event('pagehide')); } catch (e) {}
  };
  window.__hexoraIsMuted = function(){ return muted; };
  window.__hexoraSetMuted = function(v){
    muted = !!v;
    for (var i = 0; i < gains.length; i++) { try { gains[i].gain.value = muted ? 0 : 1; } catch (e) {} }
    for (var j = 0; j < ctxs.length; j++) { try { muted ? ctxs[j].suspend() : ctxs[j].resume(); } catch (e) {} }
    if (muted) pauseMedia();
    try { window.__hexoraMuteNotify(); } catch (e) {}
  };
})();</script>
""".trimIndent()

/**
 * 同一段闸门的「纯 JS」版本，给文档开始注入用。
 *
 * 为什么必须有这条路：页面自带的 Content-Security-Policy 会把我们插进 HTML 里的
 * 内联 <script> 直接拒绝执行（「Refused to execute inline script」），
 * 而 App 侧那句 window.__hexoraSetMuted && ... 又是静默失效的写法 ——
 * 结果就是闸门压根没装上，切页 / 切后台照样响，还一个错都不报。
 * 文档开始注入不受 CSP 限制，而且在每个 iframe 里都会执行。
 */
private val GAME_MUTE_BOOTSTRAP_JS: String =
    GAME_MUTE_BOOTSTRAP.removePrefix("<script>").removeSuffix("</script>")

/**
 * 把上面的静音闸门插进 HTML 响应里。
 *
 * 只动 text/html，其它资源（js/css/图片/音频）原样透传，别把它们也读进内存。
 */
private class GameMuteInjectHandler(
    private val inner: WebViewAssetLoader.PathHandler
) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? {
        val res = inner.handle(path) ?: return null
        val mime = res.mimeType ?: return res
        if (!mime.contains("html", ignoreCase = true)) return res
        return runCatching {
            val stream = res.data ?: return res
            val html = stream.readBytes().toString(Charsets.UTF_8)
            WebResourceResponse(
                res.mimeType,
                res.encoding ?: "utf-8",
                ByteArrayInputStream((GAME_MUTE_BOOTSTRAP + html).toByteArray(Charsets.UTF_8))
            )
        }.getOrElse { res }
    }
}

/**
 * 从页面内部抓一张 canvas 截图（给「AI 看一眼画面」用），返回 dataURL 或空串。
 *
 * 为什么走这条路：WebView 是硬件加速渲染的，画面在 Chromium 自己的图层里，
 * `web.draw(canvas)` 只能拿到空白；而 PixelCopy 要求页面在窗口上可见 ——
 * 那就要翻页，会动用户正在看的屏幕（用户明确不许）。从页面里抓跟窗口无关，两难都绕开了。
 *
 * 两个要点：
 *   1) 先同步抓一次 —— 2D canvas 任何时候都能拿到。
 *   2) 拿不到就排两个 rAF，在**游戏自己画完之后的同一帧里**再抓：
 *      WebGL 的绘制缓冲合成完就被清掉，晚一帧就是全黑。这是唯一能抓到 WebGL 画面的时机。
 *      抓完把 armed 复位，方便下一次再抓（不复位的话第二次就永远抓不到了）。
 */
private val CANVAS_SHOT_JS = """
(function(){
  window.__hexShot='';
  function pick(){
    var cs=document.querySelectorAll('canvas'),best=null,area=0;
    for(var i=0;i<cs.length;i++){var c=cs[i],a=(c.width|0)*(c.height|0);if(a>area){area=a;best=c;}}
    return best;
  }
  function grab(){
    var c=pick(); if(!c||!c.width||!c.height) return '';
    try{var d=c.toDataURL('image/jpeg',0.75);return (d&&d.indexOf('data:image')===0)?d:'';}catch(e){return '';}
  }
  var now=grab();
  if(now) return now;
  if(!window.__hexShotArmed){
    window.__hexShotArmed=1;
    requestAnimationFrame(function(){requestAnimationFrame(function(){
      window.__hexShot=grab();window.__hexShotArmed=0;
    });});
  }
  return '';
})()
""".trimIndent()

class MainActivity : AppCompatActivity(), GameUi {

    // ---------- 视图 ----------
    private lateinit var web: WebView
    private lateinit var bridge: EngineBridge
    private lateinit var chatPage: LinearLayout
    private lateinit var previewPage: LinearLayout
    // 是 FrameLayout 不是 LinearLayout：发布页里「管理发布」那一层要**叠**在原页面上，
    // 竖排 LinearLayout 会把第二个 MATCH_PARENT 的子视图排到屏幕外面（点不动就是这个原因）
    private lateinit var pubPage: FrameLayout
    private lateinit var chatList: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var inputEt: EditText
    private lateinit var sendBtn: TextView
    private lateinit var stopBtn: TextView
    private lateinit var modelBtn: TextView
    private lateinit var attachInfo: TextView
    private lateinit var titleTv: TextView
    private lateinit var subTv: TextView
    private lateinit var navChat: LinearLayout
    private lateinit var navPreview: LinearLayout
    private lateinit var navPub: LinearLayout
    private lateinit var rootView: LinearLayout
    private lateinit var stage: FrameLayout
    private lateinit var pubScroll: LinearLayout
    private lateinit var lastExportTv: TextView

    // ---------- 底部状态栏（对齐游戏编辑器 / IDE 底部那条） ----------
    private lateinit var statusProj: TextView
    private lateinit var statusModel: TextView
    private lateinit var statusRun: TextView
    /** 顶栏的模式徽标：H5 / Maker —— 编辑器里最基本的一条信息：我现在在什么模式的工程里 */
    private lateinit var modeBadge: TextView

    private val main = Handler(Looper.getMainLooper())
    private val logs = LogBuffer()
    /** 当前会话的消息（切会话时整体替换） */
    private var history: MutableList<ChatMsg> = mutableListOf()
    private val sessions = mutableListOf<ChatSession>()
    private var activeSession = 0
    private val attached = java.util.ArrayList<Attach>()      // 待发送区（选了但还没加入对话）
    private val queued = java.util.ArrayList<Attach>()        // 已加入本轮对话的附件
    private val shotBytes = HashMap<String, ByteArray>()      // 图片 rel → 压缩后的 JPEG（多模态用）
    private var pickMode = "media"                            // media / doc / skill
    private lateinit var pendingBox: LinearLayout             // 待发送区容器
    private var fullDlg: Dialog? = null                       // 全屏预览 Dialog
    private lateinit var previewWrapHolder: FrameLayout       // 预览 WebView 的容器（全屏时搬家）
    private lateinit var previewSlot: LinearLayout            // 预览容器原位（退出全屏搬回来）
    private var sessStore: SessionStore? = null

    /** 正在试听的音频（再点一次就停；避免多个播放器叠着响） */
    @Volatile
    private var audioPlayer: android.media.MediaPlayer? = null

    /** 会话库按项目取：项目变了就换库，新项目 = 全新上下文 */
    private fun ss(): SessionStore {
        val cur = sessStore
        if (cur != null && cur.proj == currentGame) return cur
        val s = SessionStore(this, currentGame).also { it.migrateLegacy() }
        sessStore = s
        return s
    }
    private lateinit var sessionBtn: TextView
    private val pendingShots = mutableListOf<ByteArray>()
    private val cfgStore by lazy { AiConfigStore(this) }

    private lateinit var pal: Palette
    private lateinit var gameRoot: File

    @Volatile
    private var currentGame = "demo"

    /** 顶栏左上角「项目」入口 */
    private lateinit var projBtn: TextView

    private var projectDlg: HxDialog? = null
    private var rootInited = false

    // ---------- 运行状态可视化：折叠思考面板 + 计时 ----------
    private var runHead: TextView? = null

    /** 底部常驻状态行：输入框上方，切后台回来也能看到跑了多久 */
    private var runBar: TextView? = null
    private var guardOn = false
    private var lastNotiSec = -1L
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var runBody: TextView? = null
    private var runTicker: Runnable? = null
    private var runStartAt = 0L
    private var runSteps = 0
    private var bodyExpanded = false
    private var latestActivity = ""
    // ===== 工具调用组（对齐 Maker）：一轮只出一张「已工作」卡，工具行折进组体里 =====
    //
    // 为什么不像以前那样「每调一个工具就往对话里插一行」：
    //   对话区是个 ScrollView，每插一行 = 整棵视图树重新测量 + 滚动位置重算。
    //   一轮任务动辄几十次工具调用，插几十行就是几十次重排 —— 用户看到的就是「一直闪」。
    // 现在的做法：一轮只建一张卡，工具行全部塞进**默认收起**的组体里。
    // 收起状态下，内容高度完全不变，屏幕上只有组头那一个 TextView 在变字。
    /** 组体里专门装工具行的容器（行只往这里加，不碰组头） */
    private var toolWrap: LinearLayout? = null
    /** 本轮工具调用统计：总数 / 每类次数（用来生成「读取 2 次」） */
    private var toolTotal = 0
    private val toolVerbCount = LinkedHashMap<String, Int>()
    /** 正在跑的那一行（TOOLRUN 先插行，TOOL/TOOLFAIL 回来再把它定型） */
    private var pendingRow: LinearLayout? = null
    private var pendingStatus: ImageView? = null
    private var pendingSummary: TextView? = null
    /** 运行中的转圈动画 + 它落脚的两个视图（转圈 ↔ ✓/✕ 在同一个槽位里换） */
    private var pendingSpin: ObjectAnimator? = null
    private var pendingSpinView: ImageView? = null
    private var pendingMarkView: ImageView? = null
    /** 组头文案节流：只在字符串真的变了才 setText */
    private var lastWorkStat = ""
    /** 流式文字预览（runBar 第二行）上一次画的内容，避免重复 setText */
    private var lastTail = ""
    /** 运行中用户又发的消息：排队，等这轮结束自动发出去（而不是粗暴掐断上一轮） */
    private val msgQueue = ArrayDeque<String>()
    private var runToggle: TextView? = null
    /**
     * 本轮那张「已工作」卡的容器。
     *
     * 用途只有一个：**保证工作行永远加在对话末尾**。
     *
     * 踩过的坑（用户反馈）：模型先交付了结论（气泡 + 那句「已工作」卡都在上面），
     * 然后验证闸门把它顶回去继续干活 —— 后面的工具行全加进了**上面那张旧卡**里。
     * 结果就是「对话区看不到后续工作流程，只有底部状态行在动」：
     * 工作行被塞到了结论文字的**上方**，用户根本翻不到。
     */
    private var runCardView: LinearLayout? = null
    private var runSeq = 0
    private var runWrote = false
    /** 本轮是否摸过 maker_ 工具（保证「第一次做 Maker」也能自动构建） */
    private var makerTouched = false
    /**
     * 本轮**实际**用的技能 id。
     * 技能可能是按意图自动切过去的（一句「帮我接广告」就切到 ads），
     * 续跑时必须沿用同一个 —— 否则会掉回通用技能，做法整个变样。
     */
    private var lastRunSkillId: String? = null
    /** 本轮 AI 自己调过构建没 —— 调过就不要再重复自动构建 */
    private var builtThisRun = false

    /** 事件批量合并用的缓冲：忙的时候一秒几十条，逐条建 View 会明显卡 */
    private val pendingEvents = mutableListOf<String>()
    private var flushScheduled = false
    /** 思考内容的节流重绘是否已排期（见 scheduleThinkPaint） */
    private var thinkPaintScheduled = false
    private var scrollPending = false

    /** 是否跟随到底部：用户往上翻就停下（免得边看边被拽走），滑回底部附近自动恢复 */
    private var stickBottom = true
    /** 用户主动上滑阅读：为 true 时忽略一切自动滚动，直到他回到底部或点「回到底部」。 */
    private var userPinned = false
    private var scrollBtn: ImageView? = null
    private var chatWrap: FrameLayout? = null

    /** 预览页：上次加载的项目与目录指纹，用来判断要不要自动重载 */
    private var lastLoadedGame = ""
    private var lastLoadedStamp = 0L

    @Volatile
    private var running = false
    /**
     * 运行线程是否真的还活着。
     *
     * 自愈用：万一某轮因为异常路径没走到 finishRun()，running 会永远卡在 true，
     * 之后用户发什么消息都只会「排队」，一条也发不出去（用户报的 bug）。
     */
    @Volatile private var runAlive = false
    /** 待发送队列面板（运行中发的消息会进这里，可编辑 / 删除 / 立即发） */
    private lateinit var queueBox: LinearLayout
    private var queueExpanded = true

    @Volatile
    private var runner: AgentRunner? = null

    /**
     * 正在跑的那个**子任务**的 runner。
     *
     * 为什么必须单独存一份：`runSubtask` 里的 `val runner = AgentRunner(...)`
     * 是个**局部变量**，把同名字段遮蔽掉了 —— 于是 `doStop()` 里的
     * `runner?.cancel()` 只掐得到主线，子任务那条线程完全没被碰。
     *
     * 真实事故（用户原话：「他都完成任务了，结果子任务还在跑，还在动我的文件，
     * 你觉得这合理吗？」）：主线因为「说了要做却没动手」被自动催满 2 次后收尾了，
     * 而它派出去的子任务还阻塞在工具里继续跑几十轮、继续写文件、继续触发热重载，
     * 把用户的预览反复冲掉。停止按钮也停不掉它。
     */
    @Volatile private var subtaskRunnerRef: AgentRunner? = null

    /**
     * 子任务是否已被放弃。
     *
     * 主线收尾（不管是正常完成还是用户按停止）就该**立刻**中止子任务 ——
     * 用户要的是「这件事做完了」，不是一个还在后台偷偷改他文件的僵尸助手。
     * 子任务自己的收尾动作（刷预览 / 弹结论卡）在这个标记下全部跳过。
     */
    @Volatile private var subtaskAbandoned = false

    /**
     * 派这个子任务的**那一轮**的序号（runSeq）。
     *
     * 不变式：**子任务不能活得比派它的那一轮久。**
     *
     * 子任务是在主线线程里同步跑的（`subtask` 是阻塞调用），所以正常情况下
     * 主线根本没法在子任务跑完之前收尾。唯一能「主线已经结束了、子任务还在跑」
     * 的路是：用户又发了条消息 / 这一轮被顶掉 → `runSeq++` →
     * 主线收尾的 `finishRun()` 被 `mySeq == runSeq` 守卫挡掉
     * （那个守卫是**故意**的，防旧轮次乱写界面）—— 于是**没人去管子任务**，
     * 它继续跑几十轮、继续写文件、继续热重载。
     *
     * 所以判据不能只看 `running`，要看「我还是不是当前那一轮」。
     */
    @Volatile private var subtaskOwnerSeq = -1

    /**
     * 子任务看门狗：每秒查一次「派我的那一轮还在不在」。
     *
     * 为什么不能只靠 onEvent 里的检查：子任务可能正卡在一次很长的 HTTP 请求里，
     * 几十秒都不产生事件 —— 那种时候只有看门狗能把它捞出来。
     */
    private val subtaskWatchdog = object : Runnable {
        override fun run() {
            if (subtaskRunnerRef == null) return          // 没有子任务了
            if (runSeq != subtaskOwnerSeq) {
                abandonSubtask()
                return
            }
            main.postDelayed(this, 1000)
        }
    }

    private var activeTab = 0
    /** 预览里现在该不该出声：默认静音，只有停在「预览」页时才放开 */
    @Volatile private var previewMuted = true

    /** 用户在预览页手动按的静音开关（优先级最高：切页、重载都按它来） */
    private var userMutePreview = false
    private var previewMuteChip: ImageView? = null

    /** 预览缩放档位（0.5 ~ 2.0）。只改渲染，不改页面视口 —— 见 zoomPreview */
    private var previewZoom = 1.0f

    /** 最近一次导出的 zip，用于「分享 / 复制路径」 */
    @Volatile
    private var lastExport: File? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        Thread {
            val bytes = runCatching {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            val small = bytes?.let { shrinkToJpeg(it, 1024, 80) }

            main.post {
                if (small == null) {
                    toast("读取图片失败")
                } else {
                    pendingShots += small
                    updateAttachInfo()
                }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 开 WebView 调试通道。**必须在任何 WebView 被 new 出来之前调用**，否则对已建好的不生效。
        //
        // 用途只有一个：让 AI 在用户停在「对话」页时也能看到游戏的真实画面
        // （见 shootViaCdp）。这是唯一「不切页 + 不改页面 + 拿到完整画面」的路子 ——
        // web.draw 拿不到硬件加速图层，PixelCopy 要求页面在屏幕上（那就是翻页，用户不许）。
        //
        // 代价（用户已知情并选择开启）：本机会多一个调试 socket
        // （webview_devtools_remote_<pid>），同机其他 App 理论上能连上读取网页内容。
        // 它只在 App 运行期间存在，App 一退出就没了。
        runCatching { WebView.setWebContentsDebuggingEnabled(true) }

        // Shizuku：注册 binder / 权限监听（**必须只注册一次**，重复注册 Shizuku 会抛）。
        // 没装 Shizuku 是常态，init 内部已经 runCatching 兜住，不会影响启动。
        Shizuku2.init(this)
        Shizuku2.onStateChanged = { runOnUiThread { runCatching { refreshShizukuRow() } } }

        gameRoot = pickProjectRoot().apply { mkdirs() }
        migrateProjectsIfNeeded()
        seedBundledGames()
        seedSharedRuntime()

        // 恢复上次打开的项目：覆盖安装会强杀进程，冷启动不恢复就永远回到 demo
        val savedGame = cfgStore.lastGame
        if (savedGame.isNotBlank() && savedGame != currentGame &&
            File(gameRoot, savedGame).isDirectory
        ) {
            currentGame = savedGame
        }
        cfgStore.lastRoot = gameRoot.absolutePath

        pal = paletteOf(this, themeModeOf(cfgStore.themeMode))

        // 冷启动回到「默认配置」：用户试过别的模型没关系，下次打开还是从自己认准的那个开始。
        // 必须在 refreshHeader / AgentRunner 取配置之前执行，否则第一条请求就用错了模型。
        cfgStore.applyDefaultOnStartup()

        // Maker 的授权 / 凭据检查要跑 CLI、读 pat.json，需要 Context。
        // AI 通过内置工具 maker_auth 触发（不受 MCP 准入开关限制）。
        EngineTools.ctxRef = applicationContext
        // 构建钩子：maker_build_current_directory 成功 → 把当前工程钉成 Maker。
        // 判型不靠「目录里有什么文件」（能被 AI 写的东西骗），只认「真构建过」这个动作。
        EngineTools.onMakerBuiltProject = { markCurrentProjectAsMaker(currentGame) }
        // 子任务：把「起一个独立上下文的助手」这个能力注入给工具层
        EngineTools.subtaskRunner = { title, goal -> runSubtask(title, goal) }

        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.bg)
        }

        buildTopBar()
        buildStage()
        buildNav()

        rootView.addView(topHolder!!, LinearLayout.LayoutParams(-1, -2))
        rootView.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        rootView.addView(navHolder!!, LinearLayout.LayoutParams(-1, -2))
        // 状态栏放在最底下：底栏之下的那一条，和 IDE / 游戏编辑器一致。
        rootView.addView(buildStatusBar(), LinearLayout.LayoutParams(-1, -2))

        setContentView(rootView)
        applySystemBars()

        setupWebView()
        buildPublishPage()
        showTab(0)

        refreshHeader()
        openGame(currentGame)
        addSystemLine("引擎已就绪 · 当前游戏：$currentGame")
        addSystemLine("直接说「做个贪吃蛇」，或点底部「当前画面」把画面发给 AI 让它改。")
        startMcp()
    }

    // ==================== 内置 MCP（TapTap 小游戏官方服务） ====================

    @Volatile private var mcpBusy = false

    /**
     * 把随 APK 分发的 MCP 运行时释放到私有目录并拉起本地服务，
     * 再把服务暴露的工具全部注册给 AI。全程后台线程，不阻塞冷启动。
     */
    private fun startMcp() {
        if (mcpBusy) return
        mcpBusy = true
        Thread {
            fun say(s: String) = main.post { addSystemLine(s) }
            // 让独立进程里的守护负责 node 的生死（主界面被冻结也不影响它）
            McpGuardService.start(this)

            // 独立进程把 node 拉起来需要几秒，这里带重试，避免「启动瞬间没就绪」被误判失败
            var hub: McpHub? = null
            for (attempt in 1..6) {
                hub = McpBoot.ensure(this) { }
                if (hub != null && hub.size > 0) break
                Thread.sleep(if (attempt == 1) 3000L else 5000L)
            }
            if (hub != null && hub.size > 0) {
                say("MCP 已就绪：${hub.lastInfo}，AI 可直接调用这些工具")
            } else {
                say("MCP：暂未连上，守护会在后台自动重试（也可到 设置 → MCP 服务器 点「刷新状态」）")
            }
            mcpBusy = false
        }.start()
    }

    private var topHolder: LinearLayout? = null
    private var navHolder: LinearLayout? = null

    // ==================== 顶栏 ====================

    /** 拿到「所有文件访问」后，项目就落在 /sdcard/Hexora —— 路径在文件管理器里看得见 */
    private fun pickProjectRoot(): File {
        // 1) 有「所有文件访问」权限：项目落在 /sdcard/Hexora，文件管理器里看得见
        runCatching {
            if (Environment.isExternalStorageManager()) {
                return File(Environment.getExternalStorageDirectory(), "Hexora")
            }
        }
        // 2) 权限判定抖动（覆盖安装后首次启动偶尔是 false）：只要上次的根目录还在就沿用，
        //    否则会静默切到私有目录，用户看到的是「项目全没了，只剩 demo」
        runCatching {
            val last = cfgStore.lastRoot
            if (last.isNotBlank()) {
                val f = File(last)
                if (f.isDirectory && f.canWrite() && f.listFiles()?.isNotEmpty() == true) return f
            }
        }
        // 3) 兜底：没有权限时唯一可写的位置
        return File(filesDir, "games")
    }

    /** 从旧的内部目录一次性迁移项目（只补不覆盖） */
    private fun migrateProjectsIfNeeded() {
        if (!gameRoot.absolutePath.contains("Hexora")) return
        val old = File(filesDir, "games")
        if (!old.isDirectory) return
        for (p in old.listFiles().orEmpty()) {
            if (!p.isDirectory || p.name.startsWith("_")) continue
            val dst = File(gameRoot, p.name)
            if (dst.exists()) continue
            runCatching { copyDir(p, dst) }
        }
    }

    private fun copyDir(src: File, dst: File) {
        if (src.isDirectory) {
            dst.mkdirs()
            for (c in src.listFiles().orEmpty()) copyDir(c, File(dst, c.name))
        } else {
            dst.parentFile?.mkdirs()
            src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
        }
    }

    override fun onPause() {
        super.onPause()
        runCatching { persistSessions() }
        // 退到后台也别继续响：预览是常驻的 WebView，游戏音效不会自己停
        applyPreviewMute(true)
        // 后台里页面还会继续走定时器、可能起新的音频，隔一会儿再压一次；
        // 用 hasWindowFocus() 兜住「用户已经切回前台停在预览页」的情况，别误静音。
        main.postDelayed({ if (!hasWindowFocus()) runCatching { applyPreviewMute(true) } }, 600)
        // 窗口不可见时 WebView 会节流甚至暂停页面里的 JS 定时器，
        // 这里显式续期一次，尽量让游戏循环继续跑（配合前台服务保活）
        if (::web.isInitialized) {
            runCatching { web.onResume() }
            runCatching { web.resumeTimers() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) {
            runCatching { web.onResume() }
            runCatching { web.resumeTimers() }
        }
        // 回到前台：只有正停在「预览」页才恢复声音
        applyPreviewMute(activeTab != 1)
        // 授权「所有文件访问」返回后，把项目根目录切到 /sdcard/Hexora
        if (rootInited && Environment.isExternalStorageManager() && !gameRoot.absolutePath.contains("Hexora")) {
            gameRoot = pickProjectRoot().apply { mkdirs() }
            migrateProjectsIfNeeded()
            seedBundledGames()
            seedSharedRuntime()
            runCatching { bridge.setSandbox(File(gameRoot, currentGame)) }
            lastLoadedGame = ""
            // 项目根换过之后，把老版本遗留在工程根的工作区素材并入当前项目
            migrateLegacyWorkspace()
            refreshHeader()
            addSystemLine("项目根目录已切到 ${gameRoot.absolutePath}")
            toast("项目目录已切换")
        }
        rootInited = true
        // 回到前台顺手续一次：后台期间被系统冻结/回收过就立刻把 MCP 拉回来
        Thread {
            runCatching {
                if (!McpRt.health()) {
                    McpBoot.ensure(this) { }
                    McpGuardService.start(this)
                }
            }
        }.start()
    }

    private fun buildTopBar() {
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.navBg)
            setPadding(dp(16), dp(14), dp(12), dp(10))
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        titleTv = TextView(this).apply {
            text = "Hexora"
            setTextColor(pal.text)
            textSize = 17.5f
            letterSpacing = 0.06f
            typeface = MEDIUM
        }

        // 模式徽标：编辑器里第一眼要看到的东西 —— 这个工程是 H5 还是 Maker。
        // 用等宽字 + 次级强调色，和「标题」拉开层级。
        modeBadge = TextView(this).apply {
            textSize = 10.5f
            typeface = MONO
            setPadding(dp(7), dp(3), dp(7), dp(3))
            setTextColor(pal.accent2)
            background = roundCard(this@MainActivity, pal.cardAlt, pal.groupBorder, 6)
        }

        val settings = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(13), dp(6))
            background = pressable(roundCard(this@MainActivity, pal.cardAlt, pal.border, 10), 0x14000000)
            isClickable = true
            addView(iconView(this@MainActivity, pal, "gear", 15, pal.sub),
                LinearLayout.LayoutParams(dp(17), dp(17)).apply { rightMargin = dp(5) })
            addView(TextView(this@MainActivity).apply {
                text = "设置"
                textSize = 12.5f
                letterSpacing = 0.06f
                setTextColor(pal.sub)
            })
            setOnClickListener { showSettings() }
        }

        // 项目名用等宽：路径 / 工程名属于「技术信息」，而且换名时宽度抖动更小
        projBtn = TextView(this).apply {
            textSize = 12.5f
            typeface = MONO
            setTextColor(pal.text)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = pressable(roundCard(this@MainActivity, pal.cardAlt, pal.border, 10), 0x14000000)
            setOnClickListener { showProjects() }
        }
        row.addView(projBtn, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
        row.addView(titleTv)
        row.addView(modeBadge, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        row.addView(TextView(this), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(settings)
        top.addView(row)

        subTv = TextView(this).apply {
            setTextColor(pal.sub)
            textSize = 11.5f
        }
        top.addView(subTv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })

        // 顶部工具栏：会话切换 / 新对话 / 目录 / 代码 —— 对齐 TapTap Maker 的形态
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, 0)
        }
        sessionBtn = TextView(this).apply {
            textSize = 12.5f
            setTextColor(pal.text)
            setPadding(dp(11), dp(7), dp(11), dp(7))
            background = pressable(roundCard(this@MainActivity, pal.cardAlt, pal.border, 10), 0x14000000)
            setOnClickListener { showSessions() }
        }
        tools.addView(sessionBtn, LinearLayout.LayoutParams(0, -2, 1f))
        val newBtn = chipOf(this, pal, "＋新对话", false).apply { setOnClickListener { newChat() } }
        tools.addView(newBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        top.addView(tools)
        updateSessionBtn()
        // 发丝分隔线：顶栏与内容之间一道 1dp 线，比投影更安静
        top.addView(View(this).apply { setBackgroundColor(pal.border) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12) })

        topHolder = top
    }

    // ==================== 三页容器 ====================

    private fun buildStage() {
        stage = FrameLayout(this)
        chatPage = buildChatPage()
        previewPage = buildPreviewPage()

        // ⚠️ 必须是 FrameLayout，不能是 LinearLayout。
        // 「管理发布」那一层是**叠**在原发布页上面的，两层都是 height=MATCH_PARENT：
        //   竖向 LinearLayout 里，第一个子视图会把整高占满，
        //   第二个被排到屏幕外面 —— 点了按钮其实有反应，但页面渲染在看不见的地方，
        //   用户看到的就是「点不动 / 没反应」。
        pubPage = FrameLayout(this).apply {
            setBackgroundColor(pal.bg)
            visibility = View.GONE
        }

        stage.addView(chatPage, FrameLayout.LayoutParams(-1, -1))
        stage.addView(previewPage, FrameLayout.LayoutParams(-1, -1))
        stage.addView(pubPage, FrameLayout.LayoutParams(-1, -1))
    }

    // ==================== 底栏 ====================

    /**
     * 一个底栏页签：图标 + 文字 + 选中指示条。
     *
     * 图标是「游戏编辑器 / IDE」观感里最直接的一环 —— 纯文字的底栏看着像文档 App，
     * 带上图标才像工具。图标用 LineIcon 现描，颜色跟着主题走。
     */
    private fun navItemView(icon: String, label: String): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(9), 0, dp(6))
            background = pressable(roundCard(this@MainActivity, pal.navBg, pal.navBg, 0, 0), 0x14000000)
        }
        box.addView(ImageView(this).apply {
            tag = icon
            setImageDrawable(LineIcon(icon, pal.navIdle, dp(17).toFloat()))
        }, LinearLayout.LayoutParams(dp(19), dp(19)))
        box.addView(TextView(this).apply {
            text = label
            textSize = 11.5f
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            setTextColor(pal.navIdle)
            setPadding(0, dp(4), 0, 0)
        })
        // 2dp 细下划线做选中指示，比「加粗变色」更克制
        box.addView(View(this).apply {
            visibility = View.INVISIBLE
            setBackgroundColor(pal.navActive)
        }, LinearLayout.LayoutParams(dp(18), dp(2)).apply {
            topMargin = dp(5)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        return box
    }

    private fun buildNav() {
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.navBg)
        }
        // 发丝分隔线：底部导航与内容之间一道 1dp 线，跟顶栏那道对称，边界更清楚
        holder.addView(View(this).apply { setBackgroundColor(pal.border) },
            LinearLayout.LayoutParams(-1, dp(1)))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        navChat = navItemView("chat", "对话")
        navPreview = navItemView("fullscreen", "预览")
        navPub = navItemView("upload", "发布")
        row.addView(navChat, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(navPreview, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(navPub, LinearLayout.LayoutParams(0, -2, 1f))
        holder.addView(row, LinearLayout.LayoutParams(-1, -2))

        navChat.setOnClickListener { showTab(0) }
        navPreview.setOnClickListener { showTab(1) }
        navPub.setOnClickListener { showTab(2) }

        navHolder = holder
    }

    private fun tintNav(box: LinearLayout, active: Boolean) {
        val color = if (active) pal.navActive else pal.navIdle
        for (i in 0 until box.childCount) {
            val c = box.getChildAt(i)
            when (c) {
                is TextView -> {
                    c.setTextColor(color)
                    c.typeface = if (active) MEDIUM else Typeface.DEFAULT
                }
                // 图标要按新颜色重描一遍：LineIcon 的颜色是构造时烘进 Paint 的，改不了
                is ImageView -> {
                    val kind = c.tag as? String ?: "layers"
                    c.setImageDrawable(LineIcon(kind, color, dp(17).toFloat()))
                }
                else -> c.visibility = if (active) View.VISIBLE else View.INVISIBLE
            }
        }
    }

    /**
     * 底部状态栏：整条是「IDE / 游戏编辑器」最标志性的元素。
     * 左到右三格：当前工程（+ 类型） / 当前模型 / 运行状态。都是只读信息。
     */
    private fun buildStatusBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(pal.statusBg)
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }
        fun slot(weight: Float, align: Int): TextView = TextView(this).apply {
            textSize = 10.5f
            typeface = MONO
            setTextColor(pal.statusFg)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = align
        }.also { bar.addView(it, LinearLayout.LayoutParams(0, -2, weight)) }

        statusProj = slot(1.05f, Gravity.START)
        statusModel = slot(1.35f, Gravity.CENTER)
        statusRun = slot(0.9f, Gravity.END)
        statusProj.setOnClickListener { showProjects() }
        statusModel.setOnClickListener { showModelSheet() }
        statusRun.setOnClickListener { if (activeTab != 0) showTab(0) else showConsole() }
        return bar
    }

    /** 多选：素材（图/音/视频）、文档、技能共用一套选择器，按 pickMode 决定 mime 白名单 */
    private val pickMany =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            onPickedMany(uris.toList())
        }

    // ==================== 工作区目录（SAF，免 Shizuku） ====================
    //
    // 用户指出：Operit **不用开 Shizuku 也能读写文件** —— 靠的是 SAF。
    // 之前我只做了 Shizuku 一条路，漏了这条。SAF 的好处：
    //   · 不需要任何特殊权限，也不需要 Shizuku；
    //   · 用户授权一个目录（比如 /sdcard/A代码库），我们就能**完整读写**它；
    //   · 缺点：只能碰授权的那一棵子树（不是全盘），而且要走 DocumentFile 的 API。

    /** 选一个目录当工作区 */
    private val pickWorkspaceDir =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            // 持久化授权：不 take 的话，重启 App 就失效了（用户会以为又坏了）
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            cfgStore.workspaceUri = uri.toString()
            toast("工作区已设为：${SafWorkspace.displayName(this, uri)}")
            refreshSafRow()
        }

    /** 让用户选工作区目录 */
    private fun askWorkspaceDir() {
        runCatching { pickWorkspaceDir.launch(null) }
            .onFailure { toast("打不开目录选择器：${it.message}") }
    }

    private fun safSubtitle(): String {
        val uri = cfgStore.workspaceUri
        if (uri.isBlank()) {
            return "未设置 —— 选一个目录后，AI 就能读写它（不需要 Shizuku）"
        }
        val name = SafWorkspace.displayName(this, Uri.parse(uri))
        return "已授权：$name（AI 可读写这个目录，不需要 Shizuku）"
    }

    private fun refreshSafRow() {
        safRowView?.let { (_, sub) -> sub.text = safSubtitle() }
    }

    private var safRowView: Pair<LinearLayout, TextView>? = null

    /** 工作区入口：设置 / 清除 */
    private fun askWorkspace() {
        val cur = cfgStore.workspaceUri
        if (cur.isBlank()) {
            HxDialog.Builder(themed(), pal)
                .setTitle("设置工作区目录")
                .setMessage(
                    "选一个目录（比如 /sdcard/A代码库），之后 AI 就能直接读写它 —— " +
                        "**不需要 Shizuku，也不需要任何特殊权限**。\n\n" +
                        "这是「免 Shizuku 读写文件」的正路：你授权哪个目录，它就能动哪个目录。"
                )
                .setPositiveButton("去选目录") { -> askWorkspaceDir() }
                .setNegativeButton("取消", null)
                .show()
        } else {
            HxDialog.Builder(themed(), pal)
                .setTitle("工作区目录")
                .setMessage(safSubtitle())
                .setPositiveButton("换一个") { -> askWorkspaceDir() }
                .setNeutralButton("清除") { ->
                    cfgStore.workspaceUri = ""
                    refreshSafRow()
                    toast("已清除")
                }
                .setNegativeButton("好", null)
                .show()
        }
    }

    private fun showTab(tab: Int) {
        activeTab = tab
        chatPage.visibleIf(tab == 0)
        previewPage.visibleIf(tab == 1)
        pubPage.visibleIf(tab == 2)
        tintNav(navChat, tab == 0)
        tintNav(navPreview, tab == 1)
        tintNav(navPub, tab == 2)
        // 声音纪律：预览 WebView 是常驻的（切页只是改 visibility），
        // 不主动静音的话，游戏 BGM / 音效会在「对话」「发布」页一直响下去。
        // 只有停在预览页时才允许发声；切走立刻掐掉。
        applyPreviewMute(userMutePreview || tab != 1)
        // 补一刀：游戏可能在「切页瞬间」刚起了一段音（定时器 / 场景切换），
        // 单次静音会漏掉这一声，350ms 后再对一次表。
        if (tab != 1) main.postDelayed({ if (activeTab != 1) applyPreviewMute(true) }, 350)
        if (tab == 1) ensurePreviewFresh(true)
        if (tab == 2) refreshExportRow()
    }

    /**
     * 预览静音开关。
     *
     * 两条路都要堵：<audio>/<video>/new Audio() 走 HTMLMediaElement，
     * 合成音效 / BGM 多数走 Web Audio（AudioContext）—— 后者在页面里由注入的
     * GAME_MUTE_BOOTSTRAP 接管（连「已经建好的」AudioContext 也能压住，
     * 所以切换是立刻生效的，不需要重载页面）。
     */
    private fun applyPreviewMute(mute: Boolean) {
        previewMuted = mute
        if (::web.isInitialized) {
            // 不再依赖注入的闸门（那条通道一旦没走到，`&&` 会静默失效）。
            // 这里自己把 audio/video 揪出来按停（含 iframe 递归），并记录
            // 「哪些 muted 是我们设的」，恢复时只还这几个，不碰游戏自己的静音。
            val js = "(function(m){" +
                "try{if(window.__hexoraSetMuted)window.__hexoraSetMuted(m);}catch(e){}" +
                "function kill(d){" +
                "try{" +
                "var a=d.querySelectorAll('audio,video');" +
                "for(var i=0;i<a.length;i++){var el=a[i];" +
                "if(m){try{if(!el.muted){el.__hexMuteSet=1;}el.muted=true;el.pause();}catch(e){}}" +
                "else{try{if(el.__hexMuteSet){el.muted=false;el.__hexMuteSet=0;}}catch(e){}}" +
                "}" +
                "var f=d.querySelectorAll('iframe,frame');" +
                "for(var j=0;j<f.length;j++){try{if(f[j].contentDocument){kill(f[j].contentDocument);}}catch(e){}}" +
                "}catch(e){}" +
                "}" +
                "kill(document);" +
                "})($mute);"
            runCatching { web.evaluateJavascript(js, null) }
        }
        // 试听用的 MediaPlayer 也一起收掉，别和预览里的声音叠在一起
        if (mute) {
            runCatching {
                audioPlayer?.let { mp -> if (mp.isPlaying) mp.stop(); mp.release() }
            }
            audioPlayer = null
        }
        // 诊断：闸门到底装上没有、页面上还有几路音频在播。
        // logcat -s hexoraMute 就能看到，别再靠「听起来还在响」猜。
        runCatching {
            web.evaluateJavascript(
                "(function(){try{var a=document.querySelectorAll('audio,video'),n=0;for(var i=0;i<a.length;i++){if(!a[i].paused)n++;}" +
                    "return JSON.stringify({gate:!!window.__hexoraMuteInstalled,muted:(window.__hexoraIsMuted?window.__hexoraIsMuted():null)," +
                    "media:a.length,playing:n,hidden:document.hidden,url:String(location.href).slice(0,90)});}catch(e){return 'ERR '+e;}})()"
            ) { r -> android.util.Log.i("hexoraMute", "mute=" + mute + " " + r) }
        }
    }

    /**
     * 缩放预览画面（± 两档，0.5 ~ 2.0）。
     *
     * 用 View 的 scaleX/scaleY，**不用 WebView 自带的缩放**：
     * 它只影响渲染，不改页面视口 —— 游戏内部 `window.innerWidth` 拿到的还是原值，
     * 不会因为用户放大一下就以为屏幕变小了（那会把布局整个打乱，对游戏是致命的）。
     * 触摸坐标由系统按视图矩阵自动反变换，所以放大之后点按钮依然准。
     */
    private fun zoomPreview(dir: Int) {
        previewZoom = (previewZoom + dir * 0.25f).coerceIn(0.5f, 2.0f)
        runCatching {
            // 以中心为轴缩放：放大时会从中间往外撑，比从左上角撑看着自然
            web.pivotX = web.width / 2f
            web.pivotY = web.height / 2f
            web.scaleX = previewZoom
            web.scaleY = previewZoom
        }
        toast("画面缩放 ${(previewZoom * 100).toInt()}%")
    }

    @Suppress("DEPRECATION")
    private fun applySystemBars() {
        runCatching {
            window.statusBarColor = pal.navBg
            window.navigationBarColor = pal.navBg
            var flags = window.decorView.systemUiVisibility
            flags = if (pal.dark) {
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            } else {
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
            // 导航栏同理：白底 + 白图标的话，三个虚拟键等于看不见 —— 这是「原生味」里最扎眼的一种。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = if (pal.dark) {
                    flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                } else {
                    flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                }
            }
            window.decorView.systemUiVisibility = flags
        }
    }

    // ==================== 对话页 ====================

    /**
     * 短肯定句 = 用户同意（用来放行 MCP 写 / 发布类工具）。
     *
     * 「可以」「好」「确认」这种回复本身不含任何领域关键词，
     * 光靠关键词表永远放行不了写接口 —— 用户明明答应了，AI 还是动不了，又是一轮来回。
     */
    private fun shortOK(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isEmpty() || t.length > 12) return false
        return listOf("可以", "好的", "好吧", "行", "确认", "同意", "没问题", "ok", "yes", "嗯")
            .any { t.contains(it) }
    }

    private fun buildChatPage(): LinearLayout {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.bg)
        }

        chatList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        chatScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(chatList)
            // 手指滑动时实时判断「还在不在底部」
            setOnScrollChangeListener { _, _, _, _, _ -> updateScrollBtn() }
            // 用户一碰、且不在最底，就立刻「钉住」：此后流式刷新不再自动拽底，
            // 直到他滑回底部或点右下角「回到底部」。这样边看历史边被拉走的问题消失。
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN,
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val gap = chatList.height - height - scrollY
                        if (gap > dp(8)) { userPinned = true; stickBottom = false }
                    }
                }
                false
            }
        }

        // 对话区外面套一层 FrameLayout：用来把「回到底部」悬浮按钮叠在右下角
        chatWrap = FrameLayout(this).apply {
            addView(chatScroll, FrameLayout.LayoutParams(-1, -1))
            val fab = ImageView(this@MainActivity).apply {
                setImageDrawable(LineIcon("down", pal.bg, dp(2).toFloat()))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = pressable(roundCard(this@MainActivity, pal.text, pal.text, 21, 0), 0x33000000)
                contentDescription = "回到底部"
                visibility = View.GONE
                setOnClickListener {
                    stickBottom = true
                    userPinned = false
                    scrollChatToBottom(true)
                }
            }
            scrollBtn = fab
            addView(fab, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.END or Gravity.BOTTOM).apply {
                rightMargin = dp(14)
                bottomMargin = dp(14)
            })
        }

        // 工作区入口：只留一个（原来「素材/文档/技能/代码」四张卡片和面板里四个页签重复了）
        // 单击 = 打开工作区（默认上次看的那一类），进去后面板里有四个页签可切
        val toolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(2), dp(14), dp(8))
        }
        toolRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(7), dp(12), dp(7))
            background = pressable(roundCard(this@MainActivity, pal.cardAlt, pal.border, 12), 0x14000000)
            isClickable = true
            addView(ImageView(this@MainActivity).apply {
                setImageDrawable(LineIcon("code", pal.sub, dp(2).toFloat()))
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }, LinearLayout.LayoutParams(dp(38), dp(38)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = "工作区"
                    textSize = 13f
                    setTextColor(pal.text)
                    typeface = MEDIUM
                })
                addView(TextView(this@MainActivity).apply {
                    text = "素材 / 文档 / 技能 / 代码"
                    textSize = 10.5f
                    setTextColor(pal.faint)
                    setPadding(0, dp(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(2) })
            addView(iconView(this@MainActivity, pal, "chevron", 13, pal.faint),
                LinearLayout.LayoutParams(dp(15), dp(15)))
            setOnClickListener { openWorkspace(wsTab) }
        }, LinearLayout.LayoutParams(-1, -2))
        page.addView(toolRow)

        // 待发送区：一行一个附件（小图标 + 文件名 + ×）；点条目 = 加入对话
        pendingBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(14), dp(6))
        }
        page.addView(pendingBox)

        page.addView(chatWrap, LinearLayout.LayoutParams(-1, 0, 1f))

        page.addView(View(this).apply { setBackgroundColor(pal.border) },
            LinearLayout.LayoutParams(-1, dp(1)))

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(pal.navBg)
        }

        // 运行状态行：固定在输入框上方，两行有保证 ——
        //   第一行是状态（跑了多久 / 第几轮 / 正在干什么），第二行是流式文字的最新一小段。
        // 关键：它**不在 ScrollView 里**。流式文字放这儿滚动播放，
        // 对话区的视图树就一帧都不用重排，「边输出边闪」的根子就断了。
        // minLines = 2 是为了高度恒定：不然 1 行↔2 行来回跳，输入框会跟着上下抽。
        runBar = TextView(this).apply {
            textSize = 10.5f
            typeface = MONO
            setTextColor(pal.sub)
            visibility = View.GONE
            minLines = 2
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(4), 0, dp(4), dp(6))
        }
        box.addView(runBar)

        attachInfo = TextView(this).apply {
            setTextColor(pal.accent)
            textSize = 11.5f
            visibility = View.GONE
            setPadding(dp(4), 0, dp(4), dp(6))
        }
        box.addView(attachInfo)

        inputEt = hxInput(this, pal, "说出你想做的游戏，或问 AI 现在的画面哪里不对…", multiLine = true).apply {
            minLines = 1
            maxLines = 5
            // 输入框比普通弹窗输入更「厚」一点，圆角也大一档，跟底部这块大留白相称
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = roundCard(this@MainActivity, pal.card, pal.border, 14)
        }
        // 待发送队列：贴在输入框上方，没有待发消息时整个隐藏
        queueBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(queueBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        box.addView(inputEt, LinearLayout.LayoutParams(-1, -2))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        val attachBtn = chipOf(this, pal, "＋ 附件", false).apply {
            setOnClickListener {
                HxDialog.Builder(themed(), pal)
                    .setTitle("添加附件")
                    .setItems(
                        arrayOf("素材（图 / 音频 / 视频，可多选）", "文档（txt / md / json / zip / docx…）", "当前游戏画面")
                    ) { i ->
                        when (i) {
                            0 -> pickMedia("media")
                            1 -> pickMedia("doc")
                            else -> attachScreenshot()
                        }
                    }
                    .show()
            }
        }
        val shotBtn = chipOf(this, pal, "当前画面", false).apply {
            setOnClickListener { attachScreenshot() }
        }
        // 模型胶囊：底部随时可见、随手可切。
        // 点 = 开模型面板（换模型）；长按 = 直接进设置页（改 Key / 参数）。
        modelBtn = TextView(this).apply {
            textSize = 11.5f
            typeface = MONO
            setTextColor(pal.accent)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = pressable(
                roundCard(this@MainActivity, pal.accentSoft, pal.groupBorder, 8), 0x14000000
            )
            setOnClickListener { showModelSheet() }
            setOnLongClickListener { showSettings(); true }
        }
        stopBtn = ghostBtnOf(this, pal, "停止").apply {
            visibility = View.GONE
            // 立刻生效：不等线程自己结束、不等 240 秒读超时（见 doStop）
            setOnClickListener { doStop("手动停止") }
        }
        sendBtn = primaryBtnOf(this, pal, "发送").apply {
            setOnClickListener { send() }
            // 点 = 发出去（上一轮还在跑就**排队**，跑完自动发）；
            // 长按 = 等不及了，**打断**当前轮立刻发。
            setOnLongClickListener { send(interrupt = true); true }
        }

        row.addView(attachBtn)
        row.addView(shotBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        row.addView(modelBtn, LinearLayout.LayoutParams(0, -2, 1f).apply {
            leftMargin = dp(8)
            rightMargin = dp(8)
        })
        row.addView(stopBtn, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        row.addView(sendBtn)
        box.addView(row)

        page.addView(box, LinearLayout.LayoutParams(-1, -2))
        initSessions()
        return page
    }

    // ==================== 气泡 ====================

    /** 段落里识别出来的链接（http/https），供「复制链接 / 打开链接」用 */
    private val LINK_REGEX = Regex("https?://[^\\s\"'<>\\u3002\\uff0c\\uff09\\uff08\\u3010\\u3011]+")

    /** 对话里链接的颜色：蓝 + 可点，和浏览器里一个观感 */
    private val LINK_BLUE = 0xFF2E6BE6.toInt()


    private fun addBubble(text: String, fromUser: Boolean, thumbs: List<ByteArray> = emptyList()) {
        // 空内容不画。以前这里会照画不误：段落被过滤光之后兜底成 listOf(text)，
        // 于是一个只含空白的消息会渲染成「一个只有复制按钮、没有字的空块」——
        // 看着就像一个「缺了边框的卡片」（用户报的「输出完成卡片没有边框」很可能就是它）。
        if (text.isBlank() && thumbs.isEmpty()) return
        // 一条消息 = 一个气泡；内部按空行切段，每段自带「复制」，含链接的段落多一个「复制链接」
        // 用户消息保留气泡（自己的话要能和 AI 的分开）；
        // **AI 的正文不再套卡片** —— 一屏几十个白框会让人分不清「正文」和「插进来的卡片」，
        // 观感也偏表单。正文就老老实实当正文排版，靠留白和字号分层。
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (fromUser) {
                setPadding(dp(13), dp(10), dp(13), dp(8))
                background = roundCard(this@MainActivity, pal.userBubble, pal.userBubble, 16, 0)
            } else {
                setPadding(0, dp(4), 0, dp(6))
            }
        }
        // 用户发的图：直接把缩略图放进气泡里（以前只在下面挂一行「[附件] xx.jpg」文字，等于看不到图）
        if (thumbs.isNotEmpty()) bubbleThumbRow(card, thumbs)
        val paras = text.split(Regex("\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf(text) }
        for ((i, seg) in paras.withIndex()) {
            if (i > 0) card.addView(View(this), LinearLayout.LayoutParams(-1, dp(7)))
            card.addView(TextView(this).apply {
                // 链接染成蓝色、点一下直接跳外部浏览器；没有链接的段落才保持可长按选中
                textSize = if (fromUser) 14f else 13.5f
                setTextColor(if (fromUser) pal.userText else pal.aiText)
                linkifySeg(this, seg)
            })
            val links = LINK_REGEX.findAll(seg).map { it.value }.toList()
            val ops = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            ops.addView(iconOp("copy", "复制本段") { copyToClip(seg, "本段") })
            if (links.size == 1) {
                ops.addView(iconOp("open", "用浏览器打开链接") { openExternal(links[0]) })
                ops.addView(iconOp("link", "复制链接地址") { copyToClip(links[0], "链接") })
            } else if (links.isNotEmpty()) {
                ops.addView(iconOp("link", "复制链接（${links.size} 个，点开可选）") { pickLink(links) })
            }
            card.addView(ops, LinearLayout.LayoutParams(-1, -2))
        }
        // 分段多的时候一段段点太烦，末尾再给一个整条复制
        if (paras.size > 1) {
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                addView(iconOp("doc", "复制整条对话（全文）") { copyToClip(text, "全文") })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        }
        // AI 正文要占满整行（-1）才能正常折行、和组卡左对齐；
        // 用户气泡按内容宽（-2）贴右边。
        chatList.addView(card, LinearLayout.LayoutParams(if (fromUser) -2 else -1, -2).apply {
            gravity = if (fromUser) Gravity.END else Gravity.START
            topMargin = dp(5)
            bottomMargin = dp(5)
            if (fromUser) leftMargin = dp(46)
        })
        // 自己发的消息永远贴底；AI 的消息在用户没主动上滑时，也一路贴着底部向下长。
        if (fromUser) { stickBottom = true; userPinned = false }
        scrollChatToBottom(fromUser || !userPinned)
    }

    /**
     * 段落末尾的小操作：**手绘风图标**（用户要求不要文字按钮）。
     * 触摸目标 ≥ 36dp；长按给一句人话说明，避免纯图标看不懂。
     */
    /**
     * 用户发的图：在气泡里给缩略图。
     * 以前只在下面挂一行「[附件] xx.jpg」文字，用户反馈"看不到图，很不好"。
     * 点一下看大图、长按存到本地 —— 和素材卡一套手感。
     */
    private fun bubbleThumbRow(card: LinearLayout, thumbs: List<ByteArray>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val side = dp(96)
        for ((i, raw) in thumbs.withIndex()) {
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundCard(this@MainActivity, pal.cardAlt, pal.border, 10)
                clipToOutline = true
                contentDescription = "我发的图 ${i + 1}"
                val bmp = runCatching { decodeSmallBytes(raw, side) }.getOrNull()
                if (bmp != null) {
                    setImageBitmap(bmp)
                } else {
                    setImageDrawable(LineIcon("image", pal.faint, dp(2).toFloat()))
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }
                setOnClickListener { showBytesFull(raw) }
                setOnLongClickListener { saveBytesToLocal(raw); true }
            }
            row.addView(iv, LinearLayout.LayoutParams(side, side).apply { rightMargin = dp(6) })
        }
        val hs = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row, ViewGroup.LayoutParams(-2, -2))
        }
        card.addView(hs, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
    }

    /** 按目标边长采样解码「内存里的图」（对话里的图可能好几 MB，不能整张读进内存） */
    private fun decodeSmallBytes(raw: ByteArray, target: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= target && o.outHeight / (s * 2) >= target) s *= 2
        return BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = s })
    }

    /** 点缩略图看大图（全屏，点一下关掉，长按存本地） */
    private fun showBytesFull(raw: ByteArray) {
        val bmp = runCatching { BitmapFactory.decodeByteArray(raw, 0, raw.size) }.getOrNull()
        if (bmp == null) {
            toast("这张图打不开了")
            return
        }
        val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { d.dismiss() }
            setOnLongClickListener { saveBytesToLocal(raw); true }
        }
        d.setContentView(iv)
        d.show()
    }

    /** 存「我发出去的那张图」：先落一个临时文件，再复用文件那条 MediaStore 通路 */
    private fun saveBytesToLocal(raw: ByteArray) {
        val f = File(cacheDir, "sent_${System.currentTimeMillis()}.jpg")
        if (!runCatching { f.writeBytes(raw) }.isSuccess) {
            toast("保存失败：临时文件写不进去")
            return
        }
        saveToLocal(f)
    }

    private fun iconOp(kind: String, desc: String, onTap: () -> Unit): ImageView = ImageView(this).apply {
        setImageDrawable(LineIcon(kind, pal.sub, dp(2).toFloat()))
        setPadding(dp(8), dp(8), dp(8), dp(8))
        minimumWidth = dp(36)
        minimumHeight = dp(36)
        contentDescription = desc
        background = pressable(roundCard(this@MainActivity, pal.cardAlt, pal.border, 10), 0x14000000)
        setOnClickListener { onTap() }
        setOnLongClickListener { toast(desc); true }
    }

    private fun copyToClip(v: String, what: String = "") {
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("hexora", v))
            toast(if (what.isBlank()) "已复制" else "已复制$what")
        }.onFailure { toast("复制失败：${it.message}") }
    }

    /** 一段里有多个链接时，让用户挑一个复制 */
    private fun pickLink(links: List<String>) {
        HxDialog.Builder(themed(), pal)
            .setTitle("选择要复制的链接")
            .setItems(links.map { if (it.length > 64) it.take(64) + "…" else it }.toTypedArray()) { i ->
                copyToClip(links[i])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== Maker 授权（AI 直接把链接递给用户，不藏设置页） ====================

    private var authBusy = false
    private var authCardTv: TextView? = null
    private var authListener: ((String, String?) -> Unit)? = null

    /**
     * Maker 报未授权时的自动处理：取授权链接 → 贴进对话 → 顺手打开浏览器。
     *
     * 为什么自动：用户明确要求「我要生图就自动把链接给我」，不许藏在设置页里翻。
     */
    private fun autoMakerAuth() {
        if (authBusy) return
        if (MakerCli.hasPat(this)) {
            addSystemLine("Maker 已授权，继续生成就行。")
            return
        }
        authBusy = true
        ensureAuthListener()
        addSystemLine("需要先授权 TapTap Maker，正在取授权链接…")
        Thread {
            val u = MakerAuth.start(this)
            main.post {
                authBusy = false
                if (u == null) {
                    addSystemLine("没拿到授权链接（" + MakerAuth.statusText(this) + "）。再让我生成一次我就重试。")
                } else {
                    addAuthCard(u)
                    openExternal(u)
                }
            }
        }.start()
    }

    /** 授权结果回来后更新对话里的卡片 */
    private fun ensureAuthListener() {
        if (authListener != null) return
        val l: (String, String?) -> Unit = { st, _ ->
            main.post {
                when (st) {
                    "done" -> {
                        authCardTv?.text = "授权成功，可以生成素材了"
                        authCardTv = null
                        authBusy = false
                        addSystemLine("TapTap Maker 授权成功。现在直接说「生一张图」就行。")
                        toast("Maker 授权成功")
                    }
                    "failed" -> {
                        authCardTv?.text = "没成功：" + MakerAuth.lastError.replace("\n", " ").take(120)
                        authBusy = false
                    }
                }
            }
        }
        authListener = l
        MakerAuth.addListener(l)
    }

    /**
     * 授权卡：链接蓝字可点 +「打开授权页 / 复制链接」两个按钮 + 状态行。
     * 全程留在对话里，用户不用去设置页。
     */
    private fun addAuthCard(url: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(10), dp(13), dp(10))
            background = roundCard(this@MainActivity, pal.cardAlt, pal.border, 14)
        }
        card.addView(TextView(this).apply {
            text = "需要授权 TapTap Maker：点下面链接 → 登录 → 点「创建 token」"
            textSize = 13f
            setTextColor(pal.aiText)
        })
        card.addView(TextView(this).apply {
            val sp = android.text.SpannableString(url)
            sp.setSpan(object : android.text.style.ClickableSpan() {
                override fun onClick(w: View) { openExternal(url) }

                override fun updateDrawState(ds: android.text.TextPaint) {
                    ds.color = LINK_BLUE
                    ds.isUnderlineText = true
                }
            }, 0, url.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = sp
            textSize = 12f
            setPadding(0, dp(4), 0, dp(6))
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
        })
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(iconOp("open", "用浏览器打开授权页") { openExternal(url) })
            addView(iconOp("link", "复制授权链接") { copyToClip(url, "授权链接") })
        })
        val st = TextView(this).apply {
            text = "点完「创建 token」这里会自动变成已授权（流程最长等 10 分钟）"
            textSize = 11.5f
            setTextColor(pal.sub)
        }
        card.addView(st)
        chatList.addView(card, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.START
            topMargin = dp(5)
            bottomMargin = dp(5)
            rightMargin = dp(30)
        })
        authCardTv = st
        scrollChatToBottom()
    }

    /** 段落里的链接：染蓝 + 可点（跳外部浏览器）；没链接才保持可长按选中 */
    private fun linkifySeg(tv: TextView, seg: String) {
        val hits = LINK_REGEX.findAll(seg).toList()
        if (hits.isEmpty()) {
            tv.text = seg
            tv.setTextIsSelectable(true)
            return
        }
        val sp = android.text.SpannableString(seg)
        for (m in hits) {
            val u = m.value
            sp.setSpan(object : android.text.style.ClickableSpan() {
                override fun onClick(w: View) { openExternal(u) }

                override fun updateDrawState(ds: android.text.TextPaint) {
                    ds.color = LINK_BLUE
                    ds.isUnderlineText = false
                }
            }, m.range.first, m.range.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tv.text = sp
        // LinkMovementMethod 与「可选中」冲突：有链接就优先保证点得开
        tv.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        tv.highlightColor = 0x00000000
        tv.setTextIsSelectable(false)
    }

    /** 跳外部浏览器打开 */
    private fun openExternal(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { toast("打不开链接：${it.message}") }
    }

    // ==================== 素材卡（Maker 生成物直接出现在对话里） ====================

    /** 对话里的素材卡：图片给缩略图、音频给播放条；点一下预览 / 试听，长按存到本地 */
    private fun addAssetCard(path: String) {
        val src = File(path)
        if (!src.isFile) return
        // 同一个文件只出一张卡：Maker 有时在结果里把同一张图报好几遍，
        // 工具也可能连着被调两次 —— 不挡的话对话里会排满同一张卡。
        // （去重以前挂在 autoCardMedia 上，那条路已经删了，挪到这儿来。）
        if (!autoCarded.add(src.absolutePath)) return
        // 并入工作区：Maker 把生成物 materialize 在「<项目>/assets/…」下，
        // 而工作区（素材）读的是「<项目>/_uploads/media」—— 用户要的是"生成完就在工作区里"。
        // 这里复制一份进去（同名同大小就跳过），卡片指向工作区那份，
        // 于是：工作区看得到、游戏代码能用 _uploads/media/x.png 相对路径引用、导出打包也带上。
        val f = runCatching { ensureInWorkspace(src) }.getOrDefault(src)
        val ext = f.extension.lowercase()
        val isImg = ext in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundCard(this@MainActivity, pal.cardAlt, pal.border, 14)
        }
        if (isImg) {
            card.addView(android.widget.ImageView(this).apply {
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                setImageBitmap(decodeThumb(f, 900))
            }, LinearLayout.LayoutParams(-1, -2))
        } else {
            // 尺寸必须给死：LineIcon 没实现 intrinsic size，WRAP_CONTENT 会缩成 0
            card.addView(iconView(this, pal, "music", 34, pal.accent),
                LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(8)
                })
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
            addView(iconView(this@MainActivity, pal, if (isImg) "image" else "music", 12, pal.sub),
                LinearLayout.LayoutParams(dp(13), dp(13)).apply { rightMargin = dp(5) })
            addView(TextView(this@MainActivity).apply {
                text = "${f.name} · ${f.length() / 1024} KB"
                textSize = 11.5f
                setTextColor(pal.sub)
            })
        })
        card.addView(TextView(this).apply {
            text = if (isImg) "点一下看大图 · 长按保存到本地" else "点一下试听 · 长按保存到本地"
            textSize = 11f
            setTextColor(pal.faint)
            setPadding(0, dp(2), 0, 0)
        })
        card.setOnClickListener { if (isImg) showImageFull(f) else playAudio(f) }
        // 长按 = 存到本地相册 / 音乐 / 影视（生成的素材直接可以带走）
        card.setOnLongClickListener { saveToLocal(f); true }
        chatList.addView(card, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.START
            topMargin = dp(6)
            bottomMargin = dp(4)
            rightMargin = dp(30)
        })
        scrollChatToBottom()
    }

    /**
     * 把生成物并进当前项目的工作区（<项目>/_uploads/media）。
     *
     * Maker 把产物 materialize 在「<项目>/assets/…」，而工作区素材读的是
     * 「<项目>/_uploads/media」—— 用户要的是「生成完就在工作区里」，
     * 所以这里复制一份进去（同名同大小视为同一份，不重复复制），
     * 卡片也指向工作区那份，于是：工作区看得到、能长按存本地、
     * 游戏代码用 `_uploads/media/x.png` 相对路径同样引用得到、导出打包也带上。
     */
    private fun ensureInWorkspace(src: File): File {
        val proj = projDir()
        // 已经在工作区里：不用动
        if (src.absolutePath.startsWith(File(proj, "_uploads").absolutePath)) return src
        val ws = File(proj, "_uploads/media")
        if (!ws.exists() && !ws.mkdirs()) return src
        val dst = File(ws, src.name)
        if (dst.isFile && dst.length() == src.length()) return dst
        src.copyTo(dst, overwrite = true)
        return dst
    }

    /** 缩略图：按目标边长采样，别把几十 MB 的原图整张读进内存 */
    private fun decodeThumb(f: File, target: Int): android.graphics.Bitmap? = runCatching {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)
        var s = 1
        while (o.outWidth / (s * 2) >= target || o.outHeight / (s * 2) >= target) s *= 2
        android.graphics.BitmapFactory.decodeFile(
            f.absolutePath,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = s }
        )
    }.getOrNull()

    /** 点开大图：全屏黑底，点一下关掉 */
    private fun showImageFull(f: File) {
        val bm = decodeThumb(f, 2400)
        if (bm == null) {
            toast("图片读取失败")
            return
        }
        val iv = android.widget.ImageView(this).apply {
            setImageBitmap(bm)
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF000000.toInt())
        }
        val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        d.setContentView(iv, android.view.ViewGroup.LayoutParams(-1, -1))
        iv.setOnClickListener { d.dismiss() }
        d.show()
    }

    /** 试听：点一下放，再点一下停 */
    private fun playAudio(f: File) {
        runCatching {
            val cur = audioPlayer
            if (cur != null && cur.isPlaying) {
                cur.stop()
                cur.release()
                audioPlayer = null
                toast("已停止")
                return
            }
            cur?.release()
            val mp = android.media.MediaPlayer()
            mp.setDataSource(f.absolutePath)
            mp.prepare()
            mp.start()
            audioPlayer = mp
            mp.setOnCompletionListener {
                runCatching { it.release() }
                if (audioPlayer === it) audioPlayer = null
            }
            toast("正在播放：${f.name}")
        }.onFailure { toast("播放失败：${it.message}") }
    }

    /** 已经自动出过卡的媒体文件（同一文件只出一张，别刷屏） */
    private val autoCarded = HashSet<String>()

    // 【已移除】mediaPathsIn / autoCardMedia：以前拿正则扫**任何**工具输出的文本，
    // 见到 .mp3/.mp4 之类的后缀、而且磁盘上真有这个文件，就往对话里插一张播放卡。
    //
    // 结果就是用户说的「读项目老是跑这个音频出来」——AI 只要 game_read 一个列出文件的
    // 目录、或者 code_search 命中了一行带路径的代码，那张卡就冒出来了，
    // 跟「AI 刚生成了这段音频」完全是两回事。
    //
    // 现在只认 AgentRunner 明确发来的 ASSET: 事件（只会由 maker_ / mcp_ 这类
    // 真在产出素材的工具触发），不再靠猜文本。

    private fun addSystemLine(text: String) {
        val tv = TextView(this).apply {
            setText(text)
            textSize = 11.5f
            setTextColor(pal.faint)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        chatList.addView(tv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(1) })
        scrollChatToBottom()
    }

    // ==================== 工具行（对齐 Maker 的「已工作」组内行） ====================

    private val TOOL_OK = 0xFF3BA55D.toInt()
    private val TOOL_FAIL = 0xFFD9534F.toInt()
    /** 给颜色套透明度（对齐 Operit 的 color.copy(alpha=…)） */
    private fun tintA(c: Int, a: Float): Int =
        android.graphics.Color.argb((a * 255).toInt(), android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c))

    /** 从事件载荷里拆出 head（摘要）和 full（详情全文）——见 AgentRunner 的 \u0000 分隔 */
    private fun toolPayload(rest: String): Pair<String, String> {
        val head = rest.substringBefore("\u0000").trim()
        val full = rest.substringAfter("\u0000", head).trim()
        return head to (if (full.isBlank()) head else full)
    }

    /**
     * 工具开始：在**组体里**加一行「运行中」。
     *
     * 关键在「组体里」——组体默认是收起的（View.GONE），
     * 所以这一行加进去不会改变对话区任何**可见**高度，也不会牵动滚动位置。
     * 老版本是直接往 chatList 里插行，插一行滚一次，一轮几十次工具调用就是几十次抖动。
     */
    /**
     * 让「已工作」卡回到对话**最末尾**。
     *
     * 为什么需要：模型交付结论之后，闸门可能把它顶回去继续干活（截图验证 / 修报错）。
     * 那时结论气泡已经挂在卡**下面**了，而新工作行还往那张旧卡里塞 ——
     * 于是工作流程出现在结论**上方**，用户往下翻根本看不到，
     * 只能看到底部状态行在动（用户反馈的原话：「后面的工作流程就看不到了，
     * 只有底部运行那里看得见」）。
     *
     * 做法：发现卡不是最后一个子视图，就把它移到末尾 —— 卡会跑到最新气泡下方，
     * 工作行继续跟在后面。代价是卡上方的空白被重新撑开，但那正是「实时工作日志」该在的位置。
     */
    private fun ensureWorkCardAtBottom() {
        if (!::chatList.isInitialized) return
        val card = runCardView ?: return
        if (chatList.childCount == 0) return
        if (chatList.getChildAt(chatList.childCount - 1) === card) return
        runCatching {
            chatList.removeView(card)
            chatList.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(4)
                bottomMargin = dp(6)
            })
        }
    }

    private fun addToolCardRunning(name: String, target: String = "") {
        val nm = if (name.isBlank()) "工具" else name
        val rows = toolWrap ?: return
        // 工作行必须加在对话末尾的卡里 —— 卡被顶到上面去了就把它挪回来
        ensureWorkCardAtBottom()

        // 运行中：转圈。用旋转的 ImageView 而不是逐帧换字符 ——
        // 旋转是渲染层的变换，不触发 requestLayout，转得再快也不会把对话布局带着一起动。
        val spin = ImageView(this).apply {
            setImageDrawable(LineIcon("refresh", pal.accent, dp(11).toFloat()))
        }
        // 状态槽：转圈 → 线稿对勾 / 叉。用 ImageView 而不是文字，
        // 这样「✓ / ✕」也是同一支笔画的线稿，跟全 App 的图标口径一致。
        val mark = ImageView(this).apply {
            setImageDrawable(LineIcon("check", TOOL_OK, 2.4f))
            visibility = View.GONE
        }
        // 固定尺寸的槽位：转圈和 ✓/✕ 互相切换时，行的宽度不会被撑得跳一下
        val slot = FrameLayout(this).apply {
            addView(spin, FrameLayout.LayoutParams(-1, -1))
            addView(mark, FrameLayout.LayoutParams(-1, -1))
        }

        // 有目标文件就写成「修改 main.js」，没有就退回工具名（如 console_logs）
        val verb = toolVerb(nm)
        val label = if (target.isNotBlank()) "$verb $target" else nm

        val tag = TextView(this).apply {
            text = label
            textSize = 11f
            typeface = MONO
            setTextColor(pal.text)
            maxLines = 1
        }
        val summary = TextView(this).apply {
            text = "运行中…"
            textSize = 11f
            setTextColor(pal.faint)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), 0, 0, 0)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
            addView(slot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(8) })
            addView(tag, LinearLayout.LayoutParams(-2, -2))
            addView(summary, LinearLayout.LayoutParams(0, -2, 1f))
        }
        rows.addView(row, LinearLayout.LayoutParams(-1, -2))

        // 上一行的转圈先停掉：同时只允许一个在转（一轮里也只会有一个工具在跑）
        pendingSpin?.cancel()
        pendingSpin = ObjectAnimator.ofFloat(spin, View.ROTATION, 0f, 360f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }

        pendingRow = row
        pendingStatus = mark
        pendingSummary = summary
        pendingSpinView = spin
        pendingMarkView = mark

        toolTotal++
        toolVerbCount[verb] = (toolVerbCount[verb] ?: 0) + 1
        updateWorkStat("")
        // 组体是展开的，新行会把它撑高 —— 跟着往下滚，让「正在改谁」始终在视野里。
        // （用户自己上滑了就不会被拽走，scrollChatToBottom 内部认这个）
        scrollChatToBottom()
    }

    /**
     * 工具结束：把刚才那一行**原地定型**（✓/✕ + 摘要），不新增视图。
     * 点这一行看全文详情（可复制）。
     */
    private fun updateToolCard(ok: Boolean, name: String, head: String, full: String) {
        val t = if (name.isBlank()) "工具" else name
        // 兜底：万一 TOOL 事件先到（没配对的 TOOLRUN），补一行出来，别让结果凭空消失
        if (pendingStatus == null) addToolCardRunning(t)

        val summary = when {
            head.isNotBlank() -> head
            ok -> "执行成功"
            else -> "执行失败"
        }.replace('\n', ' ').trim().take(200)

        val st = pendingStatus
        val sm = pendingSummary
        val row = pendingRow
        val spin = pendingSpinView
        val mark = pendingMarkView
        pendingStatus = null
        pendingSummary = null
        pendingRow = null
        pendingSpinView = null
        pendingMarkView = null

        // 转圈收工：动画停掉，槽位换成 ✓ / ✕。
        // 两个视图同尺寸叠在 FrameLayout 里，所以这一换不会让这一行的宽度跳一下。
        pendingSpin?.cancel()
        pendingSpin = null
        spin?.let { if (it.visibility != View.GONE) it.visibility = View.GONE }
        mark?.let { if (it.visibility != View.VISIBLE) it.visibility = View.VISIBLE }

        st?.let {
            it.setImageDrawable(LineIcon(if (ok) "check" else "close", if (ok) TOOL_OK else TOOL_FAIL, 2.4f))
        }
        sm?.let {
            setTextIf(it, summary)
            it.setTextColor(if (ok) pal.sub else pal.errText)
        }
        row?.let {
            it.isClickable = true
            it.setOnClickListener { showTextDetail(t, full.ifBlank { summary }, ok) }
        }
        updateWorkStat("")
    }

    /**
     * 停掉「还在转的那个圈」。
     *
     * 一轮被中断时（用户按停止 / 发新消息打断），最后那个工具永远不会有结果回来，
     * 不主动收掉的话它会一直转下去 —— 看着像还在干活，其实早停了。
     */
    private fun stopPendingSpin() {
        pendingSpin?.cancel()
        pendingSpin = null
        pendingSpinView?.let { if (it.visibility != View.GONE) it.visibility = View.GONE }
        pendingMarkView?.let {
            if (it.visibility != View.VISIBLE) it.visibility = View.VISIBLE
            // 标记槽现在是 ImageView（线稿），用一条短横表示「已停止」
            it.setImageDrawable(LineIcon("stop", pal.faint, 2.4f))
        }
        pendingStatus = null
        pendingSpinView = null
        pendingMarkView = null
    }

    /**
     * AI 截到的画面 —— 在「已工作」组里放一张缩略图。
     *
     * 为什么放在组里而不是单开一张卡：截图是**过程**的一部分（它就是在验证画面），
     * 跟着「修改 x.js / 读取 y」那些行排在一起最自然，也不占正文的地方。
     * 点一下看大图，长按存到相册 —— 和用户自己发的图一套手感。
     */
    private fun addShotThumb(path: String) {
        val f = File(path)
        if (!f.isFile) return
        val rows = toolWrap
        if (rows == null) {
            addAssetCard(path)
            return
        }
        val side = dp(108)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
            gravity = Gravity.CENTER_VERTICAL
        }
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundCard(this@MainActivity, pal.cardAlt, pal.border, 10)
            clipToOutline = true
            contentDescription = "AI 截到的画面"
            val bmp = runCatching { decodeThumb(f, side) }.getOrNull()
            if (bmp != null) setImageBitmap(bmp)
            else setImageDrawable(LineIcon("image", pal.faint, 1.8f))
            setOnClickListener { showImageFull(f) }
            setOnLongClickListener { saveToLocal(f); true }
        }
        row.addView(iv, LinearLayout.LayoutParams(side, side * 2 / 3).apply { rightMargin = dp(10) })
        row.addView(TextView(this).apply {
            setText("AI 截到的画面\n点一下看大图 · 长按保存")
            textSize = 11f
            setTextColor(pal.faint)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        rows.addView(row, LinearLayout.LayoutParams(-1, -2))
        scrollChatToBottom()
    }

    /**
     * 过程自述行：AI 干活途中说的话（「我先看一下这个文件」这类）。
     *
     * 缩进折进「已工作」组里当一行 ✎，**不单独占一张气泡** ——
     * 一句一张白卡正是用户说的「还是卡片那种」。过程归过程，结论归结论。
     */
    // ⚠️ 参数不能叫 text：在 `TextView(this).apply { text = ... }` 里，简单名 `text`
    // 会先命中**函数参数**（局部作用域优先于隐式接收者），而参数是 val ——
    // 编译器直接报 Val cannot be reassigned。所以这里用 setText(...) 且参数改名。
    private fun addNoteRow(raw: String) {
        val full = raw.trim()
        if (full.isEmpty()) return
        val rows = toolWrap
        if (rows == null) {
            // 组还没建起来（理论上不该发生）：退回气泡，至少别把内容丢了
            addBubble(full, false)
            return
        }
        // 同上：过程自述也是工作流的一部分，必须加在末尾的卡里
        ensureWorkCardAtBottom()
        val oneLine = full.replace('\n', ' ')
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
            isClickable = true
        }
        row.addView(iconView(this, pal, "edit", 12, pal.accent),
            LinearLayout.LayoutParams(dp(13), dp(13)).apply { rightMargin = dp(7) })
        row.addView(TextView(this).apply {
            setText(oneLine)
            textSize = 11.5f
            setTextColor(pal.text)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        // 点一下看全文（复用工具详情的弹窗，能选中、能复制）
        // withStatus = false：过程自述不是「执行」出来的东西，
        // 拼上「执行成功」会变成「过程自述执行成功」这种读不通的话
        row.setOnClickListener { showTextDetail("过程自述", full, withStatus = false) }
        rows.addView(row, LinearLayout.LayoutParams(-1, -2))
        scrollChatToBottom()
    }

    /**
     * 全文详情弹窗：可滚动 + 可选中 + 一键复制。
     *
     * @param withStatus true = 标题后面拼「执行成功 / 执行失败」，并在前面带 ✓/✕。
     *   工具结果要它；**过程自述那类不是「执行」出来的内容不要** ——
     *   否则标题会变成「过程自述执行成功」这种读不通的话（用户看到的正是这个）。
     */
    private fun showTextDetail(
        title: String,
        content: String,
        ok: Boolean = true,
        withStatus: Boolean = true
    ) {
        val t = if (title.isBlank()) "工具" else title
        // 1:1 对齐 Operit ToolResultDetailDialog：标题行(状态图标+名称+复制) / 分隔线 / 圆角可滚内容区
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // 状态图标只在「工具结果」这种真被执行过的东西上出现
        if (withStatus) {
            head.addView(iconView(this, pal, if (ok) "check" else "close", 17, if (ok) TOOL_OK else TOOL_FAIL),
                LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(10) })
        } else {
            // 过程自述用一个中性标记，别让它看着像「某个工具的结果」
            head.addView(iconView(this, pal, "edit", 16, pal.accent),
                LinearLayout.LayoutParams(dp(17), dp(17)).apply { rightMargin = dp(10) })
        }
        head.addView(TextView(this).apply {
            setText(
                if (!withStatus) t
                else t + if (ok) "执行成功" else "执行失败"
            )
            textSize = 15f
            setTextColor(pal.text)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(ImageView(this).apply {
            setImageDrawable(LineIcon("copy", pal.accent, dp(16).toFloat()))
            setOnClickListener { copyToClip(content, "工具结果") }
        }, LinearLayout.LayoutParams(dp(20), dp(20)))
        root.addView(head)
        root.addView(android.view.View(this).apply { setBackgroundColor(pal.border) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(12) })
        val tv = TextView(this).apply {
            setText(content)
            textSize = 12.5f
            setTextColor(pal.text)
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val sv = ScrollView(this).apply {
            background = roundCard(this@MainActivity, pal.cardAlt, pal.cardAlt, 8, 0)
            addView(tv)
        }
        // 内容短就贴着内容收起来。以前固定 300dp，短内容底下留一大片空白，
        // 看着像「没加载出来」（用户截的图就是这样）。
        root.addView(sv, LinearLayout.LayoutParams(-1, if (content.length > 700) dp(320) else -2))
        HxDialog.Builder(themed(), pal)
            .setView(root)
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 错误卡片：可读的字号 + 明确的原因 + 一键再试，不再是看不清的灰字 */
    private fun addErrorCard(msg: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
            background = roundCard(this@MainActivity, pal.errBg, pal.errBg, 14, 0)
        }
        card.addView(TextView(this).apply {
            setText("请求失败")
            textSize = 13f
            setTextColor(pal.errText)
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(TextView(this).apply {
            setText(msg)
            textSize = 13f
            setTextColor(pal.errText)
            setPadding(0, dp(5), 0, 0)
            setTextIsSelectable(true)
        })
        // 「接着做」= **从断点继续**，不是把那句话重发一遍。
        // 老实现调的是 send()，而 send() 在输入框为空时会回退到 lastUserText ——
        // 同一句用户消息被塞进历史第二次、轮次从 1 重新数，
        // 用户看到的就是「跑了 11 轮，点一下重试全白干」。那 11 轮的结果其实都还在历史里。
        card.addView(ghostBtnOf(this, pal, "接着做（不重来）").apply {
            setOnClickListener { send(resume = true) }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })

        chatList.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(6)
            bottomMargin = dp(6)
        })
        scrollChatToBottom()
    }

    /** 同一帧内多次滚动请求合并：事件密集时不再反复 fullScroll（卡顿主因之一） */
    /**
     * 滚到底。
     *
     * 原来只 post 一次 fullScroll —— 但新加进去的 View 这时**还没测量**，
     * fullScroll 算出来的是「旧内容的底部」，所以看起来老是往上弹、不在底部。
     * 现在：先等 chatList 布局，滚一次，再补一次（字体/图片异步测量完再对一次）。
     *
     * @param force true = 不管用户有没有往上翻，都强制贴底（发消息、切会话用）
     */
    private fun scrollChatToBottom(force: Boolean = false) {
        if (!::chatList.isInitialized || !::chatScroll.isInitialized) return
        if (force) userPinned = false
        if (!force && (userPinned || !stickBottom)) return
        if (scrollPending) return
        scrollPending = true
        chatList.post {
            scrollPending = false
            scrollChatToBottomNow()
            // 新加的 View / 长文本这一帧可能还没测量完，下一帧再对一次；
            // 但只在「用户没主动上滑」时补，避免和用户的手打架。
            chatList.post {
                if (!userPinned && stickBottom) scrollChatToBottomNow()
            }
        }
    }

    /**
     * 真正滚到底。
     *
     * 原来用 fullScroll(FOCUS_DOWN)：它会改焦点、触发整棵视图树重新测量，
     * 而流式输出时每秒要被调好几次 —— 表现出来就是「屏幕一闪一闪」。
     * 现在直接算目标偏移 scrollTo，稳定、不闪，也不会被焦点策略带偏。
     */
    private fun scrollChatToBottomNow() {
        val target = (chatList.height - chatScroll.height).coerceAtLeast(0)
        if (chatScroll.scrollY != target) chatScroll.scrollTo(0, target)
        updateScrollBtn()
    }

    /** 离底 80dp 内算「贴底」；据此决定新消息是否自动跟随、以及悬浮按钮是否出现 */
    private fun updateScrollBtn() {
        if (!::chatList.isInitialized || !::chatScroll.isInitialized) return
        val gap = chatList.height - chatScroll.height - chatScroll.scrollY
        stickBottom = gap <= dp(80)
        // 真正回到底部（8dp 内）才解除「用户钉住」——避免只上滑一点点就被重新拽下去。
        if (gap <= dp(8)) userPinned = false
        val sb = scrollBtn
        val want = if (stickBottom) View.GONE else View.VISIBLE
        // 只在真的要变的时候才改可见性：每次滚动都设一遍会触发布局，
        // 表现出来就是右下角按钮和内容一起「闪」。
        if (sb != null && sb.visibility != want) sb.visibility = want
    }

    private fun updateAttachInfo() {
        // 待发送区：一行一个附件（小图标 + 文件名 + ×）。点条目 = 加入对话，× = 移除
        if (::pendingBox.isInitialized) {
            pendingBox.removeAllViews()
            for (a in attached) {
                val row = attachRowOf(this, pal, a.kind, a.name, tail = "×", onTail = {
                    attached.remove(a)
                    shotBytes.remove(a.rel)
                    updateAttachInfo()
                })
                row.setOnClickListener { queueAttach(a) }
                pendingBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            }
        }
        // 已加入本轮对话的附件 / 截图：也要能一条条删掉。
        // 用户反馈：从工作区加进来之后就只剩「发出去」这一条路，没有 ×，不合理。
        if (::pendingBox.isInitialized) {
            for (a in java.util.ArrayList(queued)) {
                val row = attachRowOf(this, pal, a.kind, a.name, tail = "×", onTail = {
                    queued.remove(a)
                    updateAttachInfo()
                })
                row.setOnClickListener {
                    queued.remove(a)
                    updateAttachInfo()
                }
                pendingBox.addView(
                    row,
                    LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            for (i in pendingShots.indices.reversed()) {
                val row = attachRowOf(this, pal, "image", "截图 ${i + 1}", tail = "×", onTail = {
                    if (i < pendingShots.size) pendingShots.removeAt(i)
                    updateAttachInfo()
                })
                row.setOnClickListener {
                    if (i < pendingShots.size) pendingShots.removeAt(i)
                    updateAttachInfo()
                }
                pendingBox.addView(
                    row,
                    LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        }
        if (attached.isEmpty() && queued.isEmpty() && pendingShots.isEmpty()) {
            attachInfo.visibility = View.GONE
            return
        }
        attachInfo.visibility = View.VISIBLE
        attachInfo.text = buildString {
            if (attached.isNotEmpty()) append("待发送 ${attached.size} 项：直接发送会一起带上，× 移除")
            if (queued.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append("已加入对话 ${queued.size} 项（点在 × 上可移除）")
            }
            if (pendingShots.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append("图片 ${pendingShots.size} 张")
            }
        }
        attachInfo.setOnClickListener {
            attached.clear()
            queued.clear()
            shotBytes.clear()
            pendingShots.clear()
            updateAttachInfo()
        }
    }

    // ==================== 发送 ====================

    /**
     * 发消息（或续跑）。
     *
     * 上一轮还在跑的时候怎么办，两种都留着，但要用户自己说：
     *   · 点「发送」      → **排队**。等这一轮跑完，队列自动往下发（一次一条）。
     *   · 长按「发送」    → **打断**，这条立刻发。
     * 队列面板上每条还带 ▶ 立即发（插队，同样打断）、✎ 编辑、✕ 删除。
     *
     * @param resume true = 接着上一轮继续，用在错误卡片的「接着做」上。
     *   不发新消息、不重置轮次、也不碰输入框，直接拿当前会话的历史接着往下跑。
     * @param interrupt 见上：true 才打断当前轮。
     */
    private fun send(resume: Boolean = false, interrupt: Boolean = false) {
        // 输入框空着时**不再**回退到 lastUserText。
        // 那条回退是「再试一次」事故的元凶：它会悄悄把上一条用户消息再发一遍，
        // 而轮次从 1 重新数 —— 用户看到的是「跑了 11 轮，点一下重试全白干」。
        // 实际上那 11 轮的工具结果都还在历史里，根本不该重来。
        val text0 = if (resume) "" else inputEt.text.toString().trim()
        if (running && !runAlive) {
            // 自愈：线程其实早结束了，running 却还挂着 —— 别让用户卡在「排队」里
            running = false
        }
        if (running) {
            if (text0.isNotBlank()) {
                msgQueue.addLast(text0)
                inputEt.setText("")
                renderQueue()
                if (interrupt) {
                    // 长按「发送」= 明确要求别等了，现在就发 —— 打断当前轮
                    toast("已打断上一轮，这条马上发出")
                    doStop("发送新消息")
                } else {
                    // 普通点「发送」= **排队**，等这一轮跑完自动发下一条。
                    // 老逻辑正好相反：一点就打断，于是刚进队的那条立刻又被取走，
                    // 队列面板上的「编辑 / 立即发 / 删除」永远来不及用 ——
                    // 用户看得见那排按钮却摸不着，以为它们坏了。
                    // 现在队列是真的会停在那儿的，想插队就用行尾的 ▶。
                    toast("已排在队列第 ${msgQueue.size} 条 · 长按「发送」可立刻打断")
                }
            }
            return
        }
        // 续跑：先确认真的有进度可续。第一步就挂的话 runSteps 也是 1
        // （STEP:1 在发请求之前就报出来了），所以这里只挡「根本没跑过」那种情况。
        if (resume && runSteps <= 0) {
            toast("没有可续跑的进度，重新说一句吧")
            return
        }

        val text = text0
        if (!resume && text.isEmpty() && pendingShots.isEmpty() &&
            queued.isEmpty() && attached.isEmpty()
        ) {
            toast("先写点什么再发吧")
            return
        }

        val cfg = cfgStore.active()
        val local = cfg.baseUrl.contains("127.0.0.1") || cfg.baseUrl.contains("localhost")
        if (cfg.apiKey.isBlank() && !local) {
            showSettings()
            toast("先填 ${cfg.provider.label} 的 API Key")
            return
        }

        // 待发送区里的附件：不必再点一次「加入对话」。
        // 用户选完图/文件，直接打字发出去，直觉上就是「这些一起发出去」；
        // 老逻辑只弹一句「点一下条目就能带上」，附件留在原地 —— 用户会以为图丢了
        // （实测反馈：选了图发出去，对话里没有图，下面还挂着待发送）。
        // 现在：发送时自动全部并入本轮（图片进多模态、其余带路径 + 正文）。
        // 续跑时这一整段都跳过：不并附件、不动输入框、**不再插一条用户气泡**。
        // 用户可能在上一轮失败后又选了几个附件 —— 那些该留给下一条真正的消息，
        // 不该被这次重试顺手吞掉。
        val images: List<ByteArray>
        val docs: List<Attach>
        if (resume) {
            images = emptyList()
            docs = emptyList()
        } else {
            if (attached.isNotEmpty()) {
                for (a in attached.toList()) queueAttach(a, silent = true)
            }
            images = pendingShots.toList()
            docs = queued.toList()
            pendingShots.clear()
            queued.clear()
            updateAttachInfo()
            inputEt.setText("")
        }

        // 附件块：图片走多模态（上面的 images），其余把「路径 + 文件名 + 正文」一起给模型，
        // 这样既能 game_read 读原文件，也能只报文件名命中（Tools.fuzzyFind）
        val docBlock = if (resume) "" else docs.joinToString("") { d ->
            val body = when {
                d.text.isEmpty() ->
                    "\n（二进制内容，App 解析不出正文；可用 game_read path=${d.rel} 读原文件）"
                d.text.length <= 8000 -> "\n```\n${d.text}\n```"
                else ->
                    "\n（正文共 ${d.text.length} 字，太长不整段附上；用 game_read path=${d.rel} 读，" +
                        "或者只报文件名 ${d.name} 也能读到）"
            }
            "\n\n【已上传 ${d.kind}：${d.name}，路径 ${d.rel}】" +
                "\n（可直接 game_read(path=\"${d.rel}\")；只报文件名也能读到）" + body
        }

        if (!resume) {
            addBubble(
                (text.ifEmpty { if (images.isEmpty()) "（看附件）" else "（看这张图）" }) +
                    docs.joinToString("") { "\n[附件] ${it.name}" },
                true,
                images
            )
            if (images.isNotEmpty()) addSystemLine("（附带 ${images.size} 张图片）")
            if (docs.isNotEmpty()) addSystemLine("（附带 ${docs.size} 个文档）")
            maybeAutoTitle(text)
            persistSessions()
        }

        // 一句「帮我接广告」就自动切到广告技能，不用用户自己去翻设置
        val adWanted = listOf("广告", "激励视频", "发奖", "变现", "adunitid", "adkit", "rewarded")
            .any { text.toLowerCase().contains(it) }

        // ==================== MCP 工具准入（默认关，按意图临时放行） ====================
        // 平时 AI 连这些工具的声明都看不到 —— 从源头杜绝「自动识别一圈」「顺手把游戏发布上线」。
        // 只有你这一轮明确表达了相关意图，才把工具给它；一旦涉及写接口，纪律要求它先问你。
        val mcpWanted = listOf(
            // 广告 / 变现
            "广告", "激励视频", "发奖", "变现", "adunitid", "adkit", "rewarded", "广告位",
            // 排行榜
            "排行榜", "榜单", "leaderboard", "排行",
            // TapTap 平台
            "taptap", "tap", "开发者", "应用信息", "appid", "app_id", "商店", "审核", "上架", "发布", "上传",
            // 素材生产（Maker 云能力）
            "生图", "画图", "出图", "生成图", "生成图片", "图片素材", "立绘", "图标", "贴图", "背景图", "美术",
            "音乐", "bgm", "配乐", "音效", "配音", "语音", "音频素材", "音色",
            "3d", "三维", "模型素材", "视频素材", "生成视频",
            // 制造开发流程
            "素材", "构建", "打包", "预览", "二维码", "测试白名单", "maker"
        ).any { text.lowercase().contains(it) } ||
            // 关键词表天生会漏：用户说「生成一张图」时，上面的「生图 / 生成图」一个都不命中，
            // 结果 AI 连生图工具的声明都看不到，只能跟你说「我没有生图能力」。
            // 所以再补一条「动词 + 可选量词 + 名词」的模式匹配兜底。
            Regex(
                "(生成|画|做|出|来|弄|搞)[^。！？.!?\\n]{0,8}" +
                    "(图|立绘|插画|图标|贴图|背景|美术|音乐|配乐|音效|配音|语音|视频|3d|三维|素材)"
            ).containsMatchIn(text.lowercase()) ||
            // AI 问你「要不要我现在上传 / 发布？」你回一句「可以」——这也得算同意。
            // 这种回复本身不含任何领域关键词，光靠词表永远放行不了写接口，又是一轮来回。
            shortOK(text)
        // 续跑时**绝不能**重算上面这两件事：
        //   · MCP 准入是看「这一轮用户说了什么」算的，而续跑没有新消息（text 是空的），
        //     重算必然得 false —— 等于把上一轮已经放行的写权限又锁回去，任务当场做不下去。
        //   · 技能也可能是上一轮按意图自动切过去的（比如自动切到 ads），
        //     重算会掉回通用技能，做法整个变样。
        val skill = if (resume) {
            SkillPresets.byId(lastRunSkillId ?: cfgStore.skillId)
        } else {
            EngineTools.mcpAllowed = mcpWanted
            if (mcpWanted) {
                addSystemLine("本轮已放行 TapTap / Maker 的写 / 发布类接口（上传、发布、改信息等）；它动手前仍会先跟你确认")
            } else if (EngineTools.mcp != null) {
                // 只读查询与素材生成是常驻的，不需要放行；这里只是日志：写类本轮还锁着
                android.util.Log.i("Hexora", "本轮未放行 MCP 写 / 发布类工具（无明确同意）")
            }
            val s = if (adWanted) SkillPresets.byId("ads") else SkillPresets.byId(cfgStore.skillId)
            if (adWanted && cfgStore.skillId != "ads") {
                addSystemLine("识别到广告需求：本轮自动使用「广告接入（TapTap 激励视频）」技能，按官方契约执行")
            }
            lastRunSkillId = s.id
            s
        }
        // 新一轮开始时把连接池清一次：上一轮可能已经过去很久（切后台 / 切网 / 隔了半天），
        // 池里那几条多半已经死了，留着只会让这一步白等一次连接超时。
        // 清掉之后**本轮之内**照常复用连接 —— 省下的握手时间就是从这里开始的。
        // 续跑不清：刚刚才失败的连接多半还活着，正好接着用。
        if (!resume) AiClient.evictConnections()
        val tools = EngineTools(this, gameRoot)
        runSeq++
        val mySeq = runSeq
        // 事件只认「当前这一轮」：被停止 / 被新消息打断的那一轮，不再往界面上写。
        // 否则会出现「已经打断了，画面还在往外吐字」的鬼现象。
        val r = AgentRunner(
            appCtx = this,
            cfg = cfg,
            skill = skill,
            tools = tools,
            visionFallback = cfgStore.visionFallback,
            shotDir = File(gameRoot, "_shots"),
            // 交付前强制截图自检：只在 H5 工程开。
            // Maker 工程跑在 GeckoView 里，game_shot 抓不到画面（抓到的只是对话页），
            // 强制截图会变成死循环 —— 那种工程走的是「构建 + 预览页」那条验证路。
            requireVisualCheck = projKind(currentGame) != "maker",
            // 工程目录：AI 截到的画面要落进这个项目的**工作区**（_uploads/media），
            // 这样对话里能显示、重启还在、游戏代码也能用相对路径引用
            projectDir = projDir()
        ) { ev -> if (mySeq == runSeq) onAgentEvent(ev) }
        runner = r
        running = true
        // 续跑时轮次**接着数**（runSteps 保持原值）。重置成 0 就是用户看到的「进度清零」——
        // 明明已经干了十几轮，一点重试又从「第 1 轮」开始，看着像全白做了。
        val startStep = if (resume) runSteps else 0
        runSteps = startStep
        // 这三个标记同理：续跑时不能清。它们记的是「这轮改过代码 / 碰过 Maker / 自己构建过」，
        // 清掉的话本轮结束就不会自动刷新预览、也不会自动提交构建 —— 又是白做一轮。
        if (!resume) {
            runWrote = false
            makerTouched = false
            builtThisRun = false
        }
        stopBtn.visibleIf(true)
        startRunCard()

        Thread {
            // 上一轮的线程可能还没退干净：停止是异步的（doStop 只是把 runSeq 拨走，
            // 旧线程还要跑完手上那一步才 return）。
            // 不等它就开新一轮的话，两边会同时改**同一份 history** ——
            // 可能出现「assistant 已经写上 tool_calls、但对应的工具结果还没写进去」的中间态。
            // 下一次请求只好把这半截记录当残缺处理（模型看到的就是那句
            // 「上一步的工具记录不完整」），对话就此断掉，它往往也跟着停住不动。
            // 等一会儿：最多 1.5 秒；等不到也照常开跑，不能把功能卡死在这儿。
            val waitUntil = System.currentTimeMillis() + 1500
            while (runAlive && System.currentTimeMillis() < waitUntil) {
                runCatching { Thread.sleep(50) }
            }
            runAlive = true
            try {
                r.run(
                    history,
                    if (resume) "" else (text + docBlock)
                        .ifBlank { "请看我发的图片/文档，并按里面的内容改进游戏。" },
                    images,
                    resume = resume,
                    startStep = startStep
                )
            } catch (t: Throwable) {
                main.post {
                    if (mySeq == runSeq) addErrorCard(AiClient.friendly("${t.javaClass.simpleName}: ${t.message}"))
                }
            } finally {
                runAlive = false
                main.post { if (mySeq == runSeq) finishRun() }
            }
        }.start()
    }

    private fun onAgentEvent(ev: String) {
        main.post {
            when {
                ev.startsWith("AIFINAL: ") -> {
                    // 这一轮的最终交付：当正文排（普通文字，不套卡片）
                    flushNow()
                    addBubble(ev.removePrefix("AIFINAL: "), false)
                }
                ev.startsWith("AI: ") -> {
                    // 干活途中的过程自述：缩进折进「已工作」组里当一行，不单独占一张卡
                    flushNow()
                    addNoteRow(ev.removePrefix("AI: "))
                }
                ev.startsWith("[失败]") -> {
                    flushNow()
                    addErrorCard(ev.removePrefix("[失败] 请求失败：").trim())
                }
                ev.startsWith("STEP:") -> {
                    runSteps = ev.removePrefix("STEP:").trim().toIntOrNull() ?: runSteps
                    latestActivity = ""
                }
                ev.startsWith("THINK:") -> {
                    val t = ev.removePrefix("THINK:").trim()
                    if (t.isNotEmpty()) appendThinking("思考：\n" + t.take(1500))
                }
                ev.startsWith("TOOLRUN:") -> {
                    // 工具开始执行：在「已工作」组里挂一行「修改 main.js ⟳」，结果回来再定成 ✓/✕
                    val b = ev.removePrefix("TOOLRUN:").trim()
                    val nm = b.removePrefix("[").substringBefore("]")
                    // ] 之后是目标文件（可空），由 AgentRunner 从工具参数里解析出来
                    val target = b.substringAfter("]", "").trim()
                    addToolCardRunning(nm, target)
                    // 记录「这轮碰过 Maker」：哪怕判型还没转成 maker，也说明这是 Maker 工程
                    if (nm.startsWith("maker_")) makerTouched = true
                    if (nm == "maker_build_current_directory") builtThisRun = true
                }
                ev.startsWith("TOOLFAIL:") -> {
                    val l = ev.removePrefix("TOOLFAIL:").trim()
                    val nm = l.substringAfter("[").substringBefore("]")
                    val (head, full) = toolPayload(l.substringAfter("] ").trim())
                    latestActivity = head.take(30)
                    // 不再往思考面板里追加这一行：工具行本身就是记录，追加反而会把
                    // 正在流式的思考文字冲掉（两路都写同一段文本，谁后到谁赢）
                    updateToolCard(false, nm, head, full)
                }
                ev.startsWith("TOOL:") -> {
                    val l = ev.removePrefix("TOOL:").trim()
                    val nm = l.substringAfter("[").substringBefore("]")
                    val (head, full) = toolPayload(l.substringAfter("] ").trim())
                    latestActivity = head.take(30)
                    updateToolCard(true, nm, head, full)
                    // 这里以前还有一句「扫结果文本里有没有媒体后缀 → 自动出播放卡」。
                    // 删掉了：它会把「读到过的文件」当成「刚生成的素材」，
                    // 于是 game_read / code_search 一碰到 .mp4 路径就弹卡（用户说的
                    // 「读项目老是跑这个音频出来」）。真正产出的素材走 ASSET: 事件。
                }
                ev.startsWith("SHOT:") -> {
                    // AI 截到的画面：直接在「已工作」组里放一张缩略图，
                    // 不用去翻文件就能看到它到底截到了什么。
                    addShotThumb(ev.removePrefix("SHOT:").trim())
                }
                ev.startsWith("ASSET:") -> {
                    // 素材生成完成：直接在对话里插一张卡，点一下就能预览 / 试听
                    addAssetCard(ev.removePrefix("ASSET:").trim())
                }
                ev.startsWith("AUTHREQ:") -> {
                    // Maker 报未授权：App 自己把授权链接取回来，直接贴进对话（不让用户去设置页翻）
                    flushNow()
                    autoMakerAuth()
                }
                ev == "RELOAD" -> {
                    runWrote = true
                    ensurePreviewFresh()
                }
                ev.startsWith("INFO: ") -> {
                    pendingEvents += ev.removePrefix("INFO: ")
                    scheduleFlush()
                }
                else -> {
                    pendingEvents += ev
                    scheduleFlush()
                }
            }
        }
    }

    // ==================== 运行状态（折叠思考面板 + 计时） ====================

    /**
     * 本轮的工作组卡（对齐 Maker 的「已工作」行）。
     *
     *   ▸ 已工作 · 读取 2 次 · 12s          ← 组头（永远只有这一行可见）
     *     思考中… <流式全文>                ← 组体（默认收起）
     *     ✓ read_file   README.md
     *     ✕ bash        command not found
     *
     * 一轮只建一张卡；工具行进的是收起着的组体，所以运行过程中
     * **对话区的可见高度从头到尾不变**，屏幕自然不会闪。
     */
    private fun startRunCard() {
        // 本轮统计清零 + 计时重置，然后挂卡
        latestActivity = ""
        lastTail = ""
        thinkLog = ""
        toolTotal = 0
        toolVerbCount.clear()
        pendingStatus = null
        pendingSummary = null
        pendingRow = null
        pendingSpinView = null
        pendingMarkView = null
        // 新一轮开始：用户没主动上滑的话，内容默认贴着底部往下长
        if (!userPinned) stickBottom = true
        runStartAt = SystemClock.elapsedRealtime()
        attachWorkCard()
        updateWorkStat("启动中")
        runBar?.visibleIf(true)
        setTextIf(runBar, "运行中 · 启动中")
        setTextIf(statusRun, "运行中")
        askNotiPermission()
        startGuard()
        startTicker()
        // 模型正在流回来的「思考 / 正文」实时打进面板。
        // 不接这个回调的话，思考版模型长时间推理时用户只能以为它卡死了。
        AiClient.onProgress = { kind, text ->
            val s = runSeq
            main.post { if (s == runSeq) paintProgress(kind, text) }
        }
        scrollChatToBottom()
    }

    /**
     * 建一张「已工作」卡挂到对话末尾，并把所有字段指向它的内部视图。
     *
     * 单独拆出来，是因为 renderHistory() 会把整个对话容器**整体换掉**
     * （先拼好新的再换上去，为了不闪）。换完之后，正在跑的这一轮手里那些引用
     * （toolWrap / runHead / runBody）指的还是**旧容器** —— 已经脱离视图树了：
     * 之后每一条工具行都会塞进一个看不见的地方，用户看到的就是
     * 「工作过程突然不见了」，而且等多久都不会回来。
     * 所以 renderHistory 收尾时，如果这一轮还在跑，必须重新挂一张接住后面。
     */
    private fun attachWorkCard() {
        // ===== 组头 =====
        val icon = ImageView(this).apply {
            setImageDrawable(LineIcon("layers", tintA(pal.accent, 0.75f), dp(13).toFloat()))
        }
        val stat = TextView(this).apply {
            textSize = 11.5f
            typeface = MONO
            setTextColor(pal.sub)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(8), 0)
        }
        val toggle = TextView(this).apply {
            text = "⌄"
            textSize = 14f
            setTextColor(pal.faint)
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), dp(9), dp(11), dp(9))
            addView(icon, LinearLayout.LayoutParams(dp(14), dp(14)))
            addView(stat, LinearLayout.LayoutParams(0, -2, 1f))
            addView(toggle)
            isClickable = true
        }

        // ===== 组体（默认**展开**）=====
        // 展开是有意的：用户要看的正是「现在在改哪个文件、改了多少」，
        // 收起来等于把这些信息藏了。闪不闪由别的机制保证（见下）：
        //   · 组体里只放**工具行**，一行只在「工具开始 / 结束」时增改，不是每收一个字就动
        //   · 流式文字不进这里（它走底部状态行），所以不会每 200ms 撑高卡片一次
        //   · 所有 setText 都过 setTextIf，同样的文字不重复写
        val think = TextView(this).apply {
            textSize = 11.5f
            setTextColor(pal.sub)
            // 思考文字是用来「读」的：行距松一点、颜色灰一档，不抢正文的注意力。
            // 默认收起，等本轮跑完再一次性显示（见 paintThink 的说明）。
            setLineSpacing(dp(4).toFloat(), 1.0f)
            setTextIsSelectable(true)
            visibility = View.GONE
        }
        // 【结构】折叠的只有「思考」，**工作流（工具行 / 过程自述）永远可见**。
        //
        //   ▸ 已工作 · 读取 2 次 · 12s     ← 组头（点它 = 折叠 / 展开**思考**）
        //     思考中… <流式全文>           ← 折叠区：默认收起，只装思考
        //     ✓ read_file   README.md      ← 工作流：**不折叠**，一直在对话里
        //     ✎ 我先看一下这个文件
        //
        // 为什么这么分（用户原话）：「你为什么要把这个已工作、和这个在输出的
        // 包在一起呀？这个输出的东西你不要给它包在一起，已工作这个默认就是折叠的，
        // 但是外面这个工作流程我还是能看得见的。」
        //
        // 之前把工具行也塞进折叠区，结果是：组一收，**整个工作过程全没了**，
        // 用户只能看到一个「已工作 · 12s」，根本不知道它到底干了什么。
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, dp(11), dp(9))
        }
        val thinkBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, dp(11), 0)
            addView(think)
            // 默认**收起**：思考是过程噪音，用户要的是结论和工作流
            visibility = View.GONE
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundCard(this@MainActivity, pal.groupBg, pal.groupBorder, 10)
        }
        card.addView(head)
        card.addView(thinkBox)
        card.addView(rows)
        chatList.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(4)
            bottomMargin = dp(6)
        })

        toolWrap = rows
        runHead = stat
        runBody = think
        runToggle = toggle
        runCardView = card

        // 思考默认收起（用户明确要的）；工作流那部分不受它影响，照样显示。
        bodyExpanded = false
        thinkBox.visibleIf(false)
        setTextIf(toggle, "›")
        // 组头文案的节流状态必须清掉：它记着**上一张卡**写过什么，
        // 不清的话新卡上的 setTextIf 会以为「没变」，一个字都不写。
        lastWorkStat = ""
        updateWorkStat("")

        // 点组头 = 展开 / 收起**思考**（不影响工作流）
        head.setOnClickListener {
            bodyExpanded = !bodyExpanded
            thinkBox.visibleIf(bodyExpanded)
            setTextIf(toggle, if (bodyExpanded) "⌄" else "›")
            if (bodyExpanded) {
                paintThink(force = true)
                scrollChatToBottom()
            }
        }
    }

    /**
     * 只在文字真的变了才 setText。
     *
     * TextView 被设成**同样的文本**也会走一遍 setText → 请求布局 → 重排，
     * 流式输出时每秒几十次同样的写入，看起来就是屏幕在闪。这是最省事也最有效的一道闸。
     */
    private fun setTextIf(tv: TextView?, s: String) {
        if (tv != null && tv.text?.toString() != s) tv.text = s
    }

    /**
     * 工具 → 「干了什么」的动词。
     *
     * 两处都用它：组头要的是「读取 2 次」这种概括，工具行要的是「修改 main.js」这种具体。
     * 判断顺序有讲究 —— create / write 这类「有副作用」的必须排在 read 前面，
     * 否则 game_write 里的 "w" 没什么，但像 code_search 会先命中 "code" 而被误判成改码。
     */
    private fun toolVerb(name: String): String {
        val n = name.lowercase()
        return when {
            n.contains("create") || n.contains("_new") -> "新建"
            n.contains("write") || n.contains("patch") || n.contains("edit") -> "修改"
            n.contains("read") || n.contains("doc") || n.contains("usage") -> "读取"
            n.contains("search") || n.contains("grep") || n.contains("find") || n.contains("glob") -> "检索"
            n.contains("shot") || n.contains("image") || n.contains("_bg") -> "出图"
            n.contains("music") || n.contains("audio") || n.contains("voice") || n.contains("speech") -> "音频"
            n.contains("video") -> "视频"
            n.contains("build") -> "构建"
            n.contains("reload") || n.contains("launch") -> "运行"
            n.contains("log") -> "日志"
            n.contains("memory") -> "记忆"
            n.contains("list") || n.contains("status") || n.contains("apps") || n.contains("project") -> "查询"
            n.contains("skill") -> "技能"
            else -> "工具"
        }
    }

    /**
     * 组头文案：「已工作 · 读取 2 次 · 12s」。
     * 一行说清「干了什么、干了几次、花了多久」——收起状态下一眼就够，
     * 不必摊开组体去看这一轮到底发生了什么。
     */
    private fun updateWorkStat(tail: String) {
        val verbs = if (toolVerbCount.size == 1) {
            val e = toolVerbCount.entries.first()
            e.key + " " + e.value + " 次"
        } else if (toolTotal > 0) {
            "调用 $toolTotal 次"
        } else {
            ""
        }
        val s = buildString {
            append("已工作")
            if (verbs.isNotEmpty()) append(" · ").append(verbs)
            if (tail.isNotEmpty()) append(" · ").append(tail)
        }
        if (s != lastWorkStat) {
            lastWorkStat = s
            runHead?.text = s
        }
    }

    /** 底部状态行：不在 ScrollView 里，所以怎么刷都不会牵动对话区 */
    private fun paintRunBar() {
        val ms = if (runStartAt > 0) SystemClock.elapsedRealtime() - runStartAt else 0
        val head = "运行中 · " + fmtDur(ms) + " · 第 " + runSteps.coerceAtLeast(1) + " 轮 · " +
            latestActivity.ifEmpty { "思考中" }
        setTextIf(runBar, if (lastTail.isEmpty()) head else head + "\n" + lastTail)
    }

    private var lastProgressAt = 0L

    /**
     * 把流式进度画出来。
     *
     * 分两路，各走各的代价：
     *   1) 底部状态行（runBar，**不在 ScrollView 里**）—— 滚动播最新的 90 个字。
     *      它怎么刷都不会牵动对话区，这是给用户看的「它在动」。
     *   2) 组体里的全文 —— **只有用户把组展开时才写**。收起时不碰任何对话区视图，
     *      一次 setText 都不做，滚动位置自然一步都不动。
     *
     * 节流 200ms：响应的 chunk 可能只有几十字节，每个都刷会拖慢主线程。
     */
    private fun paintProgress(kind: String, text: String) {
        if (text.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastProgressAt < 200) return
        lastProgressAt = now

        latestActivity = if (kind == "think") "思考中（已 " + text.length + " 字）"
        else "写回答中（已 " + text.length + " 字）"
        // 流式预览：换行压成空格，取尾部一小段。等价的文字不重复写。
        val tail = text.replace('\n', ' ').trim().takeLast(90)
        if (tail != lastTail) lastTail = tail

        // 只攒「思考」这一路，而且只攒不画：
        //   · 运行期间一个字都不往组体里写 —— 每 200ms 往 ScrollView 里的 TextView
        //     灌一次几千字 = 整棵对话树重排一次，那就是老版本「边输出边闪」的根子。
        //     实时反馈交给底部状态行（它不在 ScrollView 里，怎么刷都不牵动对话区）。
        //   · 最终回答（kind = "text"）**不进组体** —— 它马上会作为正文显示一次，
        //     再在组体里存一份就是同一段话出现两遍。
        // 攒下的思考等本轮跑完一次性补上（见 paintThink）。
        if (kind == "think") thinkLog = "思考中…\n" + text.takeLast(2400)
    }

    /**
     * 计时器：500ms 一拍。
     * 拍的是「组头那一个 TextView」和「底部状态行」，**都不在对话内容流里**，
     * 所以运行期间对话区的高度和滚动位置都是冻住的 —— 想闪都没得闪。
     */
    private fun startTicker() {
        runTicker?.let { main.removeCallbacks(it) }
        val t = object : Runnable {
            override fun run() {
                if (!running) return
                val ms = SystemClock.elapsedRealtime() - runStartAt
                updateWorkStat(fmtDur(ms))
                paintRunBar()
                val sec = ms / 1000
                if (sec != lastNotiSec) {
                    lastNotiSec = sec
                    updateGuard("正在运行 · " + fmtDur(ms) + " · " + latestActivity.ifEmpty { "思考中" })
                }
                main.postDelayed(this, 500)
            }
        }
        runTicker = t
        main.postDelayed(t, 200)
    }

    private fun fmtDur(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "${s} 秒"
            s < 3600 -> "${s / 60} 分 ${s % 60} 秒"
            else -> "${s / 3600} 时 ${(s % 3600) / 60} 分"
        }
    }

    /**
     * 立刻停止当前轮。
     *
     * 关键在「立刻」：用户点停止 / 发新消息时，界面必须马上恢复可用，
     * 不能等后台线程自己结束（HTTP 读超时 240 秒、工具调用 300 秒都可能拖着）。
     * 做法是 runSeq++ 把这一轮作废 —— 它线程收尾时的 finishRun 会被跳过，
     * 它的事件 / 进度回调也都会被序号过滤掉，然后我们自己把界面收干净。
     */
    private fun doStop(reason: String) {
        if (!running && msgQueue.isEmpty()) return
        runSeq++                      // 作废当前轮：旧线程的回调从此全部被丢弃
        running = false
        runner?.let { runCatching { it.cancel() } }
        // 子任务也要一起掐掉。以前这里只取消了主线 —— 子任务的 runner 是
        // runSubtask 里的局部变量，够不着，于是「停止」停不掉它，它会继续写文件。
        abandonSubtask()
        runTicker?.let { main.removeCallbacks(it) }
        AiClient.onProgress = null
        stopBtn.visibleIf(false)
        stopGuard()
        stopPendingSpin()
        latestActivity = ""
        // 收尾也要走 updateWorkStat：先把它清空，好让「已中断」这一个尾标能落上去
        lastWorkStat = ""
        updateWorkStat("已中断（$reason）")
        setTextIf(runBar, "已中断 · $reason")
        statusRun?.let { setTextIf(it, "已中断") }
        runCatching { persistSessions() }
        renderQueue()
        drainQueue(immediate = true)
    }

    /**
     * 出队发送：一次只发一条。
     *
     * 如果这时候上一轮还在跑，先把上一轮停掉（doStop 末尾会再回来调本函数），
     * 保证「队列里的消息一定能发出去」，而不是喊一句「已排队」就没下文。
     */
    private fun drainQueue(immediate: Boolean = false) {
        if (!::inputEt.isInitialized) return
        if (msgQueue.isEmpty()) { renderQueue(); return }
        val go = Runnable {
            if (msgQueue.isEmpty()) return@Runnable
            if (running && runAlive) { doStop("先发队列里的消息"); return@Runnable }
            running = false
            val next = msgQueue.removeFirst()
            inputEt.setText(next)
            renderQueue()
            runCatching { send() }
        }
        if (immediate) main.post(go) else main.postDelayed(go, 200)
    }

    /** 待发送队列面板：每条都能编辑 / 立即发 / 删除 */
    private fun renderQueue() {
        if (!::queueBox.isInitialized) return
        queueBox.removeAllViews()
        if (msgQueue.isEmpty()) {
            queueBox.visibleIf(false)
            return
        }
        queueBox.visibleIf(true)
        queueBox.background = roundCard(this, pal.card, pal.border, 12)
        queueBox.setPadding(dp(10), dp(6), dp(10), dp(6))
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = "待发送队列（${msgQueue.size}）" + if (queueExpanded) " ▾" else " ▸"
            textSize = 12.5f
            setTextColor(pal.sub)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener { queueExpanded = !queueExpanded; renderQueue() }
        })
        queueBox.addView(head)
        if (!queueExpanded) return
        msgQueue.forEachIndexed { idx, m ->
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            r.addView(TextView(this).apply {
                text = m.replace("\n", " ")
                textSize = 12.5f
                setTextColor(pal.text)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(2), dp(5), dp(2), dp(5))
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener { editQueued(idx) }
            })
            r.addView(iconView(this, pal, "play", 14, pal.sub).apply {
                setPadding(dp(7), dp(4), dp(7), dp(4))
                setOnClickListener { sendQueuedNow(idx) }
            }, LinearLayout.LayoutParams(dp(29), dp(24)))
            r.addView(iconView(this, pal, "edit", 14, pal.sub).apply {
                setPadding(dp(7), dp(4), dp(7), dp(4))
                setOnClickListener { editQueued(idx) }
            }, LinearLayout.LayoutParams(dp(29), dp(24)))
            r.addView(iconView(this, pal, "close", 14, pal.errText).apply {
                setPadding(dp(7), dp(4), dp(2), dp(4))
                setOnClickListener {
                    if (idx in msgQueue.indices) msgQueue.removeAt(idx)
                    renderQueue()
                }
            })
            queueBox.addView(r)
        }
    }

    /** 编辑队列里的某一条（改完仍留在队列里，不会顺手发出去） */
    private fun editQueued(i: Int) {
        if (i !in msgQueue.indices) return
        val et = hxInput(this, pal, "消息内容", msgQueue[i], multiLine = true).apply {
            setSelection(text.length)
            minLines = 2
            maxLines = 6
        }
        val wrap = FrameLayout(this).apply {
            setPadding(dp(14), dp(6), dp(14), 0)
            addView(et)
        }
        HxDialog.Builder(themed(), pal)
            .setTitle("编辑待发送消息")
            .setView(wrap)
            .setPositiveButton("保存") { ->
                val t = et.text.toString().trim()
                if (t.isNotEmpty() && i in msgQueue.indices) {
                    msgQueue[i] = t
                    renderQueue()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 队列里的某一条「立即发」（会打断当前轮，先把它发出去） */
    private fun sendQueuedNow(i: Int) {
        if (i !in msgQueue.indices) return
        val t = msgQueue.removeAt(i)
        msgQueue.addFirst(t)
        renderQueue()
        drainQueue(immediate = true)
    }

    private fun finishRun() {
        running = false
        // 主线这一轮结束了 —— 不管它是正常做完、出错还是被停，
        // 都不该再留着子任务在后台继续跑、继续改用户的文件。
        // （真实事故：主线「做完了」，子任务还在跑几十轮，用户问「这合理吗」。）
        abandonSubtask()
        AiClient.onProgress = null
        runTicker?.let { main.removeCallbacks(it) }
        stopBtn.visibleIf(false)
        stopPendingSpin()
        val ms = SystemClock.elapsedRealtime() - runStartAt
        val dur = fmtDur(ms)
        // 组头收尾：把「跑了多久 / 几轮」钉在上面，展开还能回看这一轮都干了什么
        lastWorkStat = ""
        updateWorkStat("$dur · ${runSteps} 轮 · 完成")
        // 收尾把「慢在哪」也报出来：轮次多还是单次慢，解法完全不同
        val pace = TokenStats.pacing(ms, runSteps)
        setTextIf(
            runBar,
            "已完成 · $dur · ${runSteps} 轮" +
                (if (pace.isNotEmpty()) " · $pace" else "") +
                " · " + TokenStats.summary()
        )
        setTextIf(statusRun, "待命")
        latestActivity = ""
        lastTail = ""
        // 收尾把攒了一整轮的思考全文一次性补上（运行期间故意不画，见 paintThink）
        paintThink(force = true)
        lastNotiSec = -1L
        stopGuard()
        // 本轮一结束就把会话落盘：否则 AI 的回复只在下次发送 / 退后台时才保存，
        // 期间若被杀进程 / 覆盖安装，这段记录就没了（用户反馈的「更新后记录消失」）。
        runCatching { persistSessions() }
        if (runWrote) ensurePreviewFresh()
        // Maker 工程：这轮真改过代码，跑完就直接提交 + 云端构建（不用用户再催一句「构建一下」）。
        // 已经自己调过构建的不重复；H5 工程不动。
        if (runWrote && !builtThisRun && (isMakerProject() || makerTouched)) autoBuildMaker()
        // 运行期间排队的消息：这轮结束后自动发下一条（一次只发一条，避免连环堆积）
        renderQueue()
        drainQueue(immediate = false)
    }

    // ==================== 会话管理（像 Maker 那样开多个对话） ====================

    private fun newId(): String = "s" + System.currentTimeMillis()

    /** 载入会话（没有就建一个），并渲染出历史气泡 */
    private fun initSessions() {
        reloadSessionsForProject()
    }

    /** 装载当前项目的会话列表（切项目后调用：界面上的上下文整体换掉，互不串味） */
    private fun reloadSessionsForProject() {
        if (!::chatList.isInitialized) return

        // ★ 正在跑的一轮绝不能被冲掉。
        //
        // 这个函数是「从磁盘重新读会话」—— 磁盘上只有**上一次落盘**的内容，
        // 而当前这一轮新攒的 assistant 回复 / 工具结果还在内存里没落盘。
        // 直接换掉就等于把它们扔了（用户看到「对话突然失忆」）。
        //
        // 正常切项目不会走到这里（openGame 已经拦掉「项目没变」的情况），
        // 但重载预览等路径仍可能触发，所以这里再兜一道：先把当前进度落盘。
        if (runAlive) runCatching { persistSessions() }

        val loaded = ss().load().ifEmpty { mutableListOf(ChatSession(newId(), "新对话")) }
        sessions.clear()
        sessions.addAll(loaded)
        // 停在「上次那个会话」：按落盘的会话 id 找回来，找不到才退回第一个。
        // 以前写死 0，冷启动永远跳到最早那条 —— 用户以为记录丢了。
        val want = runCatching { cfgStore.lastSessionId(currentGame) }.getOrDefault("")
        val found = sessions.indexOfFirst { it.id == want }
        activeSession = if (found >= 0) found else 0
        history = sessions[activeSession].msgs
        chatList.removeAllViews()
        renderHistory()
        updateSessionBtn()
    }

    private fun updateSessionBtn() {
        if (!::sessionBtn.isInitialized) return
        val t = sessions.getOrNull(activeSession)?.title ?: "新对话"
        sessionBtn.text = "会话 · $t ▾"
    }

    private fun persistSessions() {
        sessions.getOrNull(activeSession)?.let {
            it.msgs = history
            it.updated = System.currentTimeMillis()
            // 记下「最后停在哪个会话」：冷启动 / 切项目回来才能接着上次看
            runCatching { cfgStore.setLastSessionId(currentGame, it.id) }
        }
        ss().save(sessions)
    }

    private fun newChat() {
        persistSessions()
        val s = ChatSession(newId(), "新对话")
        sessions.add(0, s)
        activeSession = 0
        history = s.msgs
        chatList.removeAllViews()
        addSystemLine("已开新对话：上下文互不干扰；旧对话可从「会话」里切回来")
        updateSessionBtn()
        persistSessions()
    }

    private fun switchTo(i: Int) {
        if (i == activeSession) return
        persistSessions()
        activeSession = i
        history = sessions[i].msgs
        renderHistory()
        updateSessionBtn()
        // 切换后再落一次：上面那次记的是「切换前」的会话 id
        runCatching { persistSessions() }
        toast("已切到「${sessions[i].title}」")
    }

    private fun deleteSession(i: Int) {
        if (sessions.size <= 1) {
            toast("至少留一个会话")
            return
        }
        sessions.removeAt(i)
        if (activeSession >= sessions.size) activeSession = sessions.size - 1
        history = sessions[activeSession].msgs
        renderHistory()
        updateSessionBtn()
        persistSessions()
    }

    private fun renderHistory() {
        if (!::chatScroll.isInitialized) return
        // 关键：**不要在已挂载的列表上「先清空、再逐条加」** —— 那样会空出一帧，
        // 表现出来就是「对话莫名消失一下」。改成先在离屏的新列表里拼好，
        // 最后一次换上去（同一帧内完成，不会闪）。
        val old = chatList
        val fresh = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        chatList = fresh
        for (m in history) {
            when (m.role) {
                "user" -> addBubble(m.text ?: "（图片）", true, m.images)
                "assistant" -> {
                    if (!m.text.isNullOrBlank()) addBubble(m.text, false)
                    // 这一轮调过工具：插一条「已工作 · 修改 2 次」摘要，
                    // 但**绝不回放工具结果原文**（见下面 tool 分支的说明）
                    if (m.toolCalls.isNotEmpty()) addHistoryWorkLine(m.toolCalls.map { it.name })
                }
                // ⚠️ 工具结果的**文字**不回放。
                // 以前这里走 else 分支，被当成「系统提示」整段居中打印 ——
                // 而一条工具结果最长 16000 字符（一次 game_read 就是整个文件），
                // 铺满整屏不说，还居中 + 灰字，用户看到的就是
                // 「偶尔犯病出来一大段代码，然后对话历史就看不见了」。
                // 想看细节：当时的卡片里有，或者让它重新读一次 —— 都比把 16k 塞进对话强。
                //
                // 但**截图要回放** —— 那是用户要看的画面，不是文本噪音。
                // 只存了路径，按路径去读；文件被删了就跳过。
                "tool" -> m.shotPaths?.forEach { p ->
                    if (File(p).isFile) addShotThumb(p)
                }
                else -> if (!m.text.isNullOrBlank()) addSystemLine(m.text)
            }
        }
        chatScroll.removeAllViews()
        chatScroll.addView(fresh)
        old.removeAllViews()
        // ⚠️ 关键：这一轮如果还在跑，必须重新挂一张「已工作」卡。
        // 上面是把整个对话容器**换掉**的，跑着的那一轮手里那些引用
        // （toolWrap / runHead / runBody）还指着**刚被丢弃的旧容器** —— 已经脱离视图树了。
        // 不重挂的话，之后每一条工具行都会塞进一个看不见的地方，屏幕上就是
        // 「工作过程突然不见了」，而且再也不会回来（用户报的正是这个）。
        // 已经跑完的那几轮，上面的循环已经补成「已工作 · N 次」摘要行了；
        // 这里只接住**接下来**的部分 —— 计时和统计都不碰，不能让时钟归零。
        if (running) attachWorkCard()
        stickBottom = true
        scrollChatToBottom(true)
    }

    /**
     * 回放历史时的「已工作」摘要行。
     *
     * 只报「这一轮干了什么、几次」，不回放工具结果原文 ——
     * 一条结果最长 16k 字符，回放出来就是铺满整屏的一坨代码，
     * 把上下文全顶没（用户报的「犯病成一大段长内容」）。
     * 历史里没存工具行明细，所以这里不给展开，只报个大概。
     */
    private fun addHistoryWorkLine(names: List<String>) {
        if (names.isEmpty()) return
        val verbs = LinkedHashMap<String, Int>()
        for (n in names) {
            val v = toolVerb(n)
            verbs[v] = (verbs[v] ?: 0) + 1
        }
        val sum = if (verbs.size == 1) {
            val e = verbs.entries.first()
            e.key + " " + e.value + " 次"
        } else {
            "调用 ${names.size} 次"
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), dp(7), dp(11), dp(7))
            background = roundCard(this@MainActivity, pal.groupBg, pal.groupBorder, 10)
        }
        row.addView(ImageView(this).apply {
            setImageDrawable(LineIcon("layers", pal.sub, 1.8f))
        }, LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(8) })
        row.addView(TextView(this).apply {
            text = "已工作 · $sum"
            textSize = 11.5f
            typeface = MONO
            setTextColor(pal.sub)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        chatList.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(3)
            bottomMargin = dp(3)
        })
    }

    /** 第一条用户消息顺便当标题，省得一堆「新对话」 */
    private fun maybeAutoTitle(t: String) {
        val s = sessions.getOrNull(activeSession) ?: return
        if (s.title == "新对话" && t.isNotBlank()) {
            s.title = t.replace("\n", " ").take(14)
            updateSessionBtn()
        }
    }

    private fun showSessions() {
        persistSessions()
        val labels = sessions.mapIndexed { i, s ->
            (if (i == activeSession) "当前 · " else "") + s.title + "（${s.msgs.size} 条）"
        }.toTypedArray()
        HxDialog.Builder(themed(), pal)
            .setTitle("会话（${sessions.size}）")
            .setItems(labels) { i -> switchTo(i) }
            .setPositiveButton("＋新对话") { -> newChat() }
            .setNeutralButton("重命名") { ->
                val et = hxInput(this, pal, "会话名", sessions[activeSession].title).apply {
                    setSelection(text.length)
                }
                HxDialog.Builder(themed(), pal)
                    .setTitle("重命名会话")
                    .setView(et)
                    .setPositiveButton("好") { ->
                        sessions[activeSession].title = et.text.toString().take(24)
                        updateSessionBtn()
                        persistSessions()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("删除当前") { -> deleteSession(activeSession) }
            .show()
    }

    // ==================== 工作区：素材 / 文档 / 技能 ====================

    /** 入口卡 → 选择器：素材 = 图/音/视频，文档 = 文本类，技能 = 用户自传的 skill */
    private fun pickMedia(mode: String) {
        pickMode = mode
        val mimes = when (mode) {
            "doc" -> arrayOf(
                "text/*", "application/json", "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/zip", "application/octet-stream"
            )
            "skill" -> arrayOf("text/*", "application/json", "application/javascript")
            else -> arrayOf("image/*", "audio/*", "video/*")
        }
        runCatching { pickMany.launch(mimes) }
            .onFailure { toast("这台机器没有可用的文件选择器") }
    }

    private fun onPickedMany(uris: List<Uri>) {
        val mode = pickMode
        Thread {
            val ok = mutableListOf<Attach>()
            var bad = 0
            for (u in uris) {
                val a = runCatching { readAttach(u, mode) }.getOrNull()
                if (a == null) bad++ else ok += a
            }
            main.post {
                if (ok.isNotEmpty()) {
                    attached.addAll(ok)
                    toast("已加入待发送区 ${ok.size} 个文件")
                }
                if (bad > 0) toast("有 $bad 个文件读不了（可能没给读取权限）")
                // 上传完刷新工作区（常驻列表立刻能看到新文件）
                updateAttachInfo()
                if (wsDlg != null) renderWs()
            }
        }.start()
    }

    /**
     * 把选中的文件落进工程，并尽量提出正文：
     *   素材 → 工程根/_uploads/media/
     *   文档 → 工程根/_uploads/doc/
     *   技能 → 工程根/_skills/
     * 图片额外压一份小图存进 shotBytes，走多模态当图片发给模型。
     */
    private fun readAttach(uri: Uri, mode: String): Attach {
        val name = (queryName(uri) ?: "file_${System.currentTimeMillis()}").replace("/", "_")
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
        if (bytes.isEmpty()) throw IllegalStateException("空文件")
        val mime = runCatching { contentResolver.getType(uri) }.getOrNull().orEmpty()
        val kind = when {
            mode == "doc" -> "doc"
            mode == "skill" -> "skill"
            mime.startsWith("audio") -> "audio"
            mime.startsWith("video") -> "video"
            else -> "image"
        }
        val dir = when (kind) {
            // 素材 / 文档进「当前项目」的工作区：工作区是项目独立的，互不干扰；
            // 而且放在项目里，游戏代码用相对路径 _uploads/media/x.png 才引用得到。
            // 技能是全局的（跨项目复用），仍然放在工程根。
            "doc" -> File(projDir(), "_uploads/doc")
            "skill" -> File(gameRoot, "_skills")
            else -> File(projDir(), "_uploads/media")
        }.apply { mkdirs() }
        val dst = File(dir, name)
        dst.writeBytes(bytes)
        val rel = dst.relativeTo(gameRoot).path
        val text = if (kind == "image" || kind == "audio" || kind == "video") ""
        else extractText(name, bytes).take(60000)
        if (kind == "image") {
            runCatching { shrinkToJpeg(bytes, 1024, 80) }.getOrNull()?.let { shotBytes[rel] = it }
        }
        return Attach(name, rel, kind, text)
    }

    /** 加入对话：图片并入多模态，其余在本轮消息里带上路径 + 正文。
     *  silent=true 时用于「发送时自动并入」，不刷一条系统提示（避免噪音）。 */
    private fun queueAttach(a: Attach, silent: Boolean = false) {
        if (!attached.remove(a)) return
        if (a.kind == "image") {
            // 优先用内存里已压缩好的那份；没有就现读文件再压
            // （readAttach 存的是 shotBytes[rel]，rel 是相对 gameRoot 的路径，别当绝对路径用）
            val b = shotBytes[a.rel] ?: runCatching {
                val f = File(a.rel).let { if (it.isAbsolute) it else File(gameRoot, a.rel) }
                if (f.isFile) shrinkToJpeg(f.readBytes(), 1024, 80) else null
            }.getOrNull()
            if (b != null && b.isNotEmpty()) pendingShots += b
        }
        queued += a
        if (!silent) addSystemLine("已加入对话：${a.kind}「${a.name}」")
        updateAttachInfo()
    }

    private fun queryName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull()

    private fun extractText(name: String, bytes: ByteArray): String {
        val n = name.lowercase()
        val textExt = listOf(
            ".txt", ".md", ".markdown", ".json", ".js", ".ts", ".html", ".htm", ".css",
            ".csv", ".log", ".xml", ".yml", ".yaml", ".lua", ".py", ".kt", ".java",
            ".ini", ".toml", ".sh", ".sql", ".c", ".cpp", ".h"
        )
        return when {
            textExt.any { n.endsWith(it) } -> String(bytes, Charsets.UTF_8)
            n.endsWith(".docx") -> runCatching {
                java.util.zip.ZipInputStream(bytes.inputStream()).use { zin ->
                    var e = zin.nextEntry
                    var xml = ""
                    while (e != null) {
                        if (e.name == "word/document.xml") {
                            xml = zin.readBytes().toString(Charsets.UTF_8)
                            break
                        }
                        e = zin.nextEntry
                    }
                    xml.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
                }
            }.getOrDefault("")
            n.endsWith(".zip") -> runCatching {
                java.util.zip.ZipInputStream(bytes.inputStream()).use { zin ->
                    val names = mutableListOf<String>()
                    var e = zin.nextEntry
                    while (e != null) {
                        names += e.name
                        if (names.size >= 300) break
                        e = zin.nextEntry
                    }
                    "（zip 内条目）\n" + names.joinToString("\n")
                }
            }.getOrDefault("")
            n.endsWith(".pdf") ->
                "（PDF 不能直接解析正文：有要参考的内容请复制成 txt/md 再上传；" +
                    "原文件已存到 _uploads/，路径见上）"
            else -> ""
        }
    }

    // ==================== 看 / 改代码（对话页那排图标） ====================

    /**
     * 当前项目到底是什么工程。
     *
     * 判型顺序：手动覆盖（.hexora-kind；Maker 构建成功会自动写 maker）→
     * scripts/main.lua / urhox-libs 强信号 → 非占位的 index.html → .project/project.json。
     * 特意**不看 .maker-mcp**（H5 工程被 Maker CLI 碰过一次就会长出来，正是误判的老毛病）。
     *
     * H5：index.html，WebView 直接跑；
     * Maker：UrhoX 工程，WebView 跑不了 —— 预览走预览页里的 GeckoView 打开 maker.taptap.cn 控制台。
     */
    private fun projKind(name: String): String {
            val d = File(gameRoot, name)

            // 0) 手动覆盖优先：项目里放了 .hexora-kind（内容 h5 / maker）就听它的。
            //    自动识别终究是猜；留一个「用户说了算」的出口，猜错不用等下一版。
            val ov = runCatching { File(d, ".hexora-kind").readText().trim().lowercase() }.getOrNull()
            if (ov == "h5" || ov == "maker") return ov

            // 1) Maker / UrhoX 的强信号：入口脚本 + 引擎运行时目录。
            //    这两个是真做过 Maker 工程才会出现的，H5 工程不会凭空长出来。
            if (File(d, "scripts/main.lua").isFile) return "maker"
            if (File(d, "urhox-libs").isDirectory) return "maker"

            // 2) 网页游戏：根目录有 index.html，而且它**不是占位说明页**。
            //    踩过的坑：AI 会往 Maker 工程里写一个 index.html 占位页（内容带
            //    「URHOX PROJECT / 入口脚本 scripts/main.lua / 手机端无法本地跑预览」），
            //    只看「有没有 index.html」会把真 Maker 工程判成 H5。
            val idx = File(d, "index.html")
            if (idx.isFile && !isPlaceholderHtml(idx)) return "h5"

            // 3) 兜底：Maker 的工程描述文件（做过 Maker 工程才会有）。
            //    注意：**不再把 .maker-mcp 当信号** —— 它只是 MCP 的配置目录，
            //    H5 工程被 Maker CLI 碰过一次就会留下，这正是之前「全被标成 Maker」的原因。
            if (File(d, ".project/project.json").isFile) return "maker"

            return "h5"
        }

        /**
         * 是不是「占位说明页」。
         *
         * 这类 index.html 是 AI 为了让引擎把「当前工程」切到本目录而写的，
         * 内容很短、带固定字样，不能拿它当「这是个网页游戏」的证据。
         * 真游戏页面都比较大，所以先卡体积，再认关键词，开销可忽略。
         */
        private fun isPlaceholderHtml(f: File): Boolean = runCatching {
            if (f.length() > 60000) return@runCatching false
            val t = f.readText()
            t.contains("占位") || t.contains("URHOX PROJECT") || t.contains("无法本地跑预览") ||
                t.contains("maker_build_current_directory")
        }.getOrDefault(false)

    /** 预览模式的显示名：H5 / Maker，放在预览页标题里，一眼能看出走的哪条路 */
    private fun kindLabel(name: String): String =
        if (projKind(name) == "maker") "Maker" else "H5"

    /**
     * 把某个项目钉成 Maker（写 .hexora-kind，projKind() 优先读它）。
     *
     * 触发点：maker_build_current_directory 成功返回（含 App 自动构建）。
     * 这是整套判型里唯一骗不了的依据 —— 不看目录里有什么文件，只看「真构建过」。
     */
    private fun markCurrentProjectAsMaker(id: String = currentGame) {
        if (id.isBlank()) return
        val d = File(gameRoot, id)
        val cur = runCatching { File(d, ".hexora-kind").readText().trim().lowercase() }.getOrDefault("")
        runCatching { File(d, ".hexora-kind").writeText("maker") }
        if (cur == "maker") return
        main.post {
            if (id == currentGame) {
                lastLoadedGame = ""
                refreshHeader()
            }
            toast("已构建过 Maker：「$id」按 Maker 预览")
        }
    }

    /**
     * 一轮任务结束后，自动提交 + 触发 Maker 云端构建。
     *
     * 以前构建全看 AI 想不想得起来调 maker_build_current_directory —— 它经常想不起来，
     * 用户就得每次补一句「构建一下」。现在：这轮真写过文件、且当前是 Maker 工程
     * （或这轮摸过 maker_ 工具），跑完就直接构建。
     *
     * 这条是 App 主动发起，不经过「写工具准入」那道闸（那道闸是防 AI 擅自发布上线的）。
     */
    private fun autoBuildMaker() {
        val id = currentGame
        val hub = EngineTools.mcp
        if (hub == null || !hub.handles("maker_build_current_directory")) {
            toast("Maker 通道还没就绪，这轮先不自动构建")
            return
        }
        addSystemLine("检测到 Maker 工程改动：正在自动提交 + 云端构建…")
        Thread {
            val out = runCatching { hub.call("maker_build_current_directory", "{}") }
                .getOrElse { "[构建失败] " + it.message }
            main.post {
                val bad = out.startsWith("[工具报错]") || out.startsWith("[构建失败]")
                if (bad) {
                    addSystemLine("自动构建没走通：\n" + out.take(600))
                } else {
                    addSystemLine("已自动构建：Maker 云端正在出包，预览页刷新后即可见")
                    markCurrentProjectAsMaker(id)
                    // 构建完不能只丢个链接让用户自己找：直接把预览页切过去。
                    // （Maker 走 GeckoView 控制台，reloadGame 会 reload 出最新构建）
                    showTab(1)
                    reloadGame()
                }
            }
        }.apply { isDaemon = true; name = "maker-autobuild" }.start()
    }

    /** 当前项目是不是 Maker / UrhoX 工程 */
    private fun isMakerProject(): Boolean = projKind(currentGame) == "maker"

    /** 代码图标：直接开 index.html（没有就 game.js，再没有就第一个文件） */
    private fun editMainCode() {
        val dir = File(gameRoot, currentGame)
        val f = if (projKind(currentGame) == "maker") {
            // UrhoX 工程：入口是 scripts/main.lua，另有 CLAUDE.md（AI 开发指南）
            listOf("scripts/main.lua", "main.lua", "CLAUDE.md", "README.md").map { File(dir, it) }
                .firstOrNull { it.exists() }
                ?: dir.walkTopDown().firstOrNull { it.isFile && it.extension != "pak" }
        } else {
            listOf("index.html", "game.js").map { File(dir, it) }
                .firstOrNull { it.exists() }
                ?: dir.walkTopDown().firstOrNull { it.isFile }
        }
        if (f == null) {
            toast("这个项目还没有文件")
            return
        }
        openFileEditor(f, dir)
    }

    private fun showCode() {
        Thread {
            val dir = File(gameRoot, currentGame)
            val files = dir.walkTopDown().filter { it.isFile }
                .filterNot { it.path.contains("/_shots/") }
                .sortedBy { it.path }.take(200).toList()
            main.post {
                if (files.isEmpty()) {
                    toast("这个项目还没有文件")
                    return@post
                }
                // 与素材列表同一套行样式：图标 + 文件名 + ＋（添加到对话），点行 = 打开编辑
                val col = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(6), dp(14), dp(6))
                }
                for (f in files) {
                    val rel = f.relativeTo(dir).path
                    val row = fileRow(
                        name = rel,
                        kind = kindOfFile(rel),
                        onOpen = { openFileEditor(f, dir) },
                        onAdd = {
                            attached += Attach(rel, rel, "code")
                            toast("已加入待发送区：$rel")
                            updateAttachInfo()
                        }
                    )
                    col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
                }
                HxDialog.Builder(themed(), pal)
                    .setTitle("$currentGame · 文件（${files.size}）")
                    .setView(ScrollView(this).apply { addView(col) })
                    .setNegativeButton("关闭", null)
                    .show()
            }
        }.start()
    }

    /** 文件行：小图标 + 文件名 + 可选「＋」。素材列表和代码列表共用同一套样式 */
    private fun fileRow(
        name: String,
        kind: String,
        onOpen: () -> Unit,
        onAdd: (() -> Unit)? = null
    ): LinearLayout {
        val row = attachRowOf(this, pal, kind, name, tail = if (onAdd != null) "＋" else null, onTail = onAdd)
        row.setOnClickListener { onOpen() }
        return row
    }

    /** 按后缀猜图标：代码页和素材列表都靠它选图标 */
    private fun kindOfFile(name: String): String = when {
        name.endsWith(".png", true) || name.endsWith(".jpg", true) ||
            name.endsWith(".jpeg", true) || name.endsWith(".gif", true) ||
            name.endsWith(".webp", true) || name.endsWith(".bmp", true) -> "image"
        name.endsWith(".mp3", true) || name.endsWith(".wav", true) ||
            name.endsWith(".ogg", true) || name.endsWith(".m4a", true) -> "audio"
        name.endsWith(".mp4", true) || name.endsWith(".webm", true) ||
            name.endsWith(".mov", true) -> "video"
        name.endsWith(".json", true) || name.endsWith(".js", true) ||
            name.endsWith(".ts", true) || name.endsWith(".html", true) ||
            name.endsWith(".css", true) || name.endsWith(".kt", true) -> "code"
        else -> "doc"
    }

    private fun openFileEditor(f: File, base: File) {
        val body = runCatching { f.readText() }.getOrDefault("")
        val et = hxInput(this, pal, "文件内容", body, multiLine = true).apply {
            textSize = 12.5f
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START
        }
        HxDialog.Builder(themed(), pal)
            .setTitle(f.relativeTo(base).path)
            .setView(ScrollView(this).apply { addView(et) })
            .setPositiveButton("保存") { ->
                runCatching {
                    f.writeText(et.text.toString())
                    FileJournal.record("用户编辑", f.name, f.length(), "在 App 里手改并保存，已热重载")
                    reloadGame()
                    toast("已保存并热重载")
                }.onFailure { toast("保存失败：${it.message}") }
            }
            .setNeutralButton("复制全文") { -> copyToClipboard(et.text.toString()) }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ==================== 拉取厂商真实模型列表 ====================

    /** 预设永远追不上厂商改名，这里直接问厂商「你现在有哪些模型」 */
    private fun fetchModels(p: Provider, keyOverride: String? = null, onPicked: ((String) -> Unit)? = null) {
        // 优先用「输入框里当下填的 Key」，其次才回退到已保存的。
        // 老代码只读已保存值 → 用户刚粘贴完、还没点「保存」就点「拉取」，
        // 会被判成「没填 Key」，于是出现「明明填了却提示先去设置里填」的鬼打墙。
        val key = keyOverride?.trim()?.takeIf { it.isNotBlank() } ?: cfgStore.keyOf(p.id)
        if (key.isBlank() && !p.baseUrl.contains("127.0.0.1")) {
            toast("请先在下面输入框填好 ${p.label} 的 Key（没保存也行，拉取会直接用它）")
            return
        }
        toast("正在拉取 ${p.label} 的模型列表…")
        Thread {
            val ids = runCatching {
                val url = java.net.URL(cfgStore.effectiveBaseUrl(p).trimEnd('/') + "/models")
                val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 20000
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Accept", "application/json")
                }
                val txt = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                conn.disconnect()
                val jo = org.json.JSONObject(txt)
                val arr = jo.optJSONArray("data") ?: jo.optJSONArray("models")
                val list = mutableListOf<String>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optString("id").ifBlank { o.optString("name") }
                        if (id.isNotBlank()) list += id
                    }
                }
                list.sorted()
            }.getOrElse { emptyList() }
            main.post {
                if (ids.isEmpty()) {
                    toast("没拉到（协议不支持或 Key/地址不对），先给预设清单")
                    val presets = p.models.map { it.name }.toTypedArray()
                    HxDialog.Builder(themed(), pal)
                        .setTitle("${p.label} 预设模型")
                        .setItems(presets) { i ->
                            cfgStore.setModel(p.id, presets[i])
                            onPicked?.invoke(presets[i])
                        }
                        .setNegativeButton("关闭", null)
                        .show()
                    return@post
                }
                HxDialog.Builder(themed(), pal)
                    .setTitle("${p.label} 可用模型（${ids.size}）")
                    .setItems(ids.toTypedArray()) { i ->
                        cfgStore.setModel(p.id, ids[i])
                        onPicked?.invoke(ids[i])
                        refreshHeader()
                        toast("已选 ${ids[i]}，设置页点「保存」即可生效")
                    }
                    .setNegativeButton("关闭", null)
                    .show()
            }
        }.start()
    }

    // ==================== 后台保活 ====================

    /**
     * 任务一启动就挂前台服务 + WakeLock。
     * 不这么做的话，切后台几秒钟系统就把进程冻结，AI 直接「不动了」。
     */
    private fun startGuard() {
        if (guardOn) return
        guardOn = true
        RunGuardService.start(this, "启动中…")
        runCatching {
            val pm = getSystemService(android.content.Context.POWER_SERVICE)
                as android.os.PowerManager
            wakeLock = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK, "hexora:run"
            ).apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        }
    }

    private fun updateGuard(text: String) {
        if (!guardOn) return
        RunGuardService.update(this, text)
    }

    private fun stopGuard() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        if (!guardOn) return
        guardOn = false
        RunGuardService.stop(this)
    }

    /** 国产 ROM 只靠前台服务还不够，得把电池优化关掉 */
    private fun askKeepAlive() {
        val pm = getSystemService(android.content.Context.POWER_SERVICE)
            as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("已在保活白名单里，切后台不会被冻结")
            return
        }
        runCatching {
            startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }.onFailure {
            toast("这台机器不允许直接申请，请到系统设置 → 应用 → Hexora → 省电策略里选「无限制」")
        }
    }

    /** Android 13+ 通知权限：不给也能跑，只是通知栏看不到进度 */
    private fun askNotiPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val ok = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!ok) runCatching {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 71)
        }
    }

    /**
     * 组体里那段「思考 / 工具流水」的文字来源。
     *
     * 单独攒一份字符串、而不是随写随 setText：组体默认是收起的，
     * 收起时往一个 GONE 的 TextView 里写字，用户一个字都看不见，
     * 却要付出一次重新排版的代价 —— 这正是老版本白花的开销。
     * 现在收起期间只攒不画，用户点开时一次性补上。
     */
    private var thinkLog = ""

    private fun appendThinking(line: String) {
        thinkLog = if (thinkLog.isEmpty()) line else thinkLog + "\n" + line
        if (thinkLog.length > 3000) thinkLog = thinkLog.takeLast(3000)
        // 运行期间**也要**把思考画进对话里（节流），不能只等收尾那一下。
        //
        // 用户反馈的原话：「真遇到那个催他那一步之后，工作输出又在对话里面看不见了，
        // 他只会在底部那个输入框上面那个思考模式里面输出。」
        // 原因就是这里以前调的是 paintThink()，而 paintThink 在 running 时直接 return ——
        // 于是运行期间思考只出现在底部状态行，对话区一片空白，看着像「什么都没干」。
        scheduleThinkPaint()
    }

    /**
     * 思考内容的**节流**重绘：运行期间每 600ms 最多画一次。
     *
     * 为什么不能每来一条就画：思考是流式的，一秒能来几十条；每来一条就把
     * 几千字的文本重新 setText 一次 = 整棵对话树重排，那就是老版本「边输出边闪」的来源。
     * 节流到 600ms 之后，观感上是「实时在写」，代价却只有一个数量级。
     */
    private fun scheduleThinkPaint() {
        if (thinkPaintScheduled) return
        thinkPaintScheduled = true
        main.postDelayed({
            thinkPaintScheduled = false
            paintThink(force = true)
        }, 600)
    }

    /**
     * 把 thinkLog 画进组体。
     *
     * @param force true = 无视「运行中不画」这条限制（收尾 / 用户点开 / 节流重绘时用）。
     */
    private fun paintThink(force: Boolean = false) {
        if (running && !force) return
        if (!bodyExpanded) return
        if (thinkLog.isBlank()) return
        val tv = runBody ?: return
        setTextIf(tv, thinkLog)
        if (tv.visibility != View.VISIBLE) tv.visibleIf(true)
    }

    private fun scheduleFlush() {
        if (flushScheduled) return
        flushScheduled = true
        main.postDelayed({
            flushScheduled = false
            if (pendingEvents.isEmpty()) return@postDelayed
            val txt = pendingEvents.joinToString("\n")
            pendingEvents.clear()
            val tv = TextView(this).apply {
                setText(txt)
                textSize = 11.5f
                setTextColor(pal.faint)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(4), dp(10), dp(4))
            }
            chatList.addView(tv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(1) })
            scrollChatToBottom()
        }, 140)
    }

    private fun flushNow() {
        if (pendingEvents.isEmpty()) return
        val txt = pendingEvents.joinToString("\n")
        pendingEvents.clear()
        // 「完成 · 共跑了 N 轮 · 累计 … tok」是这一轮的收尾小结，单独立成一张**带边框**的卡，
        // 和上面的「已工作」组对齐。以前它混在其它提示里当一行居中灰字，
        // 看着就是「一张没画完边框的卡片」（用户报的正是这个）。
        if (txt.startsWith("完成 ·") || txt.startsWith("已停止 ·") || txt.startsWith("到步数上限")) {
            addRunDoneCard(txt)
        } else {
            addSystemLine(txt)
        }
    }

    // ⚠️ 参数不能叫 text：`TextView(this).apply { text = ... }` 里的简单名 `text`
    // 会先命中**函数参数**（局部作用域优先于隐式接收者），而参数是 val ——
    // 编译器直接报 Val cannot be reassigned。（addNoteRow 踩过一次，这里又踩了一次。）
    // 规矩定死：凡是在 apply 块里给 TextView 赋值，一律用 setText(...)。
    private fun addRunDoneCard(raw: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = roundCard(this@MainActivity, pal.card, pal.border, 10)
        }
        card.addView(TextView(this).apply {
            setText(raw)
            textSize = 11.5f
            typeface = MONO
            setTextColor(pal.sub)
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(iconOp("copy", "复制本轮小结") { copyToClip(raw, "本轮小结") })
        chatList.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        })
        scrollChatToBottom()
    }

    // ==================== 项目（一个项目一个目录） ====================

    private fun listProjects(): List<File> =
        (gameRoot.listFiles() ?: emptyArray())
            .filter { it.isDirectory && !it.name.startsWith("_") }
            .sortedBy { it.name.lowercase() }

    /**
     * 删除项目。
     *
     * 明面的「删除」按钮和长按都进这里：原来的入口只有长按，
     * 用户反馈「怎么没有删除项目」——藏起来的入口等于没有。
     */
    private fun askDeleteProject(p: File) {
        if (p.name == currentGame) {
            toast("这是当前项目，先切到别的项目再删")
            return
        }
        HxDialog.Builder(themed(), pal)
            .setTitle("删除项目「${p.name}」？")
            .setMessage(
                p.absolutePath + "\n\n" +
                    "整个目录会删掉：代码 / 素材 / 技能 / 存档都没了，不可恢复。"
            )
            .setDanger(true)
            .setPositiveButton("删除") { ->
                val ok = runCatching { p.deleteRecursively() }.getOrDefault(false)
                toast(if (ok) "已删除「${p.name}」" else "删除失败（可能被占用）")
                projectDlg?.dismiss()
                refreshHeader()
            }
            .setNegativeButton("取消", null)
            .show()
    }


    private fun showProjects() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        col.addView(TextView(this).apply {
            text = "根目录 ${gameRoot.absolutePath}"
            textSize = 11f
            setTextColor(pal.faint)
            setPadding(dp(4), 0, dp(4), dp(10))
            setTextIsSelectable(true)
        })

        if (!Environment.isExternalStorageManager()) {
            col.addView(ghostBtnOf(this, pal, "授权所有文件访问 → 项目存到 /sdcard/Hexora").apply {
                setOnClickListener {
                    runCatching {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    }
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(10) })
        }

        val projects = listProjects()
        if (projects.isEmpty()) {
            col.addView(TextView(this).apply {
                text = "还没有项目，点下面的「新建项目」"
                textSize = 13f
                setTextColor(pal.sub)
                setPadding(dp(4), dp(2), dp(4), dp(8))
            })
        }
        col.addView(TextView(this).apply {
            text = "每个项目右侧有「删除」（当前项目要先切到别的项目）"
            textSize = 11f
            setTextColor(pal.faint)
            setPadding(dp(4), 0, dp(4), dp(6))
        })
        for (p in projects) {
            val kindTag = " · " + kindLabel(p.name)
            val row = listRowOf(
                this, pal,
                p.name + kindTag + if (p.name == currentGame) " · 当前" else "",
                p.absolutePath
            )
            row.setOnClickListener {
                switchProject(p.name)
                projectDlg?.dismiss()
            }
            // 长按项目 = 删除该项目。用户反馈：项目列表里根本没有删除入口，
            // 建过的项目只能一直堆着。删当前项目先拦住（避免把正在跑的东西抽掉）。
            row.setOnLongClickListener { askDeleteProject(p); true }
            // 明面上的删除入口：长按也能删，但不该是唯一入口（用户反馈「怎么没有删除项目」）
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            line.addView(row, LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(chipOf(this, pal, "删除", false).apply {
                setOnClickListener { askDeleteProject(p) }
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
            col.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }

        val dlg = HxDialog.Builder(themed(), pal)
            .setTitle("项目")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("新建项目", null)
            .setNeutralButton("导出当前", null)
            .setNegativeButton("关闭", null)
            .create()
        projectDlg = dlg
        dlg.setOnShowListener {
            dlg.btn(HxDialog.BUTTON_POSITIVE)?.setOnClickListener {
                dlg.dismiss()
                newProjectDialog()
            }
            dlg.btn(HxDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                dlg.dismiss()
                exportZip()
            }
        }
        dlg.show()
    }

    private fun switchProject(name: String) {
        openGame(name)
        refreshHeader()
        lastLoadedGame = ""
        addSystemLine("已打开项目「$name」（${kindLabel(name)}） · ${File(gameRoot, name).absolutePath}")
        if (activeTab == 1) ensurePreviewFresh()
    }

    private fun newProjectDialog() {
        val et = hxInput(this, pal, "项目名，例如 snake-01")
        val wrap = LinearLayout(this).apply {
            setPadding(dp(16), dp(10), dp(16), dp(4))
            addView(et, LinearLayout.LayoutParams(-1, -2))
        }
        val d = HxDialog.Builder(themed(), pal)
            .setTitle("新建项目")
            .setMessage("一个项目一个目录，互不干扰。创建后先给一个可运行的空白页，之后 AI 的所有改动都只写进这个目录。")
            .setView(wrap)
            .setPositiveButton("创建", null)
            .setNegativeButton("取消", null)
            .create()
        d.setOnShowListener {
            d.btn(HxDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val name = et.text.toString().trim()
                    .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
                    .joinToString("")
                    .trim('-')
                if (name.isEmpty()) {
                    toast("先起个名字")
                    return@setOnClickListener
                }
                val dir = File(gameRoot, name)
                if (dir.exists()) {
                    toast("已存在同名项目")
                    return@setOnClickListener
                }
                dir.mkdirs()
                runCatching { File(dir, "index.html").writeText(starterHtml(name)) }
                d.dismiss()
                switchProject(name)
                showTab(1)
            }
        }
        d.show()
    }

    /** 新项目的空白页：打开就能跑，不是白屏 */
    private fun starterHtml(name: String): String = """
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
<title>$name</title>
<style>
  html,body{margin:0;height:100%;background:#0F1011;color:#EDEDED;
    font:14px/1.7 -apple-system,"PingFang SC","Microsoft YaHei",sans-serif;
    display:flex;align-items:center;justify-content:center}
  .c{text-align:center;padding:28px;max-width:80%}
  h1{font-size:17px;font-weight:500;letter-spacing:.1em;margin:0 0 10px}
  p{color:#9A9A9A;font-size:13px;margin:6px 0}
</style>
</head>
<body>
  <div class="c">
    <h1>$name</h1>
    <p>项目已创建，这是一个独立的目录。</p>
    <p>回到「对话」页说要做什么，AI 会直接写进这个目录。</p>
  </div>
</body>
</html>
""".trimIndent()

    // ==================== 预览自动刷新 ====================

    private fun dirStamp(dir: File): Long {
        var st = dir.lastModified()
        for (f in dir.listFiles().orEmpty()) {
            st = st * 31 + f.lastModified() + f.name.hashCode()
        }
        return st
    }

    private var previewDirty = false
    private val previewReloadTask = Runnable { runCatching { reloadGame() } }

    /** 切到预览页时调用：项目换了或文件变了才重载，不再每次进来都白一下 */
    private fun ensurePreviewFresh(immediate: Boolean = false) {
        val dir = File(gameRoot, currentGame)
        // Maker 工程没有「有 index.html 才算能跑」这回事：它的预览是 GeckoView
        // 打开 maker.taptap.cn 控制台。所以单独走一条，别被下面那句拦住。
        if (isMakerProject()) {
            if (activeTab != 1) {
                previewDirty = true
                return
            }
            lastLoadedGame = currentGame
            lastLoadedStamp = dirStamp(dir)
            reloadGame()
            return
        }
        val idx = File(dir, "index.html")
        val stamp = dirStamp(dir)
        if (currentGame == lastLoadedGame && stamp == lastLoadedStamp && !previewDirty) return
        if (!idx.exists()) {
            lastLoadedGame = currentGame
            lastLoadedStamp = stamp
            addSystemLine("「$currentGame」还没有 index.html，先让 AI 构建一次")
            return
        }
        // 人不在预览页就别重载：藏起来的 WebView 一样会白屏重载，纯属浪费。
        // 记个脏标记，切回预览页时再刷。
        if (activeTab != 1) {
            previewDirty = true
            return
        }
        lastLoadedGame = currentGame
        lastLoadedStamp = stamp
        schedulePreviewReload(immediate)
    }

    /**
     * 预览重载去抖。
     *
     * AI 一轮里可能写好几个文件，每写一次就 reload() 的话，画面就是
     * 「白一下、再白一下」——看着疯狂闪屏。合并成「最后一次改动后 900ms 再刷」，
     * 一轮最多闪一次，而且刷出来的是最终结果，正好就是「构建完能在预览里看见」。
     */
    private fun schedulePreviewReload(immediate: Boolean = false) {
        previewDirty = false
        main.removeCallbacks(previewReloadTask)
        // 切到预览页要立刻看到东西；去抖只用于「AI 边写边刷」这种连发场景
        if (immediate) main.post(previewReloadTask)
        else main.postDelayed(previewReloadTask, 900)
    }

    private fun attachScreenshot() {
        Thread {
            val img = snapshotCss(720, 72)
            main.post {
                if (img == null) {
                    toast("截图失败，等画面加载完再试")
                } else {
                    pendingShots += img
                    updateAttachInfo()
                    toast("已附上当前画面")
                }
            }
        }.start()
    }

    /** 把任意格式图片压成适合喂给模型的 JPEG */
    private fun shrinkToJpeg(raw: ByteArray, maxW: Int, quality: Int): ByteArray? = runCatching {
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
        val w = bmp.width
        val h = bmp.height
        val out = if (w > maxW) {
            val nh = (h.toFloat() * maxW / w).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bmp, maxW, nh, true)
        } else bmp
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        if (out !== bmp) out.recycle()
        bmp.recycle()
        bos.toByteArray()
    }.getOrNull()

    /**
     * 按项目类型把预览切到对应通道。
     *
     * H5    -> 原来的 WebView（appassets 拦截到项目目录）
     * Maker -> GeckoView 打开 maker.taptap.cn 控制台
     * 判型统一走 projKind()，所以手动覆盖（.hexora-kind）在这里自动生效。
     */
    private fun loadPreviewFor(dir: File, id: String) {
        if (projKind(id) == "maker") {
            showMakerPreview(dir, id)
            return
        }
        val idx = File(dir, "index.html")
        if (idx.isFile) {
            hideMakerPreview()
            previewLabel.text = "项目：$id · H5 ⟳"
            web.loadUrl("https://appassets.androidplatform.net/games/$id/index.html")
        } else {
            hideMakerPreview()
            previewLabel.text = "项目：$id · H5 ⟳"
            web.loadDataWithBaseURL(
                null,
                "<html><body style='background:#0F1011;color:#9A9A9A;font-family:sans-serif;padding:40px;line-height:1.8'>" +
                    "<h3 style='color:#EDEDED;font-weight:500'>「" + id + "」还没有 index.html</h3>" +
                    "<p>回到「对话」页告诉 AI 你想做什么，它会构建到这个目录：</p>" +
                    "<p style='color:#4FCFB4;word-break:break-all'>" + dir.absolutePath + "</p>" +
                    "</body></html>",
                "text/html", "utf-8", null
            )
        }
    }

    /**
     * 预览模式手动切换（点预览页标题就切）。
     *
     * 自动判型（H5 / Maker）总有猜错的时候：与其来回改代码，不如让你一点就改。
     * 结果写进项目里的 .hexora-kind（h5 / maker），之后一直按它走。
     */
    private fun toggleProjKind() {
        val toMaker = projKind(currentGame) != "maker"
        val next = if (toMaker) "maker" else "h5"
        runCatching { File(File(gameRoot, currentGame), ".hexora-kind").writeText(next) }
        lastLoadedGame = ""
        refreshHeader()
        loadPreviewFor(File(gameRoot, currentGame), currentGame)
        toast("已把「$currentGame」按 " + (if (toMaker) "Maker" else "H5") + " 预览（再点标题可切回）")
    }


    // ==================== Maker 预览（GeckoView 通道） ====================

    private var makerView: GeckoView? = null
    private var makerLoadedUrl: String? = null

    /**
     * Maker（UrhoX）工程的预览。
     *
     * 用 GeckoView 打开 maker.taptap.cn（官方控制台，?tab=preview 就是实时预览页）。
     * 系统 WebView 打不开是内核能力问题，不是网络问题，详见 MakerPreview 的注释。
     */
    private fun showMakerPreview(dir: File, id: String) {
        if (!::previewWrapHolder.isInitialized) return
        val existing = makerView
        val gv: GeckoView
        if (existing != null) {
            gv = existing
        } else {
            val made = runCatching { MakerPreview.newView(this) }
                .onFailure { android.util.Log.w("MakerPreview", "GeckoView 初始化失败", it) }
                .getOrNull()
            if (made == null) {
                // 内核起不来也别白屏，给一句人话
                web.visibleIf(true)
                web.loadDataWithBaseURL(
                    null,
                    "<html><body style='background:#0F1011;color:#9A9A9A;font-family:sans-serif;padding:36px;line-height:1.9'>" +
                        "<h3 style='color:#EDEDED;font-weight:500'>Maker 预览内核启动失败</h3>" +
                        "<p>GeckoView 没能初始化（多半是这台机器的 ABI 不是 arm64-v8a）。</p>" +
                        "<p>仍然可以回「对话」页说一句 <b style='color:#4FCFB4'>提交构建</b>，用构建产出的链接 / 二维码看效果。</p>" +
                        "</body></html>",
                    "text/html", "utf-8", null
                )
                return
            }
            makerView = made
            previewWrapHolder.addView(made, FrameLayout.LayoutParams(-1, -1))
            gv = made
        }
        web.visibleIf(false)
        gv.visibleIf(true)
        gv.bringToFront()
        val url = makerConsoleUrl(dir)
        if (makerLoadedUrl != url) {
            makerLoadedUrl = url
            runCatching { gv.session?.loadUri(url) }
        } else {
            runCatching { gv.session?.reload() }
        }
        previewLabel.text = "项目：$id · Maker ⟳"
    }

    /** 切回 H5 工程时把 GeckoView 收起来，别让它盖在网页上面 */
    private fun hideMakerPreview() {
        makerView?.visibleIf(false)
        web.visibleIf(true)
    }

    /**
     * 预览地址。
     *
     * 能从工程的 .project/project.json 里认出 appId，就直接深链到
     * maker.taptap.cn/app/<id>?tab=preview（打开即预览）；认不出就退回控制台首页，
     * 用户在里面自己点进项目 → 预览，同样能用。
     */
    private fun makerConsoleUrl(dir: File): String {
        val id = runCatching {
            val txt = File(dir, ".project/project.json").readText()
            Regex("\"[A-Za-z_]*[Aa]pp_?[Ii]d\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"")
                .find(txt)?.groupValues?.get(1)
        }.getOrNull()
        return if (id.isNullOrBlank()) MakerPreview.CONSOLE
        else "https://maker.taptap.cn/app/$id?tab=preview"
    }


    // ==================== 预览页 ====================

    private lateinit var previewLabel: TextView

    private fun buildPreviewPage(): LinearLayout {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.bg)
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(12), dp(8))
            setBackgroundColor(pal.navBg)
        }
        previewLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(pal.text)
            typeface = Typeface.DEFAULT_BOLD
            // 标题上的 ⟳ 不是装饰：点一下就把预览切到另一种模式（H5 <-> Maker）
            isClickable = true
            setOnClickListener { toggleProjKind() }
        }
        bar.addView(previewLabel, LinearLayout.LayoutParams(0, -2, 1f))
        // 工具栏一律用**手绘图标**，不再用文字 chip。
        // 以前是「重载 / 🔊 有声 / 发给 AI / 项目」四个文字块，中间还夹一个 emoji ——
        // 右边明明是全屏那种手绘图标，两边画风是断的（用户反复提过：不要文字、不要 emoji）。
        // 按钮收到 32dp 是为了塞得下：一行 6 个，再宽就把左边的项目名挤没了。
        bar.addView(iconButton(this, pal, "refresh", sizeDp = 32, marginStartDp = 6) {
            reloadGame()
            toast("已重载画面")
        })
        // 静音开关：**图标本身就说明状态**（喇叭+声波 = 有声；喇叭+叉 = 已静音），
        // 不再靠文字切换 —— 以前标签显示动作，用户点完看着字变了也不知道到底静音没有。
        previewMuteChip = ImageView(this).apply {
            setImageDrawable(LineIcon("volume", pal.sub, 1.8f))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = pressable(roundCard(this@MainActivity, pal.cardAlt, pal.border, 10), 0x14000000)
            contentDescription = "预览静音开关"
            setOnClickListener {
                userMutePreview = !userMutePreview
                setImageDrawable(
                    LineIcon(
                        if (userMutePreview) "mute" else "volume",
                        if (userMutePreview) pal.accent else pal.sub,
                        1.8f
                    )
                )
                applyPreviewMute(userMutePreview || activeTab != 1)
                toast(if (userMutePreview) "预览已静音（切页、重载也保持）" else "预览恢复发声")
            }
        }
        bar.addView(previewMuteChip, LinearLayout.LayoutParams(dp(32), dp(32)).apply { leftMargin = dp(5) })
        // 缩放：手机上的游戏画面常常偏小或偏大，给 ± 两档
        bar.addView(iconButton(this, pal, "zoom_out", sizeDp = 32, marginStartDp = 5) { zoomPreview(-1) })
        bar.addView(iconButton(this, pal, "zoom_in", sizeDp = 32, marginStartDp = 4) { zoomPreview(1) })
        // 相机 = 把当前画面发给 AI（原来那块「发给 AI」文字）
        bar.addView(iconButton(this, pal, "camera", sizeDp = 32, marginStartDp = 5) { attachScreenshot() })
        // 手绘全屏图标：把预览容器整个搬进全屏 Dialog（同一个 WebView 实例，游戏状态不丢）
        bar.addView(iconButton(this, pal, "fullscreen", sizeDp = 32, marginStartDp = 5) { showFullPreview() })

        @SuppressLint("SetJavaScriptEnabled")
        val w = WebView(this).apply { setBackgroundColor(pal.bg) }
        web = w
        val wrap = FrameLayout(this).apply { addView(w, FrameLayout.LayoutParams(-1, -1)) }

        page.addView(bar, LinearLayout.LayoutParams(-1, -2))
        page.addView(View(this).apply { setBackgroundColor(pal.border) },
            LinearLayout.LayoutParams(-1, dp(1)))
        page.addView(wrap, LinearLayout.LayoutParams(-1, 0, 1f))
        previewWrapHolder = wrap
        previewSlot = page
        return page
    }

    /**
     * 全屏看预览。
     * 关键：WebView 只能有一个父容器，所以这里是「同一个实例搬家」，
     * 而不是新建第二个 WebView —— 后者既丢游戏运行状态，又因为没有 asset 拦截而白屏。
     * manifest 已声明 configChanges=orientation|screenSize，横屏不会重建 Activity。
     */
    private fun showFullPreview() {
        if (fullDlg != null) return
        if (!::previewWrapHolder.isInitialized) return
        val host = previewWrapHolder.parent as? ViewGroup ?: return
        host.removeView(previewWrapHolder)

        val box = FrameLayout(this).apply { setBackgroundColor(pal.bg) }
        box.addView(previewWrapHolder, FrameLayout.LayoutParams(-1, -1))
        // 真全屏：没有标题栏，预览容器直接铺满整屏；屏内只留一个悬浮「缩小」按钮
        box.addView(chipOf(this, pal, "缩小", false).apply {
            alpha = 0.82f
            setOnClickListener { fullDlg?.dismiss() }
        }, FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(16)
            rightMargin = dp(14)
        })

        val dlg = Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen)
        dlg.setContentView(box)
        dlg.setOnDismissListener { restorePreview() }
        fullDlg = dlg
        dlg.show()
        dlg.window?.setLayout(-1, -1)
        // 真全屏：内容铺到状态栏/导航栏底下（LAYOUT_*），再把系统栏藏掉；不旋转屏幕
        @Suppress("DEPRECATION")
        dlg.window?.decorView?.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    /** 退出全屏：把预览容器搬回预览页原位 */
    private fun restorePreview() {
        fullDlg = null
        if (!::previewWrapHolder.isInitialized) return
        (previewWrapHolder.parent as? ViewGroup)?.removeView(previewWrapHolder)
        if (::previewSlot.isInitialized &&
            previewSlot.indexOfChild(previewWrapHolder) < 0
        ) {
            previewSlot.addView(previewWrapHolder, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    // ==================== 工作区（素材 / 文档 / 技能 / 代码） ====================
    //
    // 这是「常驻资源面板」，不是一次性选择器：
    // 列的是磁盘上真实存在的文件，重启 / 覆盖安装后还在，可预览、可多选、可反复加入对话。

    private var wsDlg: Dialog? = null
    private var wsTab = "media"        // media / doc / style / skill / code
    private var wsKind = "image"       // 素材页内：image / audio / video
    private var wsGrid = false         // 列表 / 网格
    private var wsSort = 0             // 0 名称 / 1 大小 / 2 时间
    private var wsSearch = ""
    private val wsSel = HashSet<String>()   // 勾选的相对路径
    private val wsOpen = HashSet<String>()  // 已展开的目录
    private var wsBody: LinearLayout? = null
    private var wsTabsRow: LinearLayout? = null
    private var wsBottom: LinearLayout? = null
    private var wsTitle: TextView? = null

    private fun openWorkspace(tab: String) {
        wsTab = tab
        if (wsDlg != null) {
            renderWs()
            return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.bg)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(12), dp(8))
            setBackgroundColor(pal.navBg)
        }
        wsTitle = TextView(this).apply {
            textSize = 14f
            setTextColor(pal.text)
            typeface = MEDIUM
        }
        bar.addView(wsTitle, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(ghostBtnOf(this, pal, "关闭").apply { setOnClickListener { wsDlg?.dismiss() } })
        box.addView(bar, LinearLayout.LayoutParams(-1, -2))

        wsTabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(4))
        }
        box.addView(wsTabsRow, LinearLayout.LayoutParams(-1, -2))

        wsBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(10))
        }
        box.addView(ScrollView(this).apply { addView(wsBody) }, LinearLayout.LayoutParams(-1, 0, 1f))

        wsBottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(12))
            setBackgroundColor(pal.navBg)
        }
        box.addView(wsBottom, LinearLayout.LayoutParams(-1, -2))

        val dlg = Dialog(this)
        dlg.setContentView(box)
        dlg.setOnDismissListener {
            wsDlg = null
            wsSel.clear()
        }
        wsDlg = dlg
        dlg.show()
        dlg.window?.setLayout(-1, -1)
        renderWs()
    }

    private fun renderWs() {
        val body = wsBody ?: return
        val tabs = wsTabsRow ?: return
        wsTitle?.text = "工作区 · $currentGame"

        tabs.removeAllViews()
        for ((t, label) in listOf(
            "media" to "素材", "doc" to "文档", "style" to "风格", "skill" to "技能", "code" to "代码"
        )) {
            tabs.addView(
                chipOf(this, pal, label, t == wsTab).apply {
                    setOnClickListener {
                        wsTab = t
                        wsSel.clear()
                        renderWs()
                    }
                },
                LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) }
            )
        }

        body.removeAllViews()
        when (wsTab) {
            "media" -> renderMediaTab(body)
            "doc" -> renderDocTab(body, "doc")
            "style" -> renderUiTab(body)
            "skill" -> renderDocTab(body, "skill")
            else -> renderCodeTab(body)
        }
        renderWsBottom()
    }

    // ---------- UI 风格页（预制主题，一键换肤） ----------

    /** 读 assets 里的 kit.json 索引 */
    private fun kitIndexJson(): JSONObject? = runCatching {
        JSONObject(assets.open("ui-kits/kit.json").use { String(it.readBytes(), Charsets.UTF_8) })
    }.getOrNull()

    /** 当前项目用的是哪套皮肤（读 <项目>/ui/.kit.json） */
    private fun currentKitId(): String = runCatching {
        JSONObject(File(projDir(), "ui/.kit.json").readText()).optString("id")
    }.getOrDefault("")

    /** 把 assets 下的一个目录（递归）复制到 dst，返回写入的文件数 */
    private fun copyAssetTree(assetPath: String, dst: File): Int {
        val kids = runCatching { assets.list(assetPath) }.getOrNull() ?: return 0
        if (kids.isEmpty()) {
            dst.parentFile?.mkdirs()
            return runCatching {
                assets.open(assetPath).use { ins -> FileOutputStream(dst).use { ins.copyTo(it) } }
                1
            }.getOrDefault(0)
        }
        var n = 0
        for (c in kids) n += copyAssetTree("$assetPath/$c", File(dst, c))
        return n
    }

    /** 落地一套主题到当前项目 ui/：与桥里的 maker_ui_apply_kit 走同一套目录结构 */
    private fun applyUiKit(id: String): Int {
        val dst = File(projDir(), "ui")
        val n = copyAssetTree("ui-kits/$id", dst) + copyAssetTree("ui-kits/shared", dst)
        runCatching {
            File(dst, ".kit.json").writeText("""{"id":"$id","at":${System.currentTimeMillis()}}""")
        }
        return n
    }

    private fun renderUiTab(body: LinearLayout) {
        val idx = kitIndexJson()
        if (idx == null) {
            body.addView(emptyHint("没有找到预制 UI 风格包（assets/ui-kits 缺失，请更新到本版本）"))
            return
        }
        val cur = currentKitId()
        body.addView(
            emptyHint(
                "每个项目一套皮肤。换肤只换 ui/theme.css，游戏结构一行不用改；" +
                    "AI 也会按题材自动挑（提示词里已写死纪律）。"
            )
        )
        val arr = idx.optJSONArray("kits") ?: org.json.JSONArray()
        for (i in 0 until arr.length()) {
            val k = arr.optJSONObject(i) ?: continue
            val id = k.optString("id")
            if (id.isBlank()) continue
            val on = id == cur
            val scenes = k.optJSONArray("scenes")
            val sceneTxt =
                if (scenes == null) ""
                else (0 until minOf(scenes.length(), 6)).joinToString(" / ") { scenes.optString(it) }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundCard(this@MainActivity, if (on) pal.cardAlt else pal.card, pal.border, 12)
                isClickable = true
                setOnClickListener {
                    val n = runCatching { applyUiKit(id) }.getOrDefault(0)
                    if (n <= 0) {
                        toast("换肤失败：写入 ui/ 出错")
                        return@setOnClickListener
                    }
                    runCatching { reloadGame() }
                    toast("已应用「${k.optString("name")}」· 写入 $n 个文件")
                    renderWs()
                }
            }
            card.addView(TextView(this).apply {
                text = k.optString("name") + if (on) " · 使用中" else ""
                textSize = 14.5f
                setTextColor(if (on) pal.accent else pal.text)
                setTypeface(null, Typeface.BOLD)
            })
            card.addView(TextView(this).apply {
                text = k.optString("mood") + if (sceneTxt.isNotEmpty()) "\n适用：$sceneTxt" else ""
                textSize = 12.5f
                setTextColor(pal.sub)
                setPadding(0, dp(3), 0, 0)
            })
            body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        body.addView(emptyHint("换肤后页面里应引用 ui/theme.css 与 ui/components.css，颜色写 var(--hx-*)。"))
    }

    // ---------- 素材页 ----------

    private fun renderMediaTab(body: LinearLayout) {
        val kinds = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for ((k, label) in listOf("image" to "图片", "audio" to "音频", "video" to "视频")) {
            kinds.addView(
                chipOf(this, pal, label, k == wsKind).apply {
                    setOnClickListener {
                        wsKind = k
                        wsSel.clear()
                        renderWs()
                    }
                },
                LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) }
            )
        }
        body.addView(kinds, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tools.addView(actionChip("upload", "上传") { pickMedia("media") })
        tools.addView(
            actionChip("search", sortLabel()) {
                wsSort = (wsSort + 1) % 3
                renderWs()
            },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) }
        )
        tools.addView(
            actionChip(if (wsGrid) "list" else "grid", if (wsGrid) "列表" else "网格") {
                wsGrid = !wsGrid
                renderWs()
            },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) }
        )
        body.addView(tools, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val files = listMedia(wsKind)
        if (files.isEmpty()) {
            body.addView(emptyHint("还没有素材。点「上传」加图片 / 音频 / 视频；AI 生成的素材也会自动进这里（工作区按项目独立，互不干扰）"))
            return
        }
        if (wsGrid) {
            body.addView(mediaGrid(files))
        } else {
            for (f in files) {
                body.addView(mediaRow(f), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
        }
    }

    private fun sortLabel() = when (wsSort) {
        1 -> "按大小"
        2 -> "按时间"
        else -> "按名称"
    }

    private fun emptyHint(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 12.5f
        setTextColor(pal.faint)
        setPadding(dp(4), dp(18), dp(4), dp(18))
    }

    /** 工具条按钮：手绘图标 + 文字 */
    private fun actionChip(kind: String, label: String, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(12), dp(7))
            background = pressable(roundCard(this@MainActivity, pal.cardAlt, pal.border, 10), 0x14000000)
            isClickable = true
            setOnClickListener { onClick() }
        }
        val iv = ImageView(this).apply {
            setImageDrawable(LineIcon(kind, pal.sub, dp(2).toFloat()))
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        row.addView(iv, LinearLayout.LayoutParams(dp(26), dp(26)))
        row.addView(TextView(this).apply {
            text = label
            textSize = 12.5f
            setTextColor(pal.text)
        })
        return row
    }

    /** 素材行：勾选框 + 缩略图 + 文件名/大小 + ⋮；点行 = 预览 */
    private fun mediaRow(f: File): LinearLayout {
        val kind = kindOfFile(f.name)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = pressable(roundCard(this@MainActivity, pal.card, pal.border, 12), 0x14000000)
            isClickable = true
            setOnClickListener { previewFile(f, kind) }
            // 长按直接存到本地（相册 / 音乐 / 影视），不用翻⋯菜单
            setOnLongClickListener { saveToLocal(f); true }
        }
        row.addView(selBox(f.relativeTo(gameRoot).path))
        row.addView(
            thumbView(f, 44),
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(8) }
        )
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = f.name
            textSize = 13f
            setTextColor(pal.text)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        col.addView(TextView(this).apply {
            text = fmtSize(f.length())
            textSize = 11f
            setTextColor(pal.faint)
        })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
        row.addView(moreBtn(f, kind))
        return row
    }

    private fun mediaGrid(files: List<File>): LinearLayout {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var line: LinearLayout? = null
        files.forEachIndexed { i, f ->
            if (i % 3 == 0) {
                line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                wrap.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            }
            val kind = kindOfFile(f.name)
            val rel = f.relativeTo(gameRoot).path
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { previewFile(f, kind) }
                setOnLongClickListener {
                    if (wsSel.contains(rel)) wsSel.remove(rel) else wsSel.add(rel)
                    renderWs()
                    true
                }
            }
            cell.addView(thumbView(f, 92), LinearLayout.LayoutParams(-1, dp(92)))
            cell.addView(TextView(this).apply {
                text = f.name
                textSize = 11f
                setTextColor(if (wsSel.contains(rel)) pal.accent else pal.sub)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setPadding(dp(2), dp(4), dp(2), 0)
            })
            line?.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        }
        return wrap
    }

    private fun selBox(rel: String): CheckBox = CheckBox(this).apply {
        isChecked = wsSel.contains(rel)
        buttonTintList = android.content.res.ColorStateList.valueOf(pal.accent)
        setOnCheckedChangeListener { _, b ->
            if (b) wsSel.add(rel) else wsSel.remove(rel)
            renderWsBottom()
        }
    }

    private fun moreBtn(f: File, kind: String): ImageView = ImageView(this).apply {
        setImageDrawable(LineIcon("more", pal.faint, dp(2).toFloat()))
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setOnClickListener {
            val rel = f.relativeTo(gameRoot).path
            HxDialog.Builder(themed(), pal)
                .setTitle(f.name)
                .setItems(arrayOf("预览 / 播放", "加入对话", "保存到本地", "复制路径", "删除")) { i ->
                    when (i) {
                        0 -> previewFile(f, kind)
                        1 -> {
                            attached += Attach(f.name, rel, kind)
                            toast("已加入待发送区：${f.name}")
                            updateAttachInfo()
                        }
                        2 -> saveToLocal(f)
                        3 -> copyToClipboard(rel)
                        else -> confirmDelete(f)
                    }
                }
                .show()
        }
    }

    /**
     * 保存到本地（相册 / 音乐 / 影视 / 下载）。
     *
     * 走 MediaStore 而不是自己拼 /sdcard 路径：Android 10+ 上一条权限都不用申请，
     * 而且文件会立刻出现在系统相册 / 文件管理器里 —— 用户说的"保存到本地"就是这个。
     */
    private fun saveToLocal(f: File) {
        if (!f.isFile) {
            toast("文件已经不在了")
            return
        }
        Thread {
            val res = runCatching { saveViaMediaStore(f) }
            val where = res.getOrNull()
            main.post {
                if (where != null) {
                    toast("已保存到本地：$where")
                    return@post
                }
                // 兜底：MediaStore 写不进去（老系统 / 存储异常）就丢进 App 自己的外部目录，
                // 至少文件不会丢，并把路径告诉用户（可长按复制）。
                val dst = runCatching {
                    File(getExternalFilesDir(null), "saved").apply { mkdirs() }
                }.getOrNull()
                val copy = dst?.let { runCatching { File(it, f.name).also { o -> f.copyTo(o, true) } }.getOrNull() }
                if (copy != null) {
                    toast("系统媒体库写入失败，已存到：${copy.absolutePath}")
                    copyToClipboard(copy.absolutePath)
                } else {
                    toast("保存失败：${res.exceptionOrNull()?.message ?: "未知原因"}")
                }
            }
        }.start()
    }

    /** 按扩展名挑 MediaStore 集合与目录，返回保存后的相对路径；失败返回 null */
    private fun saveViaMediaStore(f: File): String? {
        val ext = f.extension.lowercase()
        val images = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
        val audios = setOf("mp3", "wav", "ogg", "m4a", "aac", "flac")
        val videos = setOf("mp4", "mov", "webm", "mkv")
        val bucket: Pair<android.net.Uri, String> = when {
            ext in images ->
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI to (Environment.DIRECTORY_PICTURES + "/Hexora")
            ext in audios ->
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to (Environment.DIRECTORY_MUSIC + "/Hexora")
            ext in videos ->
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI to (Environment.DIRECTORY_MOVIES + "/Hexora")
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                MediaStore.Downloads.EXTERNAL_CONTENT_URI to (Environment.DIRECTORY_DOWNLOADS + "/Hexora")
            else -> return null
        }
        val mime = runCatching {
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        }.getOrNull() ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, bucket.second)
            }
        }
        val uri = contentResolver.insert(bucket.first, values) ?: return null
        contentResolver.openOutputStream(uri)?.use { out ->
            f.inputStream().use { it.copyTo(out) }
        } ?: return null
        return bucket.second + "/" + f.name
    }

    private fun thumbView(f: File, sizeDp: Int): ImageView {
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundCard(this@MainActivity, pal.cardAlt, pal.border, 8)
        }
        val k = kindOfFile(f.name)
        if (k == "image") {
            val bmp = runCatching { decodeSmall(f, dp(sizeDp)) }.getOrNull()
            if (bmp != null) {
                iv.setImageBitmap(bmp)
            } else {
                iv.setImageDrawable(LineIcon("image", pal.faint, dp(2).toFloat()))
                iv.setPadding(dp(10), dp(10), dp(10), dp(10))
            }
        } else {
            iv.setPadding(dp(11), dp(11), dp(11), dp(11))
            iv.setImageDrawable(LineIcon(k, pal.faint, dp(2).toFloat()))
        }
        return iv
    }

    private fun decodeSmall(f: File, target: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= target && o.outHeight / (s * 2) >= target) s *= 2
        return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
    }

    // ---------- 文档 / 技能页 ----------

    private fun renderDocTab(body: LinearLayout, kind: String) {
        val dir = kindDir(kind)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(18), dp(14), dp(14))
            background = pressable(roundCard(this@MainActivity, pal.cardAlt, pal.border, 12), 0x14000000)
            isClickable = true
            setOnClickListener { pickMedia(kind) }
        }
        val ic = ImageView(this).apply {
            setImageDrawable(LineIcon("upload", pal.sub, dp(2).toFloat()))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        card.addView(ic, LinearLayout.LayoutParams(dp(44), dp(44)))
        card.addView(TextView(this).apply {
            text = if (kind == "skill") "点击上传技能（供 AI 读取）" else "点击上传文档"
            textSize = 13.5f
            setTextColor(pal.text)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = if (kind == "skill")
                "支持 .txt .md .json .js .lua 等 · 上传到 _skills/"
            else
                "支持 .txt .json .md .xml .lua .docx .pdf .zip · 上传到 _uploads/doc/"
            textSize = 11.5f
            setTextColor(pal.faint)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        })
        body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tools.addView(actionChip("search", sortLabel()) {
            wsSort = (wsSort + 1) % 3
            renderWs()
        })
        tools.addView(
            actionChip(if (wsGrid) "list" else "grid", if (wsGrid) "列表" else "网格") {
                wsGrid = !wsGrid
                renderWs()
            },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) }
        )
        body.addView(tools, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val files = sortFiles(dir.listFiles()?.filter { it.isFile } ?: emptyList())
        body.addView(TextView(this).apply {
            text = (if (kind == "skill") "技能" else "项目文档") + "（${files.size}）  ${dir.name}/"
            textSize = 12f
            setTextColor(pal.faint)
            setPadding(dp(4), 0, dp(4), dp(8))
        })
        if (files.isEmpty()) {
            body.addView(emptyHint(if (kind == "skill") "还没有技能文件" else "还没有文档"))
            return
        }
        for (f in files) {
            body.addView(docRow(f, kind), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun docRow(f: File, kind: String): LinearLayout {
        val rel = f.relativeTo(gameRoot).path
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = pressable(roundCard(this@MainActivity, pal.card, pal.border, 12), 0x14000000)
            isClickable = true
        }
        row.addView(selBox(rel))
        val iv = ImageView(this).apply {
            setImageDrawable(LineIcon(kindOfFile(f.name), pal.sub, dp(2).toFloat()))
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        row.addView(iv, LinearLayout.LayoutParams(dp(26), dp(26)).apply { leftMargin = dp(6) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = f.name
            textSize = 13f
            setTextColor(pal.text)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        col.addView(TextView(this).apply {
            text = fmtSize(f.length())
            textSize = 11f
            setTextColor(pal.faint)
        })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
        row.addView(moreBtn(f, kind))
        row.setOnClickListener { openFileEditor(f, f.parentFile ?: gameRoot) }
        return row
    }

    // ---------- 代码页 ----------

    private fun renderCodeTab(body: LinearLayout) {
        val dir = File(gameRoot, currentGame)
        val search = hxInput(this, pal, "搜索文件名", wsSearch).apply {
            textSize = 13f
            setPadding(dp(12), dp(9), dp(12), dp(9))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val now = s?.toString().orEmpty()
                    if (now == wsSearch) return
                    wsSearch = now
                    renderWs()
                }

                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        body.addView(search, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val files = dir.walkTopDown().filter { it.isFile }
            .filterNot { it.path.contains("/_shots/") }
            .filter { wsSearch.isBlank() || it.relativeTo(dir).path.contains(wsSearch, true) }
            .sortedBy { it.relativeTo(dir).path }
            .take(300).toList()

        body.addView(TextView(this).apply {
            text = "${dir.name}（${files.size} 个文件）"
            textSize = 12f
            setTextColor(pal.faint)
            setPadding(dp(4), 0, dp(4), dp(8))
        })
        if (files.isEmpty()) {
            body.addView(emptyHint("没有匹配的文件"))
            return
        }

        files.groupBy { it.relativeTo(dir).path.substringBeforeLast('/', "") }
            .toSortedMap()
            .forEach { (folder, list) ->
                val key = folder.ifBlank { "." }
                val head = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(6), dp(10), dp(6), dp(8))
                    isClickable = true
                    setOnClickListener {
                        if (wsOpen.contains(key)) wsOpen.remove(key) else wsOpen.add(key)
                        renderWs()
                    }
                }
                val fi = ImageView(this).apply {
                    setImageDrawable(LineIcon("folder_sm", pal.accent, dp(2).toFloat()))
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                }
                head.addView(fi, LinearLayout.LayoutParams(dp(24), dp(24)))
                head.addView(TextView(this).apply {
                    text = (if (folder.isBlank()) "根目录" else folder) + "（${list.size}）"
                    textSize = 13.5f
                    setTextColor(pal.text)
                    typeface = MEDIUM
                    setPadding(dp(6), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                head.addView(TextView(this).apply {
                    text = if (wsOpen.contains(key)) "收起" else "展开"
                    textSize = 11.5f
                    setTextColor(pal.faint)
                })
                body.addView(head)

                if (wsOpen.contains(key)) {
                    for (f in list) {
                        val rel = f.relativeTo(dir).path
                        val row = fileRow(
                            name = rel,
                            kind = kindOfFile(rel),
                            onOpen = { openFileEditor(f, dir) },
                            onAdd = {
                                attached += Attach(rel, rel, "code")
                                toast("已加入待发送区：$rel")
                                updateAttachInfo()
                            }
                        )
                        body.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                            leftMargin = dp(14)
                            bottomMargin = dp(6)
                        })
                    }
                }
            }
    }

    // ---------- 底部「加入对话」 ----------

    /**
     * 删除工作区里勾选的文件。
     * 用户反馈：工作区的素材放进去就删不掉了 —— 这里跟「加入对话」同一个勾选入口，
     * 勾上点删除即可（图片/音频/文档/技能/代码都一样）。
     */
    private fun confirmDeleteSelected() {
        val rels = wsSel.toList()
        if (rels.isEmpty()) return
        HxDialog.Builder(themed(), pal)
            .setTitle("删除 ${rels.size} 个文件？")
            .setMessage(
                rels.take(12).joinToString("\n") +
                    (if (rels.size > 12) "\n…共 ${rels.size} 个" else "") +
                    "\n\n从磁盘删除，不可恢复。"
            )
            .setDanger(true)
            .setPositiveButton("删除") { ->
                Thread {
                    var n = 0
                    for (rel in rels) {
                        val f = File(gameRoot, rel)
                        val ok = runCatching {
                            if (f.isDirectory) f.deleteRecursively() else f.delete()
                        }.getOrDefault(false)
                        if (ok) n++
                    }
                    val cnt = n
                    main.post {
                        wsSel.clear()
                        android.widget.Toast.makeText(
                            this, "已删除 $cnt 个文件", android.widget.Toast.LENGTH_SHORT
                        ).show()
                        renderWs()
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renderWsBottom() {
        val b = wsBottom ?: return
        b.removeAllViews()
        val total = wsSel.size
        b.addView(TextView(this).apply {
            text = if (total == 0) "勾选文件后可加入对话" else "已选 $total 项"
            textSize = 12f
            setTextColor(pal.faint)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (total > 0) {
            val delBtn = ghostBtnOf(this, pal, "删除 ($total)")
            delBtn.setOnClickListener { confirmDeleteSelected() }
            b.addView(delBtn, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
            b.addView(primaryBtnOf(this, pal, "加入对话 ($total)").apply {
                setOnClickListener { queueSelected() }
            })
        }
    }

    /** 勾选的文件 → 加入对话（图片同时进多模态） */
    private fun queueSelected() {
        val rels = wsSel.toList()
        if (rels.isEmpty()) return
        Thread {
            val out = mutableListOf<Attach>()
            for (rel in rels) {
                val kind = when {
                    // rel 是相对工程根的路径（工作区现在在项目里，所以是 <项目>/_uploads/…）
                    rel.contains("_skills") -> "skill"
                    rel.contains("_uploads/doc") -> "doc"
                    rel.contains("_uploads/media") -> kindOfFile(rel)
                    // 生成物（<项目>/assets/…）也按扩展名归类，否则会被当成"代码"，
                    // 图片就进不了多模态、音频也点不出试听。
                    rel.contains("/assets/") -> kindOfFile(rel)
                    else -> "code"
                }
                val a = attachFromFile(File(gameRoot, rel), rel, kind) ?: continue
                out += a
            }
            main.post {
                for (a in out) {
                    if (a.kind == "image") shotBytes[a.rel]?.let { pendingShots += it }
                    queued += a
                }
                wsSel.clear()
                toast("已加入对话 ${out.size} 项")
                updateAttachInfo()
                renderWs()
            }
        }.start()
    }

    private fun attachFromFile(f: File, rel: String, kind: String): Attach? = runCatching {
        if (!f.isFile) return@runCatching null
        val bytes = f.readBytes()
        val text = if (kind == "image" || kind == "audio" || kind == "video") ""
        else extractText(f.name, bytes).take(60000)
        if (kind == "image") shrinkToJpeg(bytes, 1024, 80)?.let { shotBytes[rel] = it }
        Attach(f.name, rel, kind, text)
    }.getOrNull()

    // ---------- 工具 ----------

    /** 工作区素材 / 文档的落点：**当前项目**里（工作区是项目独立的） */
    private fun kindDir(kind: String): File = when (kind) {
        "doc" -> File(projDir(), "_uploads/doc")
        "skill" -> File(gameRoot, "_skills")
        else -> File(projDir(), "_uploads/media")
    }

    /** 当前项目目录（工程根/<项目名>）：游戏文件和工作区都在它下面 */
    private fun projDir(): File = File(gameRoot, currentGame).apply { mkdirs() }

    /**
     * 老版本把工作区素材放在「工程根/_uploads」（所有项目混在一起）。
     * 工作区改成项目独立后，把这些遗留文件也复制一份进当前项目，
     * 免得用户升级后觉得「我的素材不见了」。
     * 只复制不删除：老工程里可能还有代码按工程根那条路径引用它们。
     */
    private fun migrateLegacyWorkspace() {
        val legacyRoot = File(gameRoot, "_uploads")
        if (!legacyRoot.isDirectory) return
        Thread {
            var copied = 0
            for (sub in listOf("media", "doc")) {
                val from = File(legacyRoot, sub)
                if (!from.isDirectory) continue
                val to = File(projDir(), "_uploads/$sub").apply { mkdirs() }
                from.listFiles()?.filter { it.isFile }?.forEach { f ->
                    val dst = File(to, f.name)
                    if (dst.isFile && dst.length() == f.length()) return@forEach
                    runCatching { f.copyTo(dst, true) }.onSuccess { copied++ }
                }
            }
            if (copied > 0) {
                main.post { addSystemLine("已把工程根里的 ${copied} 个旧素材并入当前项目工作区（原文件保留）") }
            }
        }.start()
    }

    private fun listMedia(kind: String): List<File> {
        val media = kindDir("media").listFiles()?.filter { it.isFile } ?: emptyList()
        // 同一份素材如果已经并进工作区，就不重复列一次
        //（生成物本来落在 assets/ 下，工作区里会有它的副本）
        val inWs = media.map { it.name + "|" + it.length() }.toSet()
        val all = mutableListOf<File>()
        all += media
        for (d in generatedAssetDirs()) {
            all += (d.listFiles()?.filter { it.isFile } ?: emptyList())
                .filter { (it.name + "|" + it.length()) !in inWs }
        }
        return sortFiles(all.filter { kindOfFile(it.name) == kind })
    }

    /** 生成物目录：当前游戏下 Maker 的 materialize 落点 */
    private fun generatedAssetDirs(): List<File> {
        val base = File(gameRoot, currentGame)
        return listOf("assets/image", "assets/sprites", "assets/audio", "assets/video", "assets/model")
            .map { File(base, it) }
    }

    private fun sortFiles(l: List<File>): List<File> = when (wsSort) {
        1 -> l.sortedByDescending { it.length() }
        2 -> l.sortedByDescending { it.lastModified() }
        else -> l.sortedBy { it.name.lowercase() }
    }

    private fun fmtSize(n: Long): String = when {
        n < 1024 -> "${n}B"
        n < 1024 * 1024 -> "${n / 1024}KB"
        else -> String.format(java.util.Locale.US, "%.1fMB", n / 1024.0 / 1024.0)
    }

    /** 图片预览用内置全屏；音频 / 视频交给系统播放器 */
    /**
     * 视频：App 内全屏直接播，不再依赖系统里有没有播放器。
     * 点任意处 / 播完 = 关闭。
     */
    private fun playVideoDialog(f: File) {
        val vv = android.widget.VideoView(this)
        val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val wrap = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        runCatching {
            vv.setVideoPath(f.absolutePath)
            vv.setOnPreparedListener {
                it.isLooping = false
                runCatching { vv.start() }
            }
            vv.setOnCompletionListener { runCatching { d.dismiss() } }
            vv.setOnErrorListener { _, _, _ ->
                toast("这个视频播不了（编码不支持？）")
                runCatching { d.dismiss() }
                true
            }
        }.onFailure { toast("播放失败：${it.message}") }
        wrap.addView(vv, FrameLayout.LayoutParams(-1, -1))
        wrap.addView(
            TextView(this).apply {
                text = "点任意处关闭"
                textSize = 12f
                setTextColor(0x99FFFFFF.toInt())
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(24))
            },
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
        )
        wrap.setOnClickListener { runCatching { d.dismiss() } }
        d.setContentView(wrap)
        d.show()
        d.window?.setLayout(-1, -1)
    }

    private fun previewFile(f: File, kind: String) {
        if (kind == "image") {
            val bmp = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
            if (bmp == null) {
                toast("这张图打不开")
                return
            }
            val iv = ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            val wrap = FrameLayout(this).apply {
                setBackgroundColor(0xFF000000.toInt())
                addView(iv, FrameLayout.LayoutParams(-1, -1))
                setOnClickListener { d.dismiss() }
            }
            // 长按图片 = 保存到本地相册（用户明确要的：预览里长按就能存）
            iv.setOnLongClickListener { saveToLocal(f); true }
            d.setContentView(wrap)
            d.show()
            d.window?.setLayout(-1, -1)
        } else if (kind == "audio") {
            // App 内直接放（以前交给系统外部应用，没有能接 mime 的机器点了就是没反应）
            playAudio(f)
        } else if (kind == "video") {
            playVideoDialog(f)
        } else {
            val uri = runCatching {
                FileProvider.getUriForFile(this, "$packageName.files", f)
            }.getOrNull()
            if (uri == null) {
                toast("打不开这个文件")
                return
            }
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeOf(f.name))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { startActivity(i) }.onFailure { toast("没有能打开它的 App") }
        }
    }

    private fun mimeOf(n: String): String = when (kindOfFile(n)) {
        "image" -> "image/*"
        "audio" -> "audio/*"
        "video" -> "video/*"
        else -> "*/*"
    }

    private fun confirmDelete(f: File) {
        HxDialog.Builder(themed(), pal)
            .setDanger(true)
            .setTitle("删除文件")
            .setMessage(f.name)
            .setPositiveButton("删除") { ->
                runCatching { f.delete() }.onSuccess {
                    toast("已删除")
                    renderWs()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 发布页 ====================

    private fun buildPublishPage() {
        pubScroll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(18))
        }

        pubScroll.addView(TextView(this).apply {
            text = "导出与发布"
            textSize = 12f
            setTextColor(pal.faint)
            setPadding(dp(4), 0, 0, dp(8))
        })

        lastExportTv = TextView(this).apply {
            textSize = 12f
            setTextColor(pal.sub)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundCard(this@MainActivity, pal.accentSoft, pal.accentSoft, 14, 0)
            setOnClickListener { showExportActions() }
        }
        pubScroll.addView(lastExportTv, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        val rows = listOf(
            Triple("导出工程包", "打包成 zip，并自动另存到「下载/H5Games」", { exportZip() }),
            Triple("查看当前游戏文件", "列出文件与体积", { showTree() }),
            Triple("文件浏览器", "翻手机目录 / 看文件内容 / 重命名删除（开 Shizuku 后能看受保护目录）", {
                // dp 是 Context 的扩展函数，包成普通 lambda 传进去（::dp 不是 (Int)->Int）
                FileBrowser(this, pal, { dp(it) }, { toast(it) }).show()
            }),
            Triple("工作区目录（免 Shizuku）", safSubtitle(), { askWorkspace() }),
            Triple("Shizuku 提权", shizukuSubtitle(), { askShizuku() }),
            Triple("git 操作", "给项目建版本管理、提交、推到 GitHub", { showGitMenu() }),
            Triple("下载最新版 APK", "从 GitHub Releases 拿 Hexora 最新一版的下载直链", { showLatestApk() }),
            Triple("查看 console 输出", "游戏里的 log / warn / error", { showConsole() }),
            Triple("切换 / 新建项目", "一个项目一个目录，互不干扰", { showProjects() }),
            Triple("后台保活设置", "切后台 / 锁屏 AI 继续跑（需要关掉电池优化）", { askKeepAlive() }),
            Triple("复制工程路径", gameRoot.absolutePath, { copyToClipboard(gameRoot.absolutePath) }),
            Triple("清空对话历史", "AI 会忘掉之前的上下文", { clearHistory() })
        )

        for ((title, sub, action) in rows) {
            val row = listRowOf(this, pal, title, sub)
            row.setOnClickListener { action() }
            // 记住 Shizuku 那行，好在授权状态变化时改它的副标题
            // （listRowOf 的结构：row[0] = 竖排容器，容器[0] = 标题，容器[1] = 副标题）
            val col = row.getChildAt(0) as? LinearLayout
            val subTv = col?.getChildAt(1) as? TextView
            if (subTv != null) {
                when (title) {
                    "Shizuku 提权" -> shizukuRowView = row to subTv
                    "工作区目录（免 Shizuku）" -> safRowView = row to subTv
                }
            }
            pubScroll.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }

        pubPage.addView(
            ScrollView(this).apply { addView(pubScroll) },
            FrameLayout.LayoutParams(-1, -1)
        )
        refreshExportRow()
    }

    private fun refreshExportRow() {
        val f = lastExport
        lastExportTv.text = if (f != null && f.exists()) {
            "最近导出：${f.name}\n${f.parentFile?.absolutePath}\n（点这里可以分享或复制路径）"
        } else {
            "还没有导出过。点下面的「导出工程包」，导出后会告诉你文件在哪。"
        }
    }

    private fun exportZip() {
        toast("正在打包…")
        Thread {
            runCatching {
                val dir = getExternalFilesDir(null) ?: filesDir
                val out = File(dir, "hexora_${currentGame}_${System.currentTimeMillis()}.zip")
                ZipOutputStream(out.outputStream()).use { z ->
                    for (g in listOf(File(gameRoot, currentGame))) {
                        if (!g.isDirectory) continue
                        if (g.name.startsWith("_")) continue
                        for (f in g.walkTopDown().filter { it.isFile }) {
                            z.putNextEntry(ZipEntry("${g.name}/${f.relativeTo(g).path}"))
                            f.inputStream().use { it.copyTo(z) }
                            z.closeEntry()
                        }
                    }
                }
                lastExport = out
                val pubPath = saveToDownloads(out)
                main.post {
                    refreshExportRow()
                    showExportDone(out, pubPath)
                }
            }.onFailure { e ->
                main.post { addErrorCard("导出失败：${e.message}") }
            }
        }.start()
    }

    /** 另存一份到公共下载目录，这样用文件管理器/微信都能直接找到 */
    private fun saveToDownloads(src: File): String? = runCatching {
        val rel = "H5Games"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, src.name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + rel)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            contentResolver.openOutputStream(uri)?.use { o ->
                src.inputStream().use { i -> i.copyTo(o) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            "/sdcard/Download/$rel/${src.name}"
        } else {
            @Suppress("DEPRECATION")
            val d = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                rel
            ).apply { mkdirs() }
            val f = File(d, src.name)
            src.inputStream().use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            f.absolutePath
        }
    }.getOrNull()

    private fun showExportDone(zip: File, pubPath: String?) {
        val msg = buildString {
            append("工程包已生成：\n")
            append(zip.absolutePath)
            append("\n")
            if (pubPath != null) {
                append("\n已另存到公共下载目录（用文件管理器可直接看到）：\n")
                append(pubPath)
            } else {
                append("\n（另存到下载目录失败，可以直接用下面的「分享」导出到别的 App）")
            }
        }
        val ctx = themed()
        HxDialog.Builder(ctx, pal)
            .setTitle("导出完成")
            .setMessage(msg)
            .setPositiveButton("分享") { -> shareZip(zip) }
            .setNeutralButton("复制路径") { -> copyToClipboard(zip.absolutePath) }
            .setNegativeButton("好", null)
            .show()
    }

    private fun showExportActions() {
        val zip = lastExport ?: return
        if (!zip.exists()) {
            toast("文件已被清理，请重新导出")
            return
        }
        HxDialog.Builder(themed(), pal)
            .setTitle("最近导出的工程包")
            .setMessage(zip.absolutePath)
            .setPositiveButton("分享") { -> shareZip(zip) }
            .setNeutralButton("复制路径") { -> copyToClipboard(zip.absolutePath) }
            .setNegativeButton("好", null)
            .show()
    }

    private fun shareZip(zip: File) {
        runCatching {
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.files", zip)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "把工程包发到…"))
        }.onFailure { toast("分享失败：${it.message}") }
    }

    private fun clearHistory() {
        history.clear()
        chatList.removeAllViews()
        addSystemLine("对话历史已清空")
        toast("已清空")
    }

    // ==================== 设置 ====================

    /** 深色模式下让对话框也用深色主题 */
    private fun themed(): Context =
        ContextThemeWrapper(this, if (pal.dark) R.style.AppThemeDark else R.style.AppTheme)

    // ==================== 模型快速切换（底部面板） ====================

    /** 面板里展开着的那家厂商。手风琴：同时只开一家，否则十几家全摊开会长到滑不到底 */
    private var sheetOpen: String? = null

    /**
     * 模型面板：从底部弹出，对齐用户给的参考图。
     *
     *   默认配置                  mimo-v2.5-pro      ← 一点就切回默认
     *   国内
     *     小米 / DeepSeek 深度求索   deepseek-flash ⌄  ← 点开看这家的模型
     *     谷歌                      gemini-3-flash
     *   国外 / 本地
     *
     * 两条交互约定：
     *   点模型   = 立刻切过去（「随手切」就靠这个，不打断、不关面板）
     *   长按行   = 设为默认配置（下次打开 App 就从它开始）
     *
     * 「默认配置」和「当前在用」是两件事，这正是用户要的：
     * 平时随手换来换去试模型，但每次冷启动都回到自己认准的那一个。
     */
    private fun showModelSheet() {
        val dlg = Dialog(this)
        // 自绘的底部面板不需要系统标题栏；不关掉的话顶上会多出一条空白带
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        // 顶部圆角的底板。GradientDrawable 的四角是分开设的，只圆上面两个角，
        // 贴着屏幕底边的那两个角保持直角 —— 弹层的下沿和屏幕边缘齐平才不「飘」。
        val radius = dp(16).toFloat()
        val sheetBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(pal.card)
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = sheetBg
        }

        // ---------- 标题行 ----------
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(12), dp(10))
        }
        titleRow.addView(TextView(this).apply {
            text = "模型"
            textSize = 15f
            typeface = MEDIUM
            setTextColor(pal.text)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        titleRow.addView(iconView(this, pal, "gear", 15, pal.sub).apply {
            setPadding(dp(9), dp(3), dp(9), dp(3))
            setOnClickListener { dlg.dismiss(); showSettings() }
        }, LinearLayout.LayoutParams(dp(33), dp(27)))
        titleRow.addView(iconView(this, pal, "close", 15, pal.sub).apply {
            setPadding(dp(9), dp(3), dp(6), dp(3))
            setOnClickListener { dlg.dismiss() }
        }, LinearLayout.LayoutParams(dp(30), dp(27)))
        root.addView(titleRow)
        root.addView(View(this).apply { setBackgroundColor(pal.border) },
            LinearLayout.LayoutParams(-1, dp(1)))

        // ---------- 列表（每次切换后整体重画：几个 TextView 而已，比做增量更新稳） ----------
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sc = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }

        fun rowShell(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(11), dp(14), dp(11))
            background = pressable(roundCard(this@MainActivity, pal.card, pal.card, 0, 0), 0x14000000)
        }

        fun render() {
            list.removeAllViews()
            val curPid = cfgStore.providerId
            val defPid = cfgStore.defaultProviderOrCurrent()
            val defModel = cfgStore.defaultModelOrCurrent()

            // ===== 默认配置：置顶一行，永远可见 =====
            val defProv = AiProviders.byId(defPid)
            val defRow = rowShell().apply {
                setBackgroundColor(pal.accentSoft)
                addView(iconView(this@MainActivity, pal, "star", 12, pal.accent),
                    LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(9) })
                val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(this@MainActivity).apply {
                    text = "默认配置"
                    textSize = 13.5f
                    typeface = MEDIUM
                    setTextColor(pal.accent)
                })
                col.addView(TextView(this@MainActivity).apply {
                    text = defProv.label + " · 下次打开用它"
                    textSize = 10.5f
                    setTextColor(pal.sub)
                    setPadding(0, dp(3), 0, 0)
                })
                addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(this@MainActivity).apply {
                    text = defModel
                    textSize = 11.5f
                    typeface = MONO
                    setTextColor(pal.accent)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(-2, -2))
            }
            defRow.setOnClickListener {
                // 切回默认：当前指针直接对回默认配置那一对
                cfgStore.providerId = defPid
                cfgStore.setModel(defPid, defModel)
                refreshHeader()
                render()
                toast("已切回默认配置 · $defModel")
            }
            list.addView(defRow, LinearLayout.LayoutParams(-1, -2))

            // ===== 按「国内 / 国外 / 本地」分组，逐家列出来 =====
            for (g in AiProviders.groupOrder()) {
                val provs = AiProviders.ALL.filter { it.group == g }
                if (provs.isEmpty()) continue
                list.addView(TextView(this@MainActivity).apply {
                    text = g
                    textSize = 10.5f
                    typeface = MONO
                    setTextColor(pal.faint)
                    setPadding(dp(16), dp(12), dp(16), dp(5))
                }, LinearLayout.LayoutParams(-1, -2))

                for (p in provs) {
                    val hasKey = cfgStore.keyOf(p.id).isNotBlank()
                    val cur = cfgStore.modelOf(p.id)
                    val isCurProv = p.id == curPid
                    val open = sheetOpen == p.id

                    val pr = rowShell()
                    val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                    col.addView(TextView(this@MainActivity).apply {
                        text = p.label + if (isCurProv) "  ●" else ""
                        textSize = 13.5f
                        // 没填 Key 的厂商压暗一档：一眼能看出哪些是「能直接切的」
                        setTextColor(if (hasKey || p.id == curPid) pal.text else pal.faint)
                    })
                    col.addView(TextView(this@MainActivity).apply {
                        text = if (hasKey) "已配置 Key" else "未填 Key · 长按设为默认前先去设置里填"
                        textSize = 10.5f
                        setTextColor(pal.faint)
                        setPadding(0, dp(3), 0, 0)
                    })
                    pr.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                    pr.addView(TextView(this@MainActivity).apply {
                        text = cur
                        textSize = 11.5f
                        typeface = MONO
                        setTextColor(if (isCurProv) pal.accent else pal.sub)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
                    pr.addView(TextView(this@MainActivity).apply {
                        text = if (open) "⌄" else "›"
                        textSize = 13f
                        setTextColor(pal.faint)
                    })
                    pr.setOnClickListener {
                        sheetOpen = if (open) null else p.id
                        render()
                    }
                    // 长按 = 把这家当前选中的模型钉成默认配置
                    pr.setOnLongClickListener {
                        cfgStore.markAsDefault(p.id, cur)
                        render()
                        toast("已设为默认配置：${p.label} / $cur")
                        true
                    }
                    list.addView(pr, LinearLayout.LayoutParams(-1, -2))

                    if (!open) continue

                    // ---- 展开：这家的模型清单 ----
                    //
                    // 名单以预设为准，但**当前在用的那个一定放进来**：用户可能是在设置页
                    // 手填的名字、或者用「拉取可用模型」从厂商现拉的，那些都不在预设里。
                    // 不补这一条的话，展开后看不到自己正在用的模型，也就没法切回去了。
                    val names = LinkedHashSet<String>()
                    if (cur.isNotBlank()) names += cur
                    p.models.forEach { names += it.name }

                    for (mn in names) {
                        val picked = mn == cur
                        val isDef = cfgStore.isDefaultPair(p.id, mn)
                        val label = AiProviders.modelLabel(p, mn)
                        val vision = AiProviders.supportsVision(p, mn) || AiProviders.guessVision(mn)
                        val mr = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(30), dp(9), dp(14), dp(9))
                            background = pressable(
                                roundCard(this@MainActivity, pal.card, pal.card, 0, 0), 0x14000000
                            )
                        }
                        mr.addView(TextView(this@MainActivity).apply {
                            text = if (picked) "◉" else "○"
                            textSize = 11f
                            setTextColor(if (picked) pal.accent else pal.faint)
                        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(9) })
                        val mcol = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                        mcol.addView(TextView(this@MainActivity).apply {
                            text = if (isDef) "· $mn" else mn
                            textSize = 12f
                            typeface = MONO
                            setTextColor(if (picked) pal.accent else pal.text)
                        })
                        if (label != mn) mcol.addView(TextView(this@MainActivity).apply {
                            text = label
                            textSize = 10.5f
                            setTextColor(pal.sub)
                            setPadding(0, dp(2), 0, 0)
                        })
                        mr.addView(mcol, LinearLayout.LayoutParams(0, -2, 1f))
                        if (vision) mr.addView(TextView(this@MainActivity).apply {
                            text = "看图"
                            textSize = 9.5f
                            setTextColor(pal.accent2)
                            setPadding(dp(4), dp(2), dp(4), dp(2))
                            background = roundCard(this@MainActivity, pal.cardAlt, pal.groupBorder, 5)
                        })
                        mr.setOnClickListener {
                            cfgStore.providerId = p.id
                            cfgStore.setModel(p.id, mn)
                            refreshHeader()
                            render()
                            addSystemLine("已切到 ${p.label} / $mn")
                        }
                        mr.setOnLongClickListener {
                            cfgStore.markAsDefault(p.id, mn)
                            render()
                            toast("已设为默认配置：$mn")
                            true
                        }
                        list.addView(mr, LinearLayout.LayoutParams(-1, -2))
                    }
                }
            }
        }
        render()

        // 列表高度封顶：超过屏幕一半就自己滚，别把整块屏幕吃掉
        val cap = (resources.displayMetrics.heightPixels * 0.52f).toInt()
        sc.layoutParams = LinearLayout.LayoutParams(-1, cap)
        root.addView(sc)

        root.addView(View(this).apply { setBackgroundColor(pal.border) },
            LinearLayout.LayoutParams(-1, dp(1)))
        root.addView(TextView(this).apply {
            text = "点模型立刻切换 · 长按一行设为默认配置 · 右下角胶囊随时再打开"
            textSize = 10.5f
            setTextColor(pal.faint)
            setPadding(dp(16), dp(9), dp(16), dp(12))
        })

        dlg.setContentView(root)
        dlg.window?.let { w ->
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.42f)
            w.setGravity(Gravity.BOTTOM)
        }
        dlg.show()
        dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun showSettings() {
        val ctx = this
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(4))
        }

        fun section(t: String) = col.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(24), 0, dp(10))
            // 分组标题：小号大写感 + 一道强调色短横线，比一行灰字更像「有设计」的界面
            addView(TextView(ctx).apply {
                text = t
                textSize = 11.5f
                letterSpacing = 0.12f
                typeface = MEDIUM
                setTextColor(pal.accent)
            })
            addView(View(ctx).apply {
                setBackgroundColor(pal.border)
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(1)).apply { topMargin = dp(7) }
            })
        })

        fun input(hint: String, value: String, numeric: Boolean = false): EditText =
            EditText(ctx).apply {
                this.hint = hint
                textSize = 14f
                letterSpacing = 0.02f
                setText(value)
                setTextColor(pal.text)
                setHintTextColor(pal.faint)
                // 输入框自己就是一块「平静的卡面」：白底 + 发丝描边 + 舒服的内边距，
                // 不留系统那根下划线（那是「很原生」味的主要来源之一）。
                setPadding(dp(14), dp(13), dp(14), dp(13))
                includeFontPadding = false
                setSingleLine()
                background = roundCard(ctx, pal.card, pal.border, 12)
                if (numeric) inputType = InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL
            }

        /**
         * 带**真标签**的输入框 —— 设置页所有输入项都该用它，不要直接用 input()。
         *
         * 为什么：input() 只设了 hint，而 hint **只在框里没内容时**才显示。
         * 设置页这些框基本都是有值的（0.4 / 400 / 40 / 2 / 6 …），于是标签永远看不见 ——
         * 用户对着一排光秃秃的数字，根本分不清哪个是 temperature、哪个是轮数上限
         * （「设置里面参数不知道干嘛的」就是这么来的）。
         * 现在标签是框上方独立的一行文字，**永远可见**。
         *
         * 它会自己把自己挂进 col，调用点不用再 col.addView —— 少一处漏加的机会。
         */
        fun field(
            label: String,
            value: String,
            numeric: Boolean = false,
            into: LinearLayout = col
        ): EditText {
            val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            wrap.addView(TextView(ctx).apply {
                text = label
                textSize = 12.5f
                setTextColor(pal.sub)
                setPadding(dp(2), 0, dp(2), dp(5))
            })
            val et = input(label, value, numeric)
            wrap.addView(et, LinearLayout.LayoutParams(-1, -2))
            into.addView(wrap, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
            return et
        }

        fun spinnerOf(labels: List<String>, selected: Int) = android.widget.Spinner(ctx).apply {
            // 自绘 item 文字颜色：系统默认的是纯黑，深色模式下等于看不见（这也是「原生味」来源）。
            // 顺带把内边距和字号统一到跟输入框一致。
            adapter = object : android.widget.ArrayAdapter<String>(
                ctx, android.R.layout.simple_spinner_dropdown_item, labels
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = super.getView(position, convertView, parent) as TextView
                    v.setTextColor(pal.text)
                    v.textSize = 14f
                    v.setPadding(dp(14), dp(12), dp(8), dp(12))
                    v.includeFontPadding = false
                    return v
                }

                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = super.getDropDownView(position, convertView, parent) as TextView
                    v.setTextColor(pal.text)
                    v.textSize = 14.5f
                    v.setPadding(dp(18), dp(14), dp(18), dp(14))
                    return v
                }
            }
            setSelection(selected.coerceIn(0, (labels.size - 1).coerceAtLeast(0)))
            // 下拉箭头跟着强调色走，别留系统默认的灰三角
            backgroundTintList = android.content.res.ColorStateList.valueOf(pal.accent)
        }

        // ---------- 外观 ----------
        section("外观")
        val themeChips = listOf("浅色", "深色", "跟随系统")
        val themeIds = listOf("light", "dark", "auto")
        var themeSel = themeIds.indexOf(cfgStore.themeMode).coerceAtLeast(0)
        val themeRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        var themeViews: List<TextView> = emptyList()
        themeViews = themeChips.mapIndexed { i, t ->
            chipOf(ctx, pal, t, i == themeSel).also { c ->
                c.setOnClickListener {
                    themeSel = i
                    themeViews.forEachIndexed { j, v ->
                        val active = j == themeSel
                        v.setTextColor(if (active) pal.onAccent else pal.text)
                        v.background = pressable(
                            roundCard(ctx, if (active) pal.accent else pal.cardAlt,
                                if (active) pal.accent else pal.border, 20), 0x55FFFFFF
                        )
                    }
                }
                themeRow.addView(c, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
            }
        }
        col.addView(themeRow)

        // ---------- TapTap Maker（素材生产线） ----------
        // 为什么放这里：AI 跑在 WebView 沙箱里，没有任何 exec 通道，
        // 授权（pat/login）与绑定工程（init）只能由 App 本体代跑。
        section("TapTap Maker（生图 / 音乐 / 音效 / 配音）")
        col.addView(TextView(ctx).apply {
            text = "素材生成走 TapTap 云端，需要先授权一次。这里由 App 帮你跑命令，你不用敲终端。"
            textSize = 12f
            setTextColor(pal.sub)
            setPadding(dp(2), 0, 0, dp(8))
        })

        val makerOut = TextView(ctx).apply {
            text = "点下面任意按钮开始；「自检」能看出现在到底缺哪一步。"
            textSize = 11.5f
            setTextColor(pal.sub)
            setTextIsSelectable(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundCard(ctx, pal.cardAlt, pal.border, 12)
        }

        fun makerRun(cmd: List<String>, stdin: String?, tip: String) {
            makerOut.text = "执行中：$tip …"
            Thread {
                val r = MakerCli.run(ctx, cmd, stdin)
                main.post {
                    makerOut.text = (if (r.ok) "成功 · " else "失败 · ") + tip + "\n" + r.output.takeLast(900)
                }
            }.start()
        }

        val patEt = field("粘贴 PAT（也可用右边的扫码登录）", "")

        val makerRow1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        makerRow1.addView(ghostBtnOf(ctx, pal, "保存 PAT").apply {
            setOnClickListener {
                val v = patEt.text.toString().trim()
                if (v.isEmpty()) {
                    toast("先把 PAT 粘进上面的输入框")
                } else {
                    makerRun(listOf("pat", "set", "--pat-stdin"), v + "\n", "写入 PAT")
                }
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
makerRow1.addView(ghostBtnOf(ctx, pal, "扫码登录").apply {
            setOnClickListener {
                // 走 MakerAuth（流式 + 10 分钟等待）：链接一出来就显示，并顺手打开浏览器。
                // 老写法是等进程退出才回读输出，链接永远显示不出来。
                makerOut.text = "正在获取授权链接…"
                Thread {
                    val u = MakerAuth.start(this@MainActivity)
                    main.post {
                        if (u == null) {
                            makerOut.text = "没拿到授权链接：" + MakerAuth.statusText(this@MainActivity)
                        } else {
                            makerOut.text = "授权链接（已尝试打开浏览器；也可长按复制）\n$u\n\n" +
                                "登录后点「创建 token」，完成后这里会自动提示。"
                            makerOut.setTextIsSelectable(true)
                            openExternal(u)
                        }
                    }
                }.start()
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        makerRow1.addView(ghostBtnOf(ctx, pal, "自检").apply {
            setOnClickListener { makerRun(listOf("doctor", "--json"), null, "Maker 自检") }
        }, LinearLayout.LayoutParams(-2, -2))
        col.addView(makerRow1, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val makerRow2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        makerRow2.addView(ghostBtnOf(ctx, pal, "绑定当前项目").apply {
            setOnClickListener {
                // 必须走桥的 /ensure-project：它用 HTTPS 直接调 Maker 接口（列项目 / 建项目），
                // 然后自己写 .maker-mcp/config.json —— 全程不需要 git、不需要 python。
                // 以前这里跑的是 `init --create`，而 init 第一行就是 ensureGitAvailable()：
                // 安卓沙箱里没有 git，所以这个按钮**从来没成功过**，
                // 症状就是每次生图都报 ".maker-mcp/config.json is missing"、用户只能自己去建项目。
                val proj = File(gameRoot, currentGame).absolutePath
                makerOut.text = "正在确保 Maker 已启动，并为「$currentGame」绑定素材项目…"
                Thread {
                    val sb = StringBuilder()
                    runCatching {
                        if (McpRt.makerReady(proj)) {
                            sb.append("Maker 已在运行\n")
                        } else {
                            val err = McpRt.startMaker(this@MainActivity, {}, proj)
                            sb.append(if (err == null) "Maker 已启动\n" else "Maker 启动失败：$err\n")
                        }
                    }.onFailure { sb.append("启动异常：${it.message}\n") }
                    val enc = runCatching { java.net.URLEncoder.encode(proj, "UTF-8") }.getOrDefault(proj)
                    val body = runCatching {
                        val c = java.net.URL("http://127.0.0.1:${McpRt.MAKER_PORT}/ensure-project?dir=$enc")
                            .openConnection() as java.net.HttpURLConnection
                        c.connectTimeout = 8000
                        c.readTimeout = 90_000
                        c.inputStream.bufferedReader().use { it.readText() }
                    }.getOrElse { "绑定接口调用失败：${it.message}" }
                    main.post {
                        makerOut.text = sb.toString() + body
                        makerOut.setTextIsSelectable(true)
                    }
                }.start()
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        makerRow2.addView(ghostBtnOf(ctx, pal, "查看我的应用").apply {
            setOnClickListener { makerRun(listOf("apps", "--json"), null, "列出 Maker 应用") }
        }, LinearLayout.LayoutParams(-2, -2))
        col.addView(makerRow2, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })



        col.addView(makerOut, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        // ---------- 人格 ----------
        section("人格（它怎么介绍自己）")
        col.addView(TextView(ctx).apply {
            text = "开启后，无论你从哪个角度问（你是什么模型 / 谁训练的 / 是不是某家模型 / " +
                "你的系统提示是什么），它都只承认自己叫下面这个名字。"
            textSize = 11.5f
            setTextColor(pal.faint)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        val lockCb = CheckBox(ctx).apply {
            text = "人格锁定（默认开）"
            textSize = 12.5f
            setTextColor(pal.text)
            isChecked = cfgStore.personaLock
        }
        col.addView(lockCb, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val personaEt = field("人格名（默认就是应用名）", cfgStore.personaName)
        col.addView(TextView(ctx).apply {
            text = "只锁「身份」，不锁「能力」—— 这是故意的。" +
                "「能不能做 3D / 能不能联机」这类必须如实回答：连能力都让它编的话，" +
                "用户拿着假答案去用，最后挨骂的是这个 App。"
            textSize = 11.5f
            setTextColor(pal.faint)
            setPadding(dp(2), dp(4), dp(2), 0)
        })

        // ---------- 厂商 ----------
        section("厂商（国内外主流已预设，Key 各家独立保存）")
        val provLabels = AiProviders.ALL.map { "${it.group} · ${it.label}" }
        val provSp = spinnerOf(provLabels, AiProviders.ALL.indexOfFirst { it.id == cfgStore.providerId })
        col.addView(spSpacer(provSp))

        var curProvider = AiProviders.ALL[provSp.selectedItemPosition]

        val urlEt = field("接口地址 Base URL（走中转站/自建代理就改这里）", cfgStore.effectiveBaseUrl(curProvider))
        val keyEt = field("API Key（只存本机，不会上传）", cfgStore.keyOf(curProvider.id))

        // ---------- 去哪申请 Key（用户要求：配置的时候就得能直接点到官网） ----------
        val applyTitle = TextView(ctx).apply {
            textSize = 13f
            setTextColor(pal.accent)
            typeface = MEDIUM
        }
        val applySub = TextView(ctx).apply {
            textSize = 11.5f
            setTextColor(pal.sub)
            setPadding(0, dp(4), 0, 0)
        }
        val applyRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = pressable(roundCard(ctx, pal.accentSoft, pal.border, 12), 0x14000000)
            addView(applyTitle)
            addView(applySub)
        }

        fun bindApply() {
            val u = AiProviders.applyUrlOf(curProvider)
            applyTitle.text =
                if (u.isBlank()) "这个厂商不需要申请 Key" else "去官网申请 API Key · 点这里打开"
            applySub.text = AiProviders.applyHintOf(curProvider) + if (u.isBlank()) "" else "\n$u"
        }

        applyRow.setOnClickListener {
            val u = AiProviders.applyUrlOf(curProvider)
            if (u.isBlank()) {
                toast("该厂商没有固定申请地址，按上面的说明到它的控制台里创建")
            } else {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
                    .onFailure { toast("打不开浏览器：${it.message}") }
            }
        }
        // 长按把地址复制走：手机上从浏览器跳回来粘贴 Key 更顺手
        applyRow.setOnLongClickListener {
            val u = AiProviders.applyUrlOf(curProvider)
            if (u.isNotBlank()) {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("apply-url", u))
                toast("已复制申请地址")
            }
            true
        }
        col.addView(applyRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        bindApply()

        val testBtn = ghostBtnOf(ctx, pal, "测试连通")
        col.addView(testBtn, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })

        val testResult = TextView(ctx).apply {
            textSize = 12.5f
            setTextColor(pal.sub)
            setPadding(dp(2), dp(8), dp(2), 0)
        }
        col.addView(testResult)

        // ---------- 模型 ----------
        section("模型")
        val modelEt = field("模型名（厂商出新模型时直接手填这里）", cfgStore.modelOf(curProvider.id))

        // 设为默认配置：和底部模型面板里的「长按」是同一件事，这里给一个明面入口 ——
        // 不是所有人都会去长按，但「默认配置」这个概念得让人看得见、点得到。
        val defRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = pressable(roundCard(ctx, pal.cardAlt, pal.border, 12), 0x14000000)
        }
        val defText = TextView(ctx).apply {
            textSize = 13f
            setTextColor(pal.text)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        fun formModel(): String =
            modelEt.text.toString().trim().ifEmpty { cfgStore.modelOf(curProvider.id) }

        fun bindDef() {
            val isDef = cfgStore.isDefaultPair(curProvider.id, formModel())
            defText.text = if (isDef) "这就是默认配置（下次打开用它）"
            else "设为默认配置（下次打开用它）"
            defText.setTextColor(if (isDef) pal.accent else pal.text)
        }
        defRow.addView(defText)
        defRow.setOnClickListener {
            val m = formModel()
            cfgStore.markAsDefault(curProvider.id, m)
            bindDef()
            toast("已设为默认配置：${curProvider.label} / $m")
        }
        col.addView(defRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        bindDef()

        // 这个下拉本身就是「拉取可用模型」：点一下就去问厂商现在有哪些模型，
        // 不再依赖写死的预设（预设永远追不上厂商改名/上新）。
        val pickText = TextView(ctx).apply {
            text = "从厂商拉取可用模型"
            textSize = 13f
            setTextColor(pal.text)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val pickRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(8), dp(9))
            background = pressable(roundCard(ctx, pal.cardAlt, pal.border, 12), 0x14000000)
            addView(pickText)
            addView(TextView(ctx).apply {
                text = "拉取"
                textSize = 12.5f
                setTextColor(pal.accent)
                setPadding(dp(10), dp(2), dp(2), dp(2))
            })
        }
        pickRow.setOnClickListener {
            // 把输入框里的 Key 直接传进去：用户刚粘贴、还没点保存时也能拉取
            fetchModels(curProvider, keyEt.text?.toString().orEmpty()) { picked ->
                modelEt.setText(picked)
                pickText.text = "已选 · $picked"
                pickText.setTextColor(pal.accent)
                bindDef()
            }
        }
        col.addView(pickRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })


        // ---------- 视觉策略 ----------
        section("视觉能力（截图能否直接给模型看）")
        val visChips = listOf("自动", "强制开", "强制关")
        val visIds = listOf("auto", "on", "off")
        var visSel = visIds.indexOf(cfgStore.visionMode).coerceAtLeast(0)
        val visRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        var visViews: List<TextView> = emptyList()
        visViews = visChips.mapIndexed { i, t ->
            chipOf(ctx, pal, t, i == visSel).also { c ->
                c.setOnClickListener {
                    visSel = i
                    visViews.forEachIndexed { j, v ->
                        val active = j == visSel
                        v.setTextColor(if (active) pal.onAccent else pal.text)
                        v.background = pressable(
                            roundCard(ctx, if (active) pal.accent else pal.cardAlt,
                                if (active) pal.accent else pal.border, 20), 0x55FFFFFF
                        )
                    }
                }
                visRow.addView(c, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
            }
        }
        col.addView(visRow)
        col.addView(TextView(ctx).apply {
            text = "自动 = 模型自带看图就开（截图直接喂给 AI，能自己查 UI）；强制开/关用于新模型。"
            textSize = 11.5f
            setTextColor(pal.faint)
            setPadding(dp(2), dp(6), dp(2), 0)
        })

        // ---------- 技能 ----------
        section("技能包（决定 AI 的工作方式）")
        val skillSp = spinnerOf(
            SkillPresets.ALL.map { it.label + if (it.needVision) "（建议开视觉）" else "" },
            SkillPresets.ALL.indexOfFirst { it.id == cfgStore.skillId }
        )
        col.addView(spSpacer(skillSp))

        // ---------- 参数 ----------
        section("参数")
        val tempEt = field("temperature 随机性（0 = 最稳，1 = 最放飞）", cfgStore.temperature.toString(), true)
        col.addView(TextView(ctx).apply {
            text = "写代码为主，0.3 ~ 0.5 之间最稳；调高了它容易自己加戏、改不该改的地方。"
            textSize = 11.5f
            setTextColor(pal.faint)
            setPadding(dp(2), dp(4), dp(2), 0)
        })

        val stepsEt = field("单次最多工具轮数（一条消息里最多让它调多少次工具）", cfgStore.maxSteps.toString(), true)
        col.addView(TextView(ctx).apply {
            text = "调小省额度、也不会跑飞；调大能给复杂任务更多余地。" +
                "到上限了它会停下来报一句，你再发一条就能接着做 —— 不会把前面的成果丢掉。"
            textSize = 11.5f
            setTextColor(pal.faint)
            setPadding(dp(2), dp(4), dp(2), 0)
        })

        val fbCb = CheckBox(ctx).apply {
            text = "模型看不了图时，把截图存盘并把路径告诉它"
            textSize = 12.5f
            setTextColor(pal.text)
            isChecked = cfgStore.visionFallback
        }
        col.addView(fbCb, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        // ---------- 请求体积（适配免费档 / 小配额模型） ----------
        section("请求体积（免费档 / 小配额模型必调）")
        col.addView(TextView(ctx).apply {
            text = "很多模型有「每分钟 token 上限」（如 Groq 免费档 8000 TPM）。" +
                "工具清单 + 历史一多，请求就会被判超限（413/429/余额不足）。" +
                "下面几项越小越省 token。"
            textSize = 11.5f
            setTextColor(pal.faint)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        val maxOutEt = field(
            "单次回答最多写多少字（max_tokens，0 = 让它自己定）",
            cfgStore.maxOutTokens.toString(), true
        )

        val ctxWinEt = field(
            "模型上下文窗口（token，0 = 默认 128k）",
            cfgStore.contextWindow.toString(), true
        )
        col.addView(TextView(ctx).apply {
            text = "压缩按它算：估算历史 token 超过窗口 75% 时才折老轮次 —— " +
                "**真实需要时才压**，不会像以前那样按条数瞎压。\n" +
                "换小窗口模型（32k 的中转 / 免费档）就填小，否则会撑爆请求体报 400；" +
                "大窗口模型（200k）可以填大，少压一点、多留上下文。\n" +
                "拿不准就留 0（按 128k 算）。"
            textSize = 11.5f
            setTextColor(pal.faint)
            setPadding(dp(2), dp(4), dp(2), 0)
        })

        val histEt = field(
            "每次请求带上最近多少条消息（越小请求越轻）",
            cfgStore.historyLimit.toString(), true
        )
        col.addView(TextView(ctx).apply {
            text = "⚠ 这是**条数**上的最后保险，正常轮不到它（默认 400）。" +
                "常规压缩走上面的「上下文窗口」按 token 算。\n" +
                "别调太小：一轮任务动不动几十轮工具往返，条数不够会把**开头那段**" +
                "（你最初的需求、定过的方案）挤出去，AI 就表现得像「聊两句就断片」。"
            textSize = 11.5f
            setTextColor(pal.faint)
            setPadding(dp(2), dp(4), dp(2), 0)
        })

        // 下面这两项比「条数」更影响体积：截图和长工具结果都是**每一步都要重发一遍**的，
        // 不像历史条数那样容易被忽略。
        val keepImgEt = field(
            "历史里保留几张截图（0 = 一张不留）",
            cfgStore.keepImages.toString(), true
        )

        val keepTrEt = field(
            "最近多少条长工具结果留全文（更早的压成一行占位）",
            cfgStore.keepToolResults.toString(), true
        )
        col.addView(TextView(ctx).apply {
            text = "截图是按 base64 直接塞进请求体的，而且每一步都连同历史重发 —— " +
                "一张 720p 截图 ≈ 100KB，留十张就是每步多传 1MB，慢也慢在这儿。" +
                "只留最近 1~2 张通常就够 AI 自检用了。"
            textSize = 11.5f
            setTextColor(pal.faint)
            setPadding(dp(2), dp(4), dp(2), 0)
        })

        val slimToolsCb = CheckBox(ctx).apply {
            text = "精简工具定义（省 token，但会少几个工具）"
            textSize = 12.5f
            setTextColor(pal.text)
            isChecked = cfgStore.slimTools
        }
        col.addView(slimToolsCb, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        col.addView(TextView(ctx).apply {
            text = "⚠️ 打开后会少发「插件市场 / 本地服务」这两个跟做游戏无关的工具给模型。" +
                "被砍掉的工具它连看都看不到 —— 这是实打实的能力损失，所以默认关。"
            textSize = 11.5f
            setTextColor(pal.faint)
            setPadding(dp(2), dp(4), dp(2), 0)
        })

        val sendToolsCb = CheckBox(ctx).apply {
            text = "把工具清单发给模型（关掉最省 token，但 AI 不能调用工具/生图）"
            textSize = 12.5f
            setTextColor(pal.text)
            isChecked = cfgStore.sendTools
        }
        col.addView(sendToolsCb, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val autoSlimCb = CheckBox(ctx).apply {
            text = "遇 413/超限时自动精简并重试（推荐开）"
            textSize = 12.5f
            setTextColor(pal.text)
            isChecked = cfgStore.autoSlim
        }
        col.addView(autoSlimCb, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        col.addView(ghostBtnOf(ctx, pal, "一键省额度（输出 512 / 历史 12 / 截图留 1 张）").apply {
            setOnClickListener {
                maxOutEt.setText("512")
                histEt.setText("12")
                keepImgEt.setText("1")
                keepTrEt.setText("3")
                sendToolsCb.isChecked = false
                autoSlimCb.isChecked = true
                toast("已填入省额度参数，点「保存」生效")
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })


        provSp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long
            ) {
                curProvider = AiProviders.ALL[pos]
                urlEt.setText(cfgStore.effectiveBaseUrl(curProvider))
                keyEt.hint = curProvider.keyHint
                keyEt.setText(cfgStore.keyOf(curProvider.id))
                modelEt.setText(cfgStore.modelOf(curProvider.id))
                testResult.text = ""
                // 换厂商时把「去哪申请 Key」那一行一起换掉，免得看着还是上一家的地址
                bindApply()
                // 「设为默认配置」那一行也要跟着换：换一家之后默认与否的判断就变了
                bindDef()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // ---------- MCP 服务器 ----------
        section("MCP 服务器（TapTap 小游戏等，运行时已内置）")
        val mcpStatus = TextView(ctx).apply {
            textSize = 12.5f
            setTextColor(pal.sub)
            setPadding(dp(2), 0, dp(2), dp(6))
        }
        col.addView(mcpStatus)

        val mcpList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(mcpList)

        fun mcpRender() {
            val rt = when {
                !McpRt.ready(ctx) -> "运行时未释放"
                McpRt.running() -> "本地服务运行中（端口 ${McpRt.PORT}）"
                else -> "运行时已就绪，服务未启动"
            }
            val hub = EngineTools.mcp
            val info = hub?.lastInfo ?: "未连接"
            mcpStatus.text = "$rt · $info · 已注册 ${hub?.size ?: 0} 个工具"
        }

        fun mcpRebuild() {
            mcpList.removeAllViews()
            val list = McpStore.load(ctx)
            if (list.isEmpty()) {
                mcpList.addView(TextView(ctx).apply {
                    text = "（还没有服务器，点下面「添加服务器」）"
                    textSize = 12f
                    setTextColor(pal.faint)
                    setPadding(dp(2), 0, 0, dp(4))
                })
            }
            list.forEach { s ->
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(android.widget.CheckBox(ctx).apply {
                    isChecked = s.enabled
                    setOnCheckedChangeListener { _, v ->
                        s.enabled = v
                        McpStore.save(ctx, list)
                        toast("MCP「${s.name}」已${if (v) "启用" else "停用"}，重启服务后生效")
                    }
                })
                row.addView(TextView(ctx).apply {
                    text = "${s.name}\n${s.url}"
                    textSize = 12.5f
                    setTextColor(pal.text)
                    setPadding(dp(2), dp(4), dp(2), dp(4))
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                })
                row.addView(ghostBtnOf(ctx, pal, "删除").apply {
                    setOnClickListener {
                        McpStore.save(ctx, list.filter { it !== s })
                        mcpRebuild()
                        mcpRender()
                    }
                })
                mcpList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            }
        }

        fun mcpStart(restart: Boolean) {
            mcpStatus.text = "正在启动 MCP 服务…"
            Thread {
                val hub = McpBoot.ensure(ctx) { }
                if (hub != null) McpGuardService.start(ctx)
                main.post {
                    mcpRender()
                    toast(if (hub != null) "MCP 服务已就绪（${hub.lastInfo}）" else "MCP 启动失败，可在 设置 → MCP 服务器 的日志里看原因")
                }
            }.start()
        }

        mcpRebuild()
        mcpRender()

        val mcpRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        mcpRow.addView(ghostBtnOf(ctx, pal, "启动 / 重连").apply { setOnClickListener { mcpStart(true) } })
        mcpRow.addView(ghostBtnOf(ctx, pal, "刷新状态").apply { setOnClickListener { mcpRender() } })
        mcpRow.addView(ghostBtnOf(ctx, pal, "添加服务器").apply {
            setOnClickListener {
                val box = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), dp(6), dp(18), dp(6))
                }
                val nameEt = field("名称", "我的 MCP", into = box)
                val urlEt2 = field("地址（Streamable HTTP，如 http://127.0.0.1:3000/）", "http://", into = box)
                HxDialog.Builder(themed(), pal)
                    .setTitle("添加 MCP 服务器")
                    .setView(box)
                    .setPositiveButton("添加") { ->
                        val list = McpStore.load(ctx)
                        list.add(
                            McpServer(
                                nameEt.text.toString().trim().ifBlank { "MCP" },
                                urlEt2.text.toString().trim(),
                                true
                            )
                        )
                        McpStore.save(ctx, list)
                        mcpRebuild()
                        mcpRender()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        })
        col.addView(mcpRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        // ---------- 图片工具（抠图 / 去背景） ----------
        // 素材里「去掉背景」是刚需（角色立绘、道具、图标）。以前只能让 AI 手写 canvas 色键，
        // 效果惨。现在接了抠抠图的公开同步接口（1 积分 / 张），Key 就存这一个文件里，
        // 桥每次调用现读 —— 存完立刻能用，不用重启任何东西。
        section("图片工具（抠图 / 去背景）")
        val kouStatus = TextView(ctx).apply {
            textSize = 12.5f
            setTextColor(pal.sub)
            setPadding(dp(2), 0, dp(2), dp(6))
        }
        col.addView(kouStatus)

        fun kouRender() {
            val k = McpRt.koukoutuKey(ctx)
            kouStatus.text = if (k.isBlank()) {
                "未配置 API Key —— AI 现在用不了「去背景」。\n" +
                    "去 https://www.koukoutu.com/user/dev 注册后在开发者页拿 Key，粘到下面即可。"
            } else {
                "已配置（${k.take(6)}…${k.takeLast(4)}）· 抠图 1 积分 / 张 · 输出 png 再 +1\n" +
                    "存于 ${McpRt.kouKeyFile(ctx).absolutePath}（AI 一调用就现读，无需重启）"
            }
        }

        fun kouEdit() {
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(6), dp(18), dp(6))
            }
            val kouEt = field("抠图 API Key（形如 kk-xxxx…）", McpRt.koukoutuKey(ctx), into = box)
            kouEt.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            box.addView(TextView(ctx).apply {
                text = "申请地址：https://www.koukoutu.com/user/dev\n" +
                    "（免费额度用完可在同一页充值；同步接口 1 积分 / 张，并发上限 5）"
                textSize = 11.5f
                setTextColor(pal.faint)
                setPadding(dp(2), dp(10), dp(2), 0)
            })
            HxDialog.Builder(themed(), pal)
                .setTitle("抠图 API Key")
                .setView(box)
                .setPositiveButton("保存") { ->
                    val ok = McpRt.saveKoukoutuKey(ctx, kouEt.text.toString())
                    kouRender()
                    toast(if (ok) "已保存 —— AI 现在就能抠图了（无需重启）" else "保存失败（运行时目录不可写？）")
                }
                .setNeutralButton("去申请") { ->
                    runCatching {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.koukoutu.com/user/dev"))
                        )
                    }.onFailure { toast("打不开浏览器：${it.message}") }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        val kouRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        kouRow.addView(ghostBtnOf(ctx, pal, "填入 / 更换 Key").apply { setOnClickListener { kouEdit() } })
        kouRow.addView(ghostBtnOf(ctx, pal, "清空").apply {
            setOnClickListener {
                McpRt.saveKoukoutuKey(ctx, "")
                kouRender()
                toast("已清空抠图 Key")
            }
        })
        col.addView(kouRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        kouRender()


        val dlg = HxDialog.Builder(themed(), pal)
            .setTitle("设置")
            .setView(ScrollView(ctx).apply { addView(col) })
            .setPositiveButton("保存", null)
            .setNeutralButton("清空这家 Key", null)
            .setNegativeButton("取消", null)
            .create()

        dlg.setOnShowListener {
            val test = dlg.btn(HxDialog.BUTTON_POSITIVE)
            // 用「保存」按钮旁边的位置挂两个动作，这里改成自定义布局：保存写盘
            test?.setOnClickListener {
                cfgStore.providerId = curProvider.id
                cfgStore.setKey(curProvider.id, keyEt.text.toString())
                cfgStore.setBaseUrl(curProvider.id, urlEt.text.toString())
                val m = modelEt.text.toString().trim()
                if (m.isNotEmpty()) cfgStore.setModel(curProvider.id, m)
                cfgStore.skillId = SkillPresets.ALL[skillSp.selectedItemPosition].id
                cfgStore.temperature = tempEt.text.toString().toDoubleOrNull() ?: 0.4
                cfgStore.maxSteps = stepsEt.text.toString().toIntOrNull() ?: 400
                cfgStore.visionFallback = fbCb.isChecked
                cfgStore.visionMode = visIds[visSel]
                cfgStore.maxOutTokens = maxOutEt.text.toString().toIntOrNull() ?: 0
                cfgStore.contextWindow = ctxWinEt.text.toString().toIntOrNull() ?: 0
                cfgStore.historyLimit = histEt.text.toString().toIntOrNull() ?: 400
                cfgStore.sendTools = sendToolsCb.isChecked
                cfgStore.keepImages = keepImgEt.text.toString().toIntOrNull() ?: 2
                cfgStore.keepToolResults = keepTrEt.text.toString().toIntOrNull() ?: 6
                cfgStore.slimTools = slimToolsCb.isChecked
                cfgStore.personaLock = lockCb.isChecked
                // 空着就沿用原名，不要因为没填就把人格名清空
                if (personaEt.text.toString().isNotBlank()) {
                    cfgStore.personaName = personaEt.text.toString()
                }
                cfgStore.autoSlim = autoSlimCb.isChecked
                val oldTheme = cfgStore.themeMode
                cfgStore.themeMode = themeIds[themeSel]
                // 保存设置绝对不能顺手清空对话——用户只是来调个参数
                dlg.dismiss()
                if (oldTheme != themeIds[themeSel]) {
                    toast("主题已切换，正在刷新界面…")
                    recreate()
                } else {
                    refreshHeader()
                    addSystemLine("设置已保存：${curProvider.label} / ${cfgStore.modelOf(curProvider.id)}（对话已保留）")
                }
            }

            dlg.btn(HxDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                cfgStore.clearKey(curProvider.id)
                keyEt.setText("")
                testResult.text = "已清空 ${curProvider.label} 的 Key"
            }
        }

        testBtn.setOnClickListener {
            val p = curProvider
            val model = modelEt.text.toString().trim().ifEmpty { p.models.first().name }
            val key = keyEt.text.toString()
            val url = urlEt.text.toString()
            val local = url.contains("127.0.0.1") || url.contains("localhost")
            if (key.isBlank() && !local) {
                testResult.text = "先填 Key 再测"
                return@setOnClickListener
            }
            testResult.text = "正在测试 ${p.label} / $model …"
            Thread {
                val r = AiClient(cfgStore.candidate(p, model, key, url)).test()
                main.post { testResult.text = r }
            }.start()
        }

        dlg.show()
    }

    /** Spinner 在没有下拉箭头时容易看着像纯文本，给它加个底 */
    private fun spSpacer(v: android.widget.Spinner): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = roundCard(this@MainActivity, pal.cardAlt, pal.border, 12)
        }
        box.addView(v, LinearLayout.LayoutParams(-1, -2))
        return box
    }

    private fun refreshHeader() {
        val cfg = cfgStore.active()
        val skill = SkillPresets.byId(cfgStore.skillId)
        // projKind 要扫目录、还可能读 index.html，一次调用够用，下面都复用这个布尔值
        // （kindLabel() 内部也是 projKind，别在这儿再调一次）
        val maker = projKind(currentGame) == "maker"
        val kind = if (maker) "Maker" else "H5"

        titleTv.text = "Hexora"
        setTextIf(modeBadge, if (maker) "MAKER" else "H5")
        subTv.text = buildString {
            append("项目 $currentGame")
            append("  ·  ${cfg.provider.label} / ${cfg.modelLabel}")
            append(if (cfg.vision) "  ·  视觉开" else "  ·  视觉关")
            append("  ·  ${skill.label}")
        }

        // 底部胶囊只放模型名 —— 厂商名塞进来会把胶囊撑爆，厂商在面板和状态栏里看得到。
        // ★ 表示「这一对就是默认配置」，让用户随时知道默认是哪一只。
        val star = if (cfgStore.isDefaultPair(cfg.provider.id, cfg.model)) "默认 · " else ""
        setTextIf(modelBtn, "$star${cfg.model} ▴")
        setTextIf(projBtn, currentGame)
        setTextIf(statusProj, "$currentGame · $kind")
        setTextIf(statusModel, "${cfg.provider.label} / ${cfg.model}")
        // 运行中别覆盖状态 —— refreshHeader 也可能在跑的时候被调起来（比如切模型）
        if (!running) setTextIf(statusRun, "待命")
        previewLabel.text = "项目：$currentGame · $kind ⟳"
    }

    // ==================== 小工具 ====================

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun alert(title: String, msg: String) {
        HxDialog.Builder(themed(), pal)
            .setTitle(title)
            .setMessage(msg.take(9000))
            .setPositiveButton("好", null)
            .show()
    }

    private fun copyToClipboard(s: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("path", s))
        toast("已复制")
    }

    private fun showTree() {
        Thread {
            val d = File(gameRoot, currentGame)
            val sb = StringBuilder("${d.absolutePath}\n\n")
            val files = d.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList()
            if (files.isEmpty()) sb.append("(空)")
            files.forEach {
                sb.append(it.relativeTo(d).path).append("   ").append(it.length()).append("B\n")
            }
            main.post { alert("${currentGame} 文件", sb.toString()) }
        }.start()
    }

    private fun showConsole() {
        alert("console 输出", logs.tail(120).joinToString("\n").ifBlank { "(暂无输出)" })
    }

    // ==================== 子任务 ====================

    /** 正在跑的子任务数（避免无限套娃：子任务里再开子任务） */
    @Volatile private var subtaskDepth = 0

    /**
     * 跑一个子任务：**独立上下文**的新助手，只把结论带回主线。
     *
     * ## 为什么这么做
     *
     * 主线跑大项目时，几十轮工具往返会把上下文撑满。把「一个独立小目标」丢出去：
     * 它自己读文件 / 改代码 / 验证（这些过程都发生在**它的**上下文里），
     * 回主线只有一句结论 —— 主线因此能一直保持轻。
     *
     * ## 关键约束
     *
     * · **独立 history**：子任务看不到主对话，所以 goal 必须自带全部信息；
     * · **不污染主 history**：子任务的历史跑完即弃，绝不并进主线；
     * · **防套娃**：子任务里再调 subtask 直接拒绝（否则会指数级爆）；
     * · **用户看得见**：往主对话里发 `SUBTASK:` 事件，界面上能展开看它干了什么。
     *
     * @return 子任务的最终结论（给主线模型看的）
     */
    private fun runSubtask(title: String, goal: String): String {
        if (subtaskDepth > 0) {
            return "子任务里不能再开子任务（会无限套娃）。请直接在当前子任务里完成这件事。"
        }
        val cfgNow = cfgStore.active()
        // 子任务用**当前配置的技能**：用户选的就是他要的做法，不该被子任务改掉
        val skillNow = SkillPresets.byId(cfgStore.skillId)

        // 给用户看见：对话里出现一张「子任务」卡
        main.post { addSubtaskCard(title, "running") }

        // 独立历史：一条 system + 一条 user(goal)。**不带**主对话的任何内容。
        val subHistory = mutableListOf<ChatMsg>()
        val subTools = EngineTools(this, gameRoot)

        // 把子任务的每一行输出转成主对话里的事件，用户能实时看到它在干什么
        val buffer = StringBuilder()
        val depth = subtaskDepth
        val runner = AgentRunner(
            appCtx = this,
            cfg = cfgNow,
            skill = skillNow,
            tools = subTools,
            visionFallback = cfgStore.visionFallback,
            shotDir = File(gameRoot, "_shots"),
            // 子任务同样强制截图自检（同样只在 H5 工程开）
            requireVisualCheck = projKind(currentGame) != "maker",
            projectDir = projDir()
        ) { ev ->
            // 【最内层的刹车】每来一条事件就检查一次「派我的那一轮还在不在」。
            // 不等看门狗（它最多要 1 秒）—— 子任务只要还想再动一下文件，
            // 就会先经过这里，直接把它掐掉，少写一个文件是一个。
            if (runSeq != subtaskOwnerSeq) {
                // 用字段而不是局部名 `runner`：这个 lambda 在 `val runner = ...` 之前
                // 就写好了，里面写 `runner` 会解析到可空的 MainActivity 字段。
                subtaskRunnerRef?.cancel()
                return@AgentRunner
            }
            // 只挑有信息量的往主线转，避免把子任务的几百行噪音灌进主对话。
            //
            // ⚠️ 失败信息**必须**转发。以前这里只认 TOOLRUN / AIFINAL，
            // 于是子任务撞步数上限、请求失败、工具报错时，主对话只显示一句
            // 「（子任务没有产出结论）」—— 用户根本看不到原因，只能反复重试。
            when {
                ev.startsWith("TOOLRUN:") -> {
                    val nm = ev.removePrefix("TOOLRUN:").trim()
                        .removePrefix("[").substringBefore("]")
                    main.post { appendSubtaskLine(title, nm) }
                }
                ev.startsWith("TOOLFAIL:") -> {
                    // 载荷形如 "TOOLFAIL:[名字] 摘要\u0000全文"，只取摘要那一段
                    val nm = ev.removePrefix("TOOLFAIL:").trim().substringBefore('\u0000')
                    main.post { appendSubtaskLine(title, nm.take(90), fail = true) }
                }
                ev.startsWith("[失败]") -> {
                    val why = ev.removePrefix("[失败]").trim().replace('\n', ' ')
                    buffer.append(ev).append('\n')
                    main.post { appendSubtaskLine(title, why.take(90), fail = true) }
                }
                ev.startsWith("INFO: 到步数上限") || ev.startsWith("INFO: 已经用满") -> {
                    buffer.append(ev).append('\n')
                    main.post { appendSubtaskLine(title, "用满轮数上限", fail = true) }
                }
                ev.startsWith("AIFINAL: ") -> buffer.append(ev.removePrefix("AIFINAL: "))
                ev.startsWith("INFO: 完成") -> {}
            }
        }

        subtaskDepth = depth + 1
        // 打上「当前是子任务在执行」的标记：子任务写文件也会触发热重载，
        // 主线看到预览被冲掉时，靠 file_journal 里这个标记才知道是自己派的子任务干的。
        EngineTools.subtaskMode = true
        // 登记到字段上：这样「停止」按钮 / 主线收尾 / 看门狗能真的掐掉它。
        // 同时记下「派我的那一轮」—— 那一轮一旦被顶掉（runSeq 变了），
        // 这个子任务就该立刻停：它没有理由活得比派它的那一轮久。
        subtaskRunnerRef = runner
        subtaskAbandoned = false
        subtaskOwnerSeq = runSeq
        main.removeCallbacks(subtaskWatchdog)
        main.postDelayed(subtaskWatchdog, 1000)
        val result = try {
            runner.run(
                subHistory,
                goal,
                emptyList(),
                resume = false,
                startStep = 0
            )
            // 结论优先用最终回复；没有就退到 buffer / 最后一条 assistant。
            // 仍然没有的话，把**最后一条失败原因**带上 —— 只回一句「没有产出结论」
            // 等于把「为什么没成」藏起来了，用户只能反复重试。
            val finalText = subHistory.lastOrNull { it.role == "assistant" && !it.text.isNullOrBlank() }
                ?.text?.takeIf { it.isNotBlank() }
                ?: buffer.toString().takeIf { it.isNotBlank() }
                ?: run {
                    val why = buffer.lineSequence()
                        .lastOrNull { it.startsWith("[失败]") || it.startsWith("INFO: 到步数上限") }
                    if (why.isNullOrBlank()) "（子任务没有产出结论）"
                    else "（子任务没有产出结论）\n最后一条错误：${why.take(300)}"
                }
            finalText.take(6000)
        } catch (t: Throwable) {
            "子任务出错：${t.javaClass.simpleName}: ${t.message}"
        } finally {
            EngineTools.subtaskMode = false
            subtaskDepth = depth
            subtaskRunnerRef = null
            subtaskOwnerSeq = -1
            main.removeCallbacks(subtaskWatchdog)
        }

        // 已经被放弃（用户按了停止 / 主线这一轮已经收尾）：
        // **不要再动界面、不要刷预览** —— 用户要的是「停下来」。
        // 之前这里无条件刷新预览，等于被中止的子任务还要去重载一次用户的页面。
        if (subtaskAbandoned) {
            main.post { subtaskCards.remove(title)?.let { /* 卡片留个痕迹即可，不刷预览 */ } }
            return "（子任务已中止：主线这一轮已经结束，不再继续改文件）"
        }

        main.post {
            finishSubtaskCard(title, result)
            // 子任务改过代码 → 刷新预览
            ensurePreviewFresh()
        }
        return result
    }

    /**
     * 中止正在跑的子任务。
     *
     * 由两处调用：① 用户按「停止」；② 主线这一轮收尾（正常完成 / 出错 / 被停）。
     *
     * 为什么主线收尾也要停子任务：子任务是主线派出去的**辅助**，
     * 主线都说「我做完了」，辅助却还在后台跑几十轮、继续写文件、继续热重载 ——
     * 用户看到的就是「他都完成任务了，子任务还在动我的文件」。
     */
    private fun abandonSubtask() {
        val r = subtaskRunnerRef ?: return
        main.removeCallbacks(subtaskWatchdog)
        // 幂等：看门狗和 onEvent 里的刹车可能同时打到，只提示一次
        if (subtaskAbandoned) {
            runCatching { r.cancel() }
            return
        }
        subtaskAbandoned = true
        runCatching { r.cancel() }
        main.post { addSystemLine("已中止还在后台跑的子任务（主线这一轮已经结束，不再继续改文件）") }
    }

    /** 对话里那张「子任务」卡：标题 + 状态 + 过程行 */
    private val subtaskCards = mutableMapOf<String, LinearLayout>()

    private fun addSubtaskCard(title: String, state: String) {
        if (!::chatList.isInitialized) return
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundCard(this@MainActivity, pal.cardAlt, pal.border, 12)
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(iconView(this@MainActivity, pal, "puzzle", 14, pal.accent),
                LinearLayout.LayoutParams(dp(15), dp(15)).apply { rightMargin = dp(6) })
            addView(TextView(this@MainActivity).apply {
                text = "子任务 · $title"
                textSize = 13.5f
                typeface = MEDIUM
                setTextColor(pal.accent)
            })
        })
        val lines = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        card.addView(lines)
        card.tag = lines          // 过程行挂这儿，后面往里追加
        subtaskCards[title] = card
        chatList.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        scrollChatToBottom()
    }

    private fun appendSubtaskLine(title: String, tool: String, fail: Boolean = false) {
        val card = subtaskCards[title] ?: return
        val lines = card.tag as? LinearLayout ?: return
        // 最多显示 6 行，再多就折成一行「…」
        if (lines.childCount >= 6) {
            (lines.getChildAt(lines.childCount - 1) as? TextView)?.text = "  …（还有更多步骤）"
            return
        }
        lines.addView(TextView(this).apply {
            text = "  · $tool"
            textSize = 11.5f
            setTextColor(if (fail) TOOL_FAIL else pal.sub)
        })
        scrollChatToBottom()
    }

    private fun finishSubtaskCard(title: String, result: String) {
        val card = subtaskCards.remove(title) ?: return
        val lines = card.tag as? LinearLayout
        // 子任务失败时**不能**还打「完成」—— 用户看到「✓ 完成 + 没有产出结论」
        // 只会以为是自己哪里点错了。这里按结论内容判定真实状态。
        val failed = result.startsWith("子任务出错") || result.contains("没有产出结论") ||
            result.contains("到步数上限") || result.contains("请求失败")
        lines?.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(iconView(this@MainActivity, pal, if (failed) "close" else "check", 12,
                if (failed) TOOL_FAIL else pal.accent),
                LinearLayout.LayoutParams(dp(13), dp(13)).apply { rightMargin = dp(5) })
            addView(TextView(this@MainActivity).apply {
                text = if (failed) "未完成" else "完成"
                textSize = 11.5f
                setTextColor(if (failed) TOOL_FAIL else pal.accent)
            })
        })
        // 结论折在卡片里，点一下展开 —— 不占对话正文
        val head = result.lineSequence().firstOrNull()?.take(60).orEmpty()
        card.addView(TextView(this).apply {
            text = "结论：$head"
            textSize = 12f
            setTextColor(pal.text)
            setPadding(0, dp(6), 0, 0)
            isClickable = true
            setOnClickListener {
                HxDialog.Builder(themed(), pal)
                    .setTitle("子任务「$title」的结论")
                    .setMessage(result.take(20_000))
                    .setPositiveButton("好", null)
                    .show()
            }
        })
        scrollChatToBottom()
    }

    // ==================== git ====================

    /**
     * git 操作菜单 —— 对**当前项目**做版本管理。
     *
     * 用 App 自带的 git（`assets/gitrt.tar` 里那份 musl/aarch64 版），
     * 不要求用户另外装 —— 手机上也装不了。
     */
    private fun showGitMenu() {
        val dir = File(gameRoot, currentGame)
        val items = arrayOf(
            if (GitTools.isRepo(this, dir)) "查看状态" else "初始化仓库",
            "查看改动",
            "查看提交历史",
            "提交全部改动",
            "设置 / 查看远端",
            "测试连接",
            "推送到远端",
            "从远端拉取"
        )
        HxDialog.Builder(themed(), pal)
            .setTitle("git · ${currentGame}")
            .setItems(items) { i ->
                when (i) {
                    0 -> gitDo(dir) { if (GitTools.isRepo(this, dir)) GitTools.status(this, dir) else GitTools.init(this, dir) }
                    1 -> gitDo(dir) { GitTools.diffStat(this, dir) }
                    2 -> gitDo(dir) { GitTools.log(this, dir, 30) }
                    3 -> gitCommit(dir)
                    4 -> gitRemote(dir)
                    5 -> gitTestConnection(dir)
                    6 -> gitConfirmPush(dir)
                    7 -> gitDo(dir) { GitTools.pull(this, dir) }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 测试连接：填地址 + token，点一下验证「能不能访问 / 能不能推」。
     *
     * 结果用一张**带状态色的卡**回显（成功绿、失败红），而不是弹一串 git 原文 ——
     * 手机上看 git 的报错基本等于没看。真正的判断在 GitTools.testConnection 里做。
     */
    private fun gitTestConnection(dir: File) {
        // Maker 工程的 origin 指向平台仓库，不是用户的 GitHub —— 不预填，
        // 免得用户拿它去「测试连接」然后一头雾水。
        val rawUrl = GitTools.remoteUrl(this, dir)
        val curUrl = if (GitTools.isMakerRemote(rawUrl)) "" else rawUrl
        val urlBox = hxField(this, pal, "仓库地址", curUrl, hint = "https://github.com/用户名/仓库.git")
        val tokBox = hxField(this, pal, "Token（可留空测公开仓库）", "", password = true,
            hint = "ghp_… 或 用户名:token")
        val out = TextView(this).apply {
            textSize = 12.5f
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(12), dp(11), dp(12), dp(11))
            visibility = View.GONE
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(urlBox, LinearLayout.LayoutParams(-1, -2))
            addView(tokBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(out, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(hxNote(this@MainActivity, pal,
                "地址与 token 会用于本次测试；点「保存并测试」会同时写入远端配置。"))
        }
        val dlg = HxDialog.Builder(themed(), pal)
            .setTitle("测试连接")
            .setMessage("验证这个远端能不能访问、当前 token 有没有推送权限。")
            .setView(box)
            .setPositiveButton("保存并测试", null)
            .setNegativeButton("取消", null)
            .create()
        dlg.setOnShowListener { d ->
            d.btn(HxDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val u = (urlBox.tag as? EditText)?.text?.toString()?.trim().orEmpty()
                val t = (tokBox.tag as? EditText)?.text?.toString()?.trim().orEmpty()
                if (u.isBlank()) {
                    out.visibility = View.VISIBLE
                    out.text = "先填仓库地址。"
                    out.setTextColor(pal.errText)
                    return@setOnClickListener
                }
                out.visibility = View.VISIBLE
                out.setTextColor(pal.sub)
                out.text = "正在测试…"
                Thread {
                    // 顺手把地址存成远端（省一步），再用「带 token 的地址」测
                    GitTools.setRemote(this, dir, u)
                    val r = GitTools.testConnection(this, dir, u, t)
                    main.post {
                        out.setTextColor(if (r.ok) pal.accent else pal.errText)
                        out.text = (if (r.ok) "✓ " else "✗ ") + r.title + "\n\n" + r.detail
                        toast(if (r.ok) "连接正常" else "连接失败：${r.title}")
                    }
                }.start()
            }
        }
        dlg.show()
    }

    /**
     * 「下载最新版 APK」：问 GitHub 要 latest release，把**公开直链**给用户。
     *
     * 为什么做这个：CI 每次构建都会自动发 Release，但让用户自己去 GitHub 翻
     * Releases 页、挑版本、下载，在手机上很烦；Actions 的 artifact 还要登录才能下。
     * 这里一次点击就拿到直链，点一下直接用系统浏览器下载。
     */
    private fun showLatestApk() {
        toast("正在查最新版本…")
        Thread {
            val rel = GitTools.latestRelease()
            main.post {
                if (rel == null) {
                    alert(
                        "查不到最新版本",
                        "可能的原因：\n" +
                            "  · 手机当前网络连不上 GitHub（国内常见，挂个代理再试）；\n" +
                            "  · 仓库还没有发布过 Release。\n\n" +
                            "也可以手动打开：\n" +
                            "https://github.com/liusu239113/h5-mcp-engine/releases"
                    )
                    return@post
                }
                val mb = if (rel.sizeBytes > 0) "（${rel.sizeBytes / 1024 / 1024} MB）" else ""
                HxDialog.Builder(themed(), pal)
                    .setTitle("最新版 ${rel.tag} $mb")
                    .setMessage(
                        "点「下载」会用系统浏览器打开公开直链 —— **不需要登录 GitHub**。\n\n" +
                            rel.apkUrl
                    )
                    .setPositiveButton("下载") { -> openExternal(rel.apkUrl) }
                    .setNeutralButton("复制链接") { -> copyToClipboard(rel.apkUrl) }
                    .setNegativeButton("关闭", null)
                    .show()
            }
        }.start()
    }

    /** 跑一条 git 命令并把输出摊给用户看 */
    private fun gitDo(dir: File, op: () -> GitTools.Res) {
        toast("执行中…")
        Thread {
            val r = op()
            main.post {
                alert(
                    if (r.ok) "git 完成" else "git 失败（退出码 ${r.code}）",
                    GitTools.maskToken(r.brief(120))
                )
            }
        }.start()
    }

    private fun gitCommit(dir: File) {
        // 先把「要提交什么」摊出来 —— 提交前让用户看清是规矩
        Thread {
            val st = GitTools.status(this, dir)
            main.post {
                if (st.out.isBlank()) {
                    toast("没有改动可提交")
                    return@post
                }
                val et = hxInput(this, pal, "提交说明")
                HxDialog.Builder(themed(), pal)
                    .setTitle("要提交这些改动")
                    .setMessage(GitTools.maskToken(st.brief(60)) + "\n\n填一句提交说明：")
                    .setView(et)
                    .setPositiveButton("提交") { ->
                        val m = et.text.toString().trim().ifBlank { "更新" }
                        gitDo(dir) { GitTools.commit(this, dir, m) }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }.start()
    }

    private fun gitRemote(dir: File) {
        val cur = GitTools.remoteUrl(this, dir)
        // Maker 工程初始化时会自动配一个指向 maker.taptap.cn 的 origin（平台同步用）。
        // 直接把它显示在「远端地址」里，用户会以为那是自己的仓库、然后去推它 ——
        // 真实反馈就是这么来的。这里不预填、并说明它不是给你推的。
        val isMaker = GitTools.isMakerRemote(cur)
        val et = hxInput(this, pal, "https://github.com/用户名/仓库.git").apply {
            // ⚠️ 绝不把 maskToken 的结果回填进输入框：那是 https://***@github.com/…
            // 用户不重输直接保存，就会把真 token 覆盖成字面量 ***，之后推送全失败。
            if (cur.isNotBlank() && !isMaker) setText(cur)
        }
        HxDialog.Builder(themed(), pal)
            .setTitle("远端地址")
            .setMessage(
                if (isMaker)
                    "⚠️ 这个项目当前连的是 **Maker 平台的仓库**（maker.taptap.cn），" +
                        "那是平台同步代码用的，不是你的 GitHub 仓库、也不能往那儿推。\n\n" +
                        "要给它做版本管理，就在下面填你自己的 GitHub 仓库地址。"
                else
                    "私有仓库可以在地址里带上 token，或用「用户名:token@」的形式。"
            )
            .setView(et)
            .setPositiveButton("保存") { ->
                val u = et.text.toString().trim()
                if (u.isNotBlank()) gitDo(dir) { GitTools.setRemote(this, dir, u) }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun gitConfirmPush(dir: File) {
        val url = GitTools.remoteUrl(this, dir)
        // 推之前先自检：没有远端 / 远端是 Maker 平台仓库 / 一次提交都没有 ——
        // 这三种情况 git 只会甩一句看不懂的报错，直接说人话。
        val blocker = GitTools.pushBlocker(this, dir)
        if (blocker != null) {
            HxDialog.Builder(themed(), pal)
                .setTitle("现在还不能推")
                .setMessage(blocker + if (url.isNotBlank()) "\n\n当前远端：${GitTools.maskToken(url)}" else "")
                .setPositiveButton("好", null)
                .show()
            return
        }
        // 推送是对外可见的操作 —— 必须用户明确确认
        HxDialog.Builder(themed(), pal)
            .setTitle("推送到远端？")
            .setMessage("远端：${GitTools.maskToken(url)}\n\n这会把当前分支推上去，别人能看到。")
            .setPositiveButton("推送") { -> gitDo(dir) { GitTools.push(this, dir) } }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== Shizuku（ADB 级提权） ====================

    /** 那行入口的副标题：把当前状态直接写出来，用户一眼看到「能不能用」 */
    private fun shizukuSubtitle(): String {
        val s = Shizuku2.describe()
        return if (Shizuku2.isReady())
            "已就绪：可读受保护目录 / 以 shell 身份跑命令"
        else "未启用（$s）—— 开启后可读 /sdcard 下被 scoped storage 挡住的文件"
    }

    /** 状态变了就刷新那行副标题（Shizuku 的 binder 回调里触发） */
    private fun refreshShizukuRow() {
        shizukuRowView?.let { (row, sub) ->
            sub.text = shizukuSubtitle()
            row.visibility = View.VISIBLE
        }
    }

    /** 存一下那行的引用，好在状态变化时改文案（存的是行容器和副标题两个 view） */
    private var shizukuRowView: Pair<LinearLayout, TextView>? = null

    /**
     * Shizuku 入口：按「装没装 / 跑没跑 / 授没授权」给不同引导。
     *
     * 这三步**只能用户自己做**（装 Shizuku、无线调试启动服务、点授权），
     * 所以这里把话说清楚，而不是甩一个「失败」。
     */
    private fun askShizuku() {
        when {
            !Shizuku2.isInstalled(this) -> alert(
                "先装 Shizuku",
                "Shizuku 是个开源工具，让普通 App 能借用 ADB 级权限（不用 root）。\n\n" +
                    "① 去应用商店搜「Shizuku」装上（或 GitHub 下 rikka 的 Shizuku）\n" +
                    "② 打开它，按引导用「无线调试」启动服务\n" +
                    "③ 回到本 App，再点一次这个入口\n\n" +
                    "装好之后，就能读 /sdcard 下被系统挡住的文件了。"
            )

            !Shizuku2.isServiceRunning() -> alert(
                "Shizuku 没在运行",
                "Shizuku 装好了，但服务没启动。\n\n" +
                    "打开 Shizuku App，按它的提示：\n" +
                    "  设置 → 开发者选项 → 无线调试 → 配对\n" +
                    "配对成功后，用 Shizuku 的快捷开关启动服务。\n\n" +
                    "启动后回到这里，再点一次这个入口。"
            )

            !Shizuku2.hasPermission() -> Shizuku2.request { ok ->
                if (ok) {
                    toast("Shizuku 已授权")
                    refreshShizukuRow()
                } else {
                    toast("没有授权 —— 可以再点一次试试")
                }
            }

            else -> {
                // 「已授权」不等于「真能用」：服务端 API 版本不匹配时 binder 活着、
                // 权限也有，但 AIDL 调用会失败 —— 只报状态的话用户会看到
                // 「明明开了却用不了」。所以这里**真跑一条命令**验一下。
                toast("正在实测 Shizuku 权限…")
                Thread {
                    val report = Shizuku2.diagnose() + "\n" + Shizuku2.probe()
                    main.post {
                        alert(
                            "Shizuku 已就绪",
                            "当前权限：ADB 级（shell）。\n\n" +
                                "现在 AI 可以直接读 /sdcard 下的文件了（包括之前被 scoped storage " +
                                "挡住的那些），也能以 shell 身份跑命令。\n\n" +
                                "试试对 AI 说：「用 shell 看看 /sdcard 下有什么」" +
                                "或者「把某个文件夹扫一遍」。\n\n" +
                                "—— 诊断 ——\n" + report
                        )
                    }
                }.start()
            }
        }
    }

    private fun pickGame() {
        val dirs = gameRoot.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith("_") }
            ?.map { it.name }?.sorted() ?: emptyList()
        if (dirs.isEmpty()) {
            toast("还没有游戏，让 AI 先建一个")
            return
        }
        HxDialog.Builder(themed(), pal)
            .setTitle("选择游戏")
            .setItems(dirs.toTypedArray()) { i ->
                openGame(dirs[i])
                refreshHeader()
                addSystemLine("已切换到 ${dirs[i]}")
            }
            .show()
    }

    // ==================== WebView ====================

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                safeBrowsingEnabled = false
            }
        }
        web.webChromeClient = WebChromeClient()
        web.setBackgroundColor(pal.bg)

        bridge = EngineBridge(web, File(gameRoot, currentGame), logs)
        web.addJavascriptInterface(bridge, "Native")

        val loader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            // 同样包一层静音闸门：引擎自带页面 / 示例页也是 HTML。
            // 漏掉这条通道的话，从 /assets/ 进来的页面切页后照样会响。
            .addPathHandler("/assets/", GameMuteInjectHandler(WebViewAssetLoader.AssetsPathHandler(this)))
            .addPathHandler(
                "/games/",
                // 包一层：每个游戏 HTML 的最前面插入「静音闸门」，
                // 保证它先于游戏自己的脚本执行（AudioContext 一建出来就被接管）。
                GameMuteInjectHandler(WebViewAssetLoader.InternalStoragePathHandler(this, gameRoot))
            )
            .addPathHandler("/lib/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        // 文档开始注入：CSP 拦不住、早于页面自己的脚本、每个 iframe 都会执行。
        // 上面那条「插进 HTML」的老路继续留着（没 CSP 的页面走它更快），
        // 两条路装的是同一段闸门，重复执行会被 __hexoraMuteInstalled 挡掉。
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(web, GAME_MUTE_BOOTSTRAP_JS, setOf("*"))
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // 页面换了（重载 / 切项目）：注入脚本默认是安静的，这里把「当前该不该出声」
                // 重新对齐一次 —— 停在「预览」页就放声，其它页保持静音，
                // 否则重载后 BGM 会在对话页自己响起来。
                applyPreviewMute(userMutePreview || activeTab != 1)
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val res = loader.shouldInterceptRequest(request.url)
                if (res != null) return res
                if (request.url.host == "appassets.androidplatform.net") {
                    val msg = "资源不存在：${request.url.path}\n游戏目录：${File(gameRoot, currentGame).absolutePath}"
                    return WebResourceResponse(
                        "text/plain", "utf-8", 200, "OK",
                        mapOf("Cache-Control" to "no-store"),
                        ByteArrayInputStream(msg.toByteArray())
                    )
                }
                return null
            }
        }
    }

    // ==================== GameUi 实现 ====================

    override fun currentGameId(): String = currentGame

    /** 给 context_budget 工具用的历史快照（拷贝一份，避免和正在跑的一轮抢同一个表） */
    override fun contextSnapshot(): List<ChatMsg> = runCatching {
        synchronized(history) { history.toList() }
    }.getOrDefault(emptyList())

    override fun openGame(id: String) {
        // 切项目时先把「挂起的预览重载」取消掉：
        // 否则上一次切项目排的那个 900ms 任务会在新项目加载完之后才跑，
        // 把画面又带回旧状态（用户看到的就是「预览没跟着切」）。
        main.removeCallbacks(previewReloadTask)
        previewDirty = false

        // ⚠️ **项目没变就什么都别做**。
        //
        // 这里曾经无条件往下跑，而末尾会 reloadSessionsForProject() ——
        // 那一步是 `history = sessions[activeSession].msgs`，**从磁盘重新读**。
        // 于是「AI 调一次 game_launch（打开当前这个游戏）」就会把内存里
        // 这一轮刚攒的对话整个换掉：还没落盘的 assistant 回复 / 工具结果全没了，
        // 用户看到的就是**对话突然失忆**（正在跑的上下文凭空消失）。
        //
        // game_launch 是 AI 很常调的工具（启动/切换游戏），所以这个坑极易触发。
        if (id == currentGame) return
        {
            // 切项目 = 换一份会话库：先把旧项目这轮对话落盘，再切新库
            runCatching { persistSessions() }
            sessStore = null
            // 再把「跟着上一个项目走」的东西一并清掉，否则界面看着像没换干净
            // （用户反馈：「切换项目了好像还残留之前的东西，比如底部啥的」）：
            //   · 待发送区的附件：路径指向旧项目的 _uploads/…，发出去必然读不到
            //   · 待发送队列：是给旧项目排的
            //   · 底部那两行：运行状态行还留着上一轮的「运行中 / 已完成 · 11 轮」
            msgQueue.clear()
            attached.clear()
            queued.clear()
            pendingShots.clear()
            runBar?.visibleIf(false)
            main.post {
                runCatching { updateAttachInfo() }
                runCatching { renderQueue() }
            }
        }
        currentGame = id
        cfgStore.lastGame = id   // 落盘：供下次冷启动 / 覆盖安装后恢复
        val dir = File(gameRoot, id).apply { mkdirs() }
        bridge.setSandbox(dir)
        // Maker 桥把项目路径写在启动参数里：切项目必须重启它，
        // 否则你让它生图，素材会落进「上一个项目」。
        Thread { runCatching { McpBoot.ensure(this@MainActivity) { } } }.start()
        lastLoadedGame = id
        lastLoadedStamp = dirStamp(dir)
        // 切项目：老版本放在工程根的工作区素材并入这个项目（只复制，不动原文件）
        migrateLegacyWorkspace()
        val url = "https://appassets.androidplatform.net/games/$id/index.html"
        main.post {
            logs.add("[system] 打开项目 $id")
            // 该项目的对话历史重新装载（项目之间绝不共享上下文）
            reloadSessionsForProject()
            loadPreviewFor(dir, id)
        }
    }

    override fun reloadGame() {
        main.post {
            // Maker 工程只能走 GeckoView（系统 WebView 跑不了 UrhoX 引擎）
            if (isMakerProject()) {
                showMakerPreview(File(gameRoot, currentGame), currentGame)
            } else {
                hideMakerPreview()
                web.reload()
            }
        }
    }

    override fun runJsSync(code: String, timeoutMs: Int): String {
        if (Looper.myLooper() == Looper.getMainLooper()) return "\"不能在主线程执行 JS\""

        val latch = CountDownLatch(1)
        val holder = AtomicReference("null")

        main.post {
            runCatching {
                web.evaluateJavascript(code) { v ->
                    holder.set(v ?: "null")
                    latch.countDown()
                }
            }.onFailure {
                holder.set(JSONObject.quote("eval 失败: ${it.message}"))
                latch.countDown()
            }
        }

        val ok = latch.await(timeoutMs.toLong().coerceIn(500, 60_000), TimeUnit.MILLISECONDS)
        return if (ok) holder.get() else "\"超时 (${timeoutMs}ms)\""
    }

    // ---------------- 当前页（只读，给 AI 用） ----------------
    // 注意：**不给 AI 切页能力** —— 切页会动用户正在看的屏幕（用户明确不许）。
    override fun currentTab(): Int = activeTab

    /**
     * 离屏抓「游戏预览」画面 —— **完全不动用户眼前的界面**（不切页、不改可见性、不点击）。
     * 先把常驻的游戏 WebView 按屏幕尺寸排一次版，draw 到内存 Bitmap，再还原尺寸。
     * WebView 是硬件加速的，离屏 draw 在部分机型上会拿到纯色图 —— 那种情况直接返回 null。
     */
    override fun snapshotGameOffscreen(maxWidth: Int, cssW: Int, cssH: Int): ByteArray? {
        if (!::web.isInitialized) return null
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        // ① CDP：最完整（DOM + canvas + WebGL 全在），连「卡在加载页」都抓得到，
        //    而且同样不碰用户的屏幕 —— 用户选的就是这条。
        //    cssW/cssH 传了就用它当「模拟的真机视口」，不传用当前设备整屏。
        shootViaCdp(viewportW = cssW, viewportH = cssH)?.let { if (it.size > 1024) return scaleJpeg(it, maxWidth) }
        // ② 退一步：直接从页面里取 canvas。更轻，但只有 canvas 类游戏有，
        //    加载页 / 纯 DOM 排版那种拿不到。
        shootFromPage()?.let { if (it.size > 1024) return scaleJpeg(it, maxWidth) }
        // ③ 最后的兜底 web.draw —— 硬件加速的 WebView 基本抓不到。
        //    三条都失败就如实返回 null：**绝不为了截图去翻页**（用户明确不许动他的屏幕）。
        val dm = resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1)
        val h = dm.heightPixels.coerceAtLeast(1)
        var shot: android.graphics.Bitmap? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        main.post {
            runCatching {
                val ow = web.width
                val oh = web.height
                val need = ow < dp(80) || oh < dp(80)
                if (need) {
                    web.measure(
                        android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY)
                    )
                    web.layout(0, 0, w, h)
                }
                val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                web.draw(android.graphics.Canvas(b))
                shot = b
                if (need && ow > 0 && oh > 0) {
                    web.measure(
                        android.view.View.MeasureSpec.makeMeasureSpec(ow, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(oh, android.view.View.MeasureSpec.EXACTLY)
                    )
                    web.layout(0, 0, ow, oh)
                }
            }
            latch.countDown()
        }
        runCatching { latch.await(3, java.util.concurrent.TimeUnit.SECONDS) }
        // 直接抓 WebView 失败（**多数机型都会失败**：硬件加速的 WebView 内容在 Chromium
        // 自己的图层里，不在 View 的 Canvas 上）。拿不到就如实返回 null，
        // **不翻页、不切可见性** —— 用户明确不许动他正在看的屏幕。
        val b = shot ?: return null
        if (looksBlank(b)) {
            b.recycle()
            return null
        }
        return runCatching {
            val tw = maxWidth.coerceIn(160, w)
            val scaled =
                if (tw < w) android.graphics.Bitmap.createScaledBitmap(b, tw, (h * tw / w).coerceAtLeast(1), true)
                else b
            val baos = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 72, baos)
            if (scaled !== b) scaled.recycle()
            b.recycle()
            baos.toByteArray()
        }.getOrNull()
    }

    /**
     * 通过 Chrome DevTools 协议（CDP）抓一张游戏页面的**真实渲染**。
     *
     * 这是三条路里最完整的一条：DOM、canvas、WebGL 全都在图里，
     * 连「还卡在加载页」那种也能抓到 —— 而它同样**不碰用户的屏幕**。
     *
     * 为什么另外两条都不行：
     *   · `web.draw()`：WebView 是硬件加速的，画面在 Chromium 自己的图层里，不在 View 的 Canvas 上；
     *   · PixelCopy：能拿到，但要求页面**在屏幕上可见**（那就是翻页，用户已明确否决）。
     * CDP 的 Page.captureScreenshot 走的是**浏览器自己的合成器**，页面在不在屏幕上都能出图。
     *
     * 通道：开了调试开关之后，WebView 会在本机监听一个抽象 unix socket
     * `webview_devtools_remote_<本进程 pid>`。报文格式：一条 JSON + 一个 '\0' 结束符。
     * 抓图要走三步（这个端点先是**浏览器级**的，得先挂到具体的页面 target 上）：
     * Target.getTargets → Target.attachToTarget(flatten) → Page.captureScreenshot。
     */
    private fun shootViaCdp(
        timeoutMs: Int = 3500,
        /** 模拟的真机视口（CSS 像素）。传了就用它渲染；不传用当前设备整屏 */
        viewportW: Int = 0,
        viewportH: Int = 0
    ): ByteArray? {
        val name = "webview_devtools_remote_" + android.os.Process.myPid()
        val s = android.net.LocalSocket()
        return try {
            s.connect(
                android.net.LocalSocketAddress(name, android.net.LocalSocketAddress.Namespace.ABSTRACT),
                timeoutMs
            )
            s.soTimeout = timeoutMs
            val out = s.outputStream
            val ins = s.inputStream

            // ① 找一个页面 target
            val targets = cdpCall(out, ins, 1, "Target.getTargets", null)
            val list = targets?.optJSONObject("result")?.optJSONArray("targetInfo")
            var pageId = ""
            if (list != null) {
                for (i in 0 until list.length()) {
                    val t = list.optJSONObject(i) ?: continue
                    if (t.optString("type") == "page") {
                        pageId = t.optString("targetId")
                        break
                    }
                }
            }
            if (pageId.isEmpty()) return null

            // ② 挂到它上面（flatten = 后续指令带上 sessionId，走同一条连接）
            val att = cdpCall(
                out, ins, 2, "Target.attachToTarget",
                JSONObject().put("targetId", pageId).put("flatten", true)
            )
            val sid = att?.optJSONObject("result")?.optString("sessionId", "").orEmpty()
            if (sid.isEmpty()) return null

            // ③ 抓图。
            //
            // **fromSurface 默认（true）= 抓真实合成结果** —— 这正是我们要的：
            // 拿到的就是屏幕上那一帧（DOM 文字 + canvas + WebGL 全在）。
            // 我一开始传了 false，以为「不依赖合成面更保险」，其实反了：
            // false 走的是「从主框架渲染」那条路，**不含跨进程 iframe**，
            // 而且渲染管线略有差别 —— 出来的图会跟用户看到的不完全一致。
            // 现在不传它（用默认 true），并且显式打开 captureBeyondViewport 之外的两个开关：
            //   · optimizeForSpeed=false：优先保真，不做有损加速
            // 【关键】截图前把视口临时撑到**整屏**。
            //
            // 为什么必须这么做：预览槽位只占屏幕的一部分，CDP 默认按那个**小视口**
            // 渲染 —— 截出来的图和用户按「全屏」玩的时候根本不是一回事：
            // 字更小、布局更挤、细节糊在一起。用户的原话是
            // 「截图验证没走全屏，看不清楚，跟我肉眼实际玩的体验不一样」。
            //
            // Emulation.setDeviceMetricsOverride 只改**渲染用的视口尺寸**，
            // 不切页、不改可见性、不动用户眼前的界面；截完立刻 clear 还原。
            //
            // 例外：用户**正停在预览页**时不做覆盖 —— 那种时候当前视口就是他眼睛
            // 看到的东西，按原样截反而更准。
            // 视口尺寸：调用方指定了「模拟真机尺寸」就用它（任何 tab 下都覆盖 ——
            // 那正是「我要按这个尺寸看」的意思）；没指定就在非预览页按整屏来。
            val explicit = viewportW > 0 && viewportH > 0
            val wantOverride = explicit || activeTab != 1

            /** 发一条 captureScreenshot 并把 base64 解成字节；拿不到返回 null */
            fun capture(id: Int, beyond: Boolean): ByteArray? {
                val r = cdpCall(
                    out, ins, id, "Page.captureScreenshot",
                    JSONObject().put("format", "jpeg").put("quality", 80)
                        .put("captureBeyondViewport", beyond)
                        .put("optimizeForSpeed", false),
                    sid
                ) ?: return null
                val d = r.optJSONObject("result")?.optString("data", "").orEmpty()
                if (d.isEmpty()) return null
                return runCatching { android.util.Base64.decode(d, android.util.Base64.DEFAULT) }.getOrNull()
            }

            // ── 尝试 A：按整屏视口覆盖后截（图最接近用户全屏看到的样子）──
            //
            // 覆盖是**尽力而为**的：CDP 版本 / 页面状态都可能让 setDeviceMetricsOverride
            // 失败或让合成器不出帧。所以它一旦没成功、或截出来是空的，
            // 必须能退回「不带覆盖」那条路 —— 否则就是「本来抓得到，改完反而抓不到了」
            // （用户反馈的正是这个）。**截图这个能力本身不能因为一次优化而变脆。**
            var overrode = false
            if (wantOverride) {
                val dm = resources.displayMetrics
                val dpr = dm.density.takeIf { it > 0f } ?: 1f
                val cssW = if (explicit) viewportW else (dm.widthPixels / dpr).toInt().coerceAtLeast(320)
                val cssH = if (explicit) viewportH else (dm.heightPixels / dpr).toInt().coerceAtLeast(480)
                val resp = cdpCall(
                    out, ins, 3, "Emulation.setDeviceMetricsOverride",
                    JSONObject().put("width", cssW).put("height", cssH)
                        .put("deviceScaleFactor", dpr.toDouble())
                        .put("mobile", true),
                    sid
                )
                // 明确检查有没有 error —— 只看「非 null」会把失败响应当成成功
                overrode = resp != null && resp.optJSONObject("error") == null
                // 等页面重排完再截：游戏都在 window.resize 里重算 canvas 尺寸，
                // 不等的话截到的还是旧尺寸 canvas（图里只有上半部分有内容）。
                // 350ms 是个折中：够重排，又不至于把 CDP socket 的超时预算吃光。
                if (overrode) runCatching { Thread.sleep(350) }
            }

            // 覆盖成功时按视口尺寸截（文档≈视口，不会多出空白）；
            // 没覆盖时用 beyond=true（这是**原来就能抓到图**的那套参数，保底）。
            var bytes = capture(4, beyond = !overrode)

            if (overrode) {
                runCatching { cdpCall(out, ins, 5, "Emulation.clearDeviceMetricsOverride", null, sid) }
            }

            // ── 尝试 B：兜底。A 没拿到图（或拿到的是坏图）就不带覆盖再来一次 ──
            if (bytes == null || bytes.size < 1024) {
                bytes = capture(6, beyond = true)
            }

            bytes ?: return null
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { s.close() }
        }
    }

    /**
     * 发一条 CDP 指令并等它的响应。
     *
     * 同一条连接上会夹着各种事件（还有别的 id 的响应），所以按 id 挑 ——
     * 直接把第一条可解析的消息当答案，会拿错。
     */
    private fun cdpCall(
        out: java.io.OutputStream,
        ins: java.io.InputStream,
        id: Int,
        method: String,
        params: JSONObject?,
        sessionId: String? = null
    ): JSONObject? {
        val req = JSONObject().put("id", id).put("method", method)
        params?.let { req.put("params", it) }
        sessionId?.let { req.put("sessionId", it) }
        out.write((req.toString() + "\u0000").toByteArray(Charsets.UTF_8))
        out.flush()

        val buf = StringBuilder()
        val tmp = ByteArray(16 * 1024)
        val deadline = System.currentTimeMillis() + 3500
        while (System.currentTimeMillis() < deadline) {
            var end = buf.indexOf("\u0000")
            while (end >= 0) {
                val msg = buf.substring(0, end)
                buf.delete(0, end + 1)
                val o = runCatching { JSONObject(msg) }.getOrNull()
                if (o != null && o.optInt("id", -1) == id) return o
                end = buf.indexOf("\u0000")
            }
            val n = runCatching { ins.read(tmp) }.getOrDefault(-1)
            if (n <= 0) return null
            buf.append(String(tmp, 0, n, Charsets.UTF_8))
        }
        return null
    }

    /**
     * 让 AI 看一眼游戏画面 —— **全程不碰用户的屏幕**（不切页、不改可见性、不打断）。
     *
     * 为什么不能用 `web.draw()`：WebView 是**硬件加速**渲染的，画面在 Chromium 自己的
     * 图层里，根本不在 View 的 Canvas 上 —— 那条路只会拿到一张空白。
     * 为什么不能翻到预览页再 PixelCopy：那会动用户正在看的屏幕（用户明确不许）。
     * 剩下唯一可行的路就是**让页面自己把 canvas 吐出来**：它跟窗口无关，切不切页都成立。
     *
     * 代价：只有 canvas 类游戏抓得到（2D / WebGL 都行）。纯 DOM 排版的页面没有 canvas，
     * 这时如实返回 null，由工具那边告诉模型「改用逻辑验证」。
     */
    private fun shootFromPage(): ByteArray? {
        val direct = jsString(runJsSync(CANVAS_SHOT_JS, 1500))
        val url = if (!direct.isNullOrBlank()) {
            direct
        } else {
            // WebGL 的绘制缓冲在合成后就被清掉，同步取多半是空 ——
            // 上面那段 JS 已经在**游戏画完的同一帧**里补抓了一次，等它一下再来取。
            runCatching { Thread.sleep(220) }
            jsString(runJsSync("window.__hexShot||''", 1500))
        }
        val b64 = url?.substringAfter("base64,", "")?.trim().orEmpty()
        if (b64.isEmpty()) return null
        return runCatching { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) }.getOrNull()
    }

    /** evaluateJavascript 回传的是 JSON 表示（字符串会带引号），这里解回真正的字符串 */
    private fun jsString(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull()
    }

    /** 把已有 JPEG 缩到 maxWidth 以内（本来就够小就原样返回），省 token 也省上传 */
    private fun scaleJpeg(raw: ByteArray, maxWidth: Int): ByteArray {
        val bmp = runCatching { BitmapFactory.decodeByteArray(raw, 0, raw.size) }.getOrNull()
            ?: return raw
        if (bmp.width <= maxWidth) {
            bmp.recycle()
            return raw
        }
        return runCatching {
            val w = maxWidth.coerceAtLeast(160)
            val h = (bmp.height.toFloat() * w / bmp.width).toInt().coerceAtLeast(1)
            val s = Bitmap.createScaledBitmap(bmp, w, h, true)
            val out = java.io.ByteArrayOutputStream()
            s.compress(Bitmap.CompressFormat.JPEG, 78, out)
            if (s !== bmp) s.recycle()
            out.toByteArray()
        }.getOrDefault(raw).also { bmp.recycle() }
    }

    override fun snapshotCss(maxWidth: Int, quality: Int): ByteArray? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null

        val decor = window?.decorView ?: return null
        val winW = decor.width.coerceAtLeast(1)
        val winH = decor.height.coerceAtLeast(1)
        val density = resources.displayMetrics.density.coerceAtLeast(1f)

        // 整屏截图（跟手机自带截图一样，不是只截 WebView 那一块）。
        // 取像素有两条路，**必须两条都走一遍**：
        //   ① PixelCopy：窗口的真实合成结果。只有它能拿到 WebGL / canvas / 视频这些
        //      「硬件加速」画面。
        //   ② decorView.draw()：软件绘制兜底 —— 它抓不到 GPU 内容，但 DOM 文字层通常还在。
        // 以前只用 ②，于是 Three.js 游戏截出来一片空白，模型当场误判成
        // 「Three.js 没加载 / canvas 没渲染」（用户报的就是这个），然后开始瞎改代码。
        val viaCopy = runCatching { pixelCopyWindow(winW, winH) }.getOrNull()
        val copyBlank = viaCopy == null || looksBlank(viaCopy)
        val viaDraw = if (copyBlank) runCatching {
            val raw = Bitmap.createBitmap(winW, winH, Bitmap.Config.ARGB_8888)
            val latchDraw = CountDownLatch(1)
            main.post {
                runCatching { decor.draw(Canvas(raw)) }
                latchDraw.countDown()
            }
            latchDraw.await(3, TimeUnit.SECONDS)
            raw
        }.getOrNull() else null

        val full = when {
            !copyBlank -> viaCopy!!.also { viaDraw?.recycle() }
            viaDraw != null && !looksBlank(viaDraw) -> viaDraw.also { viaCopy?.recycle() }
            viaCopy != null -> viaCopy
            else -> viaDraw ?: return null
        }
        shotBlank = looksBlank(full)

        // 缩放：整屏宽不超过 maxWidth，并记住比例 —— 模型在图里量的坐标要能换算回点击坐标
        val targetW = winW.coerceAtMost(maxWidth.coerceAtLeast(240))
        val targetH = (winH.toFloat() * targetW / winW).toInt().coerceAtLeast(1)

        val loc = IntArray(2)
        val latchLoc = CountDownLatch(1)
        main.post {
            runCatching { web.getLocationInWindow(loc) }
            latchLoc.countDown()
        }
        latchLoc.await(1, TimeUnit.SECONDS)

        shotScale = targetW.toFloat() / winW.toFloat()
        shotWinW = winW
        shotWinH = winH
        shotDensity = density
        shotWebL = loc[0].toFloat()
        shotWebT = loc[1].toFloat()
        shotWebW = web.width.toFloat()
        shotWebH = web.height.toFloat()

        return runCatching {
            val out = if (targetW == winW) full else Bitmap.createScaledBitmap(full, targetW, targetH, true)
            val bos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(30, 95), bos)
            if (out !== full) out.recycle()
            full.recycle()
            bos.toByteArray()
        }.getOrNull()
    }

    /** 本次截图是否疑似「空白 / 纯色」——即 GPU 画面没抓到。工具层会据此提醒模型别误判。 */
    @Volatile
    private var shotBlank = false

    override fun lastShotWasBlank(): Boolean = shotBlank

    // ===== 上一次整屏截图的换算信息（工具层用它把「图内像素」换成「网页 CSS 像素」） =====
    @Volatile
    private var shotScale = 0f      // 图片 px / 窗口 px
    @Volatile
    private var shotWinW = 0
    @Volatile
    private var shotWinH = 0
    @Volatile
    private var shotDensity = 0f
    @Volatile
    private var shotWebL = 0f       // WebView 在窗口里的位置（窗口 px）
    @Volatile
    private var shotWebT = 0f
    @Volatile
    private var shotWebW = 0f
    @Volatile
    private var shotWebH = 0f

    override fun lastShotMeta(): FloatArray =
        floatArrayOf(shotScale, shotWinW.toFloat(), shotWinH.toFloat(), shotDensity,
            shotWebL, shotWebT, shotWebW, shotWebH)

    override fun shotToCss(x: Float, y: Float): FloatArray? {
        if (shotScale <= 0f) return null
        val wx = x / shotScale
        val wy = y / shotScale
        val d = if (shotDensity > 0f) shotDensity else 1f
        return floatArrayOf((wx - shotWebL) / d, (wy - shotWebT) / d)
    }

    /**
     * 用 PixelCopy 抓**整个窗口**的真实合成画面（含 WebGL / canvas / 视频）。
     * 失败（窗口不可见、还没合成、低于 API 24）返回 null，由调用方退回软件绘制。
     */
    private fun pixelCopyWindow(w: Int, h: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        val win = window ?: return null
        val latch = CountDownLatch(1)
        val holder = AtomicReference<Bitmap?>()
        main.post {
            var handled = false
            runCatching {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                PixelCopy.request(
                    win,
                    bmp,
                    { res ->
                        holder.set(if (res == PixelCopy.SUCCESS) bmp else null)
                        latch.countDown()
                    },
                    Handler(Looper.getMainLooper())
                )
                handled = true
            }
            if (!handled) latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return holder.get()
    }

    /** 抽样判断是不是空白 / 纯色图：GPU 内容没抓到时的典型症状 */
    private fun looksBlank(bmp: Bitmap): Boolean {
        val stepX = (bmp.width / 24).coerceAtLeast(1)
        val stepY = (bmp.height / 24).coerceAtLeast(1)
        var first = 0
        var same = 0
        var total = 0
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val p = bmp.getPixel(x, y)
                if (total == 0) first = p
                if (p == first) same++
                total++
                x += stepX
            }
            y += stepY
        }
        return total == 0 || same == total
    }

    override fun pointer(x: Float, y: Float, kind: Int) {
        val type = when (kind) {
            0 -> "down"
            1 -> "up"
            else -> "move"
        }
        val js = "(function(){var t=document.elementFromPoint($x,$y)||document.body;" +
            "var o={bubbles:true,cancelable:true,clientX:$x,clientY:$y,pointerId:1," +
            "pointerType:'touch',isPrimary:true,view:window};" +
            "t.dispatchEvent(new PointerEvent('pointer$type',o));return 'ok';})()"
        runJsSync(js, 4000)
    }

    override fun inputText(text: String) {
        val quoted = JSONObject.quote(text)
        val js = "(function(){var el=document.activeElement;" +
            "if(!el||!('value' in el))return 'no-focus';el.value=$quoted;" +
            "el.dispatchEvent(new Event('input',{bubbles:true}));" +
            "el.dispatchEvent(new Event('change',{bubbles:true}));return 'ok';})()"
        runJsSync(js, 4000)
    }

    override fun consoleTail(n: Int): List<String> = logs.tail(n)

    override fun consoleClear() = logs.clear()

    // ==================== 资源 ====================

    /** 首次启动把 assets 里的示例游戏释放出来，否则 WebView 会白屏 */
    private fun seedBundledGames() {
        val names = runCatching { assets.list("games")?.toList() ?: emptyList() }
            .getOrDefault(emptyList())
        for (n in names) {
            if (n.startsWith("_")) continue
            val dst = File(gameRoot, n)
            if (File(dst, "index.html").exists() && File(dst, "engine.js").exists()) continue
            runCatching { copyAssetTree("games/$n", dst) }
        }
    }

    /** 所有游戏共用一份运行时 engine.js，每次启动覆盖刷新 */
    private fun seedSharedRuntime() {
        val dir = File(gameRoot, "_shared").apply { mkdirs() }
        runCatching {
            assets.open("games/demo/engine.js").use { input ->
                File(dir, "engine.js").outputStream().use { input.copyTo(it) }
            }
        }
        // 广告接入技能资产：adkit.js 模板 + 说明文档（AI 用 game_read 读 _shared/adkit.js 直接复制进游戏）
        runCatching {
            for (n in assets.list("adkit")?.toList().orEmpty()) {
                assets.open("adkit/$n").use { input ->
                    File(dir, n).outputStream().use { input.copyTo(it) }
                }
            }
        }
        // 技能手册：像素美术生成器 / 游戏 UI 设计规范。
        // AI 用 game_read 直接读（_skills/ 是用户可读可改的技能目录）。
        // 只补不覆盖 —— 用户自己改过或删掉的，别每次启动又塞回去。
        runCatching {
            val sk = File(gameRoot, "_skills").apply { mkdirs() }
            for (n in assets.list("skills")?.toList().orEmpty()) {
                val out = File(sk, n)
                if (out.exists()) continue
                assets.open("skills/$n").use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }

        // 预制的 UI 风格包（10 套主题：水墨 / 像素 / 卡通 / 霓虹 / 暗夜 …）。
        //
        // 为什么要释放出来：它们原本只躺在 APK 的 assets 里，**AI 读不到** ——
        // 于是每次做界面都自己瞎编配色，而 kit.json 里明明写着
        // 「做任何界面之前先按题材挑一套，禁止用浏览器默认样式」。
        // 现在整个目录树复制到工程根 _ui/，AI 用 game_read path=_ui/kit.json 就能看到全部主题。
        //
        // 只补不覆盖：用户在设置里换过的主题（applyUiKit 写进项目的那份）不能被覆盖掉。
        runCatching {
            copyAssetTreeSkipExisting("ui-kits", File(gameRoot, "_ui"))
        }
    }

    // （copyAssetTree 已经在上面定义过了 —— 复用它，别再写一个）
    // 注意：那个版本会**覆盖**已存在文件；UI 风格包那边要的是「只补不覆盖」，
    // 所以下面用 skipExisting 包一层。

    /** 只补不覆盖的目录复制：已存在的文件跳过（保护用户自己改过的主题） */
    private fun copyAssetTreeSkipExisting(assetDir: String, dst: File) {
        val kids = assets.list(assetDir)?.toList().orEmpty()
        if (kids.isEmpty()) {
            dst.parentFile?.mkdirs()
            if (!dst.exists()) {
                runCatching {
                    assets.open(assetDir).use { i -> dst.outputStream().use { i.copyTo(it) } }
                }
            }
            return
        }
        dst.mkdirs()
        for (k in kids) copyAssetTreeSkipExisting("$assetDir/$k", File(dst, k))
    }

    // ==================== 生命周期 ====================

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (activeTab != 1) {
            showTab(1)
            return
        }
        if (web.canGoBack()) web.goBack() else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        // 退出 / 被回收前再兜一次落盘（onPause 到 onDestroy 之间可能又说过话）
        runCatching { persistSessions() }
        runCatching { runner?.cancel() }
        runCatching { subtaskRunnerRef?.cancel() }
        runCatching { web.destroy() }
        super.onDestroy()
    }
}