package cn.bugstack.ai.domain.agent.model.valobj;

import lombok.Data;

import java.util.List;
import java.util.Map;


@Data
public class AiAgentConfigTableVO {

    /**
     * 应用名称
     */
    private String appName;

    /**
     * 智能体配置
     */
    private Agent agent;

    /**
     * 智能体模块
     */
    private Module module;

    @Data
    public static class Agent {

        /**
         * 智能体ID
         */
        private String agentId;

        /**
         * 智能体名称
         */
        private String agentName;

        /**
         * 智能体描述
         */
        private String agentDesc;

    }

    @Data
    public static class Module {

        private AiApi aiApi;

        private ChatModel chatModel;

        private List<Agent> agents;

        private List<AgentWorkflow> agentWorkflows;

        private Runner runner;

        @Data
        public static class AiApi {
            private String baseUrl;
            private String apiKey;
            private String completionsPath = "/v1/chat/completions";
            private String embeddingsPath = "/v1/embeddings";

        }

        @Data
        public static class ChatModel {

            private String model;

            private List<ToolMcp> toolMcpList;

            private List<ToolSkills> toolSkillsList;

            @Data
            public static class ToolMcp {

                private SSEServerParameters sse;//SSE

                private StdioServerParameters stdio;//标准输入输出

                private LocalParameters local;//本地内嵌

                @Data
                public static class SSEServerParameters {
                    private String name;
                    private String baseUri;
                    private String seeEndpoint;
                    private Integer requestTimeout = 3000;
                }

                @Data
                public static class StdioServerParameters {
                    private String name;
                    private Integer requestTimeout = 3000;
                    private ServerParameters serverParameters;

                    @Data
                    public static class ServerParameters {
                        private String command;//可执行命令
                        private List<String> args;//命令行参数
                        private Map<String, String> env;//环境变量

                    }
                }

                @Data
                public static class LocalParameters {
                    private String name;
                }

            }

            @Data
            public static class ToolSkills {
            //Skills 是指一些预定义的、可被 AI 模型调用的“技能脚本”或“函数库”。
            //通常是一组 Markdown 文件或 JSON Schema，描述工具的使用方法。
                /**
                 * 类型；directory（用户配置的，映射进来的）、resource（放到工程下的）
                 */
                private String type = "directory";

                /**
                 * 路径；
                 */
                private String path;

            }

        }

        @Data
        public static class Agent {
            private String name;
            private String instruction;
            private String description;
            private String outputKey;
            private String outputSchema;  // JSON Schema 校验输出质量
        }

        @Data
        public static class AgentWorkflow {
            /**
             * 类型；loop、parallel、sequential
             */
            private String type;
            private String name;
            private List<String> subAgents;
            private String description;
            private Integer maxIterations = 3;

        }

        @Data
        public static class Runner {
            private String agentName;
            private List<String> pluginNameList;
        }
    }
}
