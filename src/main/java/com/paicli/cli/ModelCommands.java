package com.paicli.cli;

import com.paicli.config.PaiCliConfig;
import com.paicli.llm.*;
import java.io.IOException;
import java.util.*;

final class ModelCommands {
    private ModelCommands() {}

    static String handle(String payload, PaiCliConfig config, LlmClient current) throws IOException {
        return handle(payload, config, current, new ModelDiscovery());
    }

    static String handle(String payload, PaiCliConfig config, LlmClient current, ModelDiscovery discovery) throws IOException {
        String[] args = payload.trim().split("\\s+");
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                if (args.length > 2) throw new IllegalArgumentException("用法: /model list [provider]");
                yield list(config, current, args.length == 2 ? args[1] : null);
            }
            case "refresh" -> {
                if (args.length != 2) throw new IllegalArgumentException("用法: /model refresh <provider>");
                String provider = args[1].toLowerCase(Locale.ROOT);
                ModelCatalog.requireProvider(provider);
                LlmClient client = LlmClientFactory.create(provider, config);
                if (client == null) throw new IllegalArgumentException("未配置 " + provider + " 的 API Key");
                int count = ModelCatalog.mergeDiscovered(config, provider, discovery.discover(client));
                yield "已刷新 " + provider + "：新增 " + count + " 个模型，当前模型未切换。\n"
                        + "新发现模型的能力待配置，默认按 128k 文本处理。用 /model add 补充，/model list " + provider + " 查看。";
            }
            case "add" -> add(config, args);
            default -> throw new IllegalArgumentException(usage());
        };
    }

    private static String list(PaiCliConfig config, LlmClient current, String onlyProvider) {
        if (onlyProvider != null) ModelCatalog.requireProvider(onlyProvider);
        StringBuilder out = new StringBuilder("模型列表（* 为当前会话模型）\n");
        for (String provider : onlyProvider == null ? ModelCatalog.BUILTINS.keySet() : List.of(onlyProvider)) {
            for (var entry : ModelCatalog.entries(config, provider).entrySet()) {
                String model = entry.getKey();
                ModelProfile profile = entry.getValue();
                LlmClient preview = ModelCatalog.preview(config, provider, model);
                boolean selected = current != null && current.getProviderName().equals(provider) && current.getModelName().equals(model);
                out.append(selected ? "* " : "  ").append(provider).append('/').append(model)
                        .append(" | context=").append(preview.maxContextWindow())
                        .append(" | vision=").append(preview.supportsImageInput())
                        .append(" | ").append(profile == null ? "内置/已有配置" :
                                "discovered".equals(profile.source()) ? "发现，能力待配置" : "手动配置");
                if (profile != null && profile.template() != null) out.append(" | like=").append(profile.template());
                if (profile != null && profile.thinking() != null) out.append(" | thinking=").append(profile.thinking());
                if (profile != null && profile.reasoningEffort() != null) out.append(" | effort=").append(profile.reasoningEffort());
                out.append('\n');
            }
        }
        return out.append("切换: /model <provider>/<模型ID>；添加或更新能力: /model add，查看用法。").toString();
    }

    private static String add(PaiCliConfig config, String[] args) throws IOException {
        if (args.length < 3) throw new IllegalArgumentException(usage());
        String provider = args[1].toLowerCase(Locale.ROOT);
        String model = args[2];
        ModelCatalog.requireProvider(provider);
        if (!ModelCatalog.validModelId(model)) throw new IllegalArgumentException("模型 ID 无效");
        Map<String, String> options = new LinkedHashMap<>();
        Set<String> allowed = Set.of("like", "context", "vision", "thinking", "effort", "reasoning-history", "dsml", "max-output");
        for (int i = 3; i < args.length; i++) {
            String key = args[i];
            if (!key.startsWith("--")) throw new IllegalArgumentException("选项必须以 -- 开头");
            key = key.substring(2);
            int eq = key.indexOf('=');
            String value;
            if (eq >= 0) { value = key.substring(eq + 1); key = key.substring(0, eq); }
            else {
                if (++i >= args.length) throw new IllegalArgumentException("缺少 --" + key + " 的值");
                value = args[i];
            }
            if (!allowed.contains(key)) throw new IllegalArgumentException("未知选项: --" + key);
            if (options.putIfAbsent(key, value) != null) throw new IllegalArgumentException("重复选项: --" + key);
        }
        Map<String, ModelProfile> entries = ModelCatalog.entries(config, provider);
        ModelProfile base = entries.get(model);
        String template = base == null ? null : base.template();
        if (options.containsKey("like")) {
            String like = options.get("like");
            if (!entries.containsKey(like)) throw new IllegalArgumentException("找不到同供应商的模板模型: " + like);
            base = entries.get(like);
            template = base != null && base.template() != null ? base.template() : like;
        }
        if (base == null) base = entries.containsKey(model) || template != null
                ? new ModelProfile(null, null, null, null, null, null, null, null, "manual") : ModelProfile.discovered();
        Integer context = integer(options, "context", base.contextWindow());
        Boolean vision = bool(options, "vision", base.imageInput());
        String thinking = options.containsKey("thinking") ? auto(options.get("thinking")) : base.thinking();
        String effort = options.containsKey("effort") ? auto(options.get("effort")) : base.reasoningEffort();
        if ("disabled".equals(thinking) && !options.containsKey("effort")) effort = null;
        Boolean history = bool(options, "reasoning-history", base.reasoningHistory());
        Boolean dsml = bool(options, "dsml", base.dsml());
        if (Boolean.TRUE.equals(dsml) && !provider.equals("deepseek"))
            throw new IllegalArgumentException("DSML 兼容目前仅支持 deepseek");
        ModelProfile profile = new ModelProfile(template, context, vision, thinking, effort, history, dsml,
                integer(options, "max-output", base.maxOutputTokens()), "manual");
        ModelCatalog.saveProfile(config, provider, model, profile);
        return "已保存 " + provider + "/" + model + "，当前会话模型未切换。\n"
                + "使用: /model " + provider + "/" + model;
    }

    private static String auto(String value) { return "auto".equals(value) ? null : value; }
    private static Integer integer(Map<String, String> options, String key, Integer fallback) {
        if (!options.containsKey(key)) return fallback;
        try { return Integer.valueOf(options.get(key)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(key + " 必须是整数"); }
    }
    private static Boolean bool(Map<String, String> options, String key, Boolean fallback) {
        if (!options.containsKey(key)) return fallback;
        String value = options.get(key);
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException(key + " 必须是 true 或 false");
        return Boolean.valueOf(value);
    }

    static LlmClient switchModel(PaiCliConfig config, Main.ModelSelection target) throws IOException {
        LlmClient client = LlmClientFactory.create(target.provider(), target.model(), config);
        if (client == null) throw new IllegalArgumentException("未配置 " + target.provider() + " 的 API Key 或不支持该供应商");
        PaiCliConfig.ProviderConfig previous = config.getProviders().get(target.provider());
        String previousModel = previous == null ? null : previous.getModel();
        String previousDefault = config.getDefaultProvider();
        if (target.explicitModel()) {
            config.getProviders().computeIfAbsent(target.provider(), ignored -> new PaiCliConfig.ProviderConfig()).setModel(target.model());
        }
        config.setDefaultProvider(target.provider());
        try { config.saveOrThrow(); }
        catch (IOException | RuntimeException e) {
            config.setDefaultProvider(previousDefault);
            if (previous == null) config.getProviders().remove(target.provider());
            else previous.setModel(previousModel);
            throw e;
        }
        return client;
    }

    static String usage() {
        return """
                用法:
                  /model list [provider]
                  /model refresh <provider>
                  /model add <provider> <模型ID> [--like <已有模型ID>]
                    [--context <tokens>] [--vision true|false] [--max-output <tokens>]
                    [--thinking auto|enabled|disabled] [--effort auto|low|medium|high|max]
                    [--reasoning-history true|false] [--dsml true|false]
                  /model <provider>/<模型ID>
                未指定模板的新模型默认按 128k 文本处理；auto 沿用供应商/模板设置。
                添加和刷新不会切换模型，也不会执行推理测试。
                """.stripTrailing();
    }
}
