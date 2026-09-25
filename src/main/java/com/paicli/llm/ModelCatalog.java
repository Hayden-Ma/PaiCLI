package com.paicli.llm;

import com.paicli.config.PaiCliConfig;
import java.io.IOException;
import java.util.*;

/** Builtin IDs plus user-owned entries. Discovery never replaces an existing profile. */
public final class ModelCatalog {
    public static final Map<String, List<String>> BUILTINS;
    static {
        Map<String, List<String>> models = new LinkedHashMap<>();
        models.put("deepseek", List.of("deepseek-flash", "deepseek-v4-pro", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp"));
        models.put("glm", List.of("glm-5.1", "glm-5.3-flash", "glm-5v-turbo"));
        models.put("hunyuan", List.of("hy4-preview"));
        models.put("step", List.of("step-3.5-flash"));
        models.put("kimi", List.of("kimi-k2.6"));
        models.put("freellmapi", List.of("auto"));
        models.put("xfyun", List.of("Qwen3.6-35B-A3B"));
        models.put("agnes", List.of("agnes-2.0-flash"));
        BUILTINS = Collections.unmodifiableMap(models);
    }
    private ModelCatalog() {}

    public static void requireProvider(String provider) {
        if (!BUILTINS.containsKey(provider)) throw new IllegalArgumentException("不支持的供应商: " + provider);
    }

    public static boolean validModelId(String model) {
        return model != null && model.matches("[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,199}");
    }

    public static Map<String, ModelProfile> entries(PaiCliConfig config, String provider) {
        requireProvider(provider);
        Map<String, ModelProfile> entries = new LinkedHashMap<>();
        for (String id : BUILTINS.get(provider)) entries.put(id, null);
        String selected = config.getModel(provider);
        if (validModelId(selected)) entries.putIfAbsent(selected, null);
        PaiCliConfig.ProviderConfig settings = config.getProviders().get(provider);
        if (settings != null) entries.putAll(settings.getModels());
        return entries;
    }

    public static LlmClient preview(PaiCliConfig config, String provider, String model) {
        PaiCliConfig preview = new PaiCliConfig();
        PaiCliConfig.ProviderConfig settings = new PaiCliConfig.ProviderConfig("catalog-preview", config.getBaseUrl(provider), model);
        PaiCliConfig.ProviderConfig actual = config.getProviders().get(provider);
        if (actual != null) settings.setModels(actual.getModels());
        preview.getProviders().put(provider, settings);
        return LlmClientFactory.create(provider, preview);
    }

    public static void saveProfile(PaiCliConfig config, String provider, String model, ModelProfile profile) throws IOException {
        requireProvider(provider);
        if (!validModelId(model)) throw new IllegalArgumentException("模型 ID 无效");
        Map<String, ModelProfile> additions = new LinkedHashMap<>();
        additions.put(model, profile);
        persist(config, provider, additions);
    }

    public static int mergeDiscovered(PaiCliConfig config, String provider, List<String> discovered) throws IOException {
        Map<String, ModelProfile> existing = entries(config, provider);
        Map<String, ModelProfile> additions = new LinkedHashMap<>();
        for (String model : discovered) {
            if (!validModelId(model)) throw new IllegalArgumentException("模型 ID 无效");
            if (!existing.containsKey(model)) additions.putIfAbsent(model, ModelProfile.discovered());
        }
        if (!additions.isEmpty()) persist(config, provider, additions);
        return additions.size();
    }

    private static void persist(PaiCliConfig config, String provider, Map<String, ModelProfile> additions) throws IOException {
        PaiCliConfig.ProviderConfig before = config.getProviders().get(provider);
        PaiCliConfig.ProviderConfig settings = before == null ? new PaiCliConfig.ProviderConfig() : before;
        Map<String, ModelProfile> previous = new LinkedHashMap<>(settings.getModels());
        Map<String, ModelProfile> next = new LinkedHashMap<>(previous);
        next.putAll(additions);
        settings.setModels(next);
        config.getProviders().put(provider, settings);
        try {
            // Validate before persisting, including limits inherited from the provider.
            for (String model : additions.keySet()) preview(config, provider, model);
            config.saveOrThrow();
        } catch (IOException | RuntimeException e) {
            settings.setModels(previous);
            if (before == null) config.getProviders().remove(provider);
            throw e;
        }
    }
}
