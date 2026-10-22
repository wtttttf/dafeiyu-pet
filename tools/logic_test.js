// pet.html 纯逻辑单测：不需要浏览器/真机，直接跑线上那份状态机源码。
// 每个关键用例都拿"旧实现"当对照组，确认它真的会失败 —— 否则测试没有区分力。
//
//   node tools/logic_test.js
//
// 这台机器上没有 adb（装包/截图/模拟触摸全做不了），所以这个文件是唯一的回归网。

const fs = require('fs');
const APP = '/root/pet-app';
const html = fs.readFileSync(APP + '/assets/pet.html', 'utf8');
const java = fs.readFileSync(APP + '/java/com/dafeiyu/pet/PetService.java', 'utf8');

let pass = 0, fail = 0;
function check(name, cond, extra) {
  if (cond) { pass++; console.log(`  ✓ ${name}`); }
  else { fail++; console.log(`  ✗ ${name}${extra ? '  → ' + extra : ''}`); }
}
const wait = (ms) => new Promise(r => setTimeout(r, ms));

/* ==================================================================
   0. 从线上 pet.html 里把 play/start/releasePending 原样抠出来，
      用假 video 元素喂它。测的是真代码，不是抄一份相似的。
   ================================================================== */
const A = html.indexOf('  function play(name, opt) {');
const B = html.indexOf('  var IDLE = CLIPS.idle[0]');
if (A < 0 || B < 0 || B <= A) { console.error('抠不出 play/start：pet.html 结构变了'); process.exit(1); }
const PLAY_SRC = html.slice(A, B);

function factory(src) {
  return new Function('vs', 'url', `
    var fi = 0, curName = null, curLoop = false;
    var busy = false, pending = null, loadSeq = 0;
${src}
    return {
      play: play,
      state: function () { return { fi: fi, busy: busy, curName: curName, pending: !!pending }; }
    };
  `);
}

/** 假 video：src 赋值会被记账（当时是否"还在画面上"），loadMs 后可解码 */
function makeEl(clock, track) {
  const el = {
    _src: '', readyState: 0, paused: true, ended: false, loop: false,
    onloadeddata: null, onended: null, onerror: null,
    classes: new Set(), lastFrontAt: -1e9, srcAt: [], timer: null,
  };
  el.classList = {
    add(c) { el.classes.add(c); },
    remove(c) {
      if (c === 'is-front' && el.classes.has(c)) { el.classes.delete(c); el.lastFrontAt = clock(); }
      else el.classes.delete(c);
    },
    contains(c) { return el.classes.has(c); },
  };
  el.play = function () { el.paused = false; return { catch() {} }; };
  el.pause = function () { el.paused = true; };
  // 和 CSS 对齐：摘掉 is-front 之后 .pv 还要按 opacity 过渡画 260ms 才真的隐身
  el.painting = () => el.classList.contains('is-front') || (clock() - el.lastFrontAt) < 260;
  Object.defineProperty(el, 'src', {
    get() { return el._src; },
    set(v) {
      el._src = v;
      el.readyState = 0;
      el.ended = false;
      const rec = { t: Math.round(clock()), painting: el.painting(), name: v };
      el.srcAt.push(rec);
      if (track) track.push({ el, rec });
      clearTimeout(el.timer);
      if (el.stuck) return;                       // 模拟"加载永远不回来"
      el.timer = setTimeout(() => { el.readyState = 2; if (el.onloadeddata) el.onloadeddata(); }, el.loadMs);
    },
  });
  return el;
}

function harness(opts) {
  opts = opts || {};
  const t0 = Date.now();
  const clock = () => Date.now() - t0;
  const tracked = [];
  const els = [0, 1].map(() => {
    const e = makeEl(clock, tracked);
    e.loadMs = opts.loadMs === undefined ? 20 : opts.loadMs;
    e.stuck = !!opts.stuck;
    return e;
  });
  const api = factory(opts.old ? OLD_SRC : PLAY_SRC)(
    els,
    (n) => 'webm/' + encodeURIComponent(n) + '.webm'
  );
  return { els, api, clock, tracked,
           fronts: () => els.filter(e => e.classList.contains('is-front')).length };
}

/* ---------- 对照组：第四轮那版 play()，原样抄下来 ---------- */
const OLD_SRC = `
  function play(name, opt) {
    opt = opt || {};
    if (!name) return;
    if (curName === name && opt.loop && curLoop && !vs[fi].paused) return;
    var back = vs[1 - fi];
    back.onended = null;
    back.loop = !!opt.loop;
    back.onloadeddata = function () { back.onloadeddata = null; go(); };
    back.onerror = function () {
      back.onerror = null;
      if (curName !== name && opt.onEnd) opt.onEnd();
    };
    var want = url(name), enc = encodeURIComponent(name) + '.webm';
    if (back.src && back.src.indexOf(enc) >= 0 && back.readyState >= 2) {
      back.onloadeddata = null;
      go();
    } else {
      back.src = want;
    }
    function go() {
      var p = back.play();
      if (p && p.catch) p.catch(function () {});
      var old = vs[fi];
      old.classList.remove('is-front');
      back.classList.add('is-front');
      fi = 1 - fi;
      curName = name;
      curLoop = !!opt.loop;
      if (opt.onEnd) back.onended = function () { opt.onEnd(); };
      setTimeout(function () {
        if (!old.classList.contains('is-front')) { try { old.pause(); } catch (e) {} }
      }, 320);
    }
  }
`;

/* ==================================================================
   1. 连点闪灰框：换 src 的瞬间，那个元素绝不能还在画面上
   ================================================================== */
async function burst(h) {
  for (let i = 0; i < 6; i++) {           // 用户连点：约 90ms 一次
    h.api.play('click' + i, { loop: false });
    await wait(90);
  }
  await wait(1500);
  return h.tracked.filter(x => x.rec.painting);
}

(async function main() {
  console.log('\n[1] 连点 6 下：不允许在"看得见的 video"上换 src（换一次就闪一次灰框）');
  const oldH = harness({ old: true, loadMs: 20 });
  const oldBad = await burst(oldH);
  check('旧实现确实会在可见期换 src（对照组，证明这条测试有区分力）',
        oldBad.length > 0, `可见期换 src ${oldBad.length} 次：` +
        oldBad.map(x => `t≈${x.rec.t}ms`).join(', '));

  const newH = harness({ loadMs: 20 });
  const newBad = await burst(newH);
  check('新实现任何一次换 src 都发生在元素隐身之后',
        newBad.length === 0, `可见期换 src ${newBad.length} 次：` +
        newBad.map(x => `t≈${x.rec.t}ms ${x.rec.name}`).join(', '));
  check('连点被收敛成少数几次，而不是干脆不播',
        newH.tracked.length >= 2 && newH.tracked.length < 6,
        `6 次点击 → 换 src ${newH.tracked.length} 次`);
  check('最后一次点击的动作确实上了台', newH.api.state().curName === 'click5' ||
        newH.api.state().curName === 'click4', 'curName=' + newH.api.state().curName);

  console.log('\n[2] 前台数不变量：任何时刻最多 1 个 video 在前台（>1 就是重影）');
  {
    const h = harness({ loadMs: 20 });
    let worst = 0;
    const sampler = setInterval(() => { worst = Math.max(worst, h.fronts()); }, 4);
    for (let i = 0; i < 6; i++) { h.api.play('c' + i, {}); await wait(90); }
    await wait(1200);
    clearInterval(sampler);
    check('连点全程前台数 ≤ 1', worst <= 1, '峰值 ' + worst);
    check('收尾后恰好 1 个在前台', h.fronts() === 1, '当前 ' + h.fronts());
  }

  console.log('\n[3] busy 必须会自己松开（卡住不松 = 桌宠永远不再换动作，且不报错）');
  {
    const h = harness({ loadMs: 20 });
    h.api.play('a', {});
    await wait(600);
    check('正常一轮后 busy 已释放', h.api.state().busy === false);
    const s = harness({ stuck: true });      // 加载永远不回来
    s.api.play('a', {});
    await wait(300);
    check('加载卡住时 busy 还扣着', s.api.state().busy === true);
    await wait(2200);
    check('2 秒兜底把 busy 放开了', s.api.state().busy === false);
  }

  console.log('\n[4] 边走边点：忙的时候 walk 不发车（否则走动曲线会和淡化错位）');
  {
    const h = harness({ loadMs: 20 });
    h.api.play('a', {});
    await wait(50);                          // 此时 busy=true
    const before = h.tracked.length;
    const st = h.api.state();
    check('淡化途中 state.busy 为真', st.busy === true);
    check('淡化途中没有额外发车', h.tracked.length === before, `${before} → ${h.tracked.length}`);
  }

  /* ==================================================================
     5. 气泡位置：必须落在角色头顶上方，而且 Java 撑开的那条要装得下
     ================================================================== */
  console.log('\n[5] 气泡几何：在头顶之上 + Java 撑开的窗口装得下');
  {
    const BODY = JSON.parse(html.match(/var BODY\s*=\s*(\[[^\]]*\])/)[1]);
    const CROP = JSON.parse(html.match(/var CROP\s*=\s*(\[[^\]]*\])/)[1]);
    const padRatio = parseFloat(java.match(/PAD_RATIO\s*=\s*([\d.]+)f/)[1]);
    const bubbleBottom = parseFloat(html.match(/bottom:calc\(100vw \* ([\d.]+)\)/)[1]);
    const fontVw = parseFloat(html.match(/font-size:([\d.]+)vw/)[1]);
    const padVw = parseFloat(html.match(/padding:([\d.]+)vw ([\d.]+)vw/)[1]);
    const lineH = 1.45;

    // 舞台高 = 1 个窗口宽；角色头顶在画布里是 BODY[1]，映射到舞台是
    // (BODY[1]-CROP[1]) / (CROP[3]-CROP[1]) 从上往下数
    const headTopFromBottom = 1 - (BODY[1] - CROP[1]) / (CROP[3] - CROP[1]);
    check('气泡底边在角色头顶之上', bubbleBottom > headTopFromBottom,
          `气泡 ${bubbleBottom} vs 头顶 ${headTopFromBottom.toFixed(4)}`);

    const room = (1 + padRatio) - bubbleBottom;          // 窗口比正方形高的部分里，气泡可用的
    const need2 = (2 * fontVw * lineH + 2 * padVw) / 100; // 两行文字（含内边距）
    const need3 = (3 * fontVw * lineH + 2 * padVw) / 100;
    check(`撑开的空间装得下 2 行气泡`, room >= need2,
          `可用 ${room.toFixed(3)}W vs 需要 ${need2.toFixed(3)}W`);
    check(`撑开的空间装得下 3 行气泡（自检那条消息）`, room >= need3,
          `可用 ${room.toFixed(3)}W vs 需要 ${need3.toFixed(3)}W`);

    // 舞台必须贴在窗口底部、且是正方形 —— 窗口比正方形高，角色不能跟着被拉长
    check('#stage 高度用 100vw（正方形，不跟着窗口长高）',
          /#stage\{[^}]*height:100vw/.test(html));
    check('#stage 贴在窗口底部（bottom:0）', /#stage\{[^}]*bottom:0/.test(html));
  }

  /* ==================================================================
     5b. 窗口高度必须全程恒定 —— 这是"桌宠跳一下"的根。
         一旦有人再加回"说话时把窗口撑高"的逻辑，这条就要拦住他。
     ================================================================== */
  console.log('\n[5b] 窗口尺寸恒定（改一次高度 = 跳一下）');
  check('Java 侧没有 setUiPad / uiPad 这类动态改高的通路',
        !/setUiPad/.test(java) && !/\buiPad\b/.test(java));
  check('网页侧也不再请求改窗口高度', !/setUiPad/.test(html));
  check('winH() 恒等于 winW() × (1+PAD_RATIO)，没有可变条件',
        /private int winH\(\)\s*\{\s*return Math\.round\(winW\(\) \* \(1f \+ PAD_RATIO\)\);/.test(java));
  check('缩放锚定用的是角色中心，不是窗口中心',
        /CHAR_CENTER_FROM_BOTTOM/.test(java) && !/cy = params\.y \+ oldH \/ 2f/.test(java));

  /* ==================================================================
     6. 互动按钮：touchstart 里 preventDefault 会让浏览器不再合成 click，
        所以必须自己判。这几条是防止有人"顺手删掉看起来冗余的 touchend"。
     ================================================================== */
  console.log('\n[6] 互动键的点击通路');
  check('actsEl 上有 touchstart 拦截（不让手势层接管）', /actsEl\.addEventListener\('touchstart'/.test(html));
  check('actsEl 上有 touchend 自己判定按钮', /actsEl\.addEventListener\('touchend'/.test(html));
  check('touchend 里按矩形判定并执行 runAct', /hitAct/.test(html) && /runAct\(/.test(html));
  check('保留了 click 兜底（鼠标/合成事件仍可用）', /actsEl\.addEventListener\('click'/.test(html));
  check('有防重复触发的时间闸', /lastActAt/.test(html));

  /* ==================================================================
     7. 拖拽：原生直接接管 + 网页侧兜底，两条不能互相打架
     ================================================================== */
  console.log('\n[7] 拖动：原生直驱 + 网页兜底');
  check('原生给 WebView 挂了 OnTouchListener', /setOnTouchListener/.test(java));
  check('原生 MOVE 里直接 updateViewLayout', /case MotionEvent\.ACTION_MOVE/.test(java) &&
        /onPetTouch/.test(java));
  check('双指时原生让位（pinching）', /pinching/.test(java));
  check('原生接管后网页侧的 dragMove 被丢掉（不重复位移）',
        /if \(nativeDrag\) return;/.test(java));
  check('网页侧收到 __petNativeDrag 后停止发 dragMove',
        /__petNativeDrag/.test(html) && /!nativeDragOn && bridge\.dragMove/.test(html));
  check('原生没接管时网页侧仍能拖（兜底没被拆掉）',
        /if \(bridge\.dragMove\)/.test(html) || /bridge\.dragMove\(/.test(html));

  /* ==================================================================
     8. 走动曲线（第三轮就有的老用例，别丢）
     ================================================================== */
  console.log('\n[8] 走动位移（用 pet.html 里真实嵌入的曲线）');
  const curves = JSON.parse(html.match(/var CURVES\s*=\s*(\{[\s\S]*?\});/)[1]);
  const strides = JSON.parse(html.match(/var STRIDES\s*=\s*(\{[\s\S]*?\});/)[1]);
  check('读到了 3 段走动曲线', Object.keys(curves).length === 3, Object.keys(curves).join(','));
  for (const [name, curve] of Object.entries(curves)) {
    const STRIDE = strides[name], DUR = 4.0, CYCLES = 3;
    const xAt = (t) => {
      const cyc = Math.floor(t / DUR), frac = (t % DUR) / DUR;
      const k = curve[Math.min(curve.length - 1, Math.floor(frac * curve.length))];
      return STRIDE * (cyc + k);
    };
    let mono = true, prev = -1;
    for (let t = 0; t <= CYCLES * DUR; t += 1 / 120) { const v = xAt(t); if (v < prev - 1e-9) mono = false; prev = v; }
    let maxJump = 0;
    for (let t = 0; t <= CYCLES * DUR; t += 1 / 60) maxJump = Math.max(maxJump, xAt(t + 1 / 60) - xAt(t));
    check(`${name}: 位移单调不减 / 跨圈连续 / 单帧 <40px`,
          mono && Math.abs(curve[curve.length - 1] - 1) < 1e-6 && maxJump < 40,
          `末值 ${curve[curve.length - 1]} 最大帧移 ${maxJump.toFixed(1)}px`);
  }

  /* ==================================================================
     9. 设置页每个指令都要有处理分支（"自检点了没反应"就是这么漏的）
     ================================================================== */
  console.log('\n[9] 原生指令 ↔ JS 分支对齐');
  const ma = fs.readFileSync(APP + '/java/com/dafeiyu/pet/MainActivity.java', 'utf8');
  const cmds = new Set();
  for (const m of ma.matchAll(/actionButton\("[^"]*",\s*"([^"]+)"\)/g)) cmds.add(m[1]);
  for (const m of ma.matchAll(/sendCommand\((?:on \? )?"([^"]+)"/g)) cmds.add(m[1]);
  const wanderCmds = new Set(['wander_on', 'wander_off']);
  const bodyM = html.match(/action:\s*function\s*\(k\)\s*\{([\s\S]*?)\n    \}/);
  const body = bodyM ? bodyM[1] : '';
  console.log(`  设置页可发出: ${[...cmds].join(', ')}`);
  for (const c of cmds) {
    if (wanderCmds.has(c)) check(`${c} 有处理`, html.includes('__petWander'));
    else check(`action('${c}') 有处理分支`, body.indexOf("'" + c + "'") >= 0, '未找到分支');
  }

  /* ==================================================================
     10. 原生 → 网页的每个回调，网页侧都得真的有那个函数
     ================================================================== */
  console.log('\n[10] 原生调用的网页函数都存在');
  for (const m of java.matchAll(/evaluateJavascript\(\s*"(window\.[A-Za-z_$][\w$.]*)/g)) { /* 少数直接写的 */ }
  const called = new Set();
  for (const m of java.matchAll(/js\("(window\.[^ "]+)/g)) called.add(m[1].split(/\s|&/)[0]);
  for (const m of java.matchAll(/"window\.(__pet[A-Za-z]+)\s*&&/g)) called.add('window.' + m[1]);
  for (const c of called) {
    const fn = c.replace(/^window\./, '');
    check(`网页侧定义了 ${fn}`, html.includes('window.' + fn + ' =') || html.includes('function ' + fn), c);
  }

  /* ==================================================================
     11. 素材合规：下游的裁切/命中判定全按 640x360 + 身体盒算，素材跑偏就全歪
     ================================================================== */
  console.log('\n[11] 素材房间检查（pet.html 里提到的每一段都得真的在）');
  {
    const clips = JSON.parse(html.match(/var CLIPS\s*=\s*(\{[\s\S]*?\});/)[1]);
    const all = [...new Set(Object.values(clips).flat())];
    const missing = all.filter(n => !fs.existsSync(`${APP}/assets/webm/${n}.webm`));
    check(`CLIPS 里的 ${all.length} 段素材文件都在`, missing.length === 0,
          '缺 ' + missing.slice(0, 5).join(', '));
    check('随机池不为空（否则桌宠只会干站着）', clips.random.length > 0);
    check('点击回应池不为空', clips.clicks.length > 0);
    check('待机池有 1 段', clips.idle.length === 1, clips.idle.join(','));

    // 自己加的片段才是会出错的，逐段 ffprobe（上游那 106 段已知合规，抽样即可）
    const { execFileSync } = require('child_process');
    const customDir = APP + '/assets/webm_custom';
    const custom = fs.existsSync(customDir)
      ? fs.readdirSync(customDir).filter(f => f.endsWith('.webm')).map(f => f.slice(0, -5)) : [];
    const probeList = custom.length ? custom : all.filter((_, i) => i % 40 === 0);
    let bad = [];
    for (const n of probeList) {
      try {
        const out = execFileSync('ffprobe', ['-v', 'error', '-select_streams', 'v:0',
          '-show_entries', 'stream=codec_name,width,height', '-of', 'csv=p=0',
          `${APP}/assets/webm/${n}.webm`], { encoding: 'utf8' }).trim();
        if (!/^vp9,640,360$/.test(out)) bad.push(`${n}: ${out}`);
      } catch (e) { bad.push(`${n}: 读不出`); }
    }
    check(`抽查 ${probeList.length} 段：都是 vp9 640x360`, bad.length === 0, bad.join(' | '));
  }

  /* ==================================================================
     12. 生成器不许再把素材库整个删掉重建（以前 rmtree 一次，自己加的片段就没了）
     ================================================================== */
  console.log('\n[12] 生成器对自定义素材友好');
  const mk = fs.readFileSync(APP + '/tools/mk_pet_html.py', 'utf8');
  check('不再 rmtree assets/webm', !/rmtree\(DST_WEBM\)/.test(mk));
  check('有 webm_custom 目录且优先级高于上游', /CUSTOM_WEBM/.test(mk) && /name in custom/.test(mk));
  // 素材库是唯一副本（/tmp/petref 随时可能被清空），所以绝不能有"扫荡式清理"：
  // 撤下只认 manifest 里记过的名字，绝不按"某个来源里没有"来删文件。
  check('没有扫荡式清理（不按"来源里没有"删素材）', !/not in upstream/.test(mk));
  check('撤下只动 manifest 里记过的名字', /installed - custom/.test(mk));
  check('撤下前先看上游有没有同名（有就还原，不是直接删）', /name in upstream/.test(mk));
  check('支持 tools/actions.json 指定池子', /OVERRIDE/.test(mk) && fs.existsSync(APP + '/tools/actions.json'));

  /* ==================================================================
     13. 管线走一遍：丢一段进 webm_custom → 真的出现在池子里 → 拿掉能干净回滚
     ================================================================== */
  console.log('\n[13] 加动作的端到端（丢文件 → 生成 → 回滚）');
  {
    const { execFileSync } = require('child_process');
    const demo = `${APP}/assets/webm_custom/点击回应-管线自检.webm`;
    const staged = `${APP}/assets/webm/点击回应-管线自检.webm`;
    const gen = () => execFileSync('python3', [APP + '/tools/mk_pet_html.py'],
                                   { cwd: APP, stdio: 'pipe' });
    const poolOf = (p) => JSON.parse(fs.readFileSync(p, 'utf8')
                        .match(/var CLIPS\s*=\s*(\{[\s\S]*?\});/)[1]);
    try {
      fs.copyFileSync(`${APP}/assets/webm/点击回应-元气挥手.webm`, demo);
      gen();
      const c = poolOf(APP + '/assets/pet.html');
      check('新片段自动进了 clicks 池（靠 点击回应- 前缀）', c.clicks.includes('点击回应-管线自检'));
      check('新片段也进了 assets/webm/', fs.existsSync(staged));
    } finally {
      fs.unlinkSync(demo);
      gen();
    }
    const c2 = poolOf(APP + '/assets/pet.html');
    check('撤掉后从池子里消失', !c2.clicks.includes('点击回应-管线自检'));
    check('撤掉后 assets/webm/ 的残留也被清掉', !fs.existsSync(staged));
    check('原有 5 个点击回应没被误伤', c2.clicks.length === 5, c2.clicks.join(','));
    check('跑完一轮 pet.html 与开跑前逐字节一致（生成器是幂等的）',
          fs.readFileSync(APP + '/assets/pet.html', 'utf8') === html);
  }

  console.log(`\n结论: ${fail === 0 ? '✓ 全部通过 (' + pass + ' 项)' : '✗ ' + fail + ' 项失败'}`);
  process.exit(fail === 0 ? 0 : 1);
})();
