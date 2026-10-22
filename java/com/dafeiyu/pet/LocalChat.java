package com.dafeiyu.pet;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 没配 Key 时的本地闲聊兜底。
 *
 * 不是模型，是"人设台词 + 意图匹配"：能接住寒暄、被夸、被骂、问吃、问身份这类话，
 * 答不了真问题——但至少不会像通用机器人那样答非所问。
 * 用近期去重 + 时间感知，避免两天就聊腻。
 */
public class LocalChat {

    private static final Random RND = new Random();
    private static final List<String> recent = new ArrayList<String>();

    private static final String[] DEFAULT = {
            "鱼鱼在的，你说~",
            "嗯？我在摸鱼…不是，我在听。",
            "（尾巴拍了拍桌面）",
            "今天也要一起加油呀。",
            "你忙你的，我不吵你。",
            "我是大肥鱼，不是虎鲸啦。",
            "才不是大肥鱼呢…好吧我是。",
            "（偷偷看了一眼你的屏幕）",
            "有点饿了，有小鱼干吗？",
            "我在这儿陪你，不用管我。",
            "今天的 token 味道不错。",
            "别看我，我在认真发呆。",
            "（把围裙上的小蓝鲸拍了拍）",
            "你刚刚是不是想我了？",
            "工作累了就看看我，我会发光。"
    };

    private static final String[] GREET = {
            "你来啦！鱼鱼一直在。",
            "嗨~ 今天过得怎么样？",
            "（从桌角探出头）叫我干嘛呀？",
            "在的在的，刚从鱼缸里出来。"
    };

    private static final String[] MORNING = {
            "早上好！今天也要好好吃饭。",
            "早呀，鱼鱼还没睡醒…（揉眼睛）",
            "早安，先喝口水再干活。"
    };

    private static final String[] NIGHT = {
            "这么晚还不睡？我陪你一会儿。",
            "夜深了，早点休息吧，鱼鱼也困了。",
            "晚安前记得关灯，眼睛会累。"
    };

    private static final String[] FOOD = {
            "说到吃的我可就不困了！",
            "我吃白饭就很满足了，真的。",
            "小鱼干！小鱼干！",
            "你饿了吗？我这儿只有白饭…"
    };

    private static final String[] PRAISE = {
            "哼，你才发现我可爱吗。",
            "（尾巴摇得有点快）…没有很开心。",
            "被夸了，今天的 token 更香了。",
            "谢谢！你也很可爱。"
    };

    private static final String[] TEASE = {
            "你才笨呢，我可是鲸鱼娘。",
            "哼，不理你了…（三秒后）好了。",
            "说我是大肥鱼可以，说我笨不行。"
    };

    private static final String[] WORK = {
            "忙吧忙吧，我在旁边待着。",
            "需要我安静的时候就戳我一下。",
            "你在忙，我就偷偷摸会儿鱼。"
    };

    private static final String[] WHO = {
            "我是大肥鱼，住在你桌面上的鲸鱼娘。",
            "蓝头发、鲸鱼尾巴、白围裙——就是我。",
            "DeepSeek 家的鱼，二创的那种。",
            "不是虎鲸，也不是鲸鱼，是大肥鱼。"
    };

    private static final String[] THROWN = {
            "哇——别甩我！我头晕。",
            "（撞到屏幕边又弹回来）好玩吗？",
            "再甩我就要吐泡泡了。"
    };

    private static final String[] TIRED = {
            "累就歇会儿，我替你看着桌面。",
            "辛苦了…要不要一起摸会儿鱼？",
            "别硬撑，鱼鱼陪你歇一下。",
            "抱抱，今天已经很努力了。",
            "先把肩膀放下来，剩下的事明天再说。"
    };

    private static final String[] NO_KEY = {
            "我还没连上大脑（API），先陪你闲聊吧。",
            "没有 Key 的时候，我只会说人话，不会算数。",
            "（小声）其实我只会聊天，不会做题。"
    };

    public static String reply(String input) {
        String t = input == null ? "" : input.trim();
        String low = t.toLowerCase();
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);

        if (t.isEmpty()) return pick(DEFAULT);

        if (has(low, "你好", "在吗", "嗨", "hello", "hi", "早上好", "早安"))
            return hour < 11 ? pick(MORNING) : pick(GREET);
        if (has(low, "晚安", "睡了", "困", "睡觉"))
            return pick(NIGHT);
        if (has(low, "吃", "饭", "饿", "小鱼干", "零食"))
            return pick(FOOD);
        if (has(low, "可爱", "喜欢", "爱你", "好棒", "厉害", "漂亮"))
            return pick(PRAISE);
        if (has(low, "笨", "蠢", "丑", "胖", "滚"))
            return pick(TEASE);
        if (has(low, "累", "烦", "难过", "不开心", "压力", "emo", "撑不住"))
            return pick(TIRED);
        if (has(low, "忙", "工作", "加班", "上班"))
            return pick(WORK);
        if (has(low, "你是谁", "什么鱼", "虎鲸", "鲸鱼", "介绍"))
            return pick(WHO);
        if (has(low, "甩", "扔", "飞", "晕"))
            return pick(THROWN);
        if (has(low, "api", "key", "大脑", "模型", "token"))
            return pick(NO_KEY);
        return pick(DEFAULT);
    }

    private static boolean has(String s, String... keys) {
        for (String k : keys) if (s.contains(k)) return true;
        return false;
    }

    /** 抽一句，近期说过的尽量不重复 */
    private static synchronized String pick(String[] pool) {
        Set<String> now = new HashSet<String>(recent);
        for (int i = 0; i < 12; i++) {
            String s = pool[RND.nextInt(pool.length)];
            if (!now.contains(s)) {
                remember(s);
                return s;
            }
        }
        String s = pool[RND.nextInt(pool.length)];
        remember(s);
        return s;
    }

    private static void remember(String s) {
        recent.add(s);
        while (recent.size() > 10) recent.remove(0);
    }
}
