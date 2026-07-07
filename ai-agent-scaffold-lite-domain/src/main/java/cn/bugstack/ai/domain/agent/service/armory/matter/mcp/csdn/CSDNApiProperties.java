package cn.bugstack.ai.domain.agent.service.armory.matter.mcp.csdn;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "csdn.api")
public class CSDNApiProperties {
    private String cookie;
    private String categories;
}
