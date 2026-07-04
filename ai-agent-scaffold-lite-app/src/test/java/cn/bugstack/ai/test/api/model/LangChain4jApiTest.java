package cn.bugstack.ai.test.api.model;

import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;


@Slf4j
public class LangChain4jApiTest {

    public static void main(String[] args) {
        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl("https://api.laozhang.ai/v1")
                .apiKey("sk-kKgnIC3JZlIe47YGC7773813D04e4b118a58Aa63754c244d")
                //sk-kKgnIC3JZlIe47YGC7773813D04e4b118a58Aa63754c244d
                .modelName("gpt-4o-mini")
                .build();

        String chat = model.chat("hi，你好呀！");
        log.info("测试结果：{}",chat);
    }

}
