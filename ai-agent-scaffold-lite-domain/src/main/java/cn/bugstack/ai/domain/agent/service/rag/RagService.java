package cn.bugstack.ai.domain.agent.service.rag;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RAG 检索增强生成服务
 * 文档摄入 → TF 向量化 → 检索 → 上下文注入
 *
 * @author jyk
 */
@Slf4j
@Service
public class RagService {

    @Resource
    private EmbeddingService embeddingService;

    @Resource
    private InMemoryVectorStore vectorStore;

    private static final int CHUNK_SIZE = 500;
    private static final int CHUNK_OVERLAP = 50;
    private static final int DEFAULT_TOP_K = 5;

    /**
     * 从目录加载知识库
     */
    public int ingestDirectory(String namespace, Path dirPath) throws IOException {
        vectorStore.clear(namespace);
        int count = 0;
        try (var files = Files.list(dirPath)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                count += ingestDocument(namespace, file.getFileName().toString(), content);
            }
        }
        log.info("知识库已加载 namespace={} chunks={}", namespace, count);
        return count;
    }

    /**
     * 摄入单篇文档
     */
    public int ingestDocument(String namespace, String docName, String content) {
        List<String> chunks = chunkText(content);
        int count = 0;
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            Map<String, Double> tfVec = embeddingService.tfVector(chunk);
            if (tfVec.isEmpty()) continue;
            String id = UUID.randomUUID().toString().substring(0, 8);
            vectorStore.add(namespace, id, chunk, tfVec,
                    Map.of("docName", docName, "chunkIndex", i, "totalChunks", chunks.size()));
            count++;
        }
        return count;
    }

    /**
     * 检索并格式化为 LLM 上下文
     */
    public String searchAsContext(String namespace, String query) {
        Map<String, Double> queryTf = embeddingService.tfVector(query);
        if (queryTf.isEmpty()) return "";

        List<InMemoryVectorStore.SearchResult> results = vectorStore.search(namespace, queryTf, DEFAULT_TOP_K);
        if (results.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("\n\n【参考资料】\n");
        for (int i = 0; i < results.size(); i++) {
            var r = results.get(i);
            sb.append("---\n来源: ").append(r.metadata().getOrDefault("docName", "未知"))
              .append(" 相关度: ").append(String.format("%.2f", r.score())).append("\n")
              .append(r.content()).append("\n");
        }
        return sb.toString();
    }

    private List<String> chunkText(String text) {
        if (text.length() <= CHUNK_SIZE) return List.of(text);
        List<String> chunks = new java.util.ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + CHUNK_SIZE, text.length());
            chunks.add(text.substring(start, end).trim());
            start = end - CHUNK_OVERLAP;
        }
        return chunks;
    }
}
