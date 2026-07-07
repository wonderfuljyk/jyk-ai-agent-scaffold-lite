package cn.bugstack.ai.domain.agent.service.armory.matter.mcp.csdn;

import java.io.IOException;

public interface ICSDNPort {
    ArticleFunctionResponse writeArticle(ArticleFunctionRequest request) throws IOException;
}
