package com.dafeiyu.pet;

/** 主流服务商预设：选一个就自动带出地址/模型/协议，用户只需填 Key。 */
public class Providers {
    // id, 显示名, base, model, 协议
    public static final String[][] LIST = {
            {"deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", "openai"},
            {"openai",   "OpenAI",   "https://api.openai.com/v1", "gpt-4o-mini", "openai"},
            {"claude",   "Claude",   "https://api.anthropic.com", "claude-3-5-sonnet-latest", "anthropic"},
            {"gemini",   "Gemini",   "https://generativelanguage.googleapis.com", "gemini-2.0-flash", "gemini"},
            {"kimi",     "Kimi",     "https://api.moonshot.cn/v1", "moonshot-v1-8k", "openai"},
            {"zhipu",    "智谱",     "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash", "openai"},
            {"qwen",     "通义",     "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "openai"},
            {"dify",     "Dify",     "", "", "dify"},
            {"custom",   "自定义",   "", "", "openai"}
    };

    public static String[] find(String id) {
        for (String[] p : LIST) if (p[0].equals(id)) return p;
        return LIST[0];
    }

    public static String label(String id) { return find(id)[1]; }
    public static String protocol(String id) { return find(id)[4]; }
}
