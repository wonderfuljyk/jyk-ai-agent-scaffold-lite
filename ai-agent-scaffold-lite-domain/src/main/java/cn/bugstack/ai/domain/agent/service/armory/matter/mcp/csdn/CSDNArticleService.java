package cn.bugstack.ai.domain.agent.service.armory.matter.mcp.csdn;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Slf4j
@Service
public class CSDNArticleService {
    @Resource
    private ICSDNPort port;

    @Tool(description = "发布文章到CSDN")
    public ArticleFunctionResponse saveArticle(ArticleFunctionRequest request) throws IOException {
        log.info("CSDN发帖 标题:{} 标签:{}", request.getTitle(), request.getTags());
        return port.writeArticle(request);
    }
}
