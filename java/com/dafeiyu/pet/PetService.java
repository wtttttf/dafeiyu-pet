package com.dafeiyu.pet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.widget.TextView;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;

import java.util.ArrayList;

/**
 * 大肥鱼桌宠（3D 版）。
 *
 * 渲染交给 assets/pet.html —— 移植自 GitHub 开源实现 elyoncatnecoe/whale-pet，
 * 用 three.js 程序化重建 DeepSeek 虎鲸 logo（身体/尾鳍/背鳍/胸鳍/眼斑）。
 *
 * 分工：
 *   网页侧 = 建模 + 动画 + 互动特效（换肤/情绪/眨眼/爱心/睡眠/音效）
 *   原生侧 = 悬浮窗 + 移窗口 + 捏合改大小 + 保活
 * 两者通过 addJavascriptInterface 注入的 AndroidBridge 通信；
 * 网页里 window.petWindow 是纯 JS 包装，原实现 window.petWindow.dragMove(...) 原样可用。
 */
public class PetService extends Service {
    private static final String TAG = "PetService";

    public static final String EXTRA_SCALE = "scale";
    public static final String EXTRA_CMD = "cmd";

    /** 服务是否在跑；设置页据此决定是"发指令"还是"先启动"（同进程，静态标志足够） */
    public static volatile boolean running = false;
    /** 当前实例，只给 DbgReceiver（自动化测试）用；正常代码一律不碰 */
    static volatile PetService instance;

    static final String PREFS = "dafeiyu";

    /** 桌宠当前的屏幕矩形（x,y,w,h），对话气泡据此跟随；只读快照 */
    static final int[] RECT = new int[]{0, 0, 0, 0};
    static int[] petRect() { synchronized (RECT) { return new int[]{RECT[0], RECT[1], RECT[2], RECT[3]}; } }
    private void syncRect() {
        synchronized (RECT) {
            RECT[0] = params.x; RECT[1] = params.y;
            RECT[2] = params.width; RECT[3] = params.height;
        }
    }
    static final String KEY_SCALE = "scale";
    static final String KEY_WANDER = "wander";
    static final String KEY_MODE = "mode";
    static final float MIN_SCALE = 0.5f, MAX_SCALE = 3.0f;
    /** 基准窗口边长(dp) @ scale=1 */
    private static final float BASE_DP = 240f;
    /** WebGL 就绪看门狗超时(ms) */
    private static final long WATCHDOG_MS = 4500L;
    /** 拖到左右边缘多少 dp 之内，松手时"缩进边框" */
    private static final int DOCK_TRIGGER_DP = 26;
    /** 吸附后露在外面的比例 */
    private static final float DOCK_VISIBLE_FRAC = 0.35f;
    /**
     * 窗口高度 = 宽度 × (1 + 这个比例)。多出来的那一条在角色头顶上方，专门留给气泡。
     *
     * 【为什么是恒定的，不做成"说话时才撑高"】窗口一 resize，WebView 就得重新排版 +
     * 重新光栅化，中间几帧旧画面会被拉伸着显示 → 看着就是桌宠"跳一下"。这个高度从头到尾
     * 不许变，跳的根就断了。代价是角色头顶上方有一条恒定的透明区会吃触摸（按角色算
     * 大约是"比看得见的身体高半截"），当触摸热区看待就行。
     *
     * 数值要和 pet.html 里 .bubble 的 bottom（0.91 × 窗口宽）对得上：
     * 气泡底边在那儿，往上还得装得下三行字（约 0.28 × 窗口宽）。
     */
    private static final float PAD_RATIO = 0.22f;
    /** 角色中心在窗口里的位置（× 窗口宽，从窗口底边往上量）。缩放时按它锚定，宠物不会跑偏。 */
    private static final float CHAR_CENTER_FROM_BOTTOM = 0.48f;
    /** 缓动类型 */
    private static final int EASE_INOUT = 0, EASE_BACK = 1;

    private WindowManager wm;
    private View petView;
    private WebView webView;
    private ImageView fallback;
    private WindowManager.LayoutParams params;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private volatile float scale = 1f;
    private float pinchStartScale = 1f;
    private int dragStartX, dragStartY;
    private int screenW, screenH;
    private int fallbackFrame = 0;
    private volatile boolean glReady = false;
    private boolean docked = false;
    private boolean wanderOn = true;
    private boolean dragging = false;
    /** 动画令牌：拖动/新动画一开，旧动画自查令牌过期就自己停 */
    private int animToken = 0;

    // ===== 拖拽：原生在 dispatchTouchEvent 里直接接管（见 onPetTouch）=====
    /** 超过这个位移就算"拖"而不是"点"；用系统 slop，和网页侧 8css px 的阈值基本同值 */
    private int slop;
    private boolean pinching = false;      // 双指以上：让位给网页侧的捏合缩放
    private boolean nativeDrag = false;
    /** 原生刚替网页侧收过尾（clamp/吸附）的时刻，用来丢掉网页侧迟到的重复收尾/误判点击 */
    private long nativeUpAt = 0;
    private Runnable longPressTask = null;
    /* ---- 按住说话（PTT）---- */
    private AsrClient asr;
    private TextView pttView;
    private TextView sayView;
    private Runnable sayHide;

    private boolean speechEnabled() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("speech_enabled", true);
    }

    /** 当前真正存在的模块（关掉的不在列表里，对齐 dsh-pet 的 modules= 语义） */
    private String modulesSummary() {
        StringBuilder b = new StringBuilder("window");
        if (speechEnabled()) b.append("|speech");
        if (wanderOn) b.append("|wander");
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("link_enabled", false)) b.append("|link");
        return b.toString();
    }
    private LinkPoller link;
    private android.media.SoundPool sfx;
    private int sClick = 0, sStart = 0, sDone = 0, sError = 0, sQuack = 0;
    private android.content.BroadcastReceiver screenRx;
    private boolean pttActive = false;
    private Runnable pttStartTask = null;
    private float nDownX, nDownY;
    private SysAsr sysAsr = null;        // 系统自带语音识别（无服务时的兜底）
    private boolean pttSys = false;      // 本次按住用的是系统识别
    private long lastTapAt = 0;          // 双击查余额
    private long nLastT;                 // 拖动速度采样
    private float nLastX, nLastY, nVx, nVy;
    private int nStartX, nStartY;

    /** 屏幕尺寸不能只在启动时取一次：横竖屏切换后必须刷新，否则走动范围会错乱 */
    private void refreshScreenMetrics() {
        try {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;
        } catch (Throwable ignored) { }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        refreshScreenMetrics();
        ui.post(new Runnable() {
            public void run() {
                try {
                    if (params != null) {
                        params.x = Math.max(-params.width + dp(28), Math.min(screenW - dp(28), params.x));
                        params.y = Math.max(0, Math.min(screenH - params.height, params.y));
                        if (petView != null && petView.isAttachedToWindow()) wm.updateViewLayout(petView, params);
                        syncRect();
                        positionSay();
                    }
                } catch (Throwable ignored) { }
                Log.i(TAG, "旋转后屏幕 " + screenW + "x" + screenH);
                js("window.__petScreenChanged && window.__petScreenChanged()");
            }
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        instance = this;
        startForegroundCompat();

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        refreshScreenMetrics();
        slop = ViewConfiguration.get(this).getScaledTouchSlop();

        scale = getSharedPreferences(PREFS, MODE_PRIVATE).getFloat(KEY_SCALE, 1f);
        scale = clampScale(scale);
        wanderOn = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_WANDER, true);

        params = new WindowManager.LayoutParams(
                winW(), winH(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = Math.max(0, screenW - winW() - dp(12));
        params.y = Math.round(screenH * 0.30f);

        showWebView();

        Log.i(TAG, "modules=" + modulesSummary());

        // DSH 状态联动（可在设置里关；关掉就不建轮询）
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("link_enabled", false)) {
            link = new LinkPoller(this);
            link.start();
        }

        initSfx();

        // 息屏省电：停轮询、停决策、暂停 WebView；亮屏恢复
        screenRx = new android.content.BroadcastReceiver() {
            public void onReceive(android.content.Context c, android.content.Intent i) {
                if (i == null || i.getAction() == null) return;
                if (android.content.Intent.ACTION_SCREEN_OFF.equals(i.getAction())) setAwake(false);
                else if (android.content.Intent.ACTION_SCREEN_ON.equals(i.getAction())) setAwake(true);
            }
        };
        android.content.IntentFilter sf = new android.content.IntentFilter();
        sf.addAction(android.content.Intent.ACTION_SCREEN_OFF);
        sf.addAction(android.content.Intent.ACTION_SCREEN_ON);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenRx, sf, android.content.Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenRx, sf);
            }
        } catch (Throwable t) { Log.w(TAG, "注册息屏广播失败: " + t); }

        // 看门狗：WebView/WebGL 起不来（老设备、被策略禁掉硬件加速等）就回退 2D 立绘，
        // 保证桌宠至少能以旧形态出现，不会白屏一片。
        ui.postDelayed(new Runnable() {
            public void run() {
                if (!glReady) {
                    Log.w(TAG, "WebGL 未就绪，回退 2D 立绘");
                    showFallback();
                }
            }
        }, WATCHDOG_MS);
    }

    // ==================== 视图 ====================

    private void showWebView() {
        webView = new WebView(this);
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);              // 原实现用 localStorage 存皮肤
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);  // 允许 Web Audio 出声
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.setSafeBrowsingEnabled(false);
        }

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        webView.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) { return onPetTouch(e); }
        });
        // 老系统上 file:// 页面的跨源脚本加载开关（assets 不受 allowFileAccess 影响，这里只是兜底）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            s.setAllowFileAccessFromFileURLs(true);
            s.setAllowUniversalAccessFromFileURLs(true);
        }

        swapView(webView);
        webView.loadUrl("file:///android_asset/pet.html");
        Log.i(TAG, "WebView 已加载 pet.html，窗口 " + params.width + "x" + params.height);
    }

    // ==================== 拖拽：原生在这里直接接管 ====================
    // 触摸事件本来就先过 WebView 的 dispatchTouchEvent，OnTouchListener 就是这一步的第一站。
    // 在这儿直接改窗口位置，比"页面 touchmove → 跨语言回调 → post 回主线程"少绕整整一圈，
    // 拖动才跟手。返回 false 表示"不消费"，事件照旧发给网页 —— 点击判定、捏合缩放都还要它。
    private boolean onPetTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                js("window.__petTouch && window.__petTouch(true)");
                nDownX = e.getRawX();
                nDownY = e.getRawY();
                nStartX = params.x;
                nStartY = params.y;
                nativeDrag = false;
                /* 长按判定放原生：拖动一旦超过 slop 就取消，不会被误当成"按住不动"。
                   避开顶部气泡那条区域（那里不是角色）。 */
                cancelLongPress();
                cancelPttStart();
                if (e.getY() > params.height * 0.12) {
                    /* 按住 220ms 没动 → 开始录音（微信式按住说话）；短按仍算点击 */
                    pttStartTask = new Runnable() {
                        public void run() {
                            pttStartTask = null;
                            if (nativeDrag || pinching) return;
                            nativeUpAt = SystemClock.uptimeMillis();
                            pttStart();
                        }
                    };
                    ui.postDelayed(pttStartTask, 220);
                }
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                pinching = true;      // 第二根手指落下 → 交给网页侧的捏合缩放
                nativeDrag = false;
                break;

            case MotionEvent.ACTION_MOVE: {
                if (pinching || e.getPointerCount() >= 2) break;
                float dx = e.getRawX() - nDownX, dy = e.getRawY() - nDownY;
                if (!nativeDrag) {
                    if (dx * dx + dy * dy < (float) slop * slop) break;
                    cancelLongPress();
                    cancelPttStart();
                    if (pttActive) pttStop();
                    nativeDrag = true;
                    dragging = true;
                    nVx = nVy = 0; nLastT = 0;
                    animToken++;                       // 打断正在跑的走动/吸附/弹出
                    js("window.__petNativeDrag && window.__petNativeDrag()");
                }
                params.x = nStartX + Math.round(dx);
                params.y = nStartY + Math.round(dy);
                if (petView != null) wm.updateViewLayout(petView, params);
                // 采样指针速度，松手时用于"甩出去"
                long nowT = SystemClock.uptimeMillis();
                if (nLastT > 0) {
                    float dt = (nowT - nLastT) / 1000f;
                    if (dt > 0.001f) {
                        float cvx = (e.getRawX() - nLastX) / dt;
                        float cvy = (e.getRawY() - nLastY) / dt;
                        nVx = nVx * 0.55f + cvx * 0.45f;
                        nVy = nVy * 0.55f + cvy * 0.45f;
                    }
                }
                nLastT = nowT; nLastX = e.getRawX(); nLastY = e.getRawY();
                syncRect();
                break;
            }

            case MotionEvent.ACTION_UP:
                js("window.__petTouch && window.__petTouch(false)");
                cancelLongPress();
                cancelPttStart();
                if (pttActive) {
                    pttStop();
                    /* 松手后抑制网页侧的"点击"：否则说完一句会顺带触发一次随机动作 */
                    nativeUpAt = SystemClock.uptimeMillis();
                }
                pinching = false;
                if (!nativeDrag && !pttActive) {
                    long nowT = SystemClock.uptimeMillis();
                    if (nowT - lastTapAt < 320) {          // 双击 = 查余额
                        lastTapAt = 0;
                        queryBalance();
                    } else {
                        lastTapAt = nowT;
                        playClickSfx();
                    }
                }
                if (nativeDrag) {
                    nativeDrag = false;
                    dragging = false;
                    nativeUpAt = SystemClock.uptimeMillis();
                    clampToScreen();
                    if (petView != null) wm.updateViewLayout(petView, params);
                    float sp = (float) Math.hypot(nVx, nVy);
                    if (sp > 900f) {                    // 甩得够快才触发，慢慢拖不甩
                        js("window.__petFling && window.__petFling(" + Math.round(nVx) + ","
                                + Math.round(nVy) + ")");
                        Log.i(TAG, "fling v=" + Math.round(nVx) + "," + Math.round(nVy));
                    }
                    nVx = nVy = 0; nLastT = 0;
                    /* 不自动吸附到边框：贴边时窗口大半在屏幕外，点到的是透明边距 */
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                js("window.__petTouch && window.__petTouch(false)");
                cancelLongPress();
                cancelPttStart();
                if (pttActive) pttAbort();
                pinching = false;
                nativeDrag = false;
                dragging = false;
                break;
        }
        return false;
    }

    /** 把设置页的三根频率拉杆推给网页侧（每分钟次数） */
    void applyRates() {
        int w = MainActivity.intPref(getSharedPreferences(PREFS, MODE_PRIVATE), "rate_walk", 2);
        int a = MainActivity.intPref(getSharedPreferences(PREFS, MODE_PRIVATE), "rate_act", 3);
        int t = MainActivity.intPref(getSharedPreferences(PREFS, MODE_PRIVATE), "rate_talk", 1);
        js("window.__petRates && window.__petRates(" + w + "," + a + "," + t + ")");
        Log.i(TAG, "rates walk=" + w + " act=" + a + " talk=" + t);
    }

    /** 双击桌宠：查 DeepSeek 余额，用专门的余额动画 */
    void queryBalance() {
        if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("use_api", true)) {
            showSay("API 关着呢，先打开才查得到余额");
            return;
        }
        js("window.__petPlay && window.__petPlay('余额-数金皱眉')");
        showSay("我数数钱袋…");
        BalanceClient.query(this, new BalanceClient.Cb() {
            public void onResult(double total, double granted, double topped, String cur) {
                String unit = "CNY".equalsIgnoreCase(cur) ? "¥" : (cur + " ");
                String text = "余额 " + unit + String.format("%.2f", total);
                if (granted > 0 || topped > 0) {
                    text += "（赠送 " + unit + String.format("%.2f", granted)
                            + " / 充值 " + unit + String.format("%.2f", topped) + "）";
                }
                showSay(text);
                String clip;
                if (total <= 0.01) clip = "余额-分文不剩";
                else if (total < 5) clip = "余额-数金皱眉";
                else if (total < 50) clip = "余额-钱袋如常";
                else clip = "余额-钱袋满溢";
                js("window.__petPlay && window.__petPlay('" + clip + "')");
            }
            public void onError(String msg) {
                showSay(msg);
                js("window.__petPlay && window.__petPlay('余额-袋空如洗')");
            }
        });
    }

    // ==================== 音效 ====================
    private boolean sfxOn() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("sfx_enabled", true);
    }

    private int loadSfx(String name) {
        try {
            android.content.res.AssetFileDescriptor fd = getAssets().openFd(name);
            return sfx.load(fd, 1);
        } catch (Throwable t) {
            try {
                java.io.File out = new java.io.File(getCacheDir(), name.replace('/', '_'));
                java.io.InputStream in = getAssets().open(name);
                java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                in.close(); fo.close();
                return sfx.load(out.getAbsolutePath(), 1);
            } catch (Throwable t2) { return 0; }
        }
    }

    private void initSfx() {
        if (!sfxOn()) return;
        try {
            sfx = new android.media.SoundPool.Builder().setMaxStreams(3).build();
            sClick = loadSfx("sounds/click.wav");
            sStart = loadSfx("sounds/start.wav");
            sDone = loadSfx("sounds/done.wav");
            sError = loadSfx("sounds/error.wav");
            sQuack = loadSfx("sounds/Ya1.mp3");
            Log.i(TAG, "sfx ready click=" + sClick);
        } catch (Throwable t) { Log.w(TAG, "音效初始化失败: " + t); }
    }

    void playSfx(int id, float vol) {
        if (sfx == null || id == 0 || !sfxOn()) return;
        try { sfx.play(id, vol, vol, 1, 0, 1f); } catch (Throwable ignored) { }
    }

    /** 点击音效：只用"叽"的那声（娃娃鞋/小鸭叫 Ya1），不再混低沉的 click */
    void playClickSfx() {
        if (!sfxOn()) return;
        if (sQuack != 0) playSfx(sQuack, 0.9f);
        else playSfx(sClick, 0.9f);
        Log.i(TAG, "sfx click -> " + (sQuack != 0 ? "Ya1(叽)" : "click"));
    }

    /** DSH 状态音效：start/done/error */
    static void sfxAgent(String state) {
        PetService s = instance;
        if (s == null || state == null) return;
        if ("thinking".equals(state)) s.playSfx(s.sStart, 0.6f);
        else if ("success".equals(state)) s.playSfx(s.sDone, 0.8f);
        else if ("error".equals(state)) s.playSfx(s.sError, 0.8f);
    }

    /** 息屏/亮屏：息屏时不做任何决策与轮询，避免耗电和"锁屏还动" */
    void setAwake(final boolean awake) {
        ui.post(new Runnable() { public void run() {
            if (awake) {
                if (webView != null) { try { webView.onResume(); } catch (Throwable ignored) { } }
                js("window.__petResume && window.__petResume()");
                if (link != null && !link.isRunning()) link.start();
                Log.i(TAG, "screen on: resume");
            } else {
                if (link != null) link.stop();
                if (sayView != null) sayView.setVisibility(android.view.View.GONE);
                js("window.__petPause && window.__petPause()");
                if (webView != null) { try { webView.onPause(); } catch (Throwable ignored) { } }
                Log.i(TAG, "screen off: pause");
            }
        }});
    }

    /** 桌宠说的话：独立悬浮窗，宽度最多屏幕 76%，高度随内容，能超出桌宠窗口 */
    void showSay(final String text) {
        if (!speechEnabled()) return;              // 气泡模块关闭：不建 View、不刷新
        ui.post(new Runnable() { public void run() {
            if (sayView == null) {
                TextView tv = new TextView(PetService.this);
                tv.setTextColor(0xFF1E2A44);
                tv.setTextSize(14);
                tv.setLineSpacing(dp(2), 1f);
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(0xFFF6F9FF);
                bg.setCornerRadius(dp(16));
                bg.setStroke(dp(1), 0x332C4BFF);
                tv.setBackground(bg);
                tv.setPadding(dp(14), dp(10), dp(14), dp(10));
                tv.setMaxWidth((int) (screenW * 0.76f));
                tv.setElevation(dp(6));
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        android.graphics.PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.LEFT;
                /* 关键：先在离屏量好尺寸、算好坐标，再 addView。
                   以前 addView 用的是默认 (0,0)，第一帧会出现在左上角，随后被 positionSay 拉回来，
                   看起来就是"气泡闪一下左上角再跳到大肥鱼头上"。 */
                tv.setText(text);
                tv.setAlpha(0f);
                tv.measure(android.view.View.MeasureSpec.makeMeasureSpec((int) (screenW * 0.76f), android.view.View.MeasureSpec.AT_MOST),
                        android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED));
                int[] xy0 = calcSayXY(tv.getMeasuredWidth(), tv.getMeasuredHeight());
                lp.x = xy0[0]; lp.y = xy0[1];
                try { wm.addView(tv, lp); sayView = tv; } catch (Throwable th) { return; }
                Log.i(TAG, "say first frame at " + xy0[0] + "," + xy0[1]);
                tv.post(new Runnable() { public void run() { if (sayView != null) sayView.setAlpha(1f); } });
            } else {
                sayView.setAlpha(0f);
                sayView.setText(text);
                sayView.post(new Runnable() { public void run() { positionSay(); if (sayView != null) sayView.setAlpha(1f); } });
            }
            if (sayHide != null) ui.removeCallbacks(sayHide);
            sayHide = new Runnable() { public void run() {
                if (sayView != null) { try { wm.removeView(sayView); } catch (Throwable ignored) { } sayView = null; }
            }};
            ui.postDelayed(sayHide, 9000);
        }});
    }

    /** 气泡目标坐标：优先放在桌宠头顶，顶部没空间就翻到下面 */
    private int[] calcSayXY(int w, int h) {
        int x = Math.max(dp(6), Math.min(screenW - w - dp(6), params.x + params.width / 2 - w / 2));
        boolean above = params.y - h - dp(8) > 0;
        int y = above ? (params.y - h - dp(8)) : (params.y + params.height + dp(8));
        return new int[]{x, y};
    }

    private void positionSay() {
        if (sayView == null) return;
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) sayView.getLayoutParams();
        int w = sayView.getWidth(), h = sayView.getHeight();
        if (w <= 0 || h <= 0) return;
        int[] xy = calcSayXY(w, h);
        lp.x = xy[0]; lp.y = xy[1];
        try { wm.updateViewLayout(sayView, lp); } catch (Throwable ignored) { }
    }

    private void cancelPttStart() {
        if (pttStartTask != null) { ui.removeCallbacks(pttStartTask); pttStartTask = null; }
    }

    /* ==================== 按住说话 ==================== */
    void pttStart() {
        if (pttActive) return;
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            showPttText("请先在设置页允许麦克风");
            ui.postDelayed(new Runnable() { public void run() { hidePtt(); } }, 1800);
            return;
        }
        if (asr == null) asr = new AsrClient();
        /* 没配云端 ASR、本机又没服务时，长按只会白录 3 秒再报错。
           先花 350ms 探一下端口：连不上就转文字输入。 */
        final String asrBase = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(AsrClient.KEY_ASR, "").trim();
        final String asrKey = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("asr_key", "").trim();
        Log.i(DBG, "pttStart base=[" + asrBase + "] key=" + (asrKey.isEmpty() ? "no" : "yes")
                + " sysAsr=" + SysAsr.available(this));
        if (asrBase.isEmpty() && asrKey.isEmpty()) {
            showPttText("看看语音服务在不在…");
            new Thread(new Runnable() {
                public void run() {
                    boolean ok = false;
                    java.net.Socket sk = new java.net.Socket();
                    try {
                        sk.connect(new java.net.InetSocketAddress("127.0.0.1", 8077), 350);
                        ok = true;
                    } catch (Throwable t) {
                        ok = false;
                    } finally {
                        try { sk.close(); } catch (Throwable ignored) { }
                    }
                    final boolean up = ok;
                    ui.post(new Runnable() {
                        public void run() {
                            if (up) {
                                startPttRecording();
                            } else if (startSysPtt()) {
                                /* 用手机自带的语音识别，既不要 Termux 也不要 Key */
                            } else {
                                showPttText("没装语音服务，先打字吧");
                                ui.postDelayed(new Runnable() { public void run() { hidePtt(); } }, 1500);
                                ui.postDelayed(new Runnable() { public void run() {
                                    openChat(false, true);
                                    /* 用 PTT 气泡而不是 showSay：气泡开关关着时也要能看到提示 */
                                    showPttText("语音没连上，先打字跟我聊~");
                                    ui.postDelayed(new Runnable() { public void run() { hidePtt(); } }, 2200);
                                } }, 900);
                            }
                        }
                    });
                }
            }).start();
            return;
        }
        try {
            asr.start(this);
            pttActive = true;
            showPttText("正在听…");
            Log.i(DBG, "ptt start ok");
        } catch (Throwable t) {
            pttActive = false;
            showPttText("录音启动失败：" + t.getMessage());
            ui.postDelayed(new Runnable() { public void run() { hidePtt(); } }, 1800);
        }
    }

    /** 系统自带识别：不需要任何服务 */
    boolean startSysPtt() {
        if (sysAsr != null) { sysAsr.cancel(); sysAsr = null; }
        sysAsr = new SysAsr();
        boolean ok = sysAsr.start(this, new SysAsr.Cb() {
            public void onText(String text) {
                pttSys = false;
                sysAsr = null;
                hidePtt();
                chatSend(text);
            }
            public void onError(String msg) {
                pttSys = false;
                sysAsr = null;
                showPttText(msg);
                ui.postDelayed(new Runnable() { public void run() { hidePtt(); } }, 1600);
                if (msg != null && (msg.contains("打字") || msg.contains("不可用"))) {
                    ui.postDelayed(new Runnable() { public void run() { openChat(false, true); } }, 1500);
                }
            }
        });
        Log.i(DBG, "startSysPtt ok=" + ok);
        if (!ok) { sysAsr = null; return false; }
        pttSys = true;
        pttActive = true;
        showPttText("正在听…（系统识别）");
        Log.i(DBG, "ptt sys asr start");
        return true;
    }

    /** 探测通过后真正开始录音 */
    void startPttRecording() {
        if (pttActive) return;
        if (asr == null) asr = new AsrClient();
        try {
            asr.start(this);
            pttActive = true;
            showPttText("正在听…");
            Log.i(DBG, "ptt start ok");
        } catch (Throwable t) {
            pttActive = false;
            showPttText("录音启动失败：" + t.getMessage());
            ui.postDelayed(new Runnable() { public void run() { hidePtt(); } }, 1800);
        }
    }

    /** 松手：停止录音 → 转写 → 直接发送 */
    void pttStop() {
        Log.i(DBG, "pttStop active=" + pttActive + " sys=" + pttSys);
        if (!pttActive) return;
        pttActive = false;
        if (pttSys) {
            showPttText("识别中…");
            if (sysAsr != null) sysAsr.stop();
            return;
        }
        showPttText("识别中…");
        final AsrClient a = asr;
        asr = null;
        if (a == null) { hidePtt(); return; }
        a.stopAndTranscribe(this, new AsrClient.Cb() {
            public void onText(String text) {
                hidePtt();
                chatSend(text);
            }
            public void onError(String msg) {
                Log.i(DBG, "asr error " + msg);
                showPttText(msg);
                ui.postDelayed(new Runnable() { public void run() { hidePtt(); } }, 1600);
                /* 语音没连上时别让人卡住：直接打开文字输入 */
                if (msg != null && (msg.contains("连不上") || msg.contains("未配置") || msg.contains("失败"))) {
                    ui.postDelayed(new Runnable() { public void run() { openChat(); } }, 1700);
                }
            }
        });
    }

    /** 手势取消：直接丢弃这次录音 */
    void pttAbort() {
        if (!pttActive) return;
        pttActive = false;
        if (pttSys) {
            pttSys = false;
            if (sysAsr != null) { sysAsr.cancel(); sysAsr = null; }
        }
        hidePtt();
        if (asr != null) { asr.cancel(); asr = null; }
    }


    /** 松手：停止录音，等 onResults 自动发送 */
    private void showPttText(String t) {
        ui.post(new Runnable() { public void run() {
            if (pttView == null) {
                TextView tv = new TextView(PetService.this);
                tv.setTextColor(0xFF1E2A44);
                tv.setTextSize(13);
                tv.setMaxWidth(dp(220));
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(0xFFF6F9FF);
                bg.setCornerRadius(dp(14));
                bg.setStroke(dp(1), 0x332C4BFF);
                tv.setBackground(bg);
                tv.setPadding(dp(12), dp(8), dp(12), dp(8));
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        android.graphics.PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.LEFT;
                try { wm.addView(tv, lp); pttView = tv; Log.i(DBG, "ptt bubble added"); }
                catch (Throwable th) { Log.i(DBG, "ptt bubble add failed: " + th); return; }
            }
            ((TextView) pttView).setText(t);
            positionPtt();
        }});
    }

    private void positionPtt() {
        if (pttView == null) return;
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) pttView.getLayoutParams();
        int w = pttView.getWidth() > 0 ? pttView.getWidth() : dp(120);
        int h = pttView.getHeight() > 0 ? pttView.getHeight() : dp(40);
        lp.x = params.x + params.width / 2 - w / 2;
        lp.y = Math.max(0, params.y - h - dp(8));
        lp.x = Math.max(0, Math.min(screenW - w, lp.x));
        try { wm.updateViewLayout(pttView, lp); } catch (Throwable ignored) { }
    }

    private void hidePtt() {
        ui.post(new Runnable() { public void run() {
            if (pttView != null) {
                try { wm.removeView(pttView); } catch (Throwable ignored) { }
                pttView = null;
            }
        }});
    }

    private void cancelLongPress() {
        if (longPressTask != null) { ui.removeCallbacks(longPressTask); longPressTask = null; }
    }

    private void showFallback() {
        if (fallback == null) {
            fallback = new ImageView(this);
            fallback.setScaleType(ImageView.ScaleType.FIT_CENTER);
            fallback.setImageResource(R.drawable.pet_front);
            fallback.setOnTouchListener(new FallbackTouch());
        }
        swapView(fallback);
    }

    private void swapView(View v) {
        if (petView != null) {
            try { wm.removeView(petView); } catch (Throwable ignored) { }
        }
        if (webView != null && v != webView) {
            try { webView.destroy(); } catch (Throwable ignored) { }
            webView = null;
        }
        petView = v;
        try {
            wm.addView(petView, params);
        } catch (Throwable t) {
            Log.e(TAG, "addView 失败", t);
        }
    }

    /** 回退视图也要能拖动 */
    private class FallbackTouch implements View.OnTouchListener {
        private float downX, downY;
        private long downTime;

        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX(); downY = e.getRawY();
                    dragStartX = params.x; dragStartY = params.y;
                    downTime = System.currentTimeMillis();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    params.x = dragStartX + (int) (e.getRawX() - downX);
                    params.y = dragStartY + (int) (e.getRawY() - downY);
                    wm.updateViewLayout(petView, params);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (System.currentTimeMillis() - downTime < 220) {
                        fallbackFrame = (fallbackFrame + 1) % 3;
                        int[] f = { R.drawable.pet_front, R.drawable.pet_side, R.drawable.pet_back };
                        fallback.setImageResource(f[fallbackFrame]);
                    }
                    clampToScreen();
                    wm.updateViewLayout(petView, params);
                    return true;
            }
            return false;
        }
    }

    // ==================== 网页桥 ====================
    // 注意：@JavascriptInterface 方法跑在 WebView 的 JavaBridge 线程，不是主线程，
    // 所以所有碰 View / WindowManager 的操作都必须 post 回主线程。

    private class Bridge {
        @JavascriptInterface
        public void dragStart() {
            ui.post(new Runnable() {
                public void run() {
                    // 原生那条路已经接管了，就别再把起点记成"已经挪过的位置"，否则会叠加位移
                    if (nativeDrag) return;
                    dragging = true;
                    animToken++;      // 一上手就打断自动走动
                    dragStartX = params.x;
                    dragStartY = params.y;
                }
            });
        }

        @JavascriptInterface
        public void dragMove(final float dx, final float dy) {
            ui.post(new Runnable() {
                public void run() {
                    if (nativeDrag) return;   // 原生在 onPetTouch 里已经摆好了，这是同一份位移的副本
                    params.x = dragStartX + Math.round(dx);
                    params.y = dragStartY + Math.round(dy);
                    if (petView != null) wm.updateViewLayout(petView, params);
                }
            });
        }

        @JavascriptInterface
        public void dragEnd() {
            ui.post(new Runnable() {
                public void run() {
                    // 原生刚收过尾（clamp + 吸附）就不要再做一遍，否则吸附动画会被触发两次
                    if (SystemClock.uptimeMillis() - nativeUpAt < 500) return;
                    dragging = false;
                    clampToScreen();
                    if (petView != null) wm.updateViewLayout(petView, params);
                    /* 不自动吸附（同上） */
                }
            });
        }

        @JavascriptInterface
        public void contextMenu(float x, float y) { /* v1 未用，控制项在设置页 */ }

        /** 长按桌宠：弹出互动框 */
        @JavascriptInterface
        public void onLongPress() {
            ui.post(new Runnable() {
                public void run() {
                    try {
                        Intent it = new Intent(PetService.this, ChatActivity.class);
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(it);
                    } catch (Throwable ignored) { }
                }
            });
        }

        /** 网页侧判定为"点击"（非拖动）时回调；onPet 表示是否真点在角色身上 */
        @JavascriptInterface
        public void onTap(final boolean onPet) {
            ui.post(new Runnable() {
                public void run() {
                    // 刚刚原生判过"这是拖动"：网页侧的阈值判定晚一拍，可能还当成点击，丢掉
                    if (SystemClock.uptimeMillis() - nativeUpAt < 500) return;
                    if (docked && !onPet) { undock(); return; }   // 点在角色外的空白处才弹出来
                    if (onPet && webView != null) {
                        webView.evaluateJavascript("window.__petTap && window.__petTap()", null);
                    }
                }
            });
        }


        @JavascriptInterface
        public void setScaleStart() {
            ui.post(new Runnable() {
                public void run() { pinchStartScale = scale; }
            });
        }

        @JavascriptInterface
        public void setScaleFactor(final float factor) {
            ui.post(new Runnable() {
                public void run() { applyScale(pinchStartScale * factor, false); }
            });
        }

        @JavascriptInterface
        public void setScaleEnd() {
            ui.post(new Runnable() {
                public void run() {
                    saveScale();
                    clampToScreen();
                    if (petView != null) wm.updateViewLayout(petView, params);
                }
            });
        }

        /** 走动时网页侧逐帧发 x 过来（曲线是那边的标定数据，算好再给原生摆放窗口） */
        /** 二维走动：网页侧规划好落点，这里只负责摆放（x/y 都夹在屏幕内） */
        @JavascriptInterface
        public void setWindowXY(final float x, final float y) {
            ui.post(new Runnable() {
                public void run() {
                    if (docked) return;
                    int keep = dp(28);
                    params.x = Math.max(-params.width + keep,
                            Math.min(screenW - keep, Math.round(x)));
                    /* 允许窗口越过屏幕上下边：角色本体在窗口里还有留白，
                       把 y 钳在 0 会让"头顶离屏幕顶还有 60px"就停住（空气墙）。
                       真正的可见性由网页按当前片段的包围盒保证，这里只兜底防丢失。 */
                    params.y = Math.max(-params.height + keep,
                            Math.min(screenH - keep, Math.round(y)));
                    if (petView != null && petView.isAttachedToWindow()) {
                        wm.updateViewLayout(petView, params);
                    }
                    syncRect();
                    positionSay();
                    positionPtt();
                }
            });
        }

        @JavascriptInterface
        public void setWindowX(final float x) {
            ui.post(new Runnable() {
                public void run() {
                    if (docked) return;
                    int keep = dp(28);
                    params.x = Math.max(-params.width + keep,
                            Math.min(screenW - keep, Math.round(x)));
                    if (petView != null && petView.isAttachedToWindow()) {
                        wm.updateViewLayout(petView, params);
                    }
                    syncRect();
                    positionSay();
                    positionPtt();
                }
            });
        }

        /** 网页侧规划走动前先问可用范围 */
        @JavascriptInterface
        public String bounds() {
            return "{\"x\":" + params.x + ",\"y\":" + params.y
                    + ",\"w\":" + params.width + ",\"h\":" + params.height
                    + ",\"sw\":" + screenW + ",\"sh\":" + screenH
                    + ",\"scale\":" + scale + "}";
        }

        @JavascriptInterface
        public void onReady() {
            ui.post(new Runnable() {
                public void run() {
                    glReady = true;
                    Log.i(TAG, "pet.html 就绪（WebM 可播放）");
                    js("window.__petWander && window.__petWander(" + wanderOn + ")");
        js("window.__petSetMode && window.__petSetMode('"
                + getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_MODE, "normal") + "')");
                }
            });
        }
    }

    // ==================== 尺寸 ====================

    /**
     * 窗口宽度。视频画布 640x360，角色只占 x212-428 / y60-330 那块，
     * 页面里把视频放大偏移，让这块正好铺满窗口的下半部分（见 pet.html 的 CROP 和 #stage）。
     */
    private int winW() {
        return Math.round(BASE_DP * scale * getResources().getDisplayMetrics().density);
    }

    /** 高度 = 宽度 + 头顶那条气泡区。全生命周期恒定，绝不中途改（改一次跳一下）。 */
    private int winH() {
        return Math.round(winW() * (1f + PAD_RATIO));
    }

    private float clampScale(float s) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, s));
    }

    /** 以窗口可视中心为锚改大小，宠物不会跑偏 */
    private void applyScale(float ns, boolean persist) {
        ns = clampScale(ns);
        if (Math.abs(ns - scale) < 0.002f) return;
        scale = ns;
        animToken++;          // 缩放期间别让走动动画同时改位置
        int oldW = params.width, oldH = params.height;
        int nw = winW(), nh = winH();
        // 水平：角色的水平中心 == 窗口的水平中心，按窗口中心锚定即可
        float cx = params.x + oldW / 2f;
        // 垂直：不能按窗口中心 —— 窗口上方多出一条气泡区，按窗口中心锚定会让角色在缩放时上下漂。
        // 改成锚定「角色中心」（在窗口里恒为 CHAR_CENTER_FROM_BOTTOM × 窗口宽，从底边往上量）。
        float cy = params.y + oldH - CHAR_CENTER_FROM_BOTTOM * oldW;
        params.width = nw;
        params.height = nh;
        params.x = Math.round(cx - nw / 2f);
        params.y = Math.round(cy - nh + CHAR_CENTER_FROM_BOTTOM * nw);
        clampToScreen();
        if (petView != null && petView.isAttachedToWindow()) {
            wm.updateViewLayout(petView, params);
        }
        if (persist) saveScale();
    }

    private void clampToScreen() {
        int keep = dp(28);   // 至少留这么多露在屏幕里，避免拖丢了找不回来
        params.x = Math.max(-params.width + keep, Math.min(screenW - keep, params.x));
        params.y = Math.max(-params.height + keep, Math.min(screenH - keep, params.y));
    }

    // ==================== 边缘吸附（缩进手机边框）====================

    /** 松手时判断：贴着左/右边缘就吸附进边框，露一小截在外面 */
    private void maybeDock() {
        int trigger = dp(DOCK_TRIGGER_DP);
        boolean nearLeft = params.x < trigger;
        boolean nearRight = params.x + params.width > screenW - trigger;
        if (nearLeft || nearRight) {
            docked = true;
            int visible = Math.round(params.width * DOCK_VISIBLE_FRAC);
            // 420ms + 轻微回弹：之前 220ms 线性滑过去太"闪"，这里滑出后小小地回弹一下再停
            glideTo(nearLeft ? -(params.width - visible) : (screenW - visible),
                    params.y, 420, EASE_BACK);
        } else {
            docked = false;
        }
    }

    /** 点一下收在边框里的桌宠 → 弹回屏幕内 */
    private void undock() {
        if (!docked) return;
        docked = false;
        int margin = dp(10);
        glideTo(params.x + params.width / 2 < screenW / 2
                        ? margin
                        : screenW - params.width - margin,
                params.y, 360, EASE_BACK);
    }

    // ==================== 位移动画 ====================

    /**
     * 把窗口从当前位置滑到 (tx, ty)，dur 毫秒，easeKind 选缓动。
     * 用 animToken 做打断：拖动或新动画一开始，旧动画下一帧自查令牌过期就自行停止。
     */
    private void glideTo(final int tx, final int ty, final int dur, final int easeKind) {
        final int token = ++animToken;
        final int sx = params.x, sy = params.y;
        final long t0 = SystemClock.uptimeMillis();
        ui.post(new Runnable() {
            public void run() {
                if (token != animToken) return;
                float p = Math.min(1f, (SystemClock.uptimeMillis() - t0) / (float) dur);
                float e = (easeKind == EASE_BACK) ? easeOutBack(p) : easeInOutCubic(p);
                params.x = sx + Math.round((tx - sx) * e);
                params.y = sy + Math.round((ty - sy) * e);
                if (petView != null && petView.isAttachedToWindow()) {
                    wm.updateViewLayout(petView, params);
                }
                if (p < 1f) ui.postDelayed(this, 16);
            }
        });
    }

    private static float easeInOutCubic(float p) {
        return p < 0.5f ? 4 * p * p * p : 1 - (float) Math.pow(-2 * p + 2, 3) / 2f;
    }

    /** 末端轻微过冲再回落，用于吸附/弹出，手感不生硬 */
    private static float easeOutBack(float p) {
        float c1 = 1.25f, c3 = c1 + 1f;
        return 1 + c3 * (float) Math.pow(p - 1, 3) + c1 * (float) Math.pow(p - 1, 2);
    }

    // ==================== 自动走动 ====================
    // 走动逻辑整块搬到网页侧了：那边才有每段动画的位移标定曲线（move_strides.json），
    // 按曲线推进 x 脚才不会打滑。原生这边只负责按 setWindowX 摆放窗口。

    private void setWander(boolean on) {
        wanderOn = on;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_WANDER, on).apply();
        js("window.__petWander && window.__petWander(" + on + ")");
    }

    private void saveScale() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat(KEY_SCALE, scale).apply();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ==================== 来自设置页的指令 ====================

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (intent.hasExtra(EXTRA_SCALE)) {
                applyScale(intent.getFloatExtra(EXTRA_SCALE, scale), true);
            }
            String cmd = intent.getStringExtra(EXTRA_CMD);
            if ("rates".equals(cmd)) applyRates();
            else if ("wander_on".equals(cmd)) setWander(true);
            else if ("wander_off".equals(cmd)) setWander(false);
            else if (cmd != null && cmd.startsWith("mode_")) setMode(cmd.substring(5));
            else if (cmd != null) js("window.__petAndroid && window.__petAndroid.action('" + cmd + "')");
        }
        return START_STICKY;
    }

    /** 活跃度：quiet / normal / active，只把决定权交给页面，原生只负责保存与转发 */
    void setMode(String mode) {
        if (mode == null || mode.isEmpty()) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_MODE, mode).apply();
        js("window.__petSetMode && window.__petSetMode('" + mode + "')");
    }

    /** 桌宠气泡说话（供对话回复用） */
    static void say(String text) {
        PetService s = instance;
        if (s == null || text == null) return;
        s.showSay(text);
    }

    /** 触发一次对话请求（调试广播也走这条） */
    void chatSend(String text) {
        if (text == null || text.trim().isEmpty()) return;
        ChatClient.send(this, text, new ChatClient.Cb() {
            public void onReply(String reply) {
                say(reply);        // 只冒气泡，别再触发动作——动作自己的台词会把回复盖掉
            }
        });
    }

    private void js(final String code) {
        ui.post(new Runnable() {
            public void run() {
                if (webView != null) webView.evaluateJavascript(code, null);
            }
        });
    }

    // ==================== 前台服务 ====================

    private void startForegroundCompat() {
        String chId = "pet";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(chId, "大肥鱼", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, chId)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("大肥鱼桌宠")
                .setContentText("正在陪着你 🐳")
                .setOngoing(true);
        Notification n = b.build();

        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14+ 必须声明前台服务类型（manifest 里配了 specialUse）
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        instance = null;
        if (link != null) { link.stop(); link = null; }
        if (screenRx != null) { try { unregisterReceiver(screenRx); } catch (Throwable ignored) { } screenRx = null; }
        ui.removeCallbacksAndMessages(null);
        if (petView != null) {
            try { wm.removeView(petView); } catch (Throwable ignored) { }
            petView = null;
        }
        if (webView != null) {
            try { webView.destroy(); } catch (Throwable ignored) { }
            webView = null;
        }
    }

    // ==================== 调试注入（自动化测试专用）====================
    // adb shell 拿不到 INJECT_EVENTS（MIUI 的「USB调试(安全设置)」要插卡才让开，这台没插卡），
    // 所以改成自己把触摸事件灌进 WebView。要点是坐标口径必须和真手指一致：
    //   用「屏幕坐标」obtain → getRawX() 就是屏幕坐标（onPetTouch 靠它算位移）
    //   再 offsetLocation(-窗口位置) → getX() 变成窗口内坐标（网页侧命中判定靠它）
    // 这样 onPetTouch、网页侧点击判定、文档拖动全都走真实代码路径，不是另写一套。

    private static final String DBG = "PetDbg";
    private long dbgDownTime = 0;

    private static class DbgEv {
        final long at; final int action; final float x, y;
        DbgEv(long at, int action, float x, float y) {
            this.at = at; this.action = action; this.x = x; this.y = y;
        }
    }

    /** 调试：--es api "base|key|model" */
    void dbgApi(String spec) {
        if (spec == null) return;
        String[] p = spec.split("\\|", -1);
        android.content.SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        if (p.length > 0) e.putString(ChatClient.KEY_BASE, p[0]);
        if (p.length > 1) e.putString(ChatClient.KEY_KEY, p[1]);
        if (p.length > 2) e.putString(ChatClient.KEY_MODEL, p[2]);
        if (p.length > 3) e.putString(ChatClient.KEY_PROVIDER, p[3]);
        e.apply();
        Log.i(DBG, "api 已设置: " + p[0]);
    }

    /** 调试：--ef scale 2.0 */
    void dbgScale(float v) {
        if (v > 0) applyScale(v, true);
    }

    void openChat() { openChat(false, false); }

    /** voice=true：长按直达语音输入；forceText=true：不管设置如何都用文字输入 */
    void openChat(final boolean voice) { openChat(voice, false); }

    void openChat(final boolean voice, final boolean forceText) {
        ui.post(new Runnable() {
            public void run() {
                try {
                    Intent it = new Intent(PetService.this, ChatActivity.class);
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    it.putExtra("auto_voice", voice);
                    it.putExtra("force_text", forceText);
                    /* 把桌宠当前的位置/尺寸/缩放带过去，气泡才能贴着它、并按它的比例缩放 */
                    it.putExtra("pet_x", params.x);
                    it.putExtra("pet_y", params.y);
                    it.putExtra("pet_w", params.width);
                    it.putExtra("pet_h", params.height);
                    it.putExtra("pet_scale", scale);
                    startActivity(it);
                } catch (Throwable t) {
                    Log.w(TAG, "openChat 失败: " + t);
                    say("聊天框打不开…长按我也行");
                }
            }
        });
    }

    void dbgCmd(String cmd) {
        Log.i(DBG, "cmd " + cmd);
        if (cmd == null) return;
        if (cmd.startsWith("mode_")) { setMode(cmd.substring(5)); return; }
        if ("balance".equals(cmd)) { queryBalance(); return; }
        if ("rates".equals(cmd)) { applyRates(); return; }
        if (cmd.startsWith("pref ")) {                  // pref speech_enabled:false
            String kv = cmd.substring(5);
            int i = kv.indexOf(':');
            if (i > 0) {
                String k = kv.substring(0, i), v = kv.substring(i + 1);
                if ("true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(k, Boolean.parseBoolean(v)).apply();
                } else if (v.matches("-?\\d+")) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(k, Integer.parseInt(v)).apply();
                } else {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(k, v).apply();
                }
                Log.i(DBG, "pref " + k + "=" + v + " modules=" + modulesSummary());
            }
            return;
        }
        js("window.__petAndroid && window.__petAndroid.action('" + cmd + "')");
    }

    void dbgJs(final String code) {
        ui.post(new Runnable() {
            public void run() {
                if (webView == null) { Log.w(DBG, "js 跳过（无 WebView）: " + code); return; }
                webView.evaluateJavascript(code, new ValueCallback<String>() {
                    public void onReceiveValue(String v) { Log.i(DBG, "js> " + v); }
                });
            }
        });
    }

    void dbgDump() {
        ui.post(new Runnable() {
            public void run() {
                Log.i(DBG, "dump 窗口=" + params.x + "," + params.y + " " + params.width + "x" + params.height
                        + " 缩放=" + scale + " 吸附=" + docked + " 拖动中=" + dragging
                        + " 原生拖=" + nativeDrag + " 走动=" + wanderOn + " 就绪=" + glReady
                        + " 视图=" + (petView == webView ? "webview" : (petView == null ? "null" : "fallback")));
            }
        });
        dbgJs("JSON.stringify(window.__petDbg ? window.__petDbg() : {noHook:true})");
    }

    void dbgGesture(final String script) {
        ui.post(new Runnable() {
            public void run() { dbgPlan(script); }
        });
    }

    /**
     * 把手势脚本展开成带时间戳的事件表，语句用 | 分隔，坐标一律是屏幕坐标：
     *   tap X Y                  点一下
     *   hold X Y MS              按住 MS 毫秒
     *   drag X1 Y1 X2 Y2 MS [N]  直线拖，MS 毫秒走完，可选分 N 步（默认按 16ms 一步）
     *   wait MS                  等
     *
     * 【逗号也当分隔符】am broadcast 的字符串参数会被设备侧 shell 重新拆词，
     * "drag 882 940 500 940 600" 传过去只剩一个 "drag"。所以推荐写成
     * drag,882,940,500,940,600 —— 不带空格就不会被拆。
     */
    private void dbgPlan(String script) {
        if (webView == null) { Log.w(DBG, "手势跳过（无 WebView）"); return; }
        ArrayList<DbgEv> evs = new ArrayList<DbgEv>();
        long t = 0;
        for (String raw : script.split("\\|")) {
            String st = raw.trim();
            if (st.isEmpty()) continue;
            String[] a = st.split("[\\s,]+");
            try {
                if ("wait".equals(a[0])) {
                    t += Long.parseLong(a[1]);
                } else if ("tap".equals(a[0])) {
                    float x = Float.parseFloat(a[1]), y = Float.parseFloat(a[2]);
                    evs.add(new DbgEv(t, MotionEvent.ACTION_DOWN, x, y));
                    evs.add(new DbgEv(t + 50, MotionEvent.ACTION_UP, x, y));
                    t += 180;
                } else if ("hold".equals(a[0])) {
                    float x = Float.parseFloat(a[1]), y = Float.parseFloat(a[2]);
                    long d = Long.parseLong(a[3]);
                    evs.add(new DbgEv(t, MotionEvent.ACTION_DOWN, x, y));
                    evs.add(new DbgEv(t + d, MotionEvent.ACTION_UP, x, y));
                    t += d + 140;
                } else if ("drag".equals(a[0])) {
                    float x1 = Float.parseFloat(a[1]), y1 = Float.parseFloat(a[2]);
                    float x2 = Float.parseFloat(a[3]), y2 = Float.parseFloat(a[4]);
                    long dur = Long.parseLong(a[5]);
                    int steps = a.length > 6 ? Integer.parseInt(a[6]) : Math.max(4, (int) (dur / 16));
                    evs.add(new DbgEv(t, MotionEvent.ACTION_DOWN, x1, y1));
                    for (int i = 1; i <= steps; i++) {
                        float p = i / (float) steps;
                        evs.add(new DbgEv(t + dur * i / steps, MotionEvent.ACTION_MOVE,
                                x1 + (x2 - x1) * p, y1 + (y2 - y1) * p));
                    }
                    t += dur;
                    evs.add(new DbgEv(t, MotionEvent.ACTION_UP, x2, y2));
                    t += 180;
                } else {
                    Log.w(DBG, "看不懂的语句: " + st);
                }
            } catch (Throwable ex) {
                Log.w(DBG, "解析失败: " + st, ex);
            }
        }
        Log.i(DBG, "手势开始 " + script + "（" + evs.size() + " 个事件 / " + t + "ms）");
        for (final DbgEv e : evs) {
            ui.postDelayed(new Runnable() {
                public void run() { dbgFire(e); }
            }, e.at);
        }
    }

    private void dbgFire(DbgEv e) {
        long now = SystemClock.uptimeMillis();
        if (e.action == MotionEvent.ACTION_DOWN) dbgDownTime = now;
        MotionEvent m = MotionEvent.obtain(dbgDownTime, now, e.action, e.x, e.y, 0);
        m.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        m.offsetLocation(-params.x, -params.y);
        float lx = m.getX(), ly = m.getY(), rx = m.getRawX(), ry = m.getRawY();
        int bx = params.x, by = params.y;
        if (webView != null) webView.dispatchTouchEvent(m);
        // raw对=true 说明 raw 确实是屏幕坐标（没被 offsetLocation 带跑），拖动位移才算得对
        Log.i(DBG, String.format("ev act=%d 屏(%.0f,%.0f) 窗内(%.0f,%.0f) raw(%.0f,%.0f) raw对=%b 窗口 %d,%d→%d,%d",
                e.action, e.x, e.y, lx, ly, rx, ry, Math.abs(rx - e.x) < 1f, bx, by, params.x, params.y));
        m.recycle();
    }
}
