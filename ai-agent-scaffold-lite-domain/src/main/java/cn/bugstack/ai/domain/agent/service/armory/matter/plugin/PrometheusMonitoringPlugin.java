package cn.bugstack.ai.domain.agent.service.armory.matter.plugin;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
// 【关键修复 1】：导入正确的 genai Content
import com.google.genai.types.Content;
import io.micrometer.core.instrument.MeterRegistry;
import io.reactivex.rxjava3.core.Maybe;
import lombok.extern.slf4j.Slf4j; // 【新增】：导入 lombok 的 slf4j
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * ClassName: PrometheusMonitoringPlugin
 * Package: cn.bugstack.ai.domain.agent.service.armory.matter.plugin
 * Description: Prometheus 监控插件
 *
 * @Author best jyk
 * @Create 2026/5/12
 * @Version 1.0
 */
@Slf4j
@Service("prometheusMonitoringPlugin")
public class PrometheusMonitoringPlugin extends BasePlugin {

    @Autowired(required = false)
    private MeterRegistry registry;

    private final ThreadLocal<Long> startTime = new ThreadLocal<>();

    // 【关键修复 2】：提供无参构造函数，让 Spring 能够成功实例化
    public PrometheusMonitoringPlugin() {
        super("prometheusMonitoringPlugin");
    }

    public PrometheusMonitoringPlugin(String name) {
        super(name);
    }

    @Override
    public Maybe<Content> beforeAgentCallback(BaseAgent agent, CallbackContext context) {
        // 记录开始时间
        startTime.set(System.currentTimeMillis());

        // 增加调用次数计数
        if (registry != null) {
            registry.counter("agent.invocations", "agent_name", agent.name()).increment();
        }
        return super.beforeAgentCallback(agent, context);
    }

    @Override
    public Maybe<LlmResponse> afterModelCallback(CallbackContext context, LlmResponse response) {
        // 记录大模型耗时
        if (startTime.get() != null) {
            long duration = System.currentTimeMillis() - startTime.get();

            // 【新增】：大声喊出监控到的时间！
            log.info("【Prometheus监控】拦截到大模型调用完成，精确耗时: {} ms", duration);

            if (registry != null) {
                registry.timer("agent.model.latency").record(Duration.ofMillis(duration));
            }
            startTime.remove(); // 防止线程池复用导致内存泄漏
        }

       /* // 记录 Token 消耗 (待后续确认 SDK Token 获取方式后再打开)
        if (response.usage().isPresent()) {
            registry.counter("agent.tokens.prompt").increment(response.usage().get().promptTokens());
            registry.counter("agent.tokens.completion").increment(response.usage().get().completionTokens());
        }*/
        return super.afterModelCallback(context, response);
    }
}