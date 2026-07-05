package cn.bugstack.ai.domain.agent.service.armory.matter.patch;

import cn.bugstack.ai.domain.agent.model.valobj.properties.LlmResilienceProperties;
import cn.bugstack.ai.domain.agent.service.armory.matter.fallback.FallbackResponseService;
import cn.bugstack.ai.domain.agent.service.armory.matter.resilience.LlmConcurrencyLimiter;
import cn.bugstack.ai.domain.agent.service.armory.matter.resilience.LlmExhaustedException;
import cn.bugstack.ai.domain.agent.service.armory.matter.resilience.LlmRetryHandler;
import cn.bugstack.ai.domain.agent.service.observability.AgentObservabilityService;
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
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;


@Slf4j
public class MySpringAI extends BaseLlm {

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final ObjectMapper objectMapper;
    private final MessageConverter messageConverter;
    private final SpringAIObservabilityHandler observabilityHandler;

    /** LLM 重试处理器（setter 注入，可选） */
    @Setter
    private LlmRetryHandler llmRetryHandler;

    /** 降级回复服务（setter 注入，可选） */
    @Setter
    private FallbackResponseService fallbackResponseService;

    /** 容错配置属性（setter 注入，可选） */
    @Setter
    private LlmResilienceProperties resilienceProperties;

    /** LLM 并发控制（setter 注入，可选） */
    @Setter
    private LlmConcurrencyLimiter concurrencyLimiter;

    /** 可观测性服务（setter 注入，可选） */
    @Setter
    private AgentObservabilityService observabilityService;

    @Setter
    private TokenUsageTracker tokenUsageTracker;

    @Setter
    private cn.bugstack.ai.domain.agent.service.observability.AgentTraceContext agentTraceContext;

    @Setter
    private String agentName;

    @Setter
    private java.util.concurrent.ThreadPoolExecutor threadPoolExecutor;

    @Setter
    private cn.bugstack.ai.domain.agent.service.armory.matter.contract.SchemaValidator schemaValidator;

    @Setter
    private String outputSchema;

    public MySpringAI(ChatModel chatModel) {
        super(extractModelName(chatModel));
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel =
                (chatModel instanceof StreamingChatModel) ? (StreamingChatModel) chatModel : null;
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
    }

    public MySpringAI(ChatModel chatModel, String modelName) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel =
                (chatModel instanceof StreamingChatModel) ? (StreamingChatModel) chatModel : null;
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
    }

    public MySpringAI(StreamingChatModel streamingChatModel) {
        super(extractModelName(streamingChatModel));
        this.chatModel =
                (streamingChatModel instanceof ChatModel) ? (ChatModel) streamingChatModel : null;
        this.streamingChatModel =
                Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
    }

    public MySpringAI(StreamingChatModel streamingChatModel, String modelName) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel =
                (streamingChatModel instanceof ChatModel) ? (ChatModel) streamingChatModel : null;
        this.streamingChatModel =
                Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
    }

    public MySpringAI(ChatModel chatModel, StreamingChatModel streamingChatModel, String modelName) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel =
                Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
    }

    public MySpringAI(
            ChatModel chatModel,
            StreamingChatModel streamingChatModel,
            String modelName,
            SpringAIProperties.Observability observabilityConfig) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel =
                Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(
                        Objects.requireNonNull(observabilityConfig, "observabilityConfig cannot be null"));
    }

    public MySpringAI(
            ChatModel chatModel, String modelName, SpringAIProperties.Observability observabilityConfig) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel =
                (chatModel instanceof StreamingChatModel) ? (StreamingChatModel) chatModel : null;
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(
                        Objects.requireNonNull(observabilityConfig, "observabilityConfig cannot be null"));
    }

    public MySpringAI(
            StreamingChatModel streamingChatModel,
            String modelName,
            SpringAIProperties.Observability observabilityConfig) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel =
                (streamingChatModel instanceof ChatModel) ? (ChatModel) streamingChatModel : null;
        this.streamingChatModel =
                Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(
                        Objects.requireNonNull(observabilityConfig, "observabilityConfig cannot be null"));
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
        if (stream) {
            if (this.streamingChatModel == null) {
                return Flowable.error(new IllegalStateException("StreamingChatModel is not configured"));
            }

            return generateStreamingContent(llmRequest);
        } else {
            if (this.chatModel == null) {
                return Flowable.error(new IllegalStateException("ChatModel is not configured"));
            }

            return generateContent(llmRequest);
        }
    }

    private Flowable<LlmResponse> generateContent(LlmRequest llmRequest) {
        // defer 延迟执行 + subscribeOn 异步线程，让 ParallelAgent 真正并行
        return Flowable.defer(() -> {
        SpringAIObservabilityHandler.RequestContext context =
                observabilityHandler.startRequest(model(), "chat");

        Prompt prompt = messageConverter.toLlmPrompt(llmRequest);
        observabilityHandler.logRequest(prompt.toString(), model());

        // 链路追踪：记录 Agent 执行开始
        if (agentTraceContext != null && agentName != null) {
            agentTraceContext.startAgent(agentName);
        }

        // 并发控制：获取信号量许可
        boolean acquired = false;
        if (concurrencyLimiter != null) {
            acquired = concurrencyLimiter.tryAcquire(10000);
            if (!acquired) {
                throw new RuntimeException("LLM 并发已满，请稍后重试");
            }
        }

        var timer = observabilityService != null ? observabilityService.startTimer() : null;
        boolean success = false;
        try {
                // 带重试 + 超时的 LLM 调用
                long timeoutSec = resilienceProperties != null
                    ? resilienceProperties.getLlmTimeoutSeconds() : 30;

            ChatResponse chatResponse;
            if (llmRetryHandler != null) {
                chatResponse = llmRetryHandler.executeWithRetry(
                        () -> {
                            try {
                                return java.util.concurrent.CompletableFuture
                                        .supplyAsync(() -> chatModel.call(prompt))
                                        .get(timeoutSec, java.util.concurrent.TimeUnit.SECONDS);
                            } catch (java.util.concurrent.TimeoutException e) {
                                throw new RuntimeException("LLM 调用超时 (" + timeoutSec + "s)", e);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }, model());
            } else {
                try {
                    chatResponse = java.util.concurrent.CompletableFuture
                            .supplyAsync(() -> chatModel.call(prompt))
                            .get(timeoutSec, java.util.concurrent.TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    throw new RuntimeException("LLM 调用超时 (" + timeoutSec + "s)", e);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            LlmResponse llmResponse = messageConverter.toLlmResponse(chatResponse);
            observabilityHandler.logResponse(extractTextFromResponse(llmResponse), model());
            int totalTokens = extractTokenCount(chatResponse);
            int inputTokens = extractInputTokenCount(chatResponse);
            int outputTokens = extractOutputTokenCount(chatResponse);
            observabilityHandler.recordSuccess(context, totalTokens, inputTokens, outputTokens);
            if (tokenUsageTracker != null && totalTokens > 0) {
                tokenUsageTracker.recordUsage(model(), inputTokens, outputTokens, "unknown");
            }
            // Agent 输出质量校验
            if (schemaValidator != null && outputSchema != null && !outputSchema.isBlank()) {
                String outputText = extractTextFromResponse(llmResponse);
                var validationResult = schemaValidator.validate(outputSchema, outputText);
                if (!validationResult.valid()) {
                    log.warn("Agent 输出 Schema 校验失败 agent:{} errors:{}", agentName, validationResult.errors());
                }
            }
            success = true;
            return Flowable.just(llmResponse);

        } catch (LlmExhaustedException e) {
            log.error("LLM 调用重试耗尽，触发降级 model:{}", model(), e);
            observabilityHandler.recordError(context, e);
            if (observabilityService != null) observabilityService.recordLlmCall(
                    timer != null ? timer.elapsedMs() : 0, false);
            return generateFallbackResponse(llmRequest, context);

        } catch (Exception e) {
            observabilityHandler.recordError(context, e);
            SpringAIErrorMapper.MappedError mappedError = SpringAIErrorMapper.mapError(e);
            if (observabilityService != null) observabilityService.recordLlmCall(
                    timer != null ? timer.elapsedMs() : 0, false);
            return Flowable.error(new RuntimeException(mappedError.getNormalizedMessage(), e));
        } finally {
            if (agentTraceContext != null && agentName != null) {
                agentTraceContext.endAgent(agentName);
            }
            if (acquired && concurrencyLimiter != null) concurrencyLimiter.release();
            if (success && observabilityService != null && timer != null)
                observabilityService.recordLlmCall(timer.elapsedMs(), true);
        }
        }).subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.from(
                threadPoolExecutor != null ? threadPoolExecutor
                        : java.util.concurrent.Executors.newFixedThreadPool(4)));  // 复用项目线程池
    }

    /**
     * 降级兜底响应
     * 当 LLM 重试耗尽后，返回预设的兜底文案
     */
    private Flowable<LlmResponse> generateFallbackResponse(
            LlmRequest llmRequest, SpringAIObservabilityHandler.RequestContext context) {

        String fallbackText = fallbackResponseService != null
                ? fallbackResponseService.getDefaultReply()
                : "抱歉，AI 服务暂时不可用，请稍后重试。";

        log.warn("返回降级兜底回复 model:{}", model());

        LlmResponse fallbackResponse = LlmResponse.builder()
                .content(Content.builder()
                        .role("model")
                        .parts(List.of(Part.fromText(fallbackText)))
                        .build())
                .build();

        observabilityHandler.recordSuccess(context, 0, 0, 0);
        return Flowable.just(fallbackResponse);
    }

    private Flowable<LlmResponse> generateStreamingContent(LlmRequest llmRequest) {
        SpringAIObservabilityHandler.RequestContext context =
                observabilityHandler.startRequest(model(), "streaming");

        return Flowable.create(
                emitter -> {
                    try {
                        Prompt prompt = messageConverter.toLlmPrompt(llmRequest);
                        observabilityHandler.logRequest(prompt.toString(), model());

                        Flux<ChatResponse> responseFlux = streamingChatModel.stream(prompt);

                        responseFlux
                                .doOnError(
                                        error -> {
                                            observabilityHandler.recordError(context, error);
                                            SpringAIErrorMapper.MappedError mappedError =
                                                    SpringAIErrorMapper.mapError(error);
                                            emitter.onError(
                                                    new RuntimeException(mappedError.getNormalizedMessage(), error));
                                        })
                                .subscribe(
                                        chatResponse -> {
                                            try {
                                                // Use enhanced streaming-aware conversion
                                                LlmResponse llmResponse =
                                                        messageConverter.toLlmResponse(chatResponse, true);
                                                emitter.onNext(llmResponse);
                                            } catch (Exception e) {
                                                observabilityHandler.recordError(context, e);
                                                SpringAIErrorMapper.MappedError mappedError =
                                                        SpringAIErrorMapper.mapError(e);
                                                emitter.onError(
                                                        new RuntimeException(mappedError.getNormalizedMessage(), e));
                                            }
                                        },
                                        error -> {
                                            observabilityHandler.recordError(context, error);
                                            SpringAIErrorMapper.MappedError mappedError =
                                                    SpringAIErrorMapper.mapError(error);
                                            emitter.onError(
                                                    new RuntimeException(mappedError.getNormalizedMessage(), error));
                                        },
                                        () -> {
                                            // Record success for streaming completion
                                            observabilityHandler.recordSuccess(context, 0, 0, 0);
                                            emitter.onComplete();
                                        });
                    } catch (Exception e) {
                        observabilityHandler.recordError(context, e);
                        SpringAIErrorMapper.MappedError mappedError = SpringAIErrorMapper.mapError(e);
                        emitter.onError(new RuntimeException(mappedError.getNormalizedMessage(), e));
                    }
                },
                BackpressureStrategy.BUFFER);
    }

    @Override
    public BaseLlmConnection connect(LlmRequest llmRequest) {
        throw new UnsupportedOperationException(
                "Live connection is not supported for Spring AI models.");
    }

    private static String extractModelName(Object model) {
        // Spring AI models may not always have a straightforward way to get model name
        // This is a fallback that can be overridden by providing explicit model name
        String className = model.getClass().getSimpleName();
        return className.toLowerCase().replace("chatmodel", "").replace("model", "");
    }

    private SpringAIProperties.Observability createDefaultObservabilityConfig() {
        SpringAIProperties.Observability config = new SpringAIProperties.Observability();
        config.setEnabled(true);
        config.setMetricsEnabled(true);
        config.setIncludeContent(false);
        return config;
    }

    private int extractTokenCount(ChatResponse chatResponse) {
        // Spring AI may include usage metadata in the response
        // This is a simplified implementation - actual token counts depend on provider
        try {
            if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                return chatResponse.getMetadata().getUsage().getTotalTokens();
            }
        } catch (Exception e) {
            // Ignore errors in token extraction
        }
        return 0;
    }

    private int extractInputTokenCount(ChatResponse chatResponse) {
        try {
            if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                return chatResponse.getMetadata().getUsage().getPromptTokens();
            }
        } catch (Exception e) {
            // Ignore errors in token extraction
        }
        return 0;
    }

    private int extractOutputTokenCount(ChatResponse chatResponse) {
        try {
            if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                return chatResponse.getMetadata().getUsage().getCompletionTokens();
            }
        } catch (Exception e) {
            // Ignore errors in token extraction
        }
        return 0;
    }

    private String extractTextFromResponse(LlmResponse response) {
        if (response.content().isPresent() && response.content().get().parts().isPresent()) {
            return response.content().get().parts().get().stream()
                    .map(part -> part.text().orElse(""))
                    .filter(text -> text != null && !text.isEmpty())
                    .findFirst()
                    .orElse("");
        }
        return "";
    }
    
}
