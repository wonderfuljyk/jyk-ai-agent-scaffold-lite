package cn.bugstack.ai.domain.agent.service.observability;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * Agent 链式监控 —— ThreadLocal 调用栈 + volatile 快照（跨线程可查）
 *
 * @author jyk
 */
@Slf4j
@Component
public class AgentTraceContext {

    private static final ThreadLocal<Deque<TraceEntry>> TRACE = ThreadLocal.withInitial(ArrayDeque::new);

    /** 上一次请求的 trace 快照，跨线程可见 */
    private volatile TraceSnapshot lastSnapshot = null;

    public void startAgent(String agentName) {
        var entry = new TraceEntry(agentName, System.currentTimeMillis());
        TRACE.get().push(entry);
        log.info("→ 进入 Agent: {} path={}", agentName, getCurrentPath());
    }

    public void endAgent(String agentName) {
        var stack = TRACE.get();
        if (!stack.isEmpty()) {
            var entry = stack.pop();
            long elapsed = System.currentTimeMillis() - entry.startTime;
            log.info("← 退出 Agent: {} 耗时={}ms path={}", agentName, elapsed, getCurrentPath());
        }
        // 每次弹出后更新快照（跨线程可见）
        lastSnapshot = new TraceSnapshot(getCurrentPath(), stack.size(),
                stack.stream().mapToLong(e -> System.currentTimeMillis() - e.startTime).max().orElse(0),
                Instant.now());
    }

    public String getCurrentPath() {
        var sb = new StringBuilder();
        var stack = TRACE.get();
        var it = stack.descendingIterator();
        while (it.hasNext()) {
            if (sb.length() > 0) sb.append(" → ");
            sb.append(it.next().name);
        }
        return sb.toString();
    }

    /** 查询最近一次 trace 快照（任意线程可调） */
    public Map<String, Object> getCurrentTrace() {
        var snapshot = lastSnapshot;
        if (snapshot == null) {
            return Map.of("path", "(暂无)", "depth", 0, "elapsedMs", 0);
        }
        return Map.of(
                "path", snapshot.path,
                "depth", snapshot.depth,
                "maxElapsedMs", snapshot.maxElapsedMs,
                "recordedAt", snapshot.recordedAt.toString()
        );
    }

    public void clear() {
        TRACE.get().clear();
    }

    public void remove() {
        TRACE.remove();
    }

    private record TraceEntry(String name, long startTime) {}

    private record TraceSnapshot(String path, int depth, long maxElapsedMs, Instant recordedAt) {}
}
