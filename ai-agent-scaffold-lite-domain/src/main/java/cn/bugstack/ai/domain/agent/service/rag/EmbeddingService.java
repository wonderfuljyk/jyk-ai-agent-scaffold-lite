package cn.bugstack.ai.domain.agent.service.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 轻量级文本向量化服务
 * 基于 TF-IDF 关键词权重做文本向量化和相似度检索
 * 无需外部 embedding 模型，适合小规模知识库
 *
 * @author jyk
 */
@Slf4j
@Service
public class EmbeddingService {

    /** 中文/英文分词分隔符 */
    private static final Set<Character> DELIMITERS = Set.of(
            ' ', '\n', '\t', '\r', '。', '，', '、', '；', '：', '！', '？',
            '.', ',', ';', ':', '!', '?', '(', ')', '（', '）', '"', '\'',
            '“', '”', '‘', '’', '【', '】', '《', '》', '—', '…');

    /**
     * 计算文本的 TF（词频）向量
     * @return Map<词, 频率>
     */
    public Map<String, Double> tfVector(String text) {
        if (text == null || text.isBlank()) return Map.of();
        Map<String, Integer> freq = new HashMap<>();

        StringBuilder word = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (DELIMITERS.contains(c)) {
                if (word.length() >= 1) {
                    String w = word.toString().toLowerCase();
                    freq.merge(w, 1, Integer::sum);
                    word.setLength(0);
                }
            } else {
                word.append(c);
                // 中文字符单字成词
                if (c >= 0x4e00 && c <= 0x9fff) {
                    String w = String.valueOf(c).toLowerCase();
                    freq.merge(w, 1, Integer::sum);
                    word.setLength(0);
                }
            }
        }
        if (word.length() >= 1) {
            freq.merge(word.toString().toLowerCase(), 1, Integer::sum);
        }

        double total = freq.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) return Map.of();

        Map<String, Double> tf = new HashMap<>();
        for (var e : freq.entrySet()) {
            tf.put(e.getKey(), e.getValue() / total);
        }
        return tf;
    }

    /**
     * 计算两个 TF 向量的余弦相似度
     */
    public static double cosineSimilarity(Map<String, Double> a, Map<String, Double> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> allWords = new HashSet<>();
        allWords.addAll(a.keySet());
        allWords.addAll(b.keySet());

        double dot = 0, na = 0, nb = 0;
        for (String w : allWords) {
            double va = a.getOrDefault(w, 0.0);
            double vb = b.getOrDefault(w, 0.0);
            dot += va * vb;
            na += va * va;
            nb += vb * vb;
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
