package com.dafeiyu.pet;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

/**
 * 系统自带语音识别（小米/Google 的 RecognitionService）。
 *
 * 不依赖 Termux、不依赖云端 Key：手机上只要有识别服务就能用，
 * 优先请求离线识别（EXTRA_PREFER_OFFLINE）。
 */
public class SysAsr {

    public interface Cb {
        void onText(String text);
        void onError(String msg);
    }

    private SpeechRecognizer sr;
    private Cb cb;
    private boolean active;

    public static boolean available(Context ctx) {
        try {
            return SpeechRecognizer.isRecognitionAvailable(ctx);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 返回 false 表示系统没有识别服务 */
    public boolean start(Context ctx, final Cb callback) {
        if (!available(ctx)) return false;
        this.cb = callback;
        try {
            sr = SpeechRecognizer.createSpeechRecognizer(ctx);
            sr.setRecognitionListener(new RecognitionListener() {
                public void onReadyForSpeech(Bundle p) { }
                public void onBeginningOfSpeech() { }
                public void onRmsChanged(float v) { }
                public void onBufferReceived(byte[] b) { }
                public void onEndOfSpeech() { }
                public void onPartialResults(Bundle b) { }

                public void onError(int code) {
                    active = false;
                    destroy();
                    if (cb != null) cb.onError(mapErr(code));
                }

                public void onResults(Bundle b) {
                    active = false;
                    destroy();
                    String text = first(b);
                    if (cb != null) {
                        if (text == null || text.trim().isEmpty()) cb.onError("没听清，再说一次？");
                        else cb.onText(text.trim());
                    }
                }

                public void onEvent(int t, Bundle p) { }
            });
            Intent it = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            it.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
            it.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            it.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            it.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.getPackageName());
            sr.startListening(it);
            active = true;
            return true;
        } catch (Throwable t) {
            destroy();
            return false;
        }
    }

    /** 松手：让识别器出结果 */
    public void stop() {
        try {
            if (sr != null && active) sr.stopListening();
        } catch (Throwable ignored) { }
    }

    public void cancel() {
        active = false;
        try {
            if (sr != null) sr.cancel();
        } catch (Throwable ignored) { }
        destroy();
    }

    private void destroy() {
        try {
            if (sr != null) sr.destroy();
        } catch (Throwable ignored) { }
        sr = null;
    }

    private static String first(Bundle b) {
        if (b == null) return null;
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty()) return null;
        return list.get(0);
    }

    private static String mapErr(int code) {
        switch (code) {
            case SpeechRecognizer.ERROR_NO_MATCH: return "没听清，再说一次？";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "没听到声音…";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "系统语音识别要联网，先打字吧";
            case SpeechRecognizer.ERROR_AUDIO: return "麦克风被占用，先打字吧";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "没给麦克风权限";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "识别器忙，再试一次";
            default: return "系统语音识别不可用，先打字吧";
        }
    }
}
