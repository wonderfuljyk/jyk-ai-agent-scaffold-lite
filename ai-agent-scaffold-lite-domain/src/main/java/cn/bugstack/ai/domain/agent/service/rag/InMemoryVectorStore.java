package cn.bugstack.ai.domain.agent.service.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存向量存储（基于 TF 向量）
 * 适用于小规模知识库（<1万条），后续可替换为 Milvus/Qdrant
 *
 * @author jyk
 */
@Slf4j
@Component
public class InMemoryVectorStore {

    public record DocEntry(String id, String content, Map<String, Double> tfVector, Map<String, Object> metadata) {}

    private final Map<String, List<DocEntry>> stores = new ConcurrentHashMap<>();

    public void add(String namespace, String id, String content, Map<String, Double> tfVector, Map<String, Object> metadata) {
        stores.computeIfAbsent(namespace, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(new DocEntry(id, content, tfVector, metadata != null ? metadata : Map.of()));
    }

    public List<SearchResult> search(String namespace, Map<String, Double> queryTf, int topK) {
        List<DocEntry> entries = stores.getOrDefault(namespace, List.of());
        if (entries.isEmpty()) return List.of();

        return entries.stream()
                .map(e -> new SearchResult(e.id, e.content, e.metadata,
                        EmbeddingService.cosineSimilarity(queryTf, e.tfVector)))
                .filter(r -> r.score > 0.05)
                .sorted((a, b) -> Double.compare(b.score, a.score))
                .limit(topK)
                .toList();
    }

    public int size(String namespace) {
        return stores.getOrDefault(namespace, List.of()).size();
    }

    public void clear(String namespace) {
        stores.remove(namespace);
    }

    public record SearchResult(String id, String content, Map<String, Object> metadata, double score) {}
}
