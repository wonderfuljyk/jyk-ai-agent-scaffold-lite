package cn.bugstack.ai.test.api.model;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.expression.spel.ast.OpPlus;

@Slf4j
public class SpringAiApiTest {

    public static void main(String[] args) {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl("https://api.laozhang.ai")
                .apiKey("sk-kKgnIC3JZlIe47YGC7773813D04e4b118a58Aa63754c244d")
                .completionsPath("/v1/chat/completions")
                .embeddingsPath("/v1/embeddings")
                .build();

        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-4o-mini")
                        .build())
                .build();

        String call = chatModel.call("叫爸爸");

        log.info("测试结果: {}",call);
    }

}
