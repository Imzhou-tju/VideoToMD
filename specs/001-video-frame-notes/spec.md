# 规格：带关键视频帧的 Markdown 笔记

## 背景与动机（WHY）

- 项目对外描述与简历承诺：「系统实现视频内容结构化为**带关键视频帧**的 Markdown 笔记」。
- 代码现状：`AnalysisResult.toMarkdown()`（`server/src/main/java/com/example/server/dto/AnalysisResult.java` 第 33-47 行）只输出「标题 / 核心结论 / 视频证据 / 建议」四段，证据行格式为 `- [mm:ss] 来源：文本`，**不含任何图片**。
- 关键帧其实已经存在：`VideoContext.VideoSegment.evidenceFrames` 保存的是 MinIO 图片 URL（`VideoContextService` 抽帧 → 感知哈希去重 → 上传 MinIO → 按 60 秒时间窗 merge）。缺的是最后一步：**把帧织进笔记产物**。
- 调研依据：`docs/智能笔记_时间顺序纪要_关键帧调研.md` §5.2「没有时间顺序纪要产物」、`docs/四家产品关键画面帧截取逻辑对比.md` 第二节通义听悟 8 步流程与 `KeyFrameList` 返回结构。

## 借鉴通义听悟的处理逻辑

通义「PPT 抽取及摘要」的产物形状（官方 API 返回 `PptExtraction.KeyFrameList`）：

```json
{ "Id": 1, "Start": 1160, "End": 12320, "FileUrl": "https://.../001.png", "Summary": "该页停留期间讲了什么" }
```

即：**一条记录 = 起止时间 + 一张图 + 一段文字**，渲染成 Markdown 用 `![](url)` 图文混排。

对应到本项目的已有能力：

| 通义步骤 | 本项目现状 | 是否本次实现 |
|---|---|---|
| 1 固定间隔采样 | FFmpeg 场景检测 `scene>0.35` + 30 秒保底 | 已有，不动 |
| 2 前景过滤 | 无（画中画人像会干扰） | 不做（见非目标） |
| 3 运动/静止事件锚定切换点 | 无，用固定 60 秒窗近似 | 不做（见非目标） |
| 4 时间戳校准 | 无 | 不做 |
| 5 相似度去重 | 感知哈希（汉明距离 ≤5） | 已有，不动 |
| 6 OCR 识别画面文字 | `OcrUtils` → `VideoSegment.ocrTexts` | 已有，不动 |
| 7 与 ASR 文本对齐 | merge 后同窗内 transcript + ocrTexts | 已有，**本次直接用它当节的文字** |
| 8 摘要模型出每页摘要 | 无（5 分钟块有 `segmentSummary`） | 本次不新增 LLM 调用（见决策 D3） |

## 目标（WHAT）

新增一个**纯渲染层**，把已有 `VideoContext` + `AnalysisResult` 渲染成含关键帧的 Markdown 笔记，形态对齐通义：

```
## 视频笔记（按时间顺序）

### [02:00 - 03:00]
![](https://minio.../frame_000125.jpg)
- 画面文字：前序遍历：根节点、左子树、右子树
- 讲解：接下来讲解二叉树的前序遍历……
```

同时让分析结果里的**每条证据**带上它对应时刻的画面帧。

## 验收标准（可测试）

| # | 标准 |
|---|---|
| AC1 | 每个含帧的 segment 输出**恰好一张** `![](url)`，URL 取自该 segment 的 `evidenceFrames` |
| AC2 | 无帧的 segment **不输出**图片语法（不产生坏链/空链接） |
| AC3 | 相邻节若代表帧 URL 相同，**只输出一次**（同一画面不重复贴图） |
| AC4 | 时间格式为 `mm:ss`，`endMs - startMs` 与 segment 实际区间一致 |
| AC5 | 分析结果中每条 evidence 后附其时间戳所在窗的代表帧；查不到则不附 |
| AC6 | 纯函数：相同输入必得相同输出；`null` / 空 segments / 空 evidenceFrames 不抛异常 |
| AC7 | **不改动** `AnalysisResult.toMarkdown()` 的既有行为（向后兼容，老调用方无感知） |
| AC8 | 渲染不触发任何外部 IO（不调 ASR/LLM/MinIO），可离线单元测试 |

## 非目标（本次不做，记入决策）

- 视觉段重构（按画面变化切段）——调研 §5.3 第 1 步，属于底座改造，本次不动 `VideoContextService`。
- 稳定帧选取、清晰度过滤、前景/人像过滤——需要额外解码与模型，本次不动。
- 帧分类（PPT/代码/终端/人像）与代码帧走视觉模型——本次不动。
- 每节 LLM 摘要（通义第 8 步）——避免新增 token 成本，本次不做。

## 决策记录（research）

| 编号 | 决策 | 结论 | 理由 |
|---|---|---|---|
| D1 | 节的粒度 | 以现有 60 秒 `VideoSegment` 为节 | 帧按时间窗已归属，图与时间天然贴合；不需要额外 LLM 调用 |
| D2 | 代表帧选取 | 取该窗 `evidenceFrames` 的第一张 | 帧已在窗内按时间排序；无逐帧时间戳，取首张是确定且可复现的 |
| D3 | 节的文字 | 直接用该窗 `transcript` + `ocrTexts` | 即通义第 7 步「对齐结果」；避免第 8 步的 LLM 成本 |
| D4 | 无帧的节 | 省略图片，保留文字 | 不产生坏链 |
| D5 | 相邻重复帧 | 相同 URL 连续只输出一次 | 同一画面长时间停留时不重复贴图 |
| D6 | 渲染层形态 | 纯静态工具类，零外部依赖 | 便于在无 API/无网络环境下编译并断言验证 |
