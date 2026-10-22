package com.dafeiyu.pet;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 设置页：启动/关闭桌宠、调大小、换配色、触发互动。
 * 大小滑杆与桌宠上的捏合缩放共用同一个 SharedPreferences 键，双向同步。
 */
public class MainActivity extends Activity {
    private static final int REQ_OVERLAY = 1;
    private static final int REQ_NOTIF = 2;

    private static final float MIN_SCALE = 0.5f, MAX_SCALE = 3.0f;

    private SeekBar sizeBar;
    private TextView sizeLabel;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PetService.PREFS, MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(22);
        root.setPadding(p, dp(40), p, dp(40));
        scroll.addView(root);

        // ---------- 标题 ----------
        root.addView(title("🐳 大肥鱼桌宠", 24, 0xFF1A2C6B, true));
        root.addView(title("鲸娘 · 透明 WebM 动画", 13, 0xFF7080A8, false));

        // ---------- 启动 / 关闭 ----------
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        // 注意：这里必须 MATCH_PARENT。之前误写成 lp(0, ...) 且无 weight，
        // 容器被压成 0 宽 → 里面的"启动/关闭"按钮 0 宽 → 界面看不到启动按钮。
        LinearLayout.LayoutParams rp = lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(20);
        row.setLayoutParams(rp);

        Button btnStart = new Button(this);
        btnStart.setText("启动大肥鱼 🐳");
        btnStart.setTextSize(18);
        btnStart.setAllCaps(false);
        LinearLayout.LayoutParams bsp = lp(0, dp(64), 1.4f);
        btnStart.setLayoutParams(bsp);
        btnStart.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startPet(); }
        });
        row.addView(btnStart);

        Button btnStop = new Button(this);
        btnStop.setText("关闭");
        btnStop.setTextSize(16);
        LinearLayout.LayoutParams sp = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sp.leftMargin = dp(12);
        btnStop.setLayoutParams(sp);
        btnStop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                stopService(new Intent(MainActivity.this, PetService.class));
                PetService.running = false;
            }
        });
        row.addView(btnStop);
        root.addView(row);

        // ---------- 大小 ----------
        root.addView(section("大小"));
        sizeLabel = title("1.0×", 15, 0xFF4D6BFE, true);
        root.addView(sizeLabel);

        sizeBar = new SeekBar(this);
        sizeBar.setMax(100);
        sizeBar.setProgress(scaleToProgress(prefs.getFloat(PetService.KEY_SCALE, 1f)));
        sizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                float s = progressToScale(progress);
                sizeLabel.setText(String.format("%.1f×", s));
                prefs.edit().putFloat(PetService.KEY_SCALE, s).apply();
                if (PetService.running) {
                    startService(new Intent(MainActivity.this, PetService.class)
                            .putExtra(PetService.EXTRA_SCALE, s));
                }
            }
            public void onStartTrackingTouch(SeekBar sb) { }
            public void onStopTrackingTouch(SeekBar sb) { }
        });
        root.addView(sizeBar);
        root.addView(title("桌宠上双指捏合也能实时缩放，两处同步。", 12, 0xFF8A93AA, false));

        // ---------- 行为 ----------
        root.addView(section("行为"));
        Switch swWander = new Switch(this);
        swWander.setText("自动走动（自己在桌面随机溜达）");
        swWander.setTextSize(15);
        swWander.setChecked(prefs.getBoolean(PetService.KEY_WANDER, true));
        swWander.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.edit().putBoolean(PetService.KEY_WANDER, on).apply();
                sendCommand(on ? "wander_on" : "wander_off");
            }
        });
        root.addView(swWander);

        // ---------- 活跃度：每分钟次数（走路/表演/说话），合计有上限 ----------
        final int RATE_CAP = 10;
        root.addView(section("活跃度（每分钟次数，合计上限 " + RATE_CAP + "）"));
        final String[] rk = {"rate_walk", "rate_act", "rate_talk"};
        final String[] rn = {"走路", "表演", "说话"};
        final int[] rv = {
                intPref(prefs, rk[0], 2), intPref(prefs, rk[1], 3), intPref(prefs, rk[2], 1)
        };
        /* 顺手把历史脏数据归一化成 int */
        prefs.edit().putInt(rk[0], rv[0]).putInt(rk[1], rv[1]).putInt(rk[2], rv[2]).apply();
        final SeekBar[] bars = new SeekBar[3];
        final TextView[] vals = new TextView[3];
        final TextView totalLine = title("", 13, 0xFF4D6BFE, true);
        final long[] lastCapToast = {0L};
        final Runnable refresh = new Runnable() {
            public void run() {
                int sum = rv[0] + rv[1] + rv[2];
                for (int i = 0; i < 3; i++) vals[i].setText(rv[i] + " 次/分");
                totalLine.setText("合计 " + sum + " / " + RATE_CAP);
            }
        };
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            LinearLayout srow = new LinearLayout(this);
            srow.setOrientation(LinearLayout.HORIZONTAL);
            TextView name = title(rn[i], 14, 0xFF1E2A44, false);
            name.setWidth(dp(52));
            SeekBar bar = new SeekBar(this);
            bar.setMax(RATE_CAP);
            bar.setProgress(rv[i]);
            TextView val = title(rv[i] + " 次/分", 13, 0xFF4D6BFE, true);
            val.setWidth(dp(64));
            val.setGravity(android.view.Gravity.END);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            bar.setLayoutParams(bp);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    int others = 0;
                    for (int j = 0; j < 3; j++) if (j != idx) others += rv[j];
                    if (progress + others > RATE_CAP) {
                        progress = Math.max(0, RATE_CAP - others);
                        sb.setProgress(progress);
                        long nowMs = System.currentTimeMillis();
                        if (nowMs - lastCapToast[0] > 1500) {
                            lastCapToast[0] = nowMs;
                            Toast.makeText(MainActivity.this,
                                    "合计上限 " + RATE_CAP + "，其它两项已经占了 " + others,
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                    rv[idx] = progress;
                    prefs.edit().putInt(rk[idx], progress).apply();
                    refresh.run();
                    sendCommand("rates");
                }
                public void onStartTrackingTouch(SeekBar sb) { }
                public void onStopTrackingTouch(SeekBar sb) { }
            });
            bars[i] = bar;
            vals[i] = val;
            srow.addView(name);
            srow.addView(bar);
            srow.addView(val);
            root.addView(srow);
        }
        root.addView(totalLine);
        refresh.run();

        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        final String[] pn = {"安静", "正常", "活跃"};
        final int[][] pv = {{1, 1, 0}, {2, 3, 1}, {4, 4, 2}};
        for (int i = 0; i < 3; i++) {
            final int pi = i;
            Button b = new Button(this);
            b.setText(pn[i]);
            b.setTextSize(14);
            LinearLayout.LayoutParams mp = lp(0, dp(46), 1f);
            if (i > 0) mp.leftMargin = dp(6);
            b.setLayoutParams(mp);
            b.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) {
                    for (int j = 0; j < 3; j++) {
                        rv[j] = pv[pi][j];
                        prefs.edit().putInt(rk[j], rv[j]).apply();
                        bars[j].setProgress(rv[j]);
                    }
                    refresh.run();
                    sendCommand("rates");
                }
            });
            presets.addView(b);
        }
        root.addView(presets);

        // ---------- 对话 ----------
        root.addView(section("对话"));
        Button btnChat = new Button(this);
        btnChat.setText("和大肥鱼说话（长按桌宠也行）");
        btnChat.setTextSize(15);
        btnChat.setOnClickListener(new View.OnClickListener() {
            public void onClick(android.view.View v) {
                startActivity(new Intent(MainActivity.this, ChatActivity.class));
            }
        });
        root.addView(btnChat, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        // 输入框先建好（chips 的回调要引用）
        android.widget.Switch swVoice = new android.widget.Switch(this);
        swVoice.setText("语音输入（关=文字输入）");
        swVoice.setTextSize(15);
        swVoice.setChecked("voice".equals(prefs.getString("input_mode", "text")));
        swVoice.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.edit().putString("input_mode", on ? "voice" : "text").apply();
            }
        });
        root.addView(swVoice);

        // 模块开关：关掉的模块在服务里根本不创建
        android.widget.Switch swSpeech = new android.widget.Switch(this);
        swSpeech.setText("气泡说话（关=不冒气泡，省电）");
        swSpeech.setTextSize(15);
        swSpeech.setChecked(prefs.getBoolean("speech_enabled", true));
        swSpeech.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.edit().putBoolean("speech_enabled", on).apply();
            }
        });
        root.addView(swSpeech);

        final android.widget.Switch swLink = new android.widget.Switch(this);
        swLink.setText("DSH 状态联动（显示我在干啥）");
        swLink.setTextSize(15);
        swLink.setChecked(prefs.getBoolean("link_enabled", false));
        swLink.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.edit().putBoolean("link_enabled", on).apply();
                Toast.makeText(MainActivity.this, on ? "联动已开（重启桌宠生效）" : "联动已关（重启桌宠生效）", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(swLink);

        final android.widget.Switch swSfx = new android.widget.Switch(this);
        swSfx.setText("音效（点击 / 思考 / 完成）");
        swSfx.setTextSize(15);
        swSfx.setChecked(prefs.getBoolean("sfx_enabled", true));
        swSfx.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.edit().putBoolean("sfx_enabled", on).apply();
            }
        });
        root.addView(swSfx);

        /* ---------- 语音转写服务（本地 Termux 或云端 OpenAI 兼容接口） ---------- */
        root.addView(title("语音转写服务", 15, 0xFF1E2A44, true));
        final EditText etAsrBase = apiField("地址，如 https://api.openai.com/v1/audio/transcriptions（留空=本机 127.0.0.1:8077）", AsrClient.KEY_ASR, "");
        final EditText etAsrKey = apiField("云端 Key（走本机 Termux 时留空）", "asr_key", "");
        final EditText etAsrModel = apiField("模型名（云端用，默认 whisper-1）", "asr_model", "");
        root.addView(etAsrBase);
        root.addView(etAsrKey);
        root.addView(etAsrModel);

        Button btnAsrSave = new Button(this);
        btnAsrSave.setText("保存语音服务");
        btnAsrSave.setTextSize(14);
        btnAsrSave.setLayoutParams(lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        btnAsrSave.setOnClickListener(new View.OnClickListener() {
            public void onClick(android.view.View v) {
                prefs.edit()
                        .putString(AsrClient.KEY_ASR, etAsrBase.getText().toString().trim())
                        .putString("asr_key", etAsrKey.getText().toString().trim())
                        .putString("asr_model", etAsrModel.getText().toString().trim())
                        .apply();
                Toast.makeText(MainActivity.this, "语音服务已保存", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(btnAsrSave);

        Button btnHelp = new Button(this);
        btnHelp.setText("帮助：怎么配 Key / 语音 / 联动");
        btnHelp.setTextSize(14);
        btnHelp.setLayoutParams(lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        btnHelp.setOnClickListener(new View.OnClickListener() {
            public void onClick(android.view.View v) { showHelp(); }
        });
        root.addView(btnHelp);

        String ver = "v?";
        try {
            ver = "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) { }
        root.addView(title("大肥鱼 " + ver + " · dsh", 12, 0xFF8A93AA, false));

        final android.widget.Switch swApi = new android.widget.Switch(this);
        swApi.setText("使用 API（关 = 只用本地闲聊，不花 token）");
        swApi.setTextSize(15);
        swApi.setChecked(prefs.getBoolean("use_api", true));
        swApi.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.edit().putBoolean("use_api", on).apply();
                Toast.makeText(MainActivity.this, on ? "已开启 API" : "已关闭 API（本地闲聊）", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(swApi);

        /* ---------- API Key：只允许添加 / 删除，界面上永不回显 ---------- */
        final TextView keyStatus = title("", 14, 0xFF4D6BFE, true);
        root.addView(keyStatus);
        LinearLayout keyRow = new LinearLayout(this);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);

        final Button btnAddKey = new Button(this);
        btnAddKey.setText("添加 Key");
        btnAddKey.setTextSize(14);
        btnAddKey.setLayoutParams(lp(0, dp(48), 1f));

        final Button btnDelKey = new Button(this);
        btnDelKey.setText("删除 Key");
        btnDelKey.setTextSize(14);
        LinearLayout.LayoutParams dpLp = lp(0, dp(48), 1f);
        dpLp.leftMargin = dp(8);
        btnDelKey.setLayoutParams(dpLp);

        keyRow.addView(btnAddKey);
        keyRow.addView(btnDelKey);
        root.addView(keyRow);

        final Runnable refreshKey = new Runnable() {
            public void run() {
                String k = prefs.getString(ChatClient.KEY_KEY, "");
                if (k == null || k.trim().isEmpty()) {
                    keyStatus.setText("未配置 Key");
                } else {
                    String tail = k.length() > 4 ? k.substring(k.length() - 4) : "";
                    keyStatus.setText("已配置 Key（尾号 " + tail + "）");
                }
            }
        };
        refreshKey.run();

        final EditText etBase = apiField("接口地址（自定义/Dify 才需要）", ChatClient.KEY_BASE, "");
        final EditText etModel = apiField("模型名（自定义/Dify 才需要）", ChatClient.KEY_MODEL, "");

        btnAddKey.setOnClickListener(new View.OnClickListener() {
            public void onClick(android.view.View v) {
                String k = prefs.getString(ChatClient.KEY_KEY, "");
                if (k != null && !k.trim().isEmpty()) {
                    Toast.makeText(MainActivity.this, "里面已经有 Key 了，想换请先删除", Toast.LENGTH_SHORT).show();
                    return;
                }
                final android.widget.EditText input = new android.widget.EditText(MainActivity.this);
                input.setHint("粘贴 API Key");
                input.setSingleLine(true);
                input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
                input.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("添加 API Key")
                        .setMessage("只保存在本机，添加后不会再显示")
                        .setView(input)
                        .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                String val = input.getText().toString().trim();
                                if (val.isEmpty()) return;
                                prefs.edit().putString(ChatClient.KEY_KEY, val).apply();
                                refreshKey.run();
                                Toast.makeText(MainActivity.this, "已保存（不再显示）", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });

        btnDelKey.setOnClickListener(new View.OnClickListener() {
            public void onClick(android.view.View v) {
                String k = prefs.getString(ChatClient.KEY_KEY, "");
                if (k == null || k.trim().isEmpty()) {
                    Toast.makeText(MainActivity.this, "没有 Key 可删", Toast.LENGTH_SHORT).show();
                    return;
                }
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("删除 API Key？")
                        .setMessage("删除后需要重新添加才能对话")
                        .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                prefs.edit().remove(ChatClient.KEY_KEY).apply();
                                refreshKey.run();
                                Toast.makeText(MainActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });

        // 服务商：点一下就带出地址和模型，用户只需要填 Key
        android.widget.HorizontalScrollView hsv = new android.widget.HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout provRow = new LinearLayout(this);
        provRow.setOrientation(LinearLayout.HORIZONTAL);
        final Button[] chips = new Button[Providers.LIST.length];
        String curId = prefs.getString(ChatClient.KEY_PROVIDER, "deepseek");
        for (int i = 0; i < Providers.LIST.length; i++) {
            final String id = Providers.LIST[i][0];
            Button b = new Button(this);
            b.setText(Providers.LIST[i][1]);
            b.setTextSize(13);
            b.setAlpha(id.equals(curId) ? 1f : 0.5f);
            LinearLayout.LayoutParams cp = lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
            if (i > 0) cp.leftMargin = dp(6);
            b.setLayoutParams(cp);
            b.setPadding(dp(14), 0, dp(14), 0);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(android.view.View v) {
                    boolean needUrl = "custom".equals(id) || "dify".equals(id);
                    prefs.edit().putString(ChatClient.KEY_PROVIDER, id)
                            .putString(ChatClient.KEY_BASE, "").putString(ChatClient.KEY_MODEL, "").apply();
                    for (int j = 0; j < chips.length; j++) {
                        chips[j].setAlpha(Providers.LIST[j][0].equals(id) ? 1f : 0.5f);
                    }
                    etBase.setVisibility(needUrl ? View.VISIBLE : View.GONE);
                    etModel.setVisibility(needUrl ? View.VISIBLE : View.GONE);
                }
            });
            chips[i] = b;
            provRow.addView(b);
        }
        hsv.addView(provRow);
        root.addView(hsv);

        boolean customNow = "custom".equals(curId) || "dify".equals(curId);
        etBase.setVisibility(customNow ? View.VISIBLE : View.GONE);
        etModel.setVisibility(customNow ? View.VISIBLE : View.GONE);
        root.addView(etBase);
        root.addView(etModel);

        Button btnSave = new Button(this);
        btnSave.setText("保存");
        btnSave.setTextSize(15);
        btnSave.setOnClickListener(new View.OnClickListener() {
            public void onClick(android.view.View v) {
                prefs.edit()
                        .putString(ChatClient.KEY_BASE, etBase.getText().toString().trim())
                        .putString(ChatClient.KEY_MODEL, etModel.getText().toString().trim())
                        .apply();
                Toast.makeText(MainActivity.this, "已保存", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(btnSave, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        root.addView(title("选服务商 → 填 Key → 保存。回复会同时冒到桌宠气泡上。", 12, 0xFF8A93AA, false));

        // ---------- 互动 ----------
        root.addView(section("互动"));
        LinearLayout act = new LinearLayout(this);
        act.setOrientation(LinearLayout.HORIZONTAL);
        act.addView(actionButton("转一圈", "spin"));
        act.addView(actionButton("爱心", "happy"));
        act.addView(actionButton("睡觉", "sleep"));
        act.addView(actionButton("唤醒", "wake"));
        act.addView(actionButton("自检", "diag"));
        root.addView(act);
        root.addView(title("「自检」会把桌宠底色临时变成品红 10 秒：周围露品红=透明正常；"
                + "整块黑=这台机器的 WebView 不支持 VP9 alpha。", 12, 0xFF8A93AA, false));

        // ---------- 提示 ----------
        root.addView(section("手势"));
        root.addView(title("拖动 = 移动　·　双指捏合 = 改大小　·　点击 = 互动（喂食/爱心/睡眠气泡）",
                12, 0xFF8A93AA, false));

        setContentView(scroll);
        ensureNotificationPermission();
        /* 按住说话要录音权限：进设置页时顺手申请 */
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 33);
        }

        // 自动化测试入口：adb shell am start -n com.dafeiyu.pet/.MainActivity --ez dbg_start 1
        // adb 自己发不了触摸（见 DbgReceiver 的注释），但「启动桌宠」这一步是 startService，
        // 走前台 Activity 就没限制 —— 所以启动放这儿，启动之后的灌手势走 DbgReceiver。
        if (getIntent() != null && getIntent().getBooleanExtra("dbg_start", false)) startPet();
        if (getIntent() != null && getIntent().getBooleanExtra("open_help", false)) {
            root.post(new Runnable() { public void run() { showHelp(); } });
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getBooleanExtra("dbg_start", false)) startPet();
        if (intent != null && intent.getBooleanExtra("open_help", false)) showHelp();
    }

    // ==================== 启动流程 ====================

    /** 容错读 int：历史调试命令可能把数字写成了字符串 */
    static int intPref(android.content.SharedPreferences sp, String key, int def) {
        try {
            return sp.getInt(key, def);
        } catch (Throwable t) {
            try {
                return Integer.parseInt(sp.getString(key, String.valueOf(def)));
            } catch (Throwable t2) {
                return def;
            }
        }
    }

    private void showHelp() {
        String text =
                "【一、聊天 Key】\n" +
                "1. 在「服务商」里点一个（DeepSeek / OpenAI / Claude / Gemini …）\n" +
                "2. 点「添加 KEY」，粘贴你从官网申请的 Key\n" +
                "3. 想换就点「删除 KEY」再重新添加\n" +
                "Key 只存在本机，界面不回显，也不会打进安装包。\n\n" +
                "【一·补】不想花钱？用免费额度的 Key：\n" +
                "  · Google Gemini：aistudio.google.com 申请，服务商选 Gemini\n" +
                "  · 硅基流动 SiliconFlow：cloud.siliconflow.cn，选「自定义」\n" +
                "    地址 https://api.siliconflow.cn/v1，模型如 Qwen/Qwen2.5-7B-Instruct\n" +
                "  · Groq：console.groq.com，选「自定义」，地址 https://api.groq.com/openai/v1\n" +
                "  以上都有免费额度，闲聊够用；不填 Key 时鱼鱼会用本地台词陪你聊。\n\n" +
                "【二、语音】两种用法，二选一：\n" +
                "A. 本机（进阶）：自己用 Termux 跑一个 whisper 服务，\n" +
                "   地址留空即默认 http://127.0.0.1:8077\n" +
                "B. 云端（推荐给普通用户）：任何兼容 /v1/audio/transcriptions 的服务\n" +
                "   地址示例：https://api.openai.com/v1/audio/transcriptions\n" +
                "   模型名：whisper-1（或服务商要求的名字）\n" +
                "   Key：填对应服务商的 Key\n" +
                "   配好后长按桌宠说话，松手即发送\n\n" +
                "【三、DSH 状态联动】\n" +
                "桌宠显示「正在想 / 在跑命令 / 等你确认」，需要本机跑：\n" +
                "Termux → DSH → pet-bridge（监听 127.0.0.1:8078）。\n" +
                "没装 DSH 的话保持关闭即可，不影响其他功能。\n\n" +
                "【四、其他】\n" +
                "· 双击桌宠 = 查 DeepSeek 余额（只支持 DeepSeek，会播钱袋动画）\n" +
                "· 甩飞：按住桌宠快速甩出去，会撞边反弹\n" +
                "· 大小：双指捏合或设置页滑块\n" +
                "· 音效：点击、思考、完成都有提示音，可在设置里关";
        new android.app.AlertDialog.Builder(this)
                .setTitle("帮助")
                .setMessage(text)
                .setPositiveButton("知道了", null)
                .show();
    }

    private void startPet() {
        if (!canOverlay()) {
            Toast.makeText(this, "请先授予「显示在其他应用上层」权限", Toast.LENGTH_LONG).show();
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())), REQ_OVERLAY);
            return;
        }
        startService(new Intent(this, PetService.class)
                .putExtra(PetService.EXTRA_SCALE, prefs.getFloat(PetService.KEY_SCALE, 1f)));
        Toast.makeText(this, "大肥鱼来了 🐳", Toast.LENGTH_SHORT).show();
    }

    private boolean canOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, REQ_NOTIF);
        }
    }

    private void sendCommand(String cmd) {
        if (!PetService.running) {
            Toast.makeText(this, "先点「启动」", Toast.LENGTH_SHORT).show();
            return;
        }
        startService(new Intent(this, PetService.class).putExtra(PetService.EXTRA_CMD, cmd));
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_OVERLAY && canOverlay()) startPet();
    }

    // ==================== 小工具 ====================

    private float progressToScale(int progress) {
        return MIN_SCALE + (MAX_SCALE - MIN_SCALE) * progress / 100f;
    }

    private int scaleToProgress(float scale) {
        float s = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
        return Math.round((s - MIN_SCALE) / (MAX_SCALE - MIN_SCALE) * 100f);
    }

    private Button actionButton(String text, final String cmd) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        LinearLayout.LayoutParams lp = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(6);
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { sendCommand(cmd); }
        });
        return b;
    }

    private EditText apiField(String hint, String key, String def) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(13);
        e.setSingleLine(true);
        e.setText(prefs.getString(key, def));
        return e;
    }

    private TextView title(String text, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setLineSpacing(dp(3), 1f);
        return t;
    }

    private TextView section(String text) {
        TextView t = title(text, 17, 0xFF1A2C6B, true);
        LinearLayout.LayoutParams lp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(26);
        lp.bottomMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
