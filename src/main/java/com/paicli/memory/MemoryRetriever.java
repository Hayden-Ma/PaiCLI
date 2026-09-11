package com.paicli.memory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 记忆检索器 - 根据查询从长期记忆中检索最相关的信息。
 *
 * 检索策略：
 * 1. BM25 相关度打分（k1=1.5, b=0.75）：查询侧用 jieba 分词提取词项；中文场景下词项
 *    频率按子串出现次数统计，规避分词歧义导致的漏召回；文档长度按字符数归一；IDF
 *    采用 Lucene 变体 ln(1 + (N - df + 0.5) / (df + 0.5))，保证权重非负
 * 2. 短语加成：内容完整包含查询原文时额外加权
 * 3. 时间衰减：越近的记忆权重越高
 *
 * 注入 system prompt 时每条记忆都带写入时间和最后核实时间；超过
 * {@code paicli.memory.stale.days}（默认 30 天）未核实的条目会标注“可能已过时”。
 */
public class MemoryRetriever {
    static final String STALE_DAYS_PROPERTY = "paicli.memory.stale.days";
    static final String STALE_DAYS_ENV = "PAICLI_MEMORY_STALE_DAYS";
    static final int DEFAULT_STALE_DAYS = 30;
    static final String STALE_LABEL = "可能已过时";
    static final double K1 = 1.5;
    static final double B = 0.75;
    static final double EXACT_MATCH_BOOST = 1.25;

    private final LongTermMemory longTermMemory;
    private final Duration staleAfter;
    private final Clock clock;

    public MemoryRetriever(LongTermMemory longTermMemory) {
        this(longTermMemory, configuredStaleAfter(), Clock.systemDefaultZone());
    }

    /**
     * @param staleAfter 超过多久未核实视为可能过时；null 或非正数表示不标注
     */
    MemoryRetriever(LongTermMemory longTermMemory, Duration staleAfter, Clock clock) {
        this.longTermMemory = longTermMemory;
        this.staleAfter = staleAfter;
        this.clock = clock;
    }

    /**
     * 检索与查询最相关的记忆
     *
     * @param query 查询文本
     * @param limit 返回条数上限
     * @return 按相关度排序的记忆列表
     */
    public List<MemoryEntry> retrieve(String query, int limit) {
        return retrieveLongTerm(query, limit);
    }

    /**
     * 仅从长期记忆中检索稳定事实，用于 system prompt 注入。
     *
     * 当前轮用户输入和短期对话已经在 message history 里，不应再次以"相关记忆"身份
     * 注入给模型，否则容易让模型把当前请求误读成历史事实。
     */
    public List<MemoryEntry> retrieveLongTerm(String query, int limit) {
        return retrieveLongTerm(query, limit, null);
    }

    public List<MemoryEntry> retrieveLongTerm(String query, int limit, String projectKey) {
        List<MemoryEntry> entries = longTermMemory.getAll().stream()
                .filter(entry -> LongTermMemory.isVisibleInProject(entry, projectKey))
                .collect(Collectors.toList());
        if (entries.isEmpty()) return List.of();

        String queryLower = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        Set<String> queryTerms = MemoryQueryTokenizer.tokenize(queryLower);
        if (queryTerms.isEmpty()) {
            if (queryLower.isEmpty()) return List.of();
            // 分词后为空的单字/特殊查询回退为整体子串匹配
            queryTerms = Set.of(queryLower);
        }

        return rank(entries, queryTerms, queryLower).stream()
                .sorted(Comparator.comparingDouble(ScoredEntry::score).reversed())
                .limit(limit)
                .map(ScoredEntry::entry)
                .collect(Collectors.toList());
    }

    /**
     * 构建上下文：将相关记忆组装成文本，用于注入到 LLM 的 system prompt 中
     */
    public String buildContextForQuery(String query, int maxTokens) {
        return buildContextForQuery(query, maxTokens, null);
    }

    public String buildContextForQuery(String query, int maxTokens, String projectKey) {
        List<MemoryEntry> relevant = retrieveLongTerm(query, 10, projectKey);
        if (relevant.isEmpty()) return "";

        StringBuilder context = new StringBuilder();
        context.append("## 相关长期记忆\n\n");
        context.append("以下记忆是线索而不是事实；涉及版本、配置、路径等可能变化的信息，行动前先对照当前文件核实。\n\n");

        int usedTokens = 0;
        for (MemoryEntry entry : relevant) {
            if (usedTokens + entry.getTokenCount() > maxTokens) break;

            context.append(formatEntry(entry)).append("\n");
            usedTokens += entry.getTokenCount();
        }

        context.append("\n");
        return context.toString();
    }

    public boolean isStale(MemoryEntry entry) {
        if (staleAfter == null || staleAfter.isZero() || staleAfter.isNegative()) {
            return false;
        }
        return entry.getLastVerifiedAt().plus(staleAfter).isBefore(clock.instant());
    }

    private String formatEntry(MemoryEntry entry) {
        StringBuilder line = new StringBuilder("- [").append(entry.getType()).append("]");
        if ("true".equals(entry.getMetadata().get("verification_pending"))) {
            line.append("[自动提取，待核实]");
        }
        boolean stale = isStale(entry);
        if (stale) {
            line.append("[").append(STALE_LABEL).append("]");
        }
        boolean pending = "true".equals(entry.getMetadata().get("verification_pending"));
        line.append(" ").append(entry.getContent())
                .append("（写入 ").append(date(entry.getTimestamp()));
        if (pending) {
            line.append("，尚未经用户核实");
        } else {
            line.append("，最后核实 ").append(date(entry.getLastVerifiedAt()));
        }
        if (stale) {
            line.append("，已超过 ").append(staleAfter.toDays()).append(" 天未核实，使用前必须先核实");
        }
        return line.append("）").toString();
    }

    private LocalDate date(Instant instant) {
        return LocalDate.ofInstant(instant, clock.getZone());
    }

    private static Duration configuredStaleAfter() {
        String raw = System.getProperty(STALE_DAYS_PROPERTY);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(STALE_DAYS_ENV);
        }
        if (raw == null || raw.isBlank()) {
            return Duration.ofDays(DEFAULT_STALE_DAYS);
        }
        try {
            return Duration.ofDays(Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            return Duration.ofDays(DEFAULT_STALE_DAYS);
        }
    }

    private List<ScoredEntry> rank(List<MemoryEntry> entries, Set<String> queryTerms, String queryLower) {
        int docCount = entries.size();
        Map<String, Integer> docFreq = new HashMap<>();
        List<Map<String, Integer>> termFrequencies = new ArrayList<>(docCount);
        List<String> loweredContents = new ArrayList<>(docCount);
        long totalLength = 0;

        for (MemoryEntry entry : entries) {
            String contentLower = entry.getContent().toLowerCase(Locale.ROOT);
            Map<String, Integer> tf = new HashMap<>();
            for (String term : queryTerms) {
                int count = countOccurrences(contentLower, term);
                if (count > 0) {
                    tf.put(term, count);
                    docFreq.merge(term, 1, Integer::sum);
                }
            }
            termFrequencies.add(tf);
            loweredContents.add(contentLower);
            totalLength += Math.max(1, contentLower.length());
        }
        double avgDocLength = (double) totalLength / docCount;

        List<ScoredEntry> scored = new ArrayList<>(docCount);
        for (int i = 0; i < docCount; i++) {
            MemoryEntry entry = entries.get(i);
            double score = bm25(termFrequencies.get(i), docFreq, docCount,
                    avgDocLength, Math.max(1, loweredContents.get(i).length()));
            if (score <= 0) continue;
            if (loweredContents.get(i).contains(queryLower)) {
                score *= EXACT_MATCH_BOOST;
            }
            scored.add(new ScoredEntry(entry, score * timeDecay(entry), false));
        }
        return scored;
    }

    /**
     * 标准 BM25：sum over query terms of idf * tf*(k1+1) / (tf + k1*(1-b+b*dl/avgdl))
     */
    private double bm25(Map<String, Integer> tf, Map<String, Integer> docFreq, int docCount,
                        double avgDocLength, int docLength) {
        double score = 0;
        for (Map.Entry<String, Integer> termTf : tf.entrySet()) {
            int df = docFreq.getOrDefault(termTf.getKey(), 1);
            double idf = Math.log(1 + (docCount - df + 0.5) / (df + 0.5));
            int freq = termTf.getValue();
            double lengthNorm = 1 - B + B * docLength / avgDocLength;
            score += idf * freq * (K1 + 1) / (freq + K1 * lengthNorm);
        }
        return score;
    }

    private static double timeDecay(MemoryEntry entry) {
        long ageMs = System.currentTimeMillis() - entry.getTimestamp().toEpochMilli();
        double ageHours = ageMs / (1000.0 * 60 * 60);
        return Math.max(0.5, 1.0 - ageHours / 24.0); // 24小时内从1.0衰减到0.5
    }

    private static int countOccurrences(String content, String term) {
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(term, index)) >= 0) {
            count++;
            index += term.length();
        }
        return count;
    }

    private record ScoredEntry(MemoryEntry entry, double score, boolean fromShortTerm) {}
}
