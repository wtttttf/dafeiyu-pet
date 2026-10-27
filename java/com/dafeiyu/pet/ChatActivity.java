package com.dafeiyu.pet;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

/**
 * 气泡对话：窗口本身就是气泡（wrap_content + 绝对定位），
 * 每 120ms 按桌宠矩形重算窗口 x/y —— 窗口跟着桌宠走，天然不会被坐标系绕晕。
 */
public class ChatActivity extends Activity {
    private static final int REQ_MIC = 21, REQ_VOICE = 22;

    private EditText input;
    private TextView reply, nameView, closeView, modeBtn;
    private Button send, mic;
    private FrameLayout root;
    private LinearLayout bubble, row;
    private MaxScroll replyScroll;
    private boolean showingReply;
    private TailView tail;
    private final Handler h = new Handler(Looper.getMainLooper());
    private int bubbleW, tailH, bubbleH;
    private float fs = 1f;
    private boolean voiceMode;
    private float curX = Float.NaN, curY = Float.NaN;
    private boolean above = true, switching = false;
    private long swT0;
    private float swFromX, swFromY, swToX, swToY;
    private int dbgN = 0;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        getWindow().setDimAmount(0f);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().setGravity(Gravity.TOP | Gravity.LEFT);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);

        boolean autoVoice = getIntent().getBooleanExtra("auto_voice", false);
        boolean forceText = getIntent().getBooleanExtra("force_text", false);
        /* 语音不可用时（比如语音服务没装）必须给文字输入，否则聊天框只剩一个麦克风按钮 */
        voiceMode = !forceText && (autoVoice || "voice".equals(getSharedPreferences(PetService.PREFS, MODE_PRIVATE)
                .getString("input_mode", "text")));

        root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF6F9FF);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1.2f), 0x332C4BFF);
        bubble.setBackground(bg);
        bubble.setElevation(dp(6));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        nameView = new TextView(this);
        nameView.setText("大肥鱼");
        nameView.setTextColor(0xFF2C4BFF);
        nameView.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(nameView);

        modeBtn = new TextView(this);
        modeBtn.setText(voiceMode ? "⌨️" : "🎤");
        modeBtn.setTextColor(0xFF2C4BFF);
        modeBtn.setPadding(dp(8), 0, dp(8), 0);
        modeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                voiceMode = !voiceMode;
                getSharedPreferences(PetService.PREFS, MODE_PRIVATE).edit()
                        .putString("input_mode", voiceMode ? "voice" : "text").apply();
                modeBtn.setText(voiceMode ? "⌨️" : "🎤");
                showReplyState(false);
            }
        });
        head.addView(modeBtn);

        closeView = new TextView(this);
        closeView.setText("✕");
        closeView.setTextColor(0xFF9AA4BC);
        closeView.setPadding(dp(8), 0, dp(2), 0);
        closeView.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        head.addView(closeView);
        bubble.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        reply = new TextView(this);
        reply.setText("想聊点什么？");
        reply.setTextColor(0xFF1E2A44);
        /* 不再截断行数：长回复完整显示，超过屏幕 45% 再滚动 */
        reply.setMaxLines(Integer.MAX_VALUE);
        reply.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showReplyState(false); }
        });
        replyScroll = new MaxScroll(this, (int) (getResources().getDisplayMetrics().heightPixels * 0.45f));
        replyScroll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showReplyState(false); }
        });
        replyScroll.addView(reply, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bubble.addView(replyScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        input = new EditText(this);
        input.setHint("说点什么…");
        input.setTextColor(0xFF1E2A44);
        input.setHintTextColor(0xFF9AA4BC);
        input.setSingleLine(true);
        input.setMinWidth(0);
        input.setEms(0);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        GradientDrawable ib = new GradientDrawable();
        ib.setColor(0xFFE9EFFA);
        ib.setCornerRadius(dp(12));
        input.setBackground(ib);
        row.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        send = new Button(this);
        send.setText("发送");
        send.setAllCaps(false);
        send.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { doSend(); }
        });
        row.addView(send);

        mic = new Button(this);
        mic.setText("🎤 说");
        mic.setAllCaps(false);
        mic.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startVoice(); }
        });
        row.addView(mic);
        bubble.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        input.setVisibility(voiceMode ? View.GONE : View.VISIBLE);
        send.setVisibility(voiceMode ? View.GONE : View.VISIBLE);
        mic.setVisibility(voiceMode ? View.VISIBLE : View.GONE);
        replyScroll.setVisibility(View.GONE);

        tail = new TailView(this);
        applyMetrics();

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(bubbleW, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.TOP | Gravity.LEFT;
        root.addView(bubble, blp);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams((int) (dp(26) * fs), tailH);
        tlp.gravity = Gravity.TOP | Gravity.LEFT;
        root.addView(tail, tlp);

        setContentView(root);
        bubble.post(new Runnable() { public void run() { bubbleH = bubble.getHeight(); step(); } });
        if (autoVoice) bubble.postDelayed(new Runnable() {
            public void run() { startVoice(); }
        }, 260);
        showReplyState(false);
    }

    static ChatActivity current;

    @Override protected void onResume() { super.onResume(); current = this; h.post(follow); }
    @Override protected void onDestroy() { super.onDestroy(); if (current == this) current = null; }

    /** 调试：灌一段长文本，检查气泡是否完整显示 */
    void setReplyForTest(String t) { reply.setText(t); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(follow); }

    private final Runnable follow = new Runnable() {
        public void run() { step(); h.postDelayed(this, 16); }   // 60fps，才丝滑
    };

    private void applyMetrics() {
        int[] r = PetService.petRect();
        float petScale = r[2] > 0 ? r[2] / (float) Math.max(1, dp(120))
                : getIntent().getFloatExtra("pet_scale", 1f);
        fs = Math.max(0.8f, Math.min(1.25f, Math.max(0.75f, petScale)));
        /* 宽度由 step() 的自适应逻辑决定，这里只调字号/内边距，别再覆盖 bubbleW */
        int pad = (int) (dp(13) * fs);
        bubble.setPadding(pad, (int) (dp(9) * fs), pad, pad);
        nameView.setTextSize(13 * fs);
        closeView.setTextSize(13 * fs);
        modeBtn.setTextSize(15 * fs);
        reply.setTextSize(14 * fs);
        reply.setPadding(0, (int) (dp(6) * fs), 0, (int) (dp(8) * fs));
        input.setTextSize(14 * fs);
        input.setPadding((int) (dp(10) * fs), (int) (dp(7) * fs), (int) (dp(10) * fs), (int) (dp(7) * fs));
        send.setTextSize(13 * fs);
        mic.setTextSize(13 * fs);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams((int) (dp(56) * fs), (int) (dp(38) * fs));
        sp.leftMargin = (int) (dp(6) * fs);
        send.setLayoutParams(sp);
        mic.setLayoutParams(new LinearLayout.LayoutParams((int) (dp(58) * fs), (int) (dp(38) * fs)));
        tailH = (int) (dp(12) * fs);
    }

    /** 每帧：算目标位置 → 平滑逼近；上方放不下时平滑切到下方（淡出淡入，不瞬移） */
    private void step() {
        int[] r = PetService.petRect();
        if (r[2] <= 0) {
            r[0] = getIntent().getIntExtra("pet_x", 0);
            r[1] = getIntent().getIntExtra("pet_y", 0);
            r[2] = getIntent().getIntExtra("pet_w", dp(240));
            r[3] = getIntent().getIntExtra("pet_h", dp(290));
        }
        bubbleH = bubble.getHeight();
        if (bubbleH == 0) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int sw = dm.widthPixels, sh = dm.heightPixels;
        int gap = (int) (dp(4) * fs);
        int need = bubbleH + tailH + gap;

        /* 内容自适应：以文字自然宽度为准（短则窄、长则宽），
           上限 = min(桌宠宽*1.7, 屏宽*0.8)，下限 = 230dp（保证输入行不被挤） */
        int natural = measureNatural();
        int cap = Math.min((int) (r[2] * 1.7f), (int) (sw * 0.8f));
        int floor = Math.min(dp(170), cap);
        int wantW = natural > 0 ? natural : dp(230);
        wantW = Math.max(floor, Math.min(wantW, cap));
        if (Math.abs(wantW - bubbleW) > 4) { bubbleW = wantW; applyMetrics(); }

        int cx = r[0] + r[2] / 2;
        boolean fitsAbove = r[1] - need >= dp(2);
        boolean fitsBelow = r[1] + r[3] + need <= sh - dp(2);
        /* 迟滞：在上方时只要放得下就不动；在下方时，上方要富余 40dp 才切回去，避免来回抖 */
        boolean wantAbove = above;
        if (above) { if (!fitsAbove) wantAbove = false; }
        else { if (fitsAbove && r[1] - need > dp(40)) wantAbove = true; }

        int targetY = wantAbove ? (r[1] - bubbleH - tailH - gap)
                                : (r[1] + r[3] + tailH + gap);
        targetY = Math.max(dp(2), Math.min(sh - bubbleH - tailH - dp(2), targetY));
        int targetX = Math.max(dp(4), Math.min(sw - bubbleW - dp(4), cx - bubbleW / 2));

        if (wantAbove != above) {
            if (!switching) switchSide(wantAbove, targetX, targetY);
        }
        if (switching) {
            float t = (android.os.SystemClock.uptimeMillis() - swT0) / 150f;
            if (t >= 1f) { curX = swToX; curY = swToY; switching = false; }
            else { curX = swFromX + (swToX - swFromX) * t; curY = swFromY + (swToY - swFromY) * t; }
            applyWindow(Math.round(curX), Math.round(curY), cx);
            return;
        }

        if (Float.isNaN(curX)) { curX = targetX; curY = targetY; }
        curX += (targetX - curX) * 0.38f;
        curY += (targetY - curY) * 0.38f;
        if (Math.abs(targetX - curX) < 0.5f) curX = targetX;
        if (Math.abs(targetY - curY) < 0.5f) curY = targetY;
        if ((dbgN++ % 8) == 0) {
            android.util.Log.i("PetChat", "pet=" + r[1] + " need=" + need + " above=" + above
                    + " targetY=" + targetY + " curY=" + Math.round(curY) + " bubbleH=" + bubbleH
                    + " W=" + bubbleW + " headH=" + bubble.getChildAt(0).getHeight()
                    + " replyH=" + reply.getHeight() + " rowH=" + bubble.getChildAt(2).getHeight()
                    + " fs=" + fs + " petW=" + r[2]);
        }
        applyWindow(Math.round(curX), Math.round(curY), cx);
    }

    /** 换边：150ms 直线滑到另一侧（不用 alpha，避免动画被打断后整窗隐形） */
    private void switchSide(boolean toAbove, int tx, int ty) {
        switching = true;
        above = toAbove;
        swT0 = android.os.SystemClock.uptimeMillis();
        swFromX = Float.isNaN(curX) ? tx : curX;
        swFromY = Float.isNaN(curY) ? ty : curY;
        swToX = tx; swToY = ty;
    }

    /** 文字自然宽度：用 Paint 量，别在布局期调 measure() */
    private int measureNatural() {
        try {
            String t = reply.getText().toString();
            if (t.isEmpty()) return 0;
            float w = reply.getPaint().measureText(t);
            int pad = (int) (dp(13) * fs) * 2 + (int) (dp(8) * fs);
            return (int) w + pad;
        } catch (Throwable th) { return 0; }
    }

    private void applyWindow(int wx, int wy, int petCenterX) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
        FrameLayout.LayoutParams blp = (FrameLayout.LayoutParams) bubble.getLayoutParams();
        if (blp.width != bubbleW) { blp.width = bubbleW; bubble.setLayoutParams(blp); }
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.x = wx;
        lp.y = wy;
        getWindow().setAttributes(lp);

        tail.setPointDown(above);
        int tw = (int) (dp(26) * fs);
        int tx = petCenterX - wx - tw / 2;
        tx = Math.max(dp(10), Math.min(bubbleW - tw - dp(10), tx));
        FrameLayout.LayoutParams tlp = (FrameLayout.LayoutParams) tail.getLayoutParams();
        tlp.leftMargin = tx;
        tlp.topMargin = above ? (bubbleH - dp(2)) : 0;
        tail.setLayoutParams(tlp);
    }

    /* ---------------- 语音 ---------------- */
    private void startVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        Intent it = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        it.putExtra(RecognizerIntent.EXTRA_PROMPT, "说吧，我在听…");
        try { startActivityForResult(it, REQ_VOICE); }
        catch (Exception e) { toast("这台机器没有可用的语音识别服务"); }
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] res) {
        if (code == REQ_MIC && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) startVoice();
        else toast("没有麦克风权限");
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_VOICE || res != RESULT_OK || data == null) return;
        ArrayList<String> list = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (list == null || list.isEmpty()) return;
        String text = list.get(0);
        showReplyState(true);
        reply.setText("你说：" + text);
        ChatClient.send(this, text, new ChatClient.Cb() {
            public void onReply(String s) { reply.setText(s); }
        });
    }

    /** 回复态 / 输入态互斥：同一张气泡里只显示一个 */
    private void showReplyState(boolean on) {
        showingReply = on;
        if (replyScroll != null) replyScroll.setVisibility(on ? View.VISIBLE : View.GONE);
        if (row != null) row.setVisibility(on ? View.GONE : View.VISIBLE);
        if (!on) {
            input.setVisibility(voiceMode ? View.GONE : View.VISIBLE);
            send.setVisibility(voiceMode ? View.GONE : View.VISIBLE);
            mic.setVisibility(voiceMode ? View.VISIBLE : View.GONE);
            if (!voiceMode) input.requestFocus();
        }
        if (bubble != null) bubble.requestLayout();
    }

    private void doSend() {
        final String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        send.setEnabled(false);
        showReplyState(true);
        reply.setText("……");
        ChatClient.send(this, text, new ChatClient.Cb() {
            public void onReply(String s) {
                send.setEnabled(true);
                reply.setText(s);
            }
        });
    }

    private void toast(String t) { Toast.makeText(this, t, Toast.LENGTH_SHORT).show(); }
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** 限高可滚动的容器：内容少时自适应高度，超过上限才滚动 */
    static class MaxScroll extends android.widget.ScrollView {
        private final int max;
        MaxScroll(android.content.Context c, int maxH) { super(c); max = maxH; }
        @Override protected void onMeasure(int w, int h) {
            int cap = MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST);
            super.onMeasure(w, cap);
        }
    }

    static class TailView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean down = true;
        TailView(android.content.Context c) { super(c); p.setColor(0xFFF6F9FF); }
        void setPointDown(boolean d) { down = d; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            Path path = new Path();
            if (down) { path.moveTo(0, 0); path.lineTo(w, 0); path.lineTo(w / 2f, h); }
            else { path.moveTo(0, h); path.lineTo(w, h); path.lineTo(w / 2f, 0); }
            path.close();
            c.drawPath(path, p);
        }
    }
}
