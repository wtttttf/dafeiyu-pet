package com.dafeiyu.pet;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 自动化测试入口（正常使用完全不经过这里）。
 *
 * 本机 adb shell 拿不到 INJECT_EVENTS —— MIUI 的「USB调试（安全设置）」要求插卡+登录小米账号，
 * 而这台设备没插卡，所以 adb 自己发不了触摸。退而求其次：让 adb 用广播把命令送进已经在跑的
 * 桌宠进程，由 PetService 直接往 WebView 灌触摸事件（走的是和真手指同一段代码路径）。
 *
 *   adb shell am broadcast -n com.dafeiyu.pet/.DbgReceiver --es g "tap 540 1200"
 *   adb shell am broadcast -n com.dafeiyu.pet/.DbgReceiver --es g "drag 300 1500 900 1500 500"
 *   adb shell am broadcast -n com.dafeiyu.pet/.DbgReceiver --es js "location.href"
 *   adb shell am broadcast -n com.dafeiyu.pet/.DbgReceiver --ez dump 1
 *   adb shell am broadcast -n com.dafeiyu.pet/.DbgReceiver --es cmd "spin"
 *
 * 服务没在跑就什么都不做（没有可注入的目标），所以它不可能把桌宠"启动"起来 ——
 * 启动那条路走 MainActivity（am start --ez dbg_start 1）。
 */
public class DbgReceiver extends BroadcastReceiver {
    static final String TAG = "PetDbg";

    @Override
    public void onReceive(Context ctx, Intent it) {
        // 谁能发到这儿的把关放在 manifest 上：receiver 声明了 android.permission.DUMP，
        // adb shell 持有它，普通第三方应用没有 —— 免得被别人随手一个广播就能摆布桌宠。
        PetService svc = PetService.instance;
        Log.i(TAG, "recv " + it.getAction() + " 服务=" + (svc != null));
        if (svc == null) return;

        if (it.hasExtra("g")) svc.dbgGesture(it.getStringExtra("g"));
        if (it.hasExtra("js")) svc.dbgJs(it.getStringExtra("js"));
        if (it.hasExtra("cmd")) svc.dbgCmd(it.getStringExtra("cmd"));
        if (it.hasExtra("chat")) svc.chatSend(it.getStringExtra("chat"));
        if (it.hasExtra("screen")) svc.setAwake(it.getBooleanExtra("screen", true));
        if (it.hasExtra("say")) svc.showSay(it.getStringExtra("say"));
        if (it.hasExtra("api")) svc.dbgApi(it.getStringExtra("api"));
        if (it.getBooleanExtra("chatui", false)) svc.openChat();
        if (it.getBooleanExtra("ptt", false)) svc.pttStart();
        if (it.hasExtra("reply") && ChatActivity.current != null)
            ChatActivity.current.setReplyForTest(it.getStringExtra("reply"));
        if (it.getBooleanExtra("pttstop", false)) svc.pttStop();
        if (it.hasExtra("scale")) svc.dbgScale(it.getFloatExtra("scale", 1f));
        if (it.getBooleanExtra("dump", false)) svc.dbgDump();
    }
}
