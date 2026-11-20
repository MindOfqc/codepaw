# CodePaw 架构回顾

本复现将课程中的能力映射到以下源码模块：

- **交互层**：com.codepaw.tui 负责终端对话、渲染和用户确认；CodePaw 启动应用。
- **Agent 引擎**：agent、conversation、prompt 组合模型决策、消息状态和执行循环。
- **模型与工具接入**：llm、tool、mcp、skill 接入模型、本地工具和扩展服务。
- **安全与生命周期**：permission、plan、hook 控制审批、计划模式和钩子。
- **上下文与持久化**：compact、toolresult、memory、session、history 管理上下文、记忆和会话。
- **并行协作**：subagent、task、worktree、teams 支持任务分发、隔离开发和团队协作。

课程推进顺序从模型对话、工具、Agent Loop 开始，再扩展安全、上下文、记忆与多 Agent 能力。第 16 章用于回顾架构演进，不增加新的运行时代码。