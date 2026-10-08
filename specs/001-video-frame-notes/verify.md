# 验证记录：带关键视频帧的 Markdown 笔记

规格：`spec.md`（AC1-AC8）｜任务：`tasks.md`（T1-T5）
被验证代码：

- `server/src/main/java/com/example/server/dto/VideoNoteRenderer.java`（本次新增）
- `server/src/main/java/com/example/server/service/AiService.java`（本次改动 3 处）
- `server/src/main/java/com/example/server/dto/AnalysisResult.java`（未改动，AC7 要求其行为不变）

---

## 1. 环境约束：哪些验证做不了，为什么

| 想做的验证 | 能否执行 | 原因 |
|---|---|---|
| `mvn test` | 不能 | 项目 `pom.xml` 未引入任何测试依赖（无 JUnit）；`server/src/test` 目录不存在 |
| `mvn compile` | 不能 | 本机只有 JDK 17.0.19，项目 `pom.xml` 要求 JDK 21；本机无 `~/.m2` 依赖缓存，无法下载 Spring / MyBatis-Plus 等依赖 |
| 启动应用跑一次真实解析 | 不能 | 需要 SiliconFlow ASR、DeepSeek LLM、MinIO、RocketMQ、Redis 的可用实例与 API Key，本机均无 |

因此改用**离线验证**：只编译不依赖任何第三方库的三个类，外加一个断言程序。

---

## 2. 方法

把 `VideoContext` / `AnalysisResult` / `VideoNoteRenderer` 三个类单独编译。这三个类的 import 只有 `java.util.*`，不需要 Spring、MinIO、HTTP 客户端任何一个。编译能过，就说明 `VideoNoteRenderer` 没有引入外部依赖（对应 AC8）。

断言程序：`specs/001-video-frame-notes/offline/NoteRenderCheck.java`。

## 3. 命令

在仓库根目录执行：

```bash
mkdir -p specs/001-video-frame-notes/offline/out

javac -encoding UTF-8 -d specs/001-video-frame-notes/offline/out \
  server/src/main/java/com/example/server/dto/VideoContext.java \
  server/src/main/java/com/example/server/dto/AnalysisResult.java \
  server/src/main/java/com/example/server/dto/VideoNoteRenderer.java \
  specs/001-video-frame-notes/offline/NoteRenderCheck.java

java -Dfile.encoding=UTF-8 -cp specs/001-video-frame-notes/offline/out \
  com.example.server.dto.NoteRenderCheck
```

看产物长什么样（不跑断言，只打印一份 Markdown）：

```bash
java -Dfile.encoding=UTF-8 -cp specs/001-video-frame-notes/offline/out \
  com.example.server.dto.NoteRenderCheck sample
```

Windows 下必须用 `-encoding UTF-8`，否则 JDK 17 按 GBK 读源文件会报编码错误。

## 4. 结果

```
passed=30 failed=0
```

30 条断言全部通过。逐条对应关系：

| 验收标准 | 断言 | 结果 |
|---|---|---|
| AC1 含帧的窗输出恰好一张 `![](url)`，URL 取自 `evidenceFrames` | `AC1 three pages` / `AC1 one image per page` / `AC1 url from evidenceFrames` | 通过 |
| AC2 无帧的窗不输出图片语法 | `AC2 no image syntax` / `AC2 keeps text` / `AC2 no empty link` / `AC2 frameless window merged into previous page` | 通过 |
| AC3 相邻窗代表帧相同只输出一次 | `AC3 single page` / `AC3 single image` / `AC3 merged range` / `AC3 merged transcript` / `AC3 ocr dedup` | 通过 |
| AC4 时间格式 `mm:ss`，区间与 segment 一致 | `AC4 time format` / `AC4 no mm:ss drift` | 通过 |
| AC5 证据附帧，查不到不附，同窗同帧只贴一次 | `AC5 evidence with frame` / `AC5 second window frame` / `AC5 same window posts once` / `AC5 out of range posts nothing` / `AC5 boundary endMs excluded` | 通过 |
| AC6 纯函数，null / 空集合不抛异常 | `AC6 pure function` / `AC6 null context no crash` / `AC6 null context no notes` / `AC6 empty segments` / `AC6 empty segments full render` / `AC6 null result returns notes only` / `AC6 null result no leading blank` / `AC6 blank frames ignored` / `AC6 blank frames no image` | 通过 |
| AC7 `AnalysisResult.toMarkdown()` 行为不变且不含图片 | `AC7 toMarkdown unchanged` / `AC7 toMarkdown has no image` | 通过 |
| AC8 渲染不触发外部 IO | 由「只给这三个源文件、classpath 只有 JDK 就编译通过」证明 | 通过 |

`AC5 boundary endMs excluded` 说明：区间判定用的是 `[startMs, endMs)`。时间戳 `60000` 落在 `[60000, 120000)` 这个窗，不落在 `[0, 60000)`，所以取后一个窗的帧。

## 5. 样例产物

`sample` 模式的实际输出（第四窗与第三窗帧相同，画面没变，因此并入第三页）：

```markdown
## 二叉树遍历讲解

## 核心结论
- 课程按前序、中序两个顺序展开，各配一页板书

## 视频证据
- [01:02] ASR：前序遍历先访问根节点
  ![](https://minio/frame_000312.jpg)
- [02:05] OCR：中序遍历：左子树、根节点、右子树
  ![](https://minio/frame_000688.jpg)

## 建议
- 复习后序遍历

## 视频笔记（按时间顺序）

### [00:00 - 01:00]
![](https://minio/frame_000001.jpg)
- 画面文字：第 1 页 二叉树
- 讲解：今天我们讲二叉树的遍历

### [01:00 - 02:00]
![](https://minio/frame_000312.jpg)
- 画面文字：前序遍历：根节点、左子树、右子树
- 讲解：前序遍历先访问根节点

### [02:00 - 04:00]
![](https://minio/frame_000688.jpg)
- 画面文字：中序遍历：左子树、根节点、右子树
- 讲解：再看中序遍历，它先访问左子树 这页停留了很久，画面没变
```

---

## 6. AiService 的改动：只做了静态核对

`AiService.java` 依赖 Spring、MyBatis-Plus、slf4j，本机编译不了，所以这部分没有跑编译。做了两件事：

### 6.1 语法层面解析

```bash
javac -encoding UTF-8 -XDrawDiagnostics -d /tmp/aicheck \
  server/src/main/java/com/example/server/service/AiService.java
```

诊断类型统计：

```
52  compiler.err.cant.resolve.location
26  compiler.err.doesnt.exist
 1  compiler.err.cant.resolve
```

全部是「包不存在 / 找不到符号」，没有出现 `expected` / `illegal` / `not a statement` / `reached end of file` 这类语法错误。说明文件语法是完整的，报的错都来自缺依赖。

### 6.2 三处改动的符号核对

| 位置 | 改动 | 核对项 |
|---|---|---|
| `persistResult` | `mediaFile.setAiSummary(VideoNoteRenderer.render(context, agentState.result()))` | 该行上方新增 `VideoContext context = checkpointService.loadContext(mediaFile.getId());`，`context` 为方法内局部变量，类型匹配 `render(VideoContext, AnalysisResult)` |
| `followUp` | `return VideoNoteRenderer.renderAnalysis(followUpContext, state.result());` | `followUpContext` 在同方法第 115 行定义，类型 `VideoContext`；`renderAnalysis` 是 public static |
| `reviseAndRerun` | `return VideoNoteRenderer.renderAnalysis(revisedContext, state.result());` | `revisedContext` 在同方法第 142 行定义，类型 `VideoContext` |

import 已补：`import com.example.server.dto.VideoNoteRenderer;`（`VideoContext` 原本就在 import 列表里）。

`persistResult` 里 `loadContext` 可能返回 null（Redis 里的 context 已过 7 天 TTL，而 result 还在）。这种情况 `render(null, result)` 不抛异常，只是笔记章节为空，落库内容退回成不带帧的分析结果——由 `AC6 null context no crash` 覆盖。

---

## 7. 未验证的部分

| 项目 | 状态 |
|---|---|
| 真实视频跑一遍完整链路后 `aiSummary` 长什么样 | 未验证，缺 API Key 与中间件实例 |
| FFmpeg 抽出来的帧 URL 在 Markdown 里能否被前端加载 | 未验证，需要起 MinIO 与前端 |
| `AiService` 三处改动在 Spring 容器里能否注入成功 | 未验证，本机编译不了整个模块 |
| 长视频（比如 2 小时）的笔记节数会不会太多 | 未验证，需要真实抽帧结果；目前合并规则是按画面是否变化，不按数量截断 |

## 8. 发现并修掉的两个问题

| 问题 | 位置 | 修法 |
|---|---|---|
| `render()` 在 `result == null` 时返回以空行开头的 Markdown | `VideoNoteRenderer.render` | 补一条：分析结果为空就直接返回笔记 |
| `AiService` 缺 `VideoNoteRenderer` 的 import | `AiService.java` | 补 import |

另外有两处是断言自己写错、不是实现的问题，一并记录：把 `1000ms` 的时间格式写成 `00:00`（实际是 `00:01`）；用 `List.of("", null)` 造含 null 的帧列表（`List.of` 不接受 null 元素，`VideoSegment` 构造器的 `List.copyOf` 也不接受）。
