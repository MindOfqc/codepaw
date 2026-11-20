// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.codepaw.tui;

import com.codepaw.agent.Agent;
import com.codepaw.agent.AgentEvent;
import com.codepaw.command.CommandRegistry;
import com.codepaw.config.HookConfig;
import com.codepaw.config.McpServerConfig;
import com.codepaw.config.ProviderConfig;
import com.codepaw.conversation.ConversationManager;
import com.codepaw.history.HistoryStore;
import com.codepaw.hook.HookEngine;
import com.codepaw.llm.LlmClient;
import com.codepaw.mcp.McpManager;
import com.codepaw.memory.MemoryManager;
import com.codepaw.memory.MemoryRecall;
import com.codepaw.permission.PermissionChecker;
import com.codepaw.permission.PermissionMode;
import com.codepaw.permission.PermissionResponse;
import com.codepaw.prompt.PromptBuilder;
import com.codepaw.session.SessionManager;
import com.codepaw.skill.SkillCatalog;
import com.codepaw.plan.PlanFile;
import com.codepaw.subagent.AgentTool;
import com.codepaw.subagent.SubAgentProgress;
import com.codepaw.subagent.SubAgentTaskManager;
import com.codepaw.task.TaskList;
import com.codepaw.task.TaskTools;
import com.codepaw.tool.ToolRegistry;
import com.codepaw.tool.impl.AskUserTool;
import com.codepaw.tool.impl.ToolSearchTool;
import com.codepaw.teams.TeammateProgress;
import com.codepaw.tui.dialog.PlanApprovalDialog;

import com.williamcallahan.tui4j.compat.bubbletea.Command;
import com.williamcallahan.tui4j.compat.bubbletea.KeyPressMessage;
import com.williamcallahan.tui4j.compat.bubbletea.Message;
import com.williamcallahan.tui4j.compat.bubbletea.Model;
import com.williamcallahan.tui4j.compat.bubbletea.Program;
import com.williamcallahan.tui4j.compat.bubbletea.QuitMessage;
import com.williamcallahan.tui4j.compat.bubbletea.UpdateResult;
import com.williamcallahan.tui4j.compat.bubbletea.WindowSizeMessage;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * Main TUI model for CodePaw, implementing the Bubble Tea Model interface.
 * <p>
 * P1 MVP: provider selection + streaming chat.
 * Uses a simple inputBuffer with manual key handling (Textarea component deferred to P3).
 */
public class CodePawModel implements Model {

    private static final String VERSION = "CodePaw v0.1.0";

    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    // ── Provider selection ───────────────────────────────────────────────
    private final List<ProviderConfig> providers;
    private final List<McpServerConfig> mcpServers;
    private final List<HookConfig> hookConfigs;
    private HookEngine hookEngine;
    private int providerCursor;
    private ProviderConfig selectedProvider;

    // ── Core state ──────────────────────────────────────────────────────
    private AppState state;
    private LlmClient client;
    private ConversationManager conversation;
    private Agent agent;
    private ToolRegistry registry;
    private PermissionChecker permChecker;

    // ── Chat display ────────────────────────────────────────────────────
    private final List<ChatMessage> chatMessages;
    private final StringBuilder streamBuf;
    private final StringBuilder inputBuffer;
    private boolean streaming;
    private boolean userHasSentMessage;

    // ── Streaming infrastructure ─────────────────────────────────────────
    private BlockingQueue<AgentEvent> agentQueue;
    private CompletableFuture<PermissionResponse> pendingPermission;
    private boolean permDialog;
    private String permToolName;
    private String permDesc;
    private int permCursor;
    private Program program;

    // ── Rewind dialog ───────────────────────────────────────────────────
    private boolean rewindDialog;
    private int rewindPhase;       // 0=snapshot list, 1=restore options
    private int rewindCursor;
    private int rewindOptionCursor;
    private java.util.List<com.codepaw.filehistory.FileHistory.Snapshot> rewindSnapshots;
    private com.codepaw.filehistory.FileHistory fileHistory;

    private static final String[] REWIND_OPTIONS = {
            "Restore code and conversation",
            "Restore conversation only",
            "Restore code only",
            "Never mind"
    };

    // ── AskUser dialog ──────────────────────────────────────────────────
    private final com.codepaw.tui.dialog.AskUserDialog askUserDialogState = new com.codepaw.tui.dialog.AskUserDialog();
    private CompletableFuture<Map<String, String>> askUserFuture;
    private AskUserTool askUserTool;

    // ── Advanced features ─────────────────────────────────────────────
    private McpManager mcpManager;
    private SkillCatalog skillCatalog;
    private TaskList taskList;
    private MemoryManager memoryManager;
    private String instructionsContent = "";
    private String memoryContentField = "";

    // ── Slash menu ────────────────────────────────────────────────────
    private CommandRegistry cmdRegistry;
    private boolean slashMenuOpen;
    private List<com.codepaw.command.Command> slashMatches = new ArrayList<>();
    private int slashCursor;

    // ── Command history ─────────────────────────────────────────────────
    private final HistoryStore historyStore = new HistoryStore();
    private int historyIndex = -1;
    private String historyDraft = "";

    // ── @ file menu ──────────────────────────────────────────────────
    private boolean atMenuOpen;
    private List<String> atMatches = new ArrayList<>();

    private int atCursor;

    // ── Resume ──────────────────────────────────────────────────────────
    private List<com.codepaw.session.SessionManager.SessionInfo> resumeSessions = new ArrayList<>();
    private List<com.codepaw.session.SessionManager.SessionInfo> resumeFiltered = new ArrayList<>();
    private int resumeCursor;
    private String resumeSearch = "";

    // ── Session tracking ────────────────────────────────────────────────
    private String sessionId;

    // ── Plan mode ────────────────────────────────────────────────────
    private PermissionMode prePlanMode = PermissionMode.DEFAULT;
    private final PlanApprovalDialog planApprovalDialog = new PlanApprovalDialog();

    // ── Tool blocks (accumulated during a turn, archived on TurnComplete) ──
    private final List<ChatMessage.ToolBlockInfo> toolBlocks = new ArrayList<>();
    private ChatMessage.SubAgentBlockState activeSubAgent;

    // ── Sub-agent infrastructure ────────────────────────────────────────
    private SubAgentTaskManager subAgentTaskManager;
    private AgentTool agentToolRef;

    // ── Team infrastructure ────────────────────────────────────────────
    private final com.codepaw.teams.TeamManager teamManager = new com.codepaw.teams.TeamManager();
    private final ConcurrentLinkedQueue<SubAgentProgress> subAgentProgressQueue = new ConcurrentLinkedQueue<>();

    // ── MCP status ──────────────────────────────────────────────────────
    private volatile boolean mcpConnecting;
    private boolean pendingSingleProviderInit;
    private volatile String mcpInstructions = "";
    private volatile boolean mcpInstructionsOk;
    private volatile String mcpServerInfo = "";

    // ── Scroll tracking ─────────────────────────────────────────────────
    private int scrollOffset;
    private boolean userScrolled;

    // ── Spinner ─────────────────────────────────────────────────────────
    private static final String[] SPINNER_FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private int spinnerFrame = 0;

    private String thinkingVerb = "";
    private long thinkingStartMs;

    // ── Layout ──────────────────────────────────────────────────────────
    private int width;
    private int height;

    // ── Token counters ──────────────────────────────────────────────────
    private int totalInput;
    private int totalOutput;

    // ── Custom message for agent events ─────────────────────────────────
    public record AgentEventMessage() implements Message {}
    public record MailboxPollMessage() implements Message {}

    public CodePawModel(List<ProviderConfig> providers, List<McpServerConfig> mcpServers,
                        List<HookConfig> hookConfigs) {
        this.providers = providers != null ? providers : List.of();
        this.mcpServers = mcpServers != null ? mcpServers : List.of();
        this.hookConfigs = hookConfigs != null ? hookConfigs : List.of();
        if (this.providers.size() == 1) {
            this.selectedProvider = this.providers.get(0);
            this.state = AppState.CHAT;
            this.pendingSingleProviderInit = true;
        } else {
            this.state = AppState.PROVIDER_SELECT;
        }
        this.chatMessages = new ArrayList<>();
        this.streamBuf = new StringBuilder();
        this.inputBuffer = new StringBuilder();
        this.conversation = new ConversationManager();
        this.cmdRegistry = new CommandRegistry();
        this.historyStore.load();
        this.width = 80;
        this.height = 24;

        // Initialize hook engine
        this.hookEngine = new HookEngine();
        if (!this.hookConfigs.isEmpty()) {
            List<HookEngine.Hook> hooks = this.hookConfigs.stream().map(hc -> {
                HookEngine.EventName event = parseEventName(hc.getEvent());
                HookEngine.ActionType actionType = parseActionType(hc.getType());
                var action = new HookEngine.Action(actionType, hc.getCommand(), hc.getMessage());
                return new HookEngine.Hook(hc.getId(), event, hc.getCondition(), action, hc.isReject());
            }).toList();
            this.hookEngine.loadHooks(hooks);
        }
    }

    private static HookEngine.EventName parseEventName(String s) {
        if (s == null) return HookEngine.EventName.SESSION_START;
        return switch (s.toLowerCase()) {
            case "session_start" -> HookEngine.EventName.SESSION_START;
            case "session_end" -> HookEngine.EventName.SESSION_END;
            case "turn_start" -> HookEngine.EventName.TURN_START;
            case "turn_end" -> HookEngine.EventName.TURN_END;
            case "pre_send" -> HookEngine.EventName.PRE_SEND;
            case "post_receive" -> HookEngine.EventName.POST_RECEIVE;
            case "pre_tool_use" -> HookEngine.EventName.PRE_TOOL_USE;
            case "post_tool_use" -> HookEngine.EventName.POST_TOOL_USE;
            case "shutdown" -> HookEngine.EventName.SHUTDOWN;
            default -> HookEngine.EventName.SESSION_START;
        };
    }

    private static HookEngine.ActionType parseActionType(String s) {
        if (s == null) return HookEngine.ActionType.COMMAND;
        return switch (s.toLowerCase()) {
            case "command" -> HookEngine.ActionType.COMMAND;
            case "script" -> HookEngine.ActionType.SCRIPT;
            case "prompt" -> HookEngine.ActionType.PROMPT;
            default -> HookEngine.ActionType.COMMAND;
        };
    }

    /**
     * Stores a reference to the running Program so we can send messages from
     * background threads (e.g., the streaming poller).
     */
    public void setProgram(Program program) {
        this.program = program;
    }

    /**
     * Called from the SIGINT handler. If streaming, interrupts the response;
     * otherwise sends a QuitMessage to exit the application.
     */
    public void handleSigint() {
        if (streaming) {
            savePartialResponse();
            if (program != null) {
                program.send(new AgentEventMessage());
            }
        } else {
            if (program != null) {
                program.send(new QuitMessage());
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Model interface
    // ────────────────────────────────────────────────────────────────────

    @Override
    public Command init() {
        return Command.checkWindowSize();
    }

    @Override
    public UpdateResult<? extends Model> update(Message msg) {
        if (pendingSingleProviderInit) {
            pendingSingleProviderInit = false;
            initializeProvider();
        }

        // ── Window resize ───────────────────────────────────────────────
        if (msg instanceof WindowSizeMessage wsm) {
            this.width = wsm.width();
            this.height = wsm.height();
            return UpdateResult.from(this);
        }

        // ── Ctrl+C / QuitMessage: interrupt streaming or quit ──────────
        if (msg instanceof QuitMessage) {
            if (streaming) {
                savePartialResponse();
                return UpdateResult.from(this);
            }
        }
        if (msg instanceof KeyPressMessage kpm && kpm.key().equals("ctrl+c")) {
            if (streaming) {
                savePartialResponse();
                return UpdateResult.from(this);
            }
            return UpdateResult.from(this, QuitMessage::new);
        }

        // ── Mouse events ────────────────────────────────────────────────
        if (msg instanceof com.williamcallahan.tui4j.compat.bubbletea.input.MouseMessage mm) {
            var btn = mm.getButton();
            if (btn == com.williamcallahan.tui4j.compat.bubbletea.input.MouseButton.MouseButtonWheelUp) {
                // scroll up — no viewport component, so this is a no-op for now
            } else if (btn == com.williamcallahan.tui4j.compat.bubbletea.input.MouseButton.MouseButtonWheelDown) {
                // scroll down
            }
            return UpdateResult.from(this);
        }

        // ── Agent streaming events ──────────────────────────────────────
        if (msg instanceof AgentEventMessage) {
            if (streaming) spinnerFrame++;
            return handleAgentEvents();
        }

        if (msg instanceof MailboxPollMessage) {
            if (streaming) {
                var poll = Command.tick(Duration.ofSeconds(2), t -> new MailboxPollMessage());
                return UpdateResult.from(this, poll);
            }
            var notes = new java.util.ArrayList<String>();
            notes.addAll(com.codepaw.teams.TeammateRunner.drainLeadMailbox(teamManager));
            if (subAgentTaskManager != null) {
                for (var n : subAgentTaskManager.drainNotifications()) {
                    notes.add("<task-notification>\n<task_id>" + n.taskId() + "</task_id>\n"
                            + "<status>" + n.status() + "</status>\n"
                            + "<result>" + n.output() + "</result>\n</task-notification>");
                }
            }
            if (notes.isEmpty()) {
                var poll = Command.tick(Duration.ofSeconds(2), t -> new MailboxPollMessage());
                return UpdateResult.from(this, poll);
            }
            for (String note : notes) {
                conversation.addSystemReminder(note);
            }
            streaming = true;
            thinkingStartMs = System.currentTimeMillis();
            thinkingVerb = SpinnerVerbs.random();
            streamBuf.setLength(0);
            toolBlocks.clear();
            agentQueue = agent.run(conversation);
            var pollCmd = Command.tick(POLL_INTERVAL, t -> new AgentEventMessage());
            return UpdateResult.from(this, pollCmd);
        }

        // ── Permission dialog ────────────────────────────────────────────
        if (msg instanceof KeyPressMessage kpm && permDialog) {
            return handlePermDialogKey(kpm);
        }

        // ── Rewind dialog ────────────────────────────────────────────────
        if (msg instanceof KeyPressMessage kpm && rewindDialog) {
            return handleRewindKey(kpm);
        }

        // ── Plan approval dialog ────────────────────────────────────────
        if (msg instanceof KeyPressMessage kpm && planApprovalDialog.isActive()) {
            return handlePlanApprovalKey(kpm);
        }

        // ── AskUser dialog ──────────────────────────────────────────────
        if (msg instanceof KeyPressMessage kpm && askUserDialogState.isActive()) {
            return handleAskUserDialogKey(kpm);
        }

        // ── Dispatch to state-specific handler ──────────────────────────
        if (msg instanceof KeyPressMessage kpm) {
            return switch (state) {
                case PROVIDER_SELECT -> handleProviderSelectKey(kpm);
                case CHAT -> handleChatKey(kpm);
                case RESUME -> handleResumeKey(kpm);
            };
        }

        return UpdateResult.from(this);
    }

    @Override
    public String view() {
        return switch (state) {
            case PROVIDER_SELECT -> viewProviderSelect();
            case CHAT -> viewChat();
            case RESUME -> viewResume();
        };
    }

    // ────────────────────────────────────────────────────────────────────
    // Provider selection
    // ────────────────────────────────────────────────────────────────────

    private UpdateResult<CodePawModel> handleProviderSelectKey(KeyPressMessage kpm) {
        String key = kpm.key();
        return switch (key) {
            case "up", "k" -> {
                if (providerCursor > 0) providerCursor--;
                yield UpdateResult.from(this);
            }
            case "down", "j" -> {
                if (providerCursor < providers.size() - 1) providerCursor++;
                yield UpdateResult.from(this);
            }
            case "enter" -> {
                if (providers.isEmpty()) yield UpdateResult.from(this);
                selectedProvider = providers.get(providerCursor);
                initializeProvider();
                state = AppState.CHAT;
                yield UpdateResult.from(this);
            }
            default -> UpdateResult.from(this);
        };
    }

    private void initializeProvider() {
        try {
            String workDir = System.getProperty("user.dir");

            memoryManager = new MemoryManager(workDir);

            var env = PromptBuilder.detectEnvironment(selectedProvider.getModel());
            instructionsContent = MemoryManager.loadInstructions(workDir);
            memoryContentField = buildMemorySection();
            var options = new PromptBuilder.BuildOptions(null);
            String systemPrompt = PromptBuilder.buildSystemPrompt(env, options);

            client = LlmClient.create(selectedProvider, systemPrompt);
            String protocol = selectedProvider.getProtocol();

            registry = ToolRegistry.createDefault();

            registry.register(new ToolSearchTool(registry, protocol));
            var exitPlanTool = new com.codepaw.tool.impl.ExitPlanModeTool();
            exitPlanTool.setIsPlanMode(() -> permChecker != null && permChecker.getMode() == PermissionMode.PLAN);
            exitPlanTool.setPlanExists(() -> com.codepaw.plan.PlanFile.planExists());
            registry.register(exitPlanTool);
            askUserTool = new AskUserTool();
            registry.register(askUserTool);
            agentToolRef = new AgentTool(client, registry, protocol, selectedProvider);
            subAgentTaskManager = new SubAgentTaskManager();
            agentToolRef.setProgressListener(this::handleSubAgentProgress);
            agentToolRef.setTaskManager(subAgentTaskManager);
            registry.register(agentToolRef);

            // Worktree tools and integration
            var worktreeManager = new com.codepaw.worktree.WorktreeManager(
                    workDir, java.util.List.of(), 720);
            agentToolRef.setWorktreeManager(worktreeManager);
            registry.register(new com.codepaw.tool.impl.EnterWorktreeTool(worktreeManager, sessionId));
            registry.register(new com.codepaw.tool.impl.ExitWorktreeTool(worktreeManager));
            // Restore session from previous crash
            var savedSession = com.codepaw.worktree.WorktreeSessionStore.load(workDir);
            if (savedSession != null && java.nio.file.Files.isDirectory(java.nio.file.Path.of(savedSession.worktreePath()))) {
                com.codepaw.worktree.WorktreeSessionStore.restoreSession(savedSession);
            }

            taskList = new TaskList("default", workDir);
            registry.register(new TaskTools.TaskCreateTool(taskList));
            registry.register(new TaskTools.TaskGetTool(taskList));
            registry.register(new TaskTools.TaskListTool(taskList));
            registry.register(new TaskTools.TaskUpdateTool(taskList));

            // Team tools (ch15)
            agentToolRef.setTeamManager(teamManager);
            registry.register(new com.codepaw.teams.TeamTools.TeamCreateTool(teamManager));
            registry.register(new com.codepaw.teams.TeamTools.TeamDeleteTool(teamManager));
            registry.register(new com.codepaw.teams.TeamTools.SendMessageTool(teamManager, "lead"));

            permChecker = new PermissionChecker(
                    PermissionMode.DEFAULT, Path.of(workDir));

            sessionId = com.codepaw.session.SessionManager.newId();
            fileHistory = new com.codepaw.filehistory.FileHistory(workDir, sessionId);
            var fileStateCache = new com.codepaw.tool.FileStateCache();
            for (var t : registry.listTools()) {
                if (t instanceof com.codepaw.tool.impl.EditFileTool ef) { ef.setFileHistory(fileHistory); ef.setFileStateCache(fileStateCache); }
                if (t instanceof com.codepaw.tool.impl.WriteFileTool wf) { wf.setFileHistory(fileHistory); wf.setFileStateCache(fileStateCache); }
                if (t instanceof com.codepaw.tool.impl.ReadFileTool rf) rf.setFileStateCache(fileStateCache);
            }
            agent = new Agent(client, registry, protocol, selectedProvider);
            agent.setFileHistory(fileHistory);
            agent.setInstructions(instructionsContent);
            agent.setMemoryContent(memoryContentField);
            agent.setChecker(permChecker);
            agent.setWorkDir(workDir);
            agent.setSessionId(sessionId);

            // Team notification drain: team mailbox + sub-agent task notifications
            final var teamMgrCapture = teamManager;
            agent.setNotificationFn(() -> {
                var notes = new java.util.ArrayList<String>();
                // Drain team mailbox (ch15)
                notes.addAll(com.codepaw.teams.TeammateRunner.drainLeadMailbox(teamMgrCapture));
                // Drain background task notifications (ch13)
                if (subAgentTaskManager != null) {
                    for (var n : subAgentTaskManager.drainNotifications()) {
                        notes.add("<task-notification>Task %s: %s (%s)</task-notification>"
                                .formatted(n.taskId(), n.name(), n.status()));
                    }
                }
                return notes;
            });

            // Coordinator mode: restrict Lead's tools when teams exist
            agent.setToolNameFilter(name -> {
                if (teamMgrCapture.listTeams().isEmpty()) return true;
                return com.codepaw.teams.Coordinator.isCoordinatorTool(name);
            });

            if (!mcpServers.isEmpty()) {
                mcpConnecting = true;
                final var registryRef = registry;
                Thread.ofVirtual().name("mcp-connect").start(() -> {
                    try {
                        mcpManager = new McpManager(mcpServers);
                        var result = mcpManager.connectAll();
                        for (var t : result.tools()) registryRef.register(t);
                        mcpServerInfo = "Connected to %d MCP server(s), %d tools registered".formatted(
                            result.servers().size(), result.tools().size());
                        synchronized (chatMessages) {
                            for (var e : result.errors()) {
                                chatMessages.add(new ChatMessage("error", "MCP: " + e));
                            }
                        }
                        var mcpParts = new ArrayList<String>();
                        for (var s : result.servers()) {
                            var sb2 = new StringBuilder();
                            sb2.append("## ").append(s.name()).append("\n");
                            if (s.instructions() != null && !s.instructions().isBlank()) {
                                sb2.append(s.instructions()).append("\n");
                            }
                            var toolNames = registryRef.listTools().stream()
                                    .filter(t -> t.name().startsWith("mcp__" + s.name() + "__"))
                                    .map(com.codepaw.tool.Tool::name)
                                    .toList();
                            if (!toolNames.isEmpty()) {
                                sb2.append("\nAvailable tools: ").append(String.join(", ", toolNames));
                            }
                            mcpParts.add(sb2.toString());
                        }
                        if (!mcpParts.isEmpty()) {
                            mcpInstructions = "# MCP Server Instructions\n\n"
                                    + "The following MCP servers are connected. Use their tools when the user asks.\n\n"
                                    + String.join("\n\n", mcpParts);
                        }
                    } catch (Exception e) {
                        synchronized (chatMessages) {
                            chatMessages.add(new ChatMessage("error", "MCP init failed: " + e.getMessage()));
                        }
                    } finally {
                        mcpConnecting = false;
                    }
                });
            }

            skillCatalog = new SkillCatalog();
            var skillDir = Path.of(workDir, ".codepaw", "skills");
            if (java.nio.file.Files.isDirectory(skillDir)) {
                skillCatalog.loadFromDirectory(skillDir);
            }

            wireSkillsToAgent();

            agent.setHookEngine(hookEngine);
            fireHook(HookEngine.EventName.SESSION_START, null, null);

        } catch (Exception e) {
            chatMessages.add(new ChatMessage("error",
                    "Failed to initialize: " + e.getMessage()));
        }
    }

    private void wireSkillsToAgent() {
        if (skillCatalog == null || cmdRegistry == null) return;
        for (var meta : skillCatalog.list()) {
            registerSkillCommand(meta.name());
        }
    }

    private void registerSkillCommand(String name) {
        if (cmdRegistry.find(name).isPresent()) return;
        var skill = skillCatalog.get(name);
        if (skill.isEmpty()) return;

        var meta = skill.get().meta();
        var cmd = new com.codepaw.command.Command(name, meta.description() + " [skill]",
                              new String[]{}, com.codepaw.command.Command.CommandType.PROMPT, false);

        String captured = name;
        cmdRegistry.register(cmd, ctx -> {
            var s = skillCatalog.get(captured);
            if (s.isEmpty()) return "[skill error] not found: " + captured;
            return s.get().promptBody();
        });
    }

    private String renderBanner() {
        String modelName = selectedProvider != null ? selectedProvider.getModel() : "";
        String workDir = System.getProperty("user.dir");
        return Styles.banner.render(" /\\_/\\    ") + Styles.bannerDim.render(VERSION) + "\n" +
               Styles.banner.render("( o.o )   ") + Styles.bannerDim.render(modelName) + "\n" +
               Styles.banner.render(" > ^ <    ") + Styles.bannerDim.render(workDir);
    }

    private String viewProviderSelect() {
        var sb = new StringBuilder();
        sb.append("\n");
        sb.append(renderBanner());
        sb.append("\n\n");
        sb.append(Styles.selectLabel.render("  Select a Provider"));
        sb.append("\n\n");

        for (int i = 0; i < providers.size(); i++) {
            var p = providers.get(i);
            String label = p.getName() + " (" + p.getModel() + ")";
            if (i == providerCursor) {
                sb.append(Styles.selectedItem.render("  ❯ " + label));
            } else {
                sb.append(Styles.normalItem.render("    " + label));
            }
            sb.append("\n");
        }

        sb.append("\n");
        sb.append(Styles.statusBar.render("  j/k to move · enter to select · ctrl+c to quit"));
        sb.append("\n");
        return sb.toString();
    }

    // ────────────────────────────────────────────────────────────────────
    // Chat
    // ────────────────────────────────────────────────────────────────────

