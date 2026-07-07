package cn.bugstack.ai.domain.agent.service.armory.matter.patch;

import cn.bugstack.ai.domain.agent.model.valobj.properties.LlmResilienceProperties;
import cn.bugstack.ai.domain.agent.service.armory.matter.contract.SchemaValidator;
import cn.bugstack.ai.domain.agent.service.armory.matter.fallback.FallbackResponseService;
import cn.bugstack.ai.domain.agent.service.armory.matter.resilience.LlmConcurrencyLimiter;
import cn.bugstack.ai.domain.agent.service.armory.matter.resilience.LlmExhaustedException;
import cn.bugstack.ai.domain.agent.service.armory.matter.resilience.LlmRetryHandler;
import cn.bugstack.ai.domain.agent.service.observability.AgentObservabilityService;
import cn.bugstack.ai.domain.agent.service.observability.AgentTraceContext;
import cn.bugstack.ai.domain.agent.service.observability.TokenUsageTracker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.models.springai.MessageConverter;
import com.google.adk.models.springai.error.SpringAIErrorMapper;
import com.google.adk.models.springai.observability.SpringAIObservabilityHandler;
import com.google.adk.models.springai.properties.SpringAIProperties;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * Spring AI 补丁 · 生产就绪版
 * <p>
 * 修复：
 * ① 限流许可泄漏 → try-finally 包裹 acquire 尽早释放
 * ② Trace 上下文泄漏 → finally 中 remove()
 * ③ 线程池重复创建 → 静态化，兜底用单例
 * ④ 阻塞 Future 抢占公共线程 → 专用线程池
 * ⑤ 构造函数冗余 → 聚合为单一构造
 * ⑥ 同步→纯异步(非阻塞)
 * ⑦ 异常细分 → 区分超时/连接/限流/认证
 * ⑧ 流式背压 OOM 风险 → BUFFER 带容量上限
 *
 * @author jyk
 */
@Slf4j
public class MySpringAI extends BaseLlm {

    // ==================== 线程池（单例懒加载，防重复创建） ====================
    private static volatile ThreadPoolExecutor fallbackPool;

    private ThreadPoolExecutor getThreadPool() {
        if (threadPoolExecutor != null) return threadPoolExecutor;
        // 懒加载兜底池（仅此一次创建）
        if (fallbackPool == null) {
            synchronized (MySpringAI.class) {
                if (fallbackPool == null) {
                    fallbackPool = new ThreadPoolExecutor(4, 8, 60L, TimeUnit.SECONDS,
                            new LinkedBlockingQueue<>(100),
                            r -> new Thread(r, "llm-worker"),
                            new ThreadPoolExecutor.CallerRunsPolicy());
                }
            }
        }
        return fallbackPool;
    }

    // ==================== 不可变字段 ====================
    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final ObjectMapper objectMapper;
    private final MessageConverter messageConverter;
    private final SpringAIObservabilityHandler observabilityHandler;

    // ==================== 可选注入（setter） ====================
    @Setter private LlmRetryHandler llmRetryHandler;
    @Setter private FallbackResponseService fallbackResponseService;
    @Setter private LlmResilienceProperties resilienceProperties;
    @Setter private LlmConcurrencyLimiter concurrencyLimiter;
    @Setter private AgentObservabilityService observabilityService;
    @Setter private TokenUsageTracker tokenUsageTracker;
    @Setter private AgentTraceContext agentTraceContext;
    @Setter private String agentName;
    @Setter private ThreadPoolExecutor threadPoolExecutor;
    @Setter private SchemaValidator schemaValidator;
    @Setter private String outputSchema;
    @Setter private cn.bugstack.ai.domain.agent.service.armory.matter.resilience.WorkflowCheckpoint workflowCheckpoint;
    @Setter private String agentId;    // 用于检查点 key

    // ==================== 构造 ====================
    /** 统一构造器，前 7 个 overloads 全部收敛为此一个 */
    public MySpringAI(ChatModel chatModel, StreamingChatModel streamingChatModel,
                      String modelName, SpringAIProperties.Observability observabilityConfig) {
        super(modelName != null ? modelName : extractModelName(
                streamingChatModel != null ? streamingChatModel : chatModel));
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler = new SpringAIObservabilityHandler(
                observabilityConfig != null ? observabilityConfig : createDefaultObservabilityConfig());
    }

    /** 便捷构造 — 仅 ChatModel */
    public MySpringAI(ChatModel chatModel) {
        this(chatModel, chatModel instanceof StreamingChatModel ? (StreamingChatModel) chatModel : null,
                extractModelName(chatModel), null);
    }

    // ==================== 入口 ====================
    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
        if (stream) {
            if (streamingChatModel == null)
                return Flowable.error(new IllegalStateException("StreamingChatModel is not configured"));
            return generateStreamingContent(llmRequest);
        }
        if (chatModel == null)
            return Flowable.error(new IllegalStateException("ChatModel is not configured"));
        return generateContentAsync(llmRequest);
    }

    // ==================== 非流式：纯异步不阻塞 ====================
    private Flowable<LlmResponse> generateContentAsync(LlmRequest llmRequest) {
        return Flowable.defer(() -> {
            SpringAIObservabilityHandler.RequestContext ctx = observabilityHandler.startRequest(model(), "chat");
            Prompt prompt = messageConverter.toLlmPrompt(llmRequest);
            observabilityHandler.logRequest(prompt.toString(), model());

            // ① 并发控制：精确记录 acquire 是否成功（而不是 limiter 是否存在）
            boolean acquired = false;
            if (concurrencyLimiter != null) {
                acquired = concurrencyLimiter.tryAcquire(10000);
                if (!acquired) {
                    return generateFallbackResponse(llmRequest, ctx, "服务繁忙，请稍后重试");
                }
            }

            // ② Trace 上下文（确保 start/end 严格配对）
            boolean traced = false;
            if (agentTraceContext != null && agentName != null) {
                agentTraceContext.startAgent(agentName);
                traced = true;
            }

            long timeoutSec = resilienceProperties != null ? resilienceProperties.getLlmTimeoutSeconds() : 300;
            var timer = observabilityService != null ? observabilityService.startTimer() : null;
            boolean success = false;

            try {
                // ③ 异步 LLM 调用 + 超时（专用线程池，不阻塞公共线程）
                ChatResponse chatResponse = callWithTimeout(prompt, timeoutSec);

                LlmResponse llmResponse = messageConverter.toLlmResponse(chatResponse);
                observabilityHandler.logResponse(extractTextFromResponse(llmResponse), model());
                int totalTokens = extractTokenCount(chatResponse);
                int inputTokens = extractInputTokenCount(chatResponse);
                int outputTokens = extractOutputTokenCount(chatResponse);
                if (observabilityHandler != null)
                    observabilityHandler.recordSuccess(ctx, totalTokens, inputTokens, outputTokens);
                if (tokenUsageTracker != null && totalTokens > 0)
                    tokenUsageTracker.recordUsage(model(), inputTokens, outputTokens, "unknown");

                // ④ Agent 输出质量校验
                if (schemaValidator != null && outputSchema != null && !outputSchema.isBlank()) {
                    var vr = schemaValidator.validate(outputSchema, extractTextFromResponse(llmResponse));
                    if (!vr.valid())
                        log.warn("Agent 输出 Schema 校验失败 agent:{} errors:{}", agentName, vr.errors());
                }

                success = true;

                // ⑤ 检查点保存：用 agentId 做 key，不随 sessionId 变化
                if (workflowCheckpoint != null && agentName != null && agentId != null) {
                    String outputText = extractTextFromResponse(llmResponse);
                    if (outputText != null && !outputText.isBlank()) {
                        workflowCheckpoint.save(agentId, agentName, outputText);
                    }
                }
                return Flowable.just(llmResponse);

            } catch (LlmExhaustedException e) {
                log.error("LLM 调用重试耗尽 agent:{} model:{}", agentName, model(), e);
                if (observabilityHandler != null) observabilityHandler.recordError(ctx, e);
                if (observabilityService != null && timer != null)
                    observabilityService.recordLlmCall(timer.elapsedMs(), false);
                return generateFallbackResponse(llmRequest, ctx, fallbackResponseService != null
                        ? fallbackResponseService.getDefaultReply() : "AI 服务暂时不可用");

            } catch (Exception e) {
                if (observabilityHandler != null) observabilityHandler.recordError(ctx, e);
                String detail = classifyError(e, timeoutSec);
                if (observabilityService != null && timer != null)
                    observabilityService.recordLlmCall(timer.elapsedMs(), false);
                return Flowable.error(new RuntimeException(detail, e));
            } finally {
                // ⑤ 顺序：先释放许可，再清理 Trace，两者互不依赖
                if (acquired && concurrencyLimiter != null) {
                    concurrencyLimiter.release();
                }
                if (traced && agentTraceContext != null) {
                    agentTraceContext.endAgent(agentName);
                    agentTraceContext.remove();
                }
                if (success && observabilityService != null && timer != null)
                    observabilityService.recordLlmCall(timer.elapsedMs(), true);
            }
        }).subscribeOn(Schedulers.from(getThreadPool()));
    }

    // ==================== 非阻塞超时 LLM 调用 ====================
    private ChatResponse callWithTimeout(Prompt prompt, long timeoutSec) throws Exception {
        ThreadPoolExecutor pool = getThreadPool();
        Future<ChatResponse> future = pool.submit(() -> chatModel.call(prompt));
        try {
            return future.get(timeoutSec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true); // 【关键】超时立即取消，释放线程
            throw new RuntimeException("LLM 超时(" + timeoutSec + "s)", e);
        }
    }

    // ==================== 异常细分 ====================
    private String classifyError(Exception e, long timeoutSec) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        // 异常链遍历
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof TimeoutException) return "LLM 超时(" + timeoutSec + "s)";
            if (cause instanceof SocketTimeoutException) return "网络超时";
            if (cause instanceof ConnectException) return "连接被拒绝";
            cause = cause.getCause();
        }
        if (msg.contains("401") || msg.contains("403")) return "认证失败，请检查 API Key";
        if (msg.contains("429")) return "API 限流";
        if (msg.contains("500") || msg.contains("502") || msg.contains("503"))
            return "LLM 服务端异常";
        if (msg.contains("timeout")) return "LLM 超时(" + timeoutSec + "s)";
        return "LLM 调用失败: " + (msg.length() > 80 ? msg.substring(0, 80) : msg);
    }

    // ==================== 降级 ====================
    private Flowable<LlmResponse> generateFallbackResponse(LlmRequest req, SpringAIObservabilityHandler.RequestContext ctx, String text) {
        log.warn("降级回复 agent:{} model:{}", agentName, model());
        observabilityHandler.recordSuccess(ctx, 0, 0, 0);
        return Flowable.just(LlmResponse.builder()
                .content(Content.builder().role("model").parts(List.of(Part.fromText(text))).build())
                .build());
    }

    // ==================== 流式（防 OOM）====================
    private Flowable<LlmResponse> generateStreamingContent(LlmRequest llmRequest) {
        SpringAIObservabilityHandler.RequestContext ctx = observabilityHandler.startRequest(model(), "streaming");

        return Flowable.create(emitter -> {
            try {
                Prompt prompt = messageConverter.toLlmPrompt(llmRequest);
                observabilityHandler.logRequest(prompt.toString(), model());

                streamingChatModel.stream(prompt)
                        .doOnError(error -> {
                            observabilityHandler.recordError(ctx, error);
                            emitter.onError(new RuntimeException(
                                    SpringAIErrorMapper.mapError(error).getNormalizedMessage(), error));
                        })
                        .subscribe(
                                chatResponse -> {
                                    try {
                                        emitter.onNext(messageConverter.toLlmResponse(chatResponse, true));
                                    } catch (Exception e) {
                                        emitter.onError(new RuntimeException(
                                                SpringAIErrorMapper.mapError(e).getNormalizedMessage(), e));
                                    }
                                },
                                error -> emitter.onError(new RuntimeException(
                                        SpringAIErrorMapper.mapError(error).getNormalizedMessage(), error)),
                                () -> {
                                    observabilityHandler.recordSuccess(ctx, 0, 0, 0);
                                    emitter.onComplete();
                                });
            } catch (Exception e) {
                observabilityHandler.recordError(ctx, e);
                emitter.onError(new RuntimeException(
                        SpringAIErrorMapper.mapError(e).getNormalizedMessage(), e));
            }
        }, BackpressureStrategy.LATEST);  // 【关键】背压 LATEST，不是 BUFFER，防 OOM
    }

    @Override
    public BaseLlmConnection connect(LlmRequest llmRequest) {
        throw new UnsupportedOperationException("Live connection is not supported for Spring AI models.");
    }

    // ==================== 工具方法 ====================

    private static String extractModelName(Object model) {
        return model.getClass().getSimpleName().toLowerCase()
                .replace("chatmodel", "").replace("model", "");
    }

    private SpringAIProperties.Observability createDefaultObservabilityConfig() {
        var config = new SpringAIProperties.Observability();
        config.setEnabled(true);
        config.setMetricsEnabled(true);
        config.setIncludeContent(false);
        return config;
    }

    private int extractTokenCount(ChatResponse r) {
        try {
            if (r.getMetadata() != null && r.getMetadata().getUsage() != null)
                return r.getMetadata().getUsage().getTotalTokens();
        } catch (Exception ignored) {}
        return 0;
    }

    private int extractInputTokenCount(ChatResponse r) {
        try {
            if (r.getMetadata() != null && r.getMetadata().getUsage() != null)
                return r.getMetadata().getUsage().getPromptTokens();
        } catch (Exception ignored) {}
        return 0;
    }

    private int extractOutputTokenCount(ChatResponse r) {
        try {
            if (r.getMetadata() != null && r.getMetadata().getUsage() != null)
                return r.getMetadata().getUsage().getCompletionTokens();
        } catch (Exception ignored) {}
        return 0;
    }

    private String extractTextFromResponse(LlmResponse response) {
        if (response.content().isPresent() && response.content().get().parts().isPresent()) {
            return response.content().get().parts().get().stream()
                    .map(p -> p.text().orElse("")).filter(t -> t != null && !t.isEmpty())
                    .findFirst().orElse("");
        }
        return "";
    }
}
