# 任务清单：带关键视频帧的 Markdown 笔记

规格来源：`spec.md`（验收标准 AC1-AC8、决策 D1-D6）
验证结果：`verify.md`（30 条断言全过；未验证项见该文件第 7 节）

状态标记：[x] 已完成　[ ] 未完成

## T1 — 新增渲染器（纯逻辑，零外部依赖）[x]

- 文件：`server/src/main/java/com/example/server/dto/VideoNoteRenderer.java`
- 内容：
  - `static String render(VideoContext context, AnalysisResult result)`
  - `static String renderNotes(VideoContext context)` —— 只渲染时间顺序笔记章节
  - 私有：`formatTime(long)`、`frameFor(VideoContext, long)`、`firstFrame(VideoSegment)`
- 约束：不 import 任何 Spring / MinIO / HTTP 类；AC8
- 覆盖：AC1、AC2、AC3、AC4、AC6、AC7

## T2 — 分析结果证据附帧 [x]

- 在 `renderAnalysis()` 内对每条 evidence 调 `segmentOf(context, evidence.timestampMs())`，命中则在其下方输出 `![](url)`
- 未命中不输出（AC5）
- 同一张帧在整个分析结果里只贴一次（`postedFrames` 记录已贴过的 URL）

## T3 — 接入持久化产物 [x]

- 文件：`server/src/main/java/com/example/server/service/AiService.java`
- `persistResult(...)` 改用 `VideoNoteRenderer.render(context, agentState.result())`（`context` 由 `checkpointService.loadContext(mediaId)` 取）
- `followUp(...)` 返回处改用 `VideoNoteRenderer.renderAnalysis(followUpContext, state.result())`
- `reviseAndRerun(...)` 返回处同样改用 `renderAnalysis(revisedContext, state.result())`，避免同一份分析结果走两条渲染路径
- 覆盖：让 `MediaFile.aiSummary` 落库的就是带帧笔记

## T4 — 验证（离线可执行）[x]

- 纯逻辑编译：`javac` 单独编译 `VideoContext` / `AnalysisResult` / `VideoNoteRenderer` + 断言程序（无外部依赖）
- 断言覆盖：AC1-AC7 逐条，共 30 条，全部通过
- 记录：无法执行 `mvn test`（无 JDK 21、无依赖缓存、pom 无测试依赖），无法运行时验证（无 API Key）

## T5 — 归档 [x]

- 验证结果写入 `specs/001-video-frame-notes/verify.md`（方法、命令、结果、未能验证项）

## 不做（见 spec 非目标）

视觉段重构 / 稳定帧 / 前景过滤 / 帧分类 / 每节 LLM 摘要
