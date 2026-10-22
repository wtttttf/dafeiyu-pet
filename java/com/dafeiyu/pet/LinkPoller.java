package com.dafeiyu.pet;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * DSH 状态联动：轮询容器里的 pet-bridge（127.0.0.1:8078/state），
 * 把 Agent 状态映射成桌宠气泡。
 *
 * 只在状态“变化”时说话，避免刷屏；idle/offline 不出声。
 */
public class LinkPoller {
    private static final String TAG = "PetLink";
    private static final String DEFAULT_URL = "http://127.0.0.1:8078/state";
    private static final long POLL_MS = 1500;

    private final Context ctx;
    private final Handler h = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;
    private String lastKey = "";
    private long lastSayAt = 0;

    public LinkPoller(Context c) { ctx = c; }

    public boolean isRunning() { return running; }

    public void start() {
        if (running) return;
        running = true;
        h.postDelayed(loop, 800);
        Log.i(TAG, "link poller started");
    }

    public void stop() {
        running = false;
        h.removeCallbacks(loop);
        Log.i(TAG, "link poller stopped");
    }

    private final Runnable loop = new Runnable() {
        public void run() {
            if (!running) return;
            new Thread(new Runnable() {
                public void run() {
                    final String[] r = fetch();
                    h.post(new Runnable() {
                        public void run() { onState(r[0], r[1]); }
                    });
                }
            }).start();
            h.postDelayed(loop, POLL_MS);
        }
    };

    private String[] fetch() {
        String url = DEFAULT_URL;
        try {
            SharedPreferences p = ctx.getSharedPreferences(PetService.PREFS, Context.MODE_PRIVATE);
            String u = p.getString("link_url", "");
            if (u != null && !u.isEmpty()) url = u;
        } catch (Throwable ignored) { }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(1200);
            c.setReadTimeout(1200);
            c.setRequestMethod("GET");
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[512];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            JSONObject o = new JSONObject(bo.toString("UTF-8"));
            return new String[]{o.optString("state", ""), o.optString("detail", "")};
        } catch (Throwable e) {
            return new String[]{null, null};
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) { }
        }
    }

    private void onState(String state, String detail) {
        if (state == null || state.isEmpty()) return;         // bridge 不在，静默
        if ("idle".equals(state) || "offline".equals(state)) { lastKey = ""; return; }

        String text = textFor(state, detail);
        if (text == null) return;

        String key = state + "|" + (detail == null ? "" : detail);
        long now = System.currentTimeMillis();
        if (key.equals(lastKey) && now - lastSayAt < 20000) return;   // 同一状态 20 秒内不重复
        lastKey = key;
        lastSayAt = now;
        Log.i(TAG, "state=" + state + " detail=" + detail + " -> " + text);
        PetService.say(text);
        PetService.sfxAgent(state);
    }

    private static String shortTool(String d) {
        if (d == null) return "工具";
        String s = d.trim();
        if (s.startsWith("bash") || s.startsWith("shell")) return "命令";
        if (s.startsWith("web_search") || s.startsWith("web_fetch")) return "搜索";
        if (s.startsWith("read")) return "看文件";
        if (s.startsWith("write") || s.startsWith("edit")) return "改文件";
        if (s.startsWith("subagent") || s.startsWith("workflow")) return "子任务";
        return s.length() > 10 ? s.substring(0, 10) : s;
    }

    private static String textFor(String state, String detail) {
        if ("thinking".equals(state)) return "正在想…";
        if ("working".equals(state)) {
            if (detail == null || detail.isEmpty()
                    || detail.startsWith("思考") || detail.startsWith("生成")
                    || detail.startsWith("工具返回") || detail.startsWith("已确认")) return "正在想…";
            return "在跑 " + shortTool(detail);
        }
        if ("waiting_approval".equals(state)) return "等你确认一下";
        if ("error".equals(state)) return "模型在重试…";
        if ("success".equals(state)) return "搞定了 ✓";
        return null;
    }
}
