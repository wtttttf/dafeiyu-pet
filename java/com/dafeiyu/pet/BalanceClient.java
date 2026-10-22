package com.dafeiyu.pet;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 查 DeepSeek 余额：GET https://api.deepseek.com/user/balance
 * 只有 DeepSeek 官方有这套接口，其它服务商直接返回不支持。
 */
public class BalanceClient {

    public interface Cb {
        void onResult(double total, double granted, double topped, String currency);
        void onError(String msg);
    }

    public static void query(final Context ctx, final Cb cb) {
        final Handler ui = new Handler(Looper.getMainLooper());
        final SharedPreferences sp = ctx.getSharedPreferences(PetService.PREFS, Context.MODE_PRIVATE);
        final String pid = sp.getString(ChatClient.KEY_PROVIDER, "deepseek").trim();
        final String key = sp.getString(ChatClient.KEY_KEY, "").trim();
        if (key.isEmpty()) {
            ui.post(new Runnable() { public void run() { cb.onError("还没配 Key，查不了余额"); } });
            return;
        }
        if (!"deepseek".equals(pid) && !"custom".equals(pid)) {
            ui.post(new Runnable() { public void run() { cb.onError("当前服务商没有余额接口"); } });
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                HttpURLConnection c = null;
                try {
                    String base = "https://api.deepseek.com";
                    String custom = sp.getString(ChatClient.KEY_BASE, "").trim();
                    if ("custom".equals(pid) && custom.contains("deepseek")) {
                        base = custom.replaceAll("/v1/?$", "");
                    }
                    URL u = new URL(base + "/user/balance");
                    c = (HttpURLConnection) u.openConnection();
                    c.setRequestMethod("GET");
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    c.setRequestProperty("Authorization", "Bearer " + key);
                    c.setRequestProperty("Accept", "application/json");
                    int code = c.getResponseCode();
                    BufferedReader r = new BufferedReader(new InputStreamReader(
                            code >= 400 ? c.getErrorStream() : c.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    r.close();
                    if (code >= 400) {
                        final String m = "余额查询失败 " + code;
                        ui.post(new Runnable() { public void run() { cb.onError(m); } });
                        return;
                    }
                    JSONObject o = new JSONObject(sb.toString());
                    JSONArray infos = o.optJSONArray("balance_infos");
                    if (infos == null || infos.length() == 0) {
                        ui.post(new Runnable() { public void run() { cb.onError("没有余额信息"); } });
                        return;
                    }
                    JSONObject b = infos.getJSONObject(0);
                    final double total = b.optDouble("total_balance", 0);
                    final double granted = b.optDouble("granted_balance", 0);
                    final double topped = b.optDouble("topped_up_balance", 0);
                    final String cur = b.optString("currency", "CNY");
                    ui.post(new Runnable() { public void run() { cb.onResult(total, granted, topped, cur); } });
                } catch (Throwable t) {
                    final String m = "查余额失败：" + t.getClass().getSimpleName();
                    ui.post(new Runnable() { public void run() { cb.onError(m); } });
                } finally {
                    if (c != null) try { c.disconnect(); } catch (Throwable ignored) { }
                }
            }
        }).start();
    }
}
