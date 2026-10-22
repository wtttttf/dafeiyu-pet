# -*- coding: utf-8 -*-
"""
生成 Android 版 assets/pet.html —— 按 PC2005-cloud/dsh-pet 的标准做。

标准要点（照搬，不自己发明）：
  1. 透明 VP9 WebM 动画，动画名 == 文件名
  2. 双 <video> 双缓冲 + opacity 过渡交叉淡化（原版 sprite.js 的做法）
  3. 动画池分类：idle / turn / drag / clicks / moves / random
  4. 走路按 move_strides.json 的 stride + 逐帧位移曲线推进 x —— 脚才不打滑
  5. 命中判定用角色真实包围盒 body_box，而不是整个视频画布
"""
import os, sys, json, shutil, re

APP = "/root/pet-app"
ASSETS = APP + "/assets"
OUT_HTML = ASSETS + "/pet.html"
# 上游素材的暂存目录（/tmp 可能被清掉，所以下面所有"缺了怎么办"都留了退路）
SRC_WEBM = "/tmp/petref/webm"
TREE = "/tmp/petref/tree_dshpet.json"
# 【正式素材库】生成器以后只往里加、不再整个删掉重建 —— 以前 rmtree 一次，
# 自己新加的片段就没了。
DST_WEBM = ASSETS + "/webm"
# 【自己做的动作放这里】tools/make_action.sh 的输出目录。
# 同名时它优先于上游素材，所以想替换掉某段现成动画，起一样的名字丢进来即可。
CUSTOM_WEBM = ASSETS + "/webm_custom"
# 可选的池子覆盖配置，见 tools/actions.json
OVERRIDE = APP + "/tools/actions.json"

# 视频画布与角色包围盒（探得 640x360；body_box 取自上游 manifest 同口径）
CW, CH = 640, 360
BODY = (212, 60, 428, 330)          # x0,y0,x1,y1
# 裁剪窗口：正方形，中心对齐角色，四周留白给特效
CROP = (128, 6, 472, 350)           # 344 x 344（向左上平移，覆盖放烟花/跳跃/圣诞树的特效）

def _body_box():
    """从 alpha_bbox.tsv 量出角色的身体框（排除带大特效的离群片段），
    换算成相对窗口的四边留白比例，供贴边钳制用。"""
    import statistics as st
    rows = []
    try:
        for ln in open("tools/alpha_bbox.tsv", encoding="utf-8"):
            p = ln.rstrip("\n").split("\t")
            if len(p) >= 5:
                rows.append((int(p[1]), int(p[2]), int(p[3]), int(p[4])))
    except Exception:
        return {"l": 0.0, "r": 0.0, "t": 0.0, "b": 0.0}
    if not rows:
        return {"l": 0.0, "r": 0.0, "t": 0.0, "b": 0.0}
    w_med = st.median([r[1] - r[0] for r in rows])
    keep = [r for r in rows if (r[1] - r[0]) < w_med * 1.15]
    x1 = min(r[0] for r in keep); x2 = max(r[1] for r in keep)
    y1 = min(r[2] for r in keep); y2 = max(r[3] for r in keep)
    cw = CROP[2] - CROP[0]; ch = CROP[3] - CROP[1]
    return {"l": max(0.0, (x1 - CROP[0]) / cw), "r": max(0.0, (CROP[2] - x2) / cw),
            "t": max(0.0, (y1 - CROP[1]) / ch), "b": max(0.0, (CROP[3] - y2) / ch)}

BODYBOX = _body_box()


def _bbox_map():
    """每段素材自己的 alpha 包围盒（画布像素）＋中位数兜底。
    贴边钳制必须用"当前正在播的这段"的框：用所有素材的并集会把头顶特效算进去，
    结果普通片段在离屏幕顶还有 60 多像素时就反弹（空气墙）。"""
    rows = []
    try:
        for ln in open("tools/alpha_bbox.tsv", encoding="utf-8"):
            p = ln.rstrip("\n").split("\t")
            if len(p) >= 5:
                rows.append((p[0], int(p[1]), int(p[2]), int(p[3]), int(p[4])))
    except Exception:
        return {}, [212, 60, 428, 330]
    if not rows:
        return {}, [212, 60, 428, 330]
    med = [
        int(sorted(r[1] for r in rows)[len(rows) // 2]),
        int(sorted(r[3] for r in rows)[len(rows) // 2]),
        int(sorted(r[2] for r in rows)[len(rows) // 2]),
        int(sorted(r[4] for r in rows)[len(rows) // 2]),
    ]
    return {r[0]: [r[1], r[3], r[2], r[4]] for r in rows}, med


BBOX, MEDBOX = _bbox_map()


def list_webm(d):
    return {f[:-5] for f in os.listdir(d) if f.endswith(".webm")} if os.path.isdir(d) else set()


# ---------------------------------------------------------------- 素材入库
# 【顺序很重要】必须先把 assets/webm/ 同步干净，再去做分类。
# 反过来（先分类后清理）会生成指向已经被删掉的素材的动画池 —— 桌宠播到那一段就卡住。
# 规则：自定义片段优先于上游同名片段；两个来源都没有的就撤掉。
MANIFEST = os.path.join(CUSTOM_WEBM, ".installed.json")
upstream = list_webm(SRC_WEBM)
custom = list_webm(CUSTOM_WEBM)
os.makedirs(DST_WEBM, exist_ok=True)

installed = set()
if os.path.exists(MANIFEST):
    try:
        installed = set(json.load(open(MANIFEST)))
    except Exception:
        installed = set()

reverted = removed = added = 0
for name in sorted(installed - custom):          # 上次装的，这次 custom 里没有了 → 撤
    dst = os.path.join(DST_WEBM, name + ".webm")
    if name in upstream:                          # 上游有同名 → 还原成上游那版
        shutil.copyfile(os.path.join(SRC_WEBM, name + ".webm"), dst)
        reverted += 1
    elif os.path.exists(dst):
        os.remove(dst)
        removed += 1

for name in sorted(custom | upstream):          # custom 优先于上游，逐段同步
    src_dir = CUSTOM_WEBM if name in custom else SRC_WEBM
    s, d = os.path.join(src_dir, name + ".webm"), os.path.join(DST_WEBM, name + ".webm")
    if os.path.exists(d) and os.path.getsize(d) == os.path.getsize(s):
        continue
    shutil.copyfile(s, d)
    added += 1

os.makedirs(CUSTOM_WEBM, exist_ok=True)
json.dump(sorted(custom), open(MANIFEST, "w"))
if added or removed or reverted:
    print(f"素材入库：新增/更新 {added}，撤下 {removed}，还原成上游版 {reverted}")
if not upstream:
    print(f"（上游暂存目录 {SRC_WEBM} 不在，只按本地 assets/webm + webm_custom 走）")

# 以同步完的结果为准 —— 下面分类、渲染都只看这个
have = list_webm(DST_WEBM)

tree_names = set()
if os.path.exists(TREE):
    tree = json.load(open(TREE))
    tree_names = {
        os.path.basename(x["path"])[:-5]
        for x in tree["tree"]
        if x["type"] == "blob"
        and x["path"].startswith("dsh-pet/assets/webm/")
        and x["path"].lower().endswith(".webm")
    }
else:
    print(f"（没找到 {TREE}，按本地已有素材分类）")

allnames = sorted(tree_names | have)
if custom:
    print(f"自定义片段 {len(custom)} 段: " + ", ".join(sorted(custom)[:8]) +
          (" …" if len(custom) > 8 else ""))

IDLE = ["待机呼吸休闲"]
TURN = ["东张西望"]
DRAG = ["被鼠标拖拽悬空反馈"]
MOVES = ["原地左转奔跑", "原地漂浮踏步", "螃蟹走路"]
CLICKS = [n for n in allnames if n.startswith("点击回应-")]
BALANCE = [n for n in allnames if n.startswith("余额-")]

# 可选的池子覆盖：tools/actions.json 里写 {"clicks":["我的动作"], "random":["xxx*"]}。
# 名字可以精确写，也可以用 "前缀*"。用来把新做的动作塞进指定池子，不用改文件名。
ovr = {}
if os.path.exists(OVERRIDE):
    ovr = json.load(open(OVERRIDE, encoding="utf-8"))


def expand(pats):
    out = []
    for p in pats:
        hits = [n for n in allnames if n.startswith(p[:-1])] if p.endswith("*") else ([p] if p in allnames else [])
        for n in hits:
            if n not in out:
                out.append(n)
        if not hits:
            print(f"  ⚠ actions.json 里的 {p!r} 没有对应素材")
    return out


def uniq(seq):
    return list(dict.fromkeys(seq))


IDLE = expand(ovr.get("idle", [])) or IDLE
TURN = expand(ovr.get("turn", [])) or TURN
DRAG = expand(ovr.get("drag", [])) or DRAG
MOVES = expand(ovr.get("moves", [])) or MOVES
CLICKS = uniq(CLICKS + expand(ovr.get("clicks", [])))
BALANCE = uniq(BALANCE + expand(ovr.get("balance", [])))
RESERVED = set(IDLE + TURN + DRAG + MOVES + CLICKS + BALANCE)
RANDOM = uniq([n for n in allnames if n not in RESERVED] + expand(ovr.get("random", [])))

# 走动位移标定（来自上游 move_strides.json，名称对齐 dsh-pet 的写法）
STRIDES = {
    "原地左转奔跑": 240,
    "原地漂浮踏步": 90,
    "螃蟹走路": 220,
}
CURVE_FILE = "/tmp/petref/indesktop__assets_characters_shenshen_videos_move_strides.json"
src_curve = json.load(open(CURVE_FILE, encoding="utf-8")) if os.path.exists(CURVE_FILE) else {}
ALIAS = {"原地左转奔跑": "左转奔跑", "原地漂浮踏步": "漂浮踏步", "螃蟹走路": "螃蟹走路"}
curves = {}
for dst, up in ALIAS.items():
    c = src_curve.get(up, {}).get("curve")
    if c:
        # 抽稀：每 4 帧取一个，够顺滑又别把 HTML 撑爆
        curves[dst] = [round(c[i], 4) for i in range(0, len(c), 4)]

missing = [n for n in allnames if n not in have]
print(f"仓库 {len(allnames)} 段；本地已有 {len(have)} 段；缺 {len(missing)}")
if missing:
    print("  缺失:", ", ".join(missing[:12]) + (" …" if len(missing) > 12 else ""))

# 按实测 alpha 包围盒算"自适应缩放"：只有超出裁剪框的片段才缩，框本身不动。
FIT = {}
_bb = APP + "/tools/alpha_bbox.tsv"
if os.path.exists(_bb):
    _cw = CROP[2] - CROP[0]; _ch = CROP[3] - CROP[1]
    for _line in open(_bb, encoding="utf-8"):
        _p = _line.rstrip("\n").split("\t")
        if len(_p) != 5: continue
        _n = _p[0]
        try: _x0, _x1, _y0, _y1 = map(int, _p[1:])
        except ValueError: continue
        _w = _x1 - _x0; _h = _y1 - _y0
        _k = min(_cw / _w, _ch / _h) * 0.98 if _w and _h else 1.0
        if _k < 0.995: FIT[_n] = round(_k, 3)

avail = lambda lst: [n for n in lst if n in have]
CLIPS = {
    "idle": avail(IDLE),
    "turn": avail(TURN),
    "drag": avail(DRAG),
    "moves": avail(MOVES),
    "clicks": avail(CLICKS),
    "balance": avail(BALANCE),
    "random": avail(RANDOM),
}
for k, v in CLIPS.items():
    print(f"  {k:<8} {len(v)}")

# 素材同步已经在本文件开头做完了（必须在分类之前），这里只报个数
mb = sum(os.path.getsize(os.path.join(DST_WEBM, f)) for f in os.listdir(DST_WEBM)) / 1048576
print(f"assets/webm/ 共 {len(list_webm(DST_WEBM))} 段  {mb:.1f} MB")

# 旧的 three.js 不再需要
for old in ["vendor/three.min.js", "vendor/OrbitControls.js"]:
    p = os.path.join(ASSETS, old)
    if os.path.exists(p):
        os.remove(p); print("  移除", old)
vd = os.path.join(ASSETS, "vendor")
if os.path.isdir(vd) and not os.listdir(vd):
    os.rmdir(vd)

# ---------------------------------------------------------------- 生成 HTML
HTML = r'''<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,minimum-scale=1,user-scalable=no,viewport-fit=cover">
<title>大肥鱼</title>
<style>
  html,body{margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:transparent;
    -webkit-user-select:none;user-select:none;-webkit-tap-highlight-color:transparent;
    font-family:-apple-system,"PingFang SC","Noto Sans CJK SC",sans-serif;}
  /* 舞台永远是「边长 = 窗口宽」的正方形，贴在窗口最底下。
     窗口比正方形高 (1 + PAD_RATIO) 倍（见 PetService.winH），上面多出来的那一条
     专门留给气泡 —— 所以气泡不会压在角色身上。
     ⚠ 别改回 inset:0：那样舞台会跟着窗口被拉长，角色会变形。
     ⚠ 更别想着"平时正方形、说话时把窗口撑高"：窗口一 resize，WebView 就得重新排版
       + 重新光栅化，中间那几帧画面会被拉伸 → 看着就是桌宠"跳一下"。高度必须全程恒定。 */
  #sq{position:absolute;left:0;bottom:0;width:100%;height:100%;transform-origin:50% 100%;will-change:transform;}
  #stage{position:absolute;left:0;bottom:0;width:100%;height:100vw;overflow:hidden;
         transform-origin:50% 100%;transition:transform .28s ease;}
  /* 双缓冲视频：叠在同一个舞台里，靠 opacity 过渡交叉淡化（沿用原版 sprite.js 的做法）。
     visibility 跟着一起切 —— 淡出结束后彻底不参与绘制，
     这样任何一个"还没有画面的 video"都不可能被渲染成灰色空块。 */
  .pv{position:absolute;width:__VW__%;height:__VH__%;left:__VL__%;top:__VT__%;
      opacity:0;visibility:hidden;pointer-events:none;object-fit:fill;-webkit-user-drag:none;
      background:transparent;
      transition:opacity .26s linear,visibility 0s linear .26s;}
  .pv.is-front{opacity:1;visibility:visible;transition:opacity .26s linear,visibility 0s;}
  /* 接地阴影：脚下一枚椭圆，随窗口等比缩放。别用 filter:drop-shadow —— 那会沿着
     头发/道具的轮廓描一圈，看起来像发光而不是影子。 */
  #shadow{position:absolute;left:50%;bottom:1.2%;transform:translateX(-50%);
          width:54%;height:7%;border-radius:50%;pointer-events:none;
          background:radial-gradient(ellipse at center, rgba(0,0,0,.42) 0%, rgba(0,0,0,.22) 46%, rgba(0,0,0,0) 74%);
          filter:blur(1.5px);}
  /* 气泡浮在角色头顶上方的空白里：0.91 个窗口宽 = 头顶(0.872)再往上一点。
     尺寸一律用 vw，桌宠调大调小时气泡/按钮跟着等比缩放。 */
  .bubble{position:fixed;left:50%;bottom:calc(100vw * 0.91);
      transform:translateX(-50%) translateY(8px);
      max-width:92%;padding:2.6vw 4.6vw;border-radius:5.4vw;background:rgba(18,30,70,.88);
      color:#eaf1ff;font-size:5.2vw;line-height:1.45;text-align:center;word-break:break-word;
      opacity:0;transition:opacity .22s,transform .22s;pointer-events:none;z-index:40;}
  .bubble.show{opacity:1;transform:translateX(-50%) translateY(0);}
  #acts{position:fixed;left:50%;bottom:3.3vw;transform:translateX(-50%) scale(.9);
      display:flex;gap:2.9vw;opacity:0;pointer-events:none;
      transition:opacity .2s,transform .2s;z-index:41;}
  #acts.open{opacity:1;transform:translateX(-50%) scale(1);pointer-events:auto;}
  .act{width:16.7vw;height:16.7vw;border-radius:50%;border:0;padding:0;font-size:7.9vw;
      background:rgba(255,255,255,.92);box-shadow:0 .8vw 3.3vw rgba(0,0,0,.28);}
</style>
</head>
<body>
<div id="stage">
  <div id="shadow"></div>
  <video id="va" class="pv" muted playsinline preload="auto"></video>
  <video id="vb" class="pv" muted playsinline preload="auto"></video>
</div>
<div class="bubble" id="bubble"></div>
<div id="acts">
  <button class="act" data-c="eat" title="投喂">&#x1F35C;</button>
  <button class="act" data-c="dance" title="跳舞">&#x1F495;</button>
  <button class="act" data-c="sleep" title="睡觉">&#x1F4A4;</button>
</div>
<script>
(function () {
  'use strict';
  var CLIPS   = __CLIPS__;
  var FIT     = __FIT__;       // 超出裁剪框的片段：整页缩放显示，保证元素完整
  var STRIDES = __STRIDES__;
  var CURVES  = __CURVES__;
  var BODY    = __BODY__;      // 角色包围盒（视频画布像素）
  var CROP    = __CROP__;      // 窗口对应到画布的那块（方形）
  var BODYBOX = __BODYBOX__;   // 全体素材并集的留白（仅兜底）
  var BBOX    = __BBOX__;      // 每段素材自己的包围盒（画布像素）
  var MEDBOX  = __MEDBOX__;    // 中位数兜底
  var paused  = false;         // 息屏时置真：不做决策、不动、不播
  var fling   = null;          // 拖动甩出后的惯性状态（桌球效果）
  var touching = false;        // 手指是否按在桌宠上（含长按说话）
  var touchEndAt = 0;          // 松手时刻，留一点点冷却

  /* 屏幕边缘按“可见身体”算，而不是按窗口算：窗口可以探出去一点，
     让角色真正贴到屏幕边，而不是隔着一圈透明留白就停住 */
  /* 用"当前这段素材"的身体框算四边留白（像素）：
     每段的人物位置不一样，用并集会让头顶特效拉低上边界 → 空中反弹。 */
  function bodyMargins(W, H, name) {
    var n = name || curName;
    var bb = (BBOX && n && BBOX[n]) || MEDBOX || BODY || [212, 60, 428, 330];
    var cw = CROP[2] - CROP[0], ch = CROP[3] - CROP[1];
    var l = Math.max(0, (bb[0] - CROP[0]) / cw) * W;
    var r = Math.max(0, (CROP[2] - bb[2]) / cw) * W;
    var t = Math.max(0, (bb[1] - CROP[1]) / ch) * H;
    var b = Math.max(0, (CROP[3] - bb[3]) / ch) * H;
    return { l: l, r: r, t: t, b: b };
  }

  var bridge = window.AndroidBridge || {};
  var vs = [document.getElementById('va'), document.getElementById('vb')];
  var stage = document.getElementById('stage');
  /* 点击"缩一下"用形变实现：把视频包一层，压扁时只动这一层，不影响方向/缩放的 transform */
  var sqWrap = document.createElement('div');
  sqWrap.id = 'sq';
  var vids = stage.querySelectorAll('video');
  for (var vi = 0; vi < vids.length; vi++) sqWrap.appendChild(vids[vi]);
  stage.appendChild(sqWrap);
  var bubbleEl = document.getElementById('bubble');
  var actsEl = document.getElementById('acts');
  var fi = 0, curName = null, curLoop = false;
  var dragging = false, walking = null, timer = null, actsTimer = null, wanderOn = true;
  var acting = false;      // 正在演"点击/随机动作"：期间不启动走动，否则动作在播、窗口在飘
  /* 交叉淡化是不是正忙：从「开始加载下一段」一直忙到「旧的那段淡出结束」。
     这段时间里绝不能再动旧元素的 src —— 那正是"连点闪灰框"的来源。 */
  var busy = false;
  /* 忙的时候来的新请求不丢，收敛成"最后来的那一个"，等这一轮收尾再放行。 */
  var pending = null;
  var loadSeq = 0;
  var nativeDragOn = false;      // 原生已经接管拖动，网页侧别再发 dragMove

  function url(name) { return 'webm/' + encodeURIComponent(name) + '.webm'; }
  function pick(a) { return a[Math.floor(Math.random() * a.length)]; }

  /* ---------- 双缓冲交叉淡化 ----------
     连点闪灰框的根子：只要在「某个 video 还看得见」的时候去动它的 src，
     它就会瞬间失去画面 → 露一个还没渲染出内容的空块。两种触法：
       ① 前一次淡化还没收尾，就把正在淡出的那个又拿去装新片；
       ② 上一段的加载回调还没回来，同一个元素又被赋了新的 src。
     这里用两条不变量把路堵死：
       A. busy 期间不接受新的播放，只把请求收敛进 pending —— 淡化妆间内绝不重叠；
       B. 只有在元素「既不是前台、又不在淡出窗口里」时才给它赋 src。
     再配一条兜底：淡出结束后 .pv 会切到 visibility:hidden，彻底不参与绘制。 */
  // 朝向/透视：mirror 用于向右跑（把朝左的片段水平翻转），scale 用于"远离/靠近"的透视感
  function setStagePose(mirror, k) {
    var el = document.getElementById('stage');
    if (!el) return;
    var t = (mirror ? 'scaleX(-1) ' : '') + ((k && k !== 1) ? ('scale(' + k + ')') : '');
    el.style.transform = t;
  }
  function play(name, opt) {
    opt = opt || {};
    if (!name) return;
    if (curName === name && opt.loop && curLoop && vs[fi] && !vs[fi].paused) return;
    if (busy) { pending = { name: name, opt: opt }; return; }   // 收敛：连点只留最后一下
    start(name, opt);
  }

  function start(name, opt) {
    busy = true;
    var seq = ++loadSeq;
    var back = vs[1 - fi];
    var settled = false;

    back.onloadeddata = null;
    back.onended = null;
    back.onerror = null;
    back.loop = !!opt.loop;

    // 兜底：2 秒还没就绪（解码卡住 / 文件坏）就解除占用，
    // 否则 busy 卡死 = 桌宠再也不换动作，而且一句话都不报。
    var guard = setTimeout(function () {
      if (settled) return;
      settled = true;
      busy = false;
      if (seq === loadSeq) releasePending();
    }, 2000);

    back.onloadeddata = function () {
      if (seq !== loadSeq) return;        // 已被更新的请求顶掉，回调作废
      back.onloadeddata = null;
      go();
    };
    back.onerror = function () {          // 某段加载失败：兜回 idle，别让画面僵在那
      back.onerror = null;
      if (seq === loadSeq && opt.onEnd) opt.onEnd();
    };

    // 快路径只在「这一段的 src 本来就已加载好，而且还没播到头」时才走。
    // 设完 src 再读 readyState 会读到上一段的陈旧值 → 立刻 go() → 露一个还没画面的空块（灰框）。
    // 播完的（ended）也不走快路径：重播要 seek 回 0，seek 同样会清掉当前画面。
    var want = url(name), enc = encodeURIComponent(name) + '.webm';
    if (back.src && back.src.indexOf(enc) >= 0 && back.readyState >= 2 && !back.ended) {
      back.onloadeddata = null;
      go();
    } else {
      back.src = want;                    // 否则老实等 onloadeddata 再上台
    }

    function go() {
      if (settled) return;
      if (opt.seekTo) { try { back.currentTime = opt.seekTo; } catch (e) {} }
      window.__syncLog = window.__syncLog || [];
      window.__syncLog.push({ ev: "clip", clip: name, t: Math.round(performance.now()) });
      if (window.__syncLog.length > 24) window.__syncLog.shift();
      settled = true;
      clearTimeout(guard);
      back.onerror = null;
      var old = vs[fi];
      var p = back.play();
      if (p && p.catch) p.catch(function () {});
      // 【必须】把旧的那个摘掉 is-front —— 否则两个都留在前台，桌宠重影成两个各播各的
      old.classList.remove('is-front');
      back.classList.add('is-front');
      fi = 1 - fi;
      curName = name;
      curLoop = !!opt.loop;
      /* 关键：等"第一帧真的画上去"再通知，而不是换源那一刻。
         rVFC 在帧呈现时回调；不支持/不触发时用 140ms 兜底。 */
      /* 两件事要分开：
         · 首帧呈现 → 切朝向/缩放（onFirstFrame），否则"右跑"会先播未镜像的转身
         · 播到起跑帧 → 才开始位移（onStart），否则人在原地就已经滑出去了 */
      var firstDone = false, moveDone = false;
      var firstFrame = function () {
        if (firstDone) return; firstDone = true;
        window.__syncLog = window.__syncLog || [];
        window.__syncLog.push({ ev: "frame", clip: name, t: Math.round(performance.now()) });
        if (window.__syncLog.length > 24) window.__syncLog.shift();
        if (opt.onFirstFrame) { try { opt.onFirstFrame(); } catch (e) {} }
      };
      var startMove = function () {
        if (moveDone) return; moveDone = true;
        if (opt.onStart) { try { opt.onStart(); } catch (e) {} }
      };
      var armWait = function (cb) {
        var t0 = performance.now();
        var poll = function () {
          if (moveDone) return;
          if (back.currentTime >= opt.waitTime || performance.now() - t0 > 3200) { cb(); return; }
          requestAnimationFrame(poll);
        };
        poll();
      };
      var afterFirst = function () {
        firstFrame();
        if (opt.waitTime) armWait(startMove);
        else startMove();
      };
      if (back.requestVideoFrameCallback) { try { back.requestVideoFrameCallback(afterFirst); } catch (e) {} }
      setTimeout(afterFirst, 140);
      back.onended = opt.onEnd ? function () { opt.onEnd(); } : null;
      // 旧的这时才开始淡出（.26s）。等它彻底隐身了才能被再次拿去装片，
      // 所以 busy 一直扣到这一刻才松手。旧元素只 pause，绝不 removeAttribute('src')+load()。
      setTimeout(function () {
        if (!old.classList.contains('is-front')) { try { old.pause(); } catch (e) {} }
        busy = false;
        releasePending();
      }, 320);
    }
  }

  function releasePending() {
    if (!pending) return;
    var q = pending;
    pending = null;
    if (!busy) start(q.name, q.opt);
  }

  var IDLE = CLIPS.idle[0] || (CLIPS.random[0] || '');
  var idleIdx = 0;
  /* 待机不再永远是同一条：池子里有几条就轮着播，来回切换才不呆 */
  /* 间隔期一律回到"安静不动"的待机片段，别用哼歌/照镜子这类有戏的片段过渡 */
  var CALM = "待机呼吸休闲";
  function toIdle() {
    if (CLIPS.idle.indexOf(CALM) >= 0) { play(CALM, { loop: true }); return; }
    var pool = CLIPS.idle.length ? CLIPS.idle : (IDLE ? [IDLE] : []);
    if (!pool.length) return;
    idleIdx = (idleIdx + 1) % pool.length;
    play(pool[idleIdx], { loop: true });
  }
  /* 走一半被"做个动作"打断时，必须同时停掉位移——否则动作在播、窗口还在慢慢飘 */
  function cancelWalk() {
    if (!walking) return;
    walking = null;
    setStagePose(false, 1);
  }
  var actingTimer = null;
  function oneShot(name, then) {
    cancelWalk();
    acting = true;
    if (actingTimer) clearTimeout(actingTimer);
    /* 保险：ended 回调在"动作被下一条顶掉"时不会触发，会把 acting 永久卡住 */
    actingTimer = setTimeout(function () { acting = false; schedule(); }, 8000);
    play(name, { loop: false, onEnd: function () {
      acting = false; if (actingTimer) { clearTimeout(actingTimer); actingTimer = null; }
      (then || toIdle)();
      schedule();
    } });
  }

  /* ---------- 说话 ---------- */
  var TALK = ['我是大肥鱼，不是虎鲸啦！', '摸鱼中，别打扰我~', '今天也要加油哦',
              '你在忙什么呀？', '我在这儿陪着你', '想吃小鱼干了…', '困了…zzZ'];
  function say(t, ms) {
    bubbleEl.textContent = t;
    bubbleEl.classList.add('show');
    clearTimeout(say._t);
    say._t = setTimeout(function () { bubbleEl.classList.remove('show'); }, ms || 3000);
  }

  /* ---------- 走动：按标定曲线推进，脚不打滑 ---------- */
  function bounds() {
    try { return JSON.parse(bridge.bounds ? bridge.bounds() : '{}'); }
    catch (e) { return {}; }
  }
  /* 方向 → 片段：
     ← 左跑：原地左转奔跑（天生朝左）
     → 右跑：同一段水平翻转
     ↓ 向下（朝观众）：原地漂浮踏步（正面）
     ↑ 向上（远离）：正面片段 + 缩到 0.9 做透视（素材里没有真正的背面走路） */
  function pickMove(dx, dy) {
    var adx = Math.abs(dx), ady = Math.abs(dy);
    if (adx >= ady) {
      /* 原地跑片段没有真实位移，按曲线推进会"走一下停一下"；
         桌面宠的通行做法就是匀速滑动 + 跑步动画循环。 */
      return { name: "原地左转奔跑", mirror: dx > 0, scale: 1, speed: 150 };
    }
    if (dy > 0) return { name: "原地漂浮踏步", mirror: false, scale: 1, speed: 340 };
    return { name: "原地漂浮踏步", mirror: false, scale: 0.9, speed: 300 };
  }

  function walkTo(tx, ty) {
    if (touching || fling || !CLIPS.moves.length || busy || acting) return false;
    var b = bounds();
    var W = b.w || 0, H = b.h || 0, SW = b.sw || 0, SH = b.sh || 0, X = b.x || 0, Y = b.y || 0;
    var bm = bodyMargins(W, H, "原地左转奔跑");
    var minX = -bm.l, maxX = SW - W + bm.r;
    var minY = -bm.t, maxY = SH - H + bm.b;
    tx = Math.max(minX, Math.min(maxX, Math.round(tx)));
    ty = Math.max(minY, Math.min(maxY, Math.round(ty)));
    var dx = tx - X, dy = ty - Y, dist = Math.hypot(dx, dy);
    if (!isFinite(tx) || !isFinite(ty)) return false;
    if (dist < 8) return false;
    var mv = pickMove(dx, dy);
    if (CLIPS.moves.indexOf(mv.name) < 0) mv = { name: CLIPS.moves[0], mirror: false, scale: 1 };
    if (mv.speed) mv.speed = mv.speed * (0.85 + Math.random() * 0.3);
    var sc = b.scale || 1;
    /* 镜像/缩放也不提前切：否则加载期间旧片段被镜像，看起来像"先做了个表情"。
       统一放到首帧回调里。 */
    /* 位移必须等新动画真正上台再开始：play() 是异步交叉淡化（最坏 2 秒），
       否则窗口先动、旧动作还在画面上，看起来就是"移动抢了动作"。 */
    var begin = function () {
      if (walking) return;
      window.__syncLog = window.__syncLog || [];
      window.__syncLog.push({ ev: "move", clip: mv.name, t: Math.round(performance.now()) });
      var bb = bounds();
      var X2 = bb.x || 0, Y2 = bb.y || 0;
      var dx2 = tx - X2, dy2 = ty - Y2, dist2 = Math.hypot(dx2, dy2);
      if (!isFinite(dist2) || dist2 < 8) { toIdle(); return; }
      if (mv.speed) {
        /* 漂浮没有脚接触，直接匀速过去：距离多长都按 speed 换算时长 */
        var dur = Math.max(1.5, dist2 / (mv.speed * sc));
        if (!isFinite(dur)) { toIdle(); return; }
        walking = { mode: "linear", ux: dx2 / dist2, uy: dy2 / dist2, dist: dist2, dur: dur,
                    t0: performance.now() / 1000, x0: X2, y0: Y2 };
      } else {
        var stride = (STRIDES[mv.name] || 200) * sc;
        var cycles = Math.max(1, Math.min(2, Math.round(dist2 / stride)));
        walking = { mode: "curve", ux: dx2 / dist2, uy: dy2 / dist2, stride: stride, cycles: cycles,
                    t0: performance.now() / 1000, x0: X2, y0: Y2, curve: CURVES[mv.name] };
      }
      requestAnimationFrame(tickWalk);
    };
    var horiz = (mv.name === "原地左转奔跑");
    play(mv.name, { loop: true, onStart: begin,
                    onFirstFrame: function () { setStagePose(mv.mirror, mv.scale || 1); },
                    waitTime: horiz ? 2.2 : 0,
                    seekTo: horiz ? 1.2 : 0 });
    return true;
  }

  var lastDir = [0, 0], lastDirIdx = -1;
  function walk() {
    if (paused || touching || fling || !CLIPS.moves.length || busy || acting) return;
    var b = bounds();
    var W = b.w || 0, H = b.h || 0, SW = b.sw || 0, SH = b.sh || 0, X = b.x || 0, Y = b.y || 0;
    var bm = bodyMargins(W, H);
    var minX = -bm.l, maxX = SW - W + bm.r;
    var minY = -bm.t, maxY = SH - H + bm.b;
    var sc = b.scale || 1;
    /* 四方向直线移动：不再朝随机点斜着走，否则"向上"看起来像横向漂移 */
    var dirs = [[0, -1], [0, 1], [-1, 0], [1, 0]];
    var d = dirs[Math.floor(Math.random() * 4)];
    /* 方向尽量别连着重复：连续两次同一方向看起来像"任务"，不像闲逛 */
    if (lastDirIdx >= 0 && Math.random() < 0.8) {
      var again = dirs.slice().sort(function () { return Math.random() - 0.5; })
                       .filter(function (v) { return v[0] !== lastDir[0] || v[1] !== lastDir[1]; })[0];
      if (again) d = again;
    }
    lastDir = d; lastDirIdx = 0;
    var dist = (140 + Math.random() * 460) * sc;   // 140–600，短挪和长跑都有
    var tx = X + d[0] * dist, ty = Y + d[1] * dist;
    /* 撞墙：先把该轴的方向反射一次（像球弹回来），两轴都出界就朝屏幕中心走。
       之前只反射一次、而且反射后不再校验，被逼到角落时就会原地一直"跑"。 */
    if (tx < minX || tx > maxX) { d[0] = -d[0]; tx = X + d[0] * dist; }
    if (ty < minY || ty > maxY) { d[1] = -d[1]; ty = Y + d[1] * dist; }
    if (tx < minX || tx > maxX || ty < minY || ty > maxY) {
      var cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;
      var vx = cx - X, vy = cy - Y, vl = Math.hypot(vx, vy) || 1;
      tx = X + vx / vl * dist; ty = Y + vy / vl * dist;
    }
    walkTo(tx, ty);
  }
  function tickWalk() {
    if (!walking) return;
    if (!isFinite(walking.dur) || !isFinite(walking.dist)) { endWalk(); return; }
    if (performance.now() / 1000 - walking.t0 > walking.dur * 3 + 2) { endWalk(); return; }
    var now = performance.now() / 1000;
    var d, nx, ny;
    if (walking.mode === "linear") {
      var t = (now - walking.t0) / walking.dur;
      if (t >= 1) { endWalk(); return; }
      var e = t * t * (3 - 2 * t);            // smoothstep：起步和收尾都软一点
      d = walking.dist * e;
    } else {
      var v = vs[fi];
      var clipDur = (v && v.duration > 0 && isFinite(v.duration)) ? v.duration : 2.0;
      var c = walking.curve || [];
      /* 曲线第一次到 1.0 的位置 = 这一趟真正走完的时刻；之后片段还在播但不该再算时间 */
      var endFrac = 1;
      for (var i = 0; i < c.length; i++) { if (c[i] >= 0.999) { endFrac = (i + 1) / c.length; break; } }
      var cycDur = clipDur * Math.max(0.15, endFrac);
      var tt = now - walking.t0;
      if (tt >= walking.cycles * cycDur) { endWalk(); return; }
      var cyc = Math.floor(tt / cycDur), frac = (tt % cycDur) / cycDur;
      var k = frac;
      if (c.length) k = c[Math.min(c.length - 1, Math.floor(frac * c.length))];
      d = walking.stride * (cyc + k);
    }
    nx = Math.round(walking.x0 + walking.ux * d);
    ny = Math.round(walking.y0 + walking.uy * d);
    if (bridge.setWindowXY) bridge.setWindowXY(nx, ny); else bridge.setWindowX(nx);
    requestAnimationFrame(tickWalk);
  }
  /* ---------- 桌球效果：拖动甩出后带惯性、撞边反弹 ---------- */
  function tickFling() {
    if (!fling) return;
    try { tickFlingInner(); }
    catch (e) { fling = null; try { toIdle(); } catch (e2) {} try { schedule(); } catch (e3) {} }
  }
  function tickFlingInner() {
    if (!fling) return;
    var now = performance.now() / 1000;
    var dt = Math.min(0.05, Math.max(0.001, now - fling.last));
    fling.last = now;

    var b = bounds();
    var W = b.w || 0, H = b.h || 0, SW = b.sw || 0, SH = b.sh || 0;
    var bm = bodyMargins(W, H);
    var minX = -bm.l, maxX = SW - W + bm.r, minY = -bm.t, maxY = SH - H + bm.b;

    var x = fling.x + fling.vx * dt, y = fling.y + fling.vy * dt;
    var bounce = 0.72;
    if (x < minX) { x = minX; fling.vx = Math.abs(fling.vx) * bounce; fling.hits++; }
    if (x > maxX) { x = maxX; fling.vx = -Math.abs(fling.vx) * bounce; fling.hits++; }
    if (y < minY) { y = minY; fling.vy = Math.abs(fling.vy) * bounce; fling.hits++; }
    if (y > maxY) { y = maxY; fling.vy = -Math.abs(fling.vy) * bounce; fling.hits++; }

    var drag = Math.exp(-1.15 * dt);        // 空气阻力
    fling.vx *= drag; fling.vy *= drag;
    fling.x = x; fling.y = y;
    window.__ftrace = window.__ftrace || [];
    window.__ftrace.push({ t: Math.round(now * 1000), x: Math.round(x), y: Math.round(y),
                           vx: Math.round(fling.vx), vy: Math.round(fling.vy), minY: Math.round(minY), maxY: Math.round(maxY) });
    if (window.__ftrace.length > 400) window.__ftrace.shift();

    var sp = Math.hypot(fling.vx, fling.vy);
    var dir = Math.abs(fling.vx) >= Math.abs(fling.vy)
      ? (fling.vx < 0 ? "left" : "right")
      : (fling.vy > 0 ? "down" : "up");
    if (dir !== fling.dir) {
      fling.dir = dir;
      if (dir === "left" || dir === "right") {
        setStagePose(dir === "right", 1);
        play("原地左转奔跑", { loop: true });
      } else {
        setStagePose(false, dir === "up" ? 0.9 : 1);
        play("原地漂浮踏步", { loop: true });
      }
    }
    if (bridge.setWindowXY) bridge.setWindowXY(x, y); else bridge.setWindowX(x);

    if (sp < 60) {
      fling = null;
      toIdle();
      schedule();
      return;
    }
    requestAnimationFrame(tickFling);
  }

  window.__petFling = function (vx, vy) {
    if (paused || !isFinite(vx) || !isFinite(vy)) return false;
    if (Math.hypot(vx, vy) < 120) return false;
    cancelWalk();
    acting = false;
    var b = bounds();
    fling = { x: b.x || 0, y: b.y || 0, vx: vx, vy: vy,
              last: performance.now() / 1000, dir: "", hits: 0 };
    setTimeout(function () { if (fling) { fling = null; toIdle(); schedule(); } }, 8000);
    requestAnimationFrame(tickFling);
    return true;
  };

  function endWalk() {
    walking = null; setStagePose(false, 1); toIdle();
    /* 走完别立刻进入长待机：先给一个很短的窗口，活跃档还有概率直接续走一段，
       看起来就是“连着溜达”，而不是走两步站半天。 */
    var M = MODES[mode] || MODES.normal;
    var chain = (mode === "active") ? 0.65 : (mode === "quiet" ? 0.15 : 0.4);
    if (wanderOn && Math.random() < chain) {
      setTimeout(function () { if (!paused && !acting && !walking) walk(); }, 200 + Math.random() * 400);
    }
    schedule();
  }

  /* ---------- 待机时的随机行为 ---------- */
  /* 三档活跃度：wait=两次行为之间的间隔，walk/act/talk=该次抽中后的概率 */
  var MODES = {
    /* 用户反馈：正常档表演太频繁。把原来的"正常"整体下移到"活跃"，
       正常档改成更安静的节奏；安静档再淡一点。 */
    /* 手机上"跑来跑去"很打扰：整体少走、多做静态表演 */
    quiet:  { wait: [45000, 90000], walk: 0.06, act: 0.14, talk: 0.00 },
    normal: { wait: [18000, 32000], walk: 0.18, act: 0.34, talk: 0.12 },
    active: { wait: [8000, 15000],  walk: 0.30, act: 0.34, talk: 0.12 }
  };
  var mode = "normal";
  window.__petSetMode = function (m) { if (MODES[m]) mode = m; };
  window.__petSay = function (t) { if (t) say(String(t), 9000); };

  window.__petPause = function () {
    paused = true;
    clearTimeout(timer);
    walking = null;
  };
  window.__petTouch = function (on) {
    if (on) { touching = true; cancelWalk(); }   // 手一按下去就停住，别边按边跑
    else { touching = false; touchEndAt = performance.now(); }
  };
  window.__petPaused = function () { return paused; };
  window.__petResume = function () {
    if (!paused) return;
    paused = false;
    schedule();
  };

  /* ---------- 频率控制：每分钟 走路/表演/说话 的次数（合计有上限，由设置页保证） ---------- */
  var RATES = { walk: 2, act: 3, talk: 1 };
  window.__petRates = function (w, a, t) {
    RATES.walk = Math.max(0, w | 0);
    RATES.act = Math.max(0, a | 0);
    RATES.talk = Math.max(0, t | 0);
    schedule();
  };

  function schedule() {
    clearTimeout(timer);
    if (paused) return;
    var total = RATES.walk + RATES.act + RATES.talk;
    if (total <= 0) { timer = setTimeout(schedule, 60000); return; }   // 全 0 = 完全安静
    var gap = 60000 / total;
    var jitter = 0.7 + Math.random() * 0.6;
    timer = setTimeout(function () {
      if (paused) return;
      /* 忙就跳过这次，等下一拍：频率是"上限"，不堆积 */
      if (touching || performance.now() - touchEndAt < 900
              || dragging || walking || acting || fling || busy) { schedule(); return; }
      var r = Math.random() * total;
      window.__dec = window.__dec || { walk: 0, act: 0, talk: 0 };
      if (r < RATES.walk && wanderOn && CLIPS.moves.length) { window.__dec.walk++; walk(); }
      else if (r < RATES.walk + RATES.act && CLIPS.random.length) { window.__dec.act++; oneShot(pick(CLIPS.random)); }
      else if (RATES.talk > 0) { window.__dec.talk++; say(pick(TALK)); }
      schedule();
    }, gap * jitter);
  }

  /* ---------- 点击回应 ---------- */
  /* 点击"缩一下"：对待机画面做一次 Q 弹形变（下压 8%，横向胀 6%，再弹回）。
     不用视频片段：无黑框、无 seek 卡顿、任意浅度、随点随有。
     slow=true 只给截图调试用。 */
  var sqTimer = null;
  window.__petSquish = function (slow) {
    if (!sqWrap) return false;
    var dn = slow ? 900 : 80, up = slow ? 1200 : 190;
    if (sqTimer) { clearTimeout(sqTimer); sqTimer = null; }
    sqWrap.style.transition = 'transform ' + dn + 'ms cubic-bezier(.2,.85,.3,1)';
    sqWrap.style.transform = 'scaleY(0.88) scaleX(1.08)';
    sqTimer = setTimeout(function () {
      sqWrap.style.transition = 'transform ' + up + 'ms cubic-bezier(.2,1.9,.4,1)';
      sqWrap.style.transform = 'scaleY(1) scaleX(1)';
      sqTimer = setTimeout(function () { sqWrap.style.transition = ''; sqTimer = null; }, up + 40);
    }, dn);
    return true;
  };
  window.__tapN = 0;
  window.__petTap = function () {
    window.__tapN++;
    cancelWalk();
    if (walking) { walking = null; }
    if (Math.random() < 0.8) window.__petSquish();
    else {
      var pool = CLIPS.clicks.length ? CLIPS.clicks : CLIPS.random;
      if (pool.length) oneShot(pick(pool));
    }
    if (Math.random() < 0.35) say(pick(TALK));
    showActs();
  };
  window.__petSleeping = function () { return false; };

  function showActs() {
    actsEl.classList.add('open');
    clearTimeout(actsTimer);
    actsTimer = setTimeout(function () { actsEl.classList.remove('open'); }, 4500);
  }

  function runAct(c) {
    actsEl.classList.remove('open');
    var pool = CLIPS.random;
    var re, line;
    if (c === 'eat') { re = /吃|零食|火锅|饺子|西瓜/; line = '好吃！'; }
    else if (c === 'dance') { re = /舞|摇摆|女仆/; line = '嘿~'; }
    else { re = /小憩|沉眠|哈欠|打瞌睡/; line = '晚安…'; }
    var a = pool.filter(function (n) { return re.test(n); });
    oneShot(a.length ? pick(a) : pick(pool));
    say(line, 1800);
  }

  /* 互动按钮必须在这里自己判定 —— 手势层在 touchstart 里 preventDefault 了，
     浏览器就【不会再合成 click】，挂在 .act 上的 click 监听永远不会响：
     点了半天没反应就是这么来的。所以改成在 touchend 里按按钮矩形自己判。 */
  var lastActAt = 0;
  function hitAct(node, x, y) {
    while (node && node !== document) {
      if (node.classList && node.classList.contains('act')) {
        var r = node.getBoundingClientRect(), m = 10;
        if (x >= r.left - m && x <= r.right + m && y >= r.top - m && y <= r.bottom + m) return node;
        return null;
      }
      node = node.parentNode;
    }
    return null;
  }
  function onActTouchEnd(e) {
    var t = e.changedTouches && e.changedTouches[0];
    var btn = t ? hitAct(e.target, t.clientX, t.clientY) : null;
    if (!btn) return false;
    var now = Date.now();
    if (now - lastActAt < 250) return true;    // 防重（真机上偶尔仍会合成一次 click）
    lastActAt = now;
    runAct(btn.getAttribute('data-c'));
    return true;
  }
  actsEl.addEventListener('touchstart', function (e) {
    e.stopPropagation();      // 按钮上的手势不归拖拽/点击那套管
  }, { passive: false });
  actsEl.addEventListener('touchend', function (e) {
    e.stopPropagation();
    e.preventDefault();
    onActTouchEnd(e);
  }, { passive: false });
  // 鼠标/合成 click 的兜底（真机触摸被 preventDefault 挡掉了，桌面调试还能用）
  actsEl.addEventListener('click', function (e) {
    var btn = e.target.closest ? e.target.closest('.act') : null;
    if (!btn) return;
    var now = Date.now();
    if (now - lastActAt < 250) return;
    lastActAt = now;
    runAct(btn.getAttribute('data-c'));
  });

  /* ---------- 命中判定：只认点在角色包围盒里 ---------- */
  window.__petHitTest = function (cx, cy) {
    var r = stage.getBoundingClientRect();
    if (!r.width || !r.height) return false;
    var px = CROP[0] + ((cx - r.left) / r.width) * (CROP[2] - CROP[0]);
    var py = CROP[1] + ((cy - r.top) / r.height) * (CROP[3] - CROP[1]);
    var pad = 7;
    return px >= BODY[0] - pad && px <= BODY[2] + pad &&
           py >= BODY[1] - pad && py <= BODY[3] + pad;
  };

  /* ---------- 拖拽中播"被拖拽"反馈 ---------- */
  window.__petDragState = function (on) {
    dragging = !!on;
    if (on) { if (CLIPS.drag.length) play(CLIPS.drag[0], { loop: true }); walking = null; }
    else toIdle();
  };
  // 手指一落下就掐掉走动：否则走动的 rAF 仍在按曲线改窗口位置，和拖动抢同一个 params.x → 桌宠来回晃
  window.__petStopWalk = function () { walking = null; };

  /* ---------- 启动：先拉第一段，再上报就绪 ---------- */
  var reported = false, tries = 0;
  toIdle();   // 必须主动起一次：否则两个 video 都没 src，videoWidth 恒为 0，永远不就绪
  function ready() {
    if (reported) return;
    if (vs[0].videoWidth > 0 || vs[1].videoWidth > 0) {
      reported = true;
      if (bridge.onReady) bridge.onReady();
      schedule();
    }
  }
  var iv = setInterval(function () { ready(); if (reported || ++tries > 100) clearInterval(iv); }, 100);

  /* ---------- 手势层：全部自己接管，不依赖 WebView 合成鼠标事件 ----------
     （原实现靠 window 上的 mouse 事件拖窗口，手机上是真触摸，根本不会触发；
       再加上 OrbitControls 会抢事件 —— 早期"拖不动只原地转"就是这么来的。） */
  var TAP_MOVE = 8, pinch = { on: false, d0: 0 }, drag = { id: -1, x: 0, y: 0, moved: false };
  /* 物理像素 / CSS 像素。WebView 里 clientX 是 CSS px，窗口位置是物理 px。
     不换算的话手指走 100px 窗口只挪 100/DPR ≈ 36px —— 就是"拖动不跟手、太慢"。 */
  var ppc = window.devicePixelRatio || 1;
  function refreshPPC() {
    var b = bounds();
    if (b && b.w && window.innerWidth) ppc = b.w / window.innerWidth;
    else ppc = window.devicePixelRatio || 1;
  }
  function findTouch(l, id) { for (var i = 0; i < l.length; i++) if (l[i].identifier === id) return l[i]; return null; }
  function tdist(t) { var dx = t[0].clientX - t[1].clientX, dy = t[0].clientY - t[1].clientY; return Math.sqrt(dx * dx + dy * dy); }

  document.addEventListener('contextmenu', function (e) { e.preventDefault(); });
  document.addEventListener('gesturestart', function (e) { e.preventDefault(); });
  document.addEventListener('dblclick', function (e) { e.preventDefault(); });

  document.addEventListener('touchstart', function (e) {
    e.preventDefault();
    if (e.touches.length >= 2) {
      pinch.on = true; pinch.d0 = tdist(e.touches); drag.id = -1;
      if (bridge.setScaleStart) bridge.setScaleStart();
      return;
    }
    var t = e.changedTouches[0];
    drag.id = t.identifier; drag.x = t.clientX; drag.y = t.clientY; drag.moved = false;
    dragging = true;
    nativeDragOn = false;               // 新手势重新等下判定：拖窗口归原生还是归这里
    walking = null;                     // 立刻停下走动，不和拖动抢窗口位置
    refreshPPC();                       // 每次起手重算一次换算系数（缩放/换屏后也对）
    if (bridge.dragStart) bridge.dragStart();
  }, { passive: false });

  document.addEventListener('touchmove', function (e) {
    e.preventDefault();
    if (pinch.on && e.touches.length >= 2 && pinch.d0 > 1) {
      if (bridge.setScaleFactor) bridge.setScaleFactor(tdist(e.touches) / pinch.d0);
      return;
    }
    var t = findTouch(e.changedTouches, drag.id);
    if (!t) return;
    var mdx = t.clientX - drag.x, mdy = t.clientY - drag.y;      // CSS px
    if (!drag.moved && Math.abs(mdx) + Math.abs(mdy) > TAP_MOVE) {
      drag.moved = true;
      window.__petDragState(true);        // 真拖起来才切"被拖拽"动画，轻点不闪
    }
    // 原生侧已经在 move 事件里直接摆了窗口（那条路少绕一整圈，更跟手），
    // 这里就别再发一遍了 —— 这段只是原生不接管时的兜底。
    if (drag.moved && !nativeDragOn && bridge.dragMove) bridge.dragMove(mdx * ppc, mdy * ppc);
  }, { passive: false });

  /* 原生宣布「拖动我自己来」：后面不再发 dragMove，省掉每次移动一次的跨语言往返 */
  window.__petNativeDrag = function () { nativeDragOn = true; };

  document.addEventListener('touchend', function (e) {
    e.preventDefault();
    if (pinch.on && e.touches.length < 2) { pinch.on = false; if (bridge.setScaleEnd) bridge.setScaleEnd(); }
    var t = findTouch(e.changedTouches, drag.id);
    if (!t) { if (e.touches.length === 0) drag.id = -1; return; }
    if (bridge.dragEnd) bridge.dragEnd();
    var wasTap = !drag.moved;
    drag.id = -1;
    dragging = false;
    if (wasTap) {
      // 只有真点在角色身上才算点击；点在透明处不冒气泡。
      var onPet = !window.__petHitTest || window.__petHitTest(t.clientX, t.clientY);
      if (bridge.onTap) bridge.onTap(onPet);   // 原生决定：已吸附到边框则弹出，否则回调 __petTap
    } else {
      window.__petDragState(false);
    }
  }, { passive: false });

  /* ---------- 原生动作入口（设置页那排按钮） ---------- */
  function pickBy(re) { var a = CLIPS.random.filter(function (n) { return re.test(n); }); return a.length ? pick(a) : pick(CLIPS.random); }
  window.__petAndroid = {
    action: function (k) {
      if (k === 'spin') { oneShot('小幅度原地360度旋转展示'); say('转圈~', 1600); }
      else if (k === 'happy') { oneShot(pickBy(/舞|摇摆|轻快/)); say('嘿嘿~', 1800); }
      else if (k === 'sleep') { oneShot(pickBy(/小憩|沉眠|哈欠|打瞌睡/)); say('晚安…', 2200); }
      else if (k === 'wake') { toIdle(); say('醒啦~', 1800); }
      else if (k === 'diag') { window.__petAndroid.diag(); }   // ← 之前漏了这个分支，设置页点"自检"静默无反应
    }
  };

  window.__petPlay = function (n) { oneShot(n); };   // 调试：强行播某一段
  window.__petWalkTo = function (x, y) { return walkTo(x, y); };   // 调试：走到屏幕某点
  window.__petState = function () {
    return JSON.stringify({ acting: acting, walking: !!walking, busy: busy,
      wander: wanderOn, dragging: dragging, clip: curName, mode: mode });
  };
  window.__rates = function () { return JSON.stringify(RATES); };
  window.__decStats = function () { return JSON.stringify(window.__dec || { walk: 0, act: 0, talk: 0 }); };
  window.__decReset = function () { window.__dec = { walk: 0, act: 0, talk: 0 }; return 1; };
  window.__petDebug = function () {
    return JSON.stringify({ fling: !!fling, busy: busy, acting: acting, walking: !!walking,
                            paused: paused, touching: !!touching,
                            sinceRelease: touchEndAt ? Math.round(performance.now() - touchEndAt) : -1 });
  };
  window.__petClearFling = function () { fling = null; return true; };
  window.__petWalkDebug = function (dx, dy) {
    var b = bounds();
    var tx = (b.x || 0) + (dx || 0), ty = (b.y || 0) + (dy || 0);
    var pre = { busy: !!busy, acting: !!acting, fling: !!fling, walking: !!walking, paused: !!paused,
                moves: (CLIPS.moves || []).length, x: b.x, y: b.y };
    var r = walkTo(tx, ty);
    return JSON.stringify({ pre: pre, tx: tx, ty: ty, result: r, clip: curName });
  };
  window.__sync = function () { return JSON.stringify(window.__syncLog || []); };
  window.__petPos = function () {
    var b = bounds();
    return JSON.stringify([Math.round(b.x || 0), Math.round(b.y || 0)]);
  };
  window.__petWalkState = function () {
    if (!walking) return "null";
    return JSON.stringify({ m: walking.mode, dist: Math.round(walking.dist || 0),
      dur: Math.round((walking.dur || 0) * 100) / 100,
      u: [Math.round(walking.ux * 1000) / 1000, Math.round(walking.uy * 1000) / 1000],
      x0: walking.x0, y0: walking.y0, el: Math.round((performance.now() / 1000 - walking.t0) * 100) / 100 });
  };
  window.__petWander = function (on) { wanderOn = !!on; };
  window.__petAndroid.wander = function (on) { wanderOn = !!on; };

  /* ---------- 自检：一眼判断本机 WebView 能不能解 VP9 alpha ----------
     把底色临时换成品红。周围露出品红 = alpha 正常；整块黑 = 解码器忽略了 alpha 平面。 */
  window.__petAndroid.diag = function () {
    var v0 = document.getElementById('va'), v1 = document.getElementById('vb');
    var info = [v0, v1].map(function (v) {
      return (v.videoWidth || 0) + 'x' + (v.videoHeight || 0) +
             ' rs' + v.readyState + (v.error ? 'E' + v.error.code : '');
    }).join(' ');
    document.body.style.background = '#FF00FF';
    // 气泡最多装 3 行（见 PetService.PAD_RATIO），这条已经到极限了，别再往里加字
    say('露品红 = 透明正常 ✓　黑块 = 不支持 ✗　' + info, 10000);
    setTimeout(function () { document.body.style.background = 'transparent'; }, 10000);
  };

  var t0 = setTimeout(function () { say('我来了~', 2200); }, 1200);
})();
</script>
</body>
</html>
'''

vw = CW * 100.0 / (CROP[2] - CROP[0])
vh = CH * 100.0 / (CROP[3] - CROP[1])
vl = -CROP[0] * 100.0 / (CROP[2] - CROP[0])
vt = -CROP[1] * 100.0 / (CROP[3] - CROP[1])

html = (HTML
        .replace("__VW__", f"{vw:.4f}").replace("__VH__", f"{vh:.4f}")
        .replace("__VL__", f"{vl:.4f}").replace("__VT__", f"{vt:.4f}")
        .replace("__CLIPS__", json.dumps(CLIPS, ensure_ascii=False))
        .replace("__FIT__", json.dumps(FIT, ensure_ascii=False))
        .replace("__STRIDES__", json.dumps(STRIDES, ensure_ascii=False))
        .replace("__CURVES__", json.dumps(curves, ensure_ascii=False))
        .replace("__BODY__", json.dumps(list(BODY)))
        .replace("__BODYBOX__", json.dumps(BODYBOX))
        .replace("__BBOX__", json.dumps(BBOX))
        .replace("__MEDBOX__", json.dumps(MEDBOX))
        .replace("__CROP__", json.dumps(list(CROP))))
open(OUT_HTML, "w", encoding="utf-8").write(html)

print(f"\n生成 {OUT_HTML}: {len(html):,} 字符")
print(f"  视频铺放: width {vw:.2f}% height {vh:.2f}%  left {vl:.2f}%  top {vt:.2f}%")
print(f"  动画池: idle {len(CLIPS['idle'])} turn {len(CLIPS['turn'])} drag {len(CLIPS['drag'])} "
      f"clicks {len(CLIPS['clicks'])} moves {len(CLIPS['moves'])} random {len(CLIPS['random'])}")
print(f"  走动曲线: {list(curves.keys())}  长度 {[len(v) for v in curves.values()]}")
tot = sum(os.path.getsize(os.path.join(ASSETS, r, f))
          for r, _, fs in os.walk(ASSETS) for f in fs)
print(f"  assets/ 合计 {tot/1048576:.1f} MB")
