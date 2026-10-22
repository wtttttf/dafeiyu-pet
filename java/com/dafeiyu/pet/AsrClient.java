package com.dafeiyu.pet;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 按住说话：AudioRecord 录 16k 单声道 PCM → WAV → POST 到 ASR 服务（OpenAI 兼容 /v1/audio/transcriptions）。
 * 默认端点指向容器里的本地 whisper 服务（127.0.0.1:8077，容器与手机共享网络栈）。
 */
public class AsrClient {
    public static final String KEY_ASR = "asr_base";
    public static final String DEFAULT_ASR = "http://127.0.0.1:8077";
    private static final int RATE = 16000;

    public interface Cb { void onText(String text); void onError(String msg); }

    private AudioRecord rec;
    private ByteArrayOutputStream pcm;
    private Thread reader;
    private volatile boolean running;

    public void start(Context ctx) throws Exception {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int buf = Math.max(min, RATE * 2);
        rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf);
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("麦克风不可用");
        pcm = new ByteArrayOutputStream();
        running = true;
        rec.startRecording();
        reader = new Thread(new Runnable() {
            public void run() {
                byte[] b = new byte[4096];
                while (running) {
                    int n = rec.read(b, 0, b.length);
                    if (n > 0) pcm.write(b, 0, n);
                }
            }
        }, "asr-read");
        reader.start();
    }

    /** 停止录音并把音频送去转写 */
    public void stopAndTranscribe(final Context ctx, final Cb cb) {
        running = false;
        try { if (rec != null) { rec.stop(); rec.release(); } } catch (Throwable ignored) { }
        rec = null;
        final byte[] data = pcm != null ? pcm.toByteArray() : new byte[0];
        android.util.Log.i("PetDbg", "asr stop, bytes=" + data.length);
        final Handler ui = new Handler(Looper.getMainLooper());
        if (data.length < RATE) {   // 不到 0.5 秒，当误触
            ui.post(new Runnable() { public void run() { cb.onError("太短了，再说一次？"); } });
            return;
        }
        new Thread(new Runnable() { public void run() {
            String out = null;
            String err = null;
            try {
                File f = new File(ctx.getCacheDir(), "ptt.wav");
                FileOutputStream fo = new FileOutputStream(f);
                fo.write(wav(data));
                fo.close();

                SharedPreferences sp = ctx.getSharedPreferences(PetService.PREFS, Context.MODE_PRIVATE);
                String base = sp.getString(KEY_ASR, DEFAULT_ASR).trim();
                if (base.isEmpty()) base = DEFAULT_ASR;
                String url = base.endsWith("/v1/audio/transcriptions") ? base
                        : (base.endsWith("/") ? base + "v1/audio/transcriptions" : base + "/v1/audio/transcriptions");

                String boundary = "----dafeiyu" + System.currentTimeMillis();
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setConnectTimeout(5000);
                c.setReadTimeout(120000);
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                String asrKey = sp.getString("asr_key", "").trim();
                if (!asrKey.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + asrKey);
                DataOutputStream dos = new DataOutputStream(c.getOutputStream());
                dos.writeBytes("--" + boundary + "\r\n");
                String asrModel = sp.getString("asr_model", "whisper-1").trim();
                if (asrModel.isEmpty()) asrModel = "whisper-1";
                dos.writeBytes("Content-Disposition: form-data; name=\"model\"\r\n\r\n" + asrModel + "\r\n");
                dos.writeBytes("--" + boundary + "\r\n");
                dos.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"a.wav\"\r\n");
                dos.writeBytes("Content-Type: audio/wav\r\n\r\n");
                dos.write(wav(data));
                dos.writeBytes("\r\n--" + boundary + "--\r\n");
                dos.flush(); dos.close();

                android.util.Log.i("PetDbg", "asr post " + url);
                int code = c.getResponseCode();
                android.util.Log.i("PetDbg", "asr code=" + code);
                BufferedReader r = new BufferedReader(new InputStreamReader(
                        code >= 400 ? c.getErrorStream() : c.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
                if (code >= 400) err = "转写失败 " + code + "：" + sb;
                else {
                    JSONObject o = new JSONObject(sb.toString());
                    out = o.optString("text", "").trim();
                    if (out.isEmpty()) err = "没听清，再说一次？";
                }
            } catch (Exception e) {
                err = "语音没连上（本机没 ASR 就配云端地址/Key）";
            }
            final String ok = out, bad = err;
            ui.post(new Runnable() { public void run() {
                if (ok != null) cb.onText(ok); else cb.onError(bad);
            }});
        }}, "asr-post").start();
    }

    public void cancel() {
        running = false;
        try { if (rec != null) { rec.stop(); rec.release(); } } catch (Throwable ignored) { }
        rec = null;
    }

    private static byte[] wav(byte[] pcm) {
        int len = pcm.length;
        ByteArrayOutputStream b = new ByteArrayOutputStream(len + 44);
        java.nio.ByteBuffer h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()); h.putInt(36 + len); h.put("WAVE".getBytes());
        h.put("fmt ".getBytes()); h.putInt(16); h.putShort((short) 1); h.putShort((short) 1);
        h.putInt(RATE); h.putInt(RATE * 2); h.putShort((short) 2); h.putShort((short) 16);
        h.put("data".getBytes()); h.putInt(len);
        b.write(h.array(), 0, 44);
        b.write(pcm, 0, len);
        return b.toByteArray();
    }
}
