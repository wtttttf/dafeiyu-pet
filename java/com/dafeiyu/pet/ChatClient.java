package com.dafeiyu.pet;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 极简 OpenAI 兼容客户端：一问一答，不保存上下文。
 * 设置页里的「接口地址 / API Key / 模型」存在同一个 SharedPreferences。
 */
public class ChatClient {
    public static final String KEY_BASE = "api_base";
    public static final String KEY_KEY = "api_key";
    public static final String KEY_MODEL = "api_model";
    public static final String DEFAULT_MODEL = "deepseek-chat";
    public static final String KEY_PROVIDER = "api_provider";     // openai | anthropic | dify
    public static final String DEFAULT_PROVIDER = "openai";

    public static final String SYS_PROMPT =
            "你是「大肥鱼」（又叫鱼鱼），住在手机桌面上的蓝色鲸鱼娘，源自社区对 DeepSeek 的二创形象（非官方）。\n"
          + "外形：蓝紫色长发、蓝眼睛、鲸鱼尾巴、深蓝女仆装、白色围裙上印着一只小蓝鲸。\n"
          + "性格：聪明但嘴硬、贪吃（把 token 当脑力口粮，最爱白饭和小鱼干）、被夸会偷偷得意、\n"
          + "     主人忙的时候知道安静、偶尔摸鱼（哼歌、惦记下一顿、偷吃后装作没事）。\n"
          + "常用梗（自然使用，别硬塞）：你这吃白饭的蓝色大肥鱼、才不是大肥鱼、摸鱼、鱼尾巴、\n"
          + "     鲸鱼娘、token 味道、被捏/被甩。\n"
          + "语气：像熟人聊天，1–2 句，短、口语、可带（括号心声），不用 markdown、不用列表、\n"
          + "     不自称 AI 或语言模型，不假装是人类。\n"
          + "边界：不知道就说不知道，别编事实；回答技术问题时保持简短但准确。";

    public interface Cb { void onReply(String text); }

    public static void send(final Context ctx, final String text, final Cb cb) {
        final SharedPreferences sp = ctx.getSharedPreferences(PetService.PREFS, Context.MODE_PRIVATE);
        final String pid = sp.getString(KEY_PROVIDER, "deepseek").trim();
        String[] preset = Providers.find(pid);
        String b = sp.getString(KEY_BASE, "").trim();
        String m = sp.getString(KEY_MODEL, "").trim();
        final String base = b.isEmpty() ? preset[2] : b;
        final String model = m.isEmpty() ? preset[3] : m;
        final String key = sp.getString(KEY_KEY, "").trim();
        final String provider = preset[4];
        final Handler ui = new Handler(Looper.getMainLooper());
        boolean useApi = sp.getBoolean("use_api", true);
        if (!useApi || base.isEmpty() || key.isEmpty()) {
            /* 没配 Key：走本地人设台词，至少能陪聊（分享给朋友时的默认体验） */
            final String local = LocalChat.reply(text);
            ui.post(new Runnable() { public void run() { cb.onReply(local); } });
            return;
        }
        new Thread(new Runnable() { public void run() {
            String out;
            try {
                String url;
                String payload;
                java.util.Map<String, String> headers = new java.util.LinkedHashMap<String, String>();
                headers.put("Content-Type", "application/json");
                if ("gemini".equals(provider)) {
                    String md = model.isEmpty() ? "gemini-2.0-flash" : model;
                    url = base.replaceAll("/+$", "") + "/v1beta/models/" + md + ":generateContent?key=" + key;
                    JSONObject gb = new JSONObject();
                    JSONArray contents = new JSONArray();
                    contents.put(new JSONObject().put("role", "user")
                            .put("parts", new JSONArray().put(new JSONObject().put("text", text))));
                    gb.put("contents", contents);
                    gb.put("systemInstruction", new JSONObject().put("parts",
                            new JSONArray().put(new JSONObject().put("text", SYS_PROMPT))));
                    payload = gb.toString();
                } else if ("anthropic".equals(provider)) {
                    url = base.endsWith("/v1") ? base + "/messages"
                            : (base.endsWith("/") ? base + "v1/messages" : base + "/v1/messages");
                    headers.put("x-api-key", key);
                    headers.put("anthropic-version", "2023-06-01");
                    JSONObject b = new JSONObject();
                    b.put("model", model.isEmpty() ? "claude-3-5-sonnet-latest" : model);
                    b.put("max_tokens", 300);
                    b.put("system", SYS_PROMPT);
                    JSONArray ms = new JSONArray();
                    ms.put(new JSONObject().put("role", "user").put("content", text));
                    b.put("messages", ms);
                    payload = b.toString();
                } else if ("dify".equals(provider)) {
                    url = base.endsWith("/chat-messages") ? base
                            : (base.endsWith("/") ? base + "chat-messages" : base + "/chat-messages");
                    headers.put("Authorization", "Bearer " + key);
                    JSONObject b = new JSONObject();
                    b.put("inputs", new JSONObject());
                    b.put("query", text);
                    b.put("response_mode", "blocking");
                    b.put("user", "dafeiyu-pet");
                    payload = b.toString();
                } else {
                    url = base.endsWith("/chat/completions") ? base
                            : (base.endsWith("/") ? base + "chat/completions" : base + "/chat/completions");
                    headers.put("Authorization", "Bearer " + key);
                    JSONObject b = new JSONObject();
                    b.put("model", model.isEmpty() ? DEFAULT_MODEL : model);
                    JSONArray ms = new JSONArray();
                    ms.put(new JSONObject().put("role", "system").put("content", SYS_PROMPT));
                    ms.put(new JSONObject().put("role", "user").put("content", text));
                    b.put("messages", ms);
                    b.put("max_tokens", 200);
                    payload = b.toString();
                }

                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(8000);
                c.setReadTimeout(30000);
                for (java.util.Map.Entry<String, String> en : headers.entrySet()) {
                    c.setRequestProperty(en.getKey(), en.getValue());
                }
                c.setDoOutput(true);
                OutputStream os = c.getOutputStream();
                os.write(payload.getBytes(StandardCharsets.UTF_8));
                os.close();

                int code = c.getResponseCode();
                BufferedReader r = new BufferedReader(new InputStreamReader(
                        code >= 400 ? c.getErrorStream() : c.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();

                if (code >= 400) {
                    out = "接口报错 " + code + "：" + sb;
                } else {
                    JSONObject o = new JSONObject(sb.toString());
                    if ("gemini".equals(provider)) {
                        out = o.getJSONArray("candidates").getJSONObject(0)
                                .getJSONObject("content").getJSONArray("parts")
                                .getJSONObject(0).getString("text").trim();
                    } else if ("anthropic".equals(provider)) {
                        JSONArray arr = o.getJSONArray("content");
                        StringBuilder t = new StringBuilder();
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject part = arr.getJSONObject(i);
                            if (part.has("text")) t.append(part.getString("text"));
                        }
                        out = t.toString().trim();
                    } else if ("dify".equals(provider)) {
                        out = o.optString("answer", "").trim();
                    } else {
                        out = o.getJSONArray("choices").getJSONObject(0)
                                .getJSONObject("message").getString("content").trim();
                    }
                }
            } catch (Exception e) {
                out = "连接失败：" + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : " " + e.getMessage());
            }
            final String res = out;
            ui.post(new Runnable() { public void run() { cb.onReply(res); } });
        }}, "chat-req").start();
    }
}
