package cn.bugstack.ai.test.api.tool.skills;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ClassPathResource;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;


@Slf4j
public class SpringAiToolTest {

    public static void main(String[] args) {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl("https://apis.itedus.cn")
                .apiKey("sk-L1HhsSyQcQA4ngHHFf87E87f4c624158A3748c7691E57089")
                .completionsPath("v1/chat/completions")
                .embeddingsPath("v1/embeddings")
                .build();

        // https://github.com/spring-ai-community/spring-ai-agent-utils
//        ToolCallback toolCallback01 = SkillsTool.builder()
//                .addSkillsDirectory("/Users/fuzhengwei/coding/gitcode/KnowledgePlanet/road-map/xfg-dev-tech-agent-skills/docs/skills")
//                .build();

        ToolCallback toolCallback02 = SkillsTool.builder()
                .addSkillsResource(new ClassPathResource("agent/skills"))
                .build();

        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-4.1")
                        .toolCallbacks(new ArrayList<>(){{
                            add(toolCallback02);
                        }})
                        .build())
                .build();

        String call = chatModel.call("基于 skill 解答，电脑性能优化");

        log.info("测试结果:{}", call);
    }

}
