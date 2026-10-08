# AIVideo 视频处理架构解析（RocketMQ + Redisson + 分片续传）

## 一、部署形态

仓库通过 `docker-compose.yml` 在单宿主机上以 docker bridge 网络（`my-network`）编排 MySQL(:3307)、Redis(:6379)、MinIO(:9000/9001)、RocketMQ NameServer(:9876)+Broker(:10911)+Dashboard(:8180)，各中间件均为单实例；应用 `server`（端口 9090）作为独立进程启动，与 compose 编排的中间件通过本地网络互联。

"分布式锁"指跨多个应用实例协调同一份资源的锁：本项目中应用实例共享连接同一个 Redis 服务，由 Redis 作为集中式协调器，使锁、限流、完成标记在实例间保持一致。

## 二、三痛点与各组件实现

### 2.1 长耗时阻塞 → RocketMQ 异步消息解耦（用消息队列把"接收请求"和"干重活"拆到两个线程，接收方立即返回，重活在别处慢慢跑）

代码锚点：`controller/AnalysisController.java`→`aiAnalyze()`；`consumer/VideoAnalysisConsumer.java`→`onMessage()`；`service/AiService.java`→`asyncAnalyze()`；`service/VideoContextService.java`→`build()`。

关键事实：`asyncAnalyze()` 是同步方法，解耦靠 RocketMQ 消费者线程，不是 Spring `@Async`（后者只挂在独立的文字提取接口上）。

```text
// AnalysisController.aiAnalyze()：HTTP 线程，毫秒级返回
mediaFile = requireOwnedMedia(mediaId, userId)              // 越权校验
if !rateLimiter.tryAcquire(): return 429                    // 见 2.2 限流
contentHash = normalizeContentHash(mediaId, redis.get("media:md5:"+mediaId))
goalDigest  = sha256(normalize(goal))
activeKey   = "analysis:active:"+contentHash+":"+goalDigest
if !redis.setIfAbsent(activeKey, mediaId, 2h): return 409   // 见 2.2 去重
mediaFile.aiSummary = "分析任务已排队"; db.update(mediaId, mediaFile)
rocketMQTemplate.convertAndSend(topic, new AnalysisTaskMsg(mediaId, "START_ANALYSIS", contentHash, goal))
return 202 ACCEPTED                                        // 长耗时逻辑从不在此跑

// VideoAnalysisConsumer.onMessage()：消费者线程，跑重活
contentHash  = normalizeContentHash(msg.mediaId, msg.contentHash)
goalDigest   = sha256(msg.userGoal)
lockKey      = "lock:analysis:"+contentHash+":"+goalDigest
completedKey = "analysis:completed:"+msg.mediaId+":"+goalDigest
lock = redisson.getLock(lockKey); acquired = lock.tryLock()
if !acquired || redis.hasKey(completedKey): return          // 幂等（同一操作重复执行结果一致，不产生副作用）跳过
try:
    aiService.asyncAnalyze(msg.mediaId, msg.userGoal)        // 含 60min VideoContext + AgentLoop
    redis.set(completedKey, "1", 7d)
finally:
    if acquired:
        redis.delete(activeKey)
        if lock.heldByCurrentThread: lock.unlock()
```

`asyncAnalyze()` 内部：`checkpointService.loadResult()` 若已存在已完成结果则直接持久化返回；否则 `loadContext()` 命中则跳过 `videoContextService.build()`，未命中则用 `CompletableFuture` 并行跑 ASR/OCR（`build()` 中 `allOf(...).get(60, MINUTES)` 为硬预算）并存入 Redis Checkpoint；最后 `agentLoopService.run()` 多次调用大模型生成结论。全部在消费者线程，不阻塞任何 HTTP 请求。

部署交互：App → NameServer(:9876) 取路由 → 发到 Broker(:10911) → Broker 推送到某 app 实例的消费者线程；`onMessage` 抛异常触发 RocketMQ 默认重试（约 16 次带退避）兜底。

### 2.2 高并发资源冲突 → Redisson 三道闸门

三道闸门都落在 Redis（单实例部署，被所有 app 实例共享连接，使锁/限流/完成标记能跨实例保持一致，见一的分布式锁说明）。Key 命名统一出自 `utils/AnalysisTaskKeys.java`。

```text
// 第一道：令牌桶限流（全局，非 per-user）
rateLimiter = redisson.getRateLimiter("limit:ai:global")
rateLimiter.trySetRate(OVERALL, 10, 1, MINUTES)   // 全局 10 次/分钟
if !rateLimiter.tryAcquire(): return 429

// 第二道：内容级去重（防重复提交）
activeKey = "analysis:active:"+contentHash+":"+goalDigest
if !redis.setIfAbsent(activeKey, mediaId, 2h): return 409

// 第三道：分布式锁 + 完成标记（防重复消费）
lock = redisson.getLock("lock:analysis:"+contentHash+":"+goalDigest)
acquired = lock.tryLock()
if !acquired || redis.hasKey("analysis:completed:"+mediaId+":"+goalDigest): return
... 执行 ...
redis.set("analysis:completed:"+mediaId+":"+goalDigest, "1", 7d)
```

- `contentHash` 为视频 MD5（`media:md5:<mediaId>`，缺失回退 `media-<id>`）；`goalDigest` 为目标文本 SHA-256。**同一视频 + 同一目标 → 同一 key**，并发第二次直接 409；不同提问 → 不同 `goalDigest` → 不同 key，天然隔离。
- 锁保证单实例执行，`completedKey` 保证 MQ 重复投递不重复解析，二者双保险即消费幂等。
- 限流为 `OVERALL` 全局共享桶，所有用户共用同一速率配额，单用户高频请求会占用全部额度。

### 2.3 大文件传输不稳定 → 分片上传 + 断点续传（把大文件切成小块分别上传；传到一半断了能从中断处接着传，不用从头再来）

代码锚点：`controller/MediaController.java`（四端点 `/init-upload`、`/upload-status`、`/upload-chunk`、`/complete-upload`）；`service/MediaService.java`（`initChunkedUpload`/`uploadChunk`/`getUploadedChunks`/`completeChunkedUpload`）；Key 常量同文件 48–51 行。

```text
// initChunkedUpload：开会话
uploadId = uuid()
redis.hset("upload:chunked:"+uploadId, {filename, totalChunks, userId}); redis.expire(..., 1d)
fs.mkdir(localTmpDir + "/" + uploadId)            // localTmpDir = java.io.tmpdir/video-chunks
return uploadId

// uploadChunk：单片落地（≤5MB）
requireUpload(uploadId, userId)                   // 校验归属，防篡改别人任务
if chunk.size > 5MB: throw
fs.write(localTmpDir+"/"+uploadId+"/part-"+chunkIndex, chunk.bytes)
redis.sadd("upload:chunked:"+uploadId+":parts", chunkIndex); redis.expire(..., 1d)

// getUploadedChunks：断点续传核心
return redis.smembers("upload:chunked:"+uploadId+":parts")   // 前端 diff 出缺失下标，只重传缺失分片

// completeChunkedUpload：校验 + 合并 + 算 MD5 + 落 MinIO
if redis.scard(parts) != totalChunks: throw "not all chunks"
merge parts[0..totalChunks-1] via DigestOutputStream(MD5)    // 边合并边算 MD5
fileUrl = minio.put(mergedFile, filename); mediaId = db.insert(...)
redis.set("media:md5:"+mediaId, md5); invalidateUserList(userId); cleanup(uploadId)
```

安全：`requireUpload()` 比对 uploadId 归属 userId 防止补全他人上传任务；列表与删除等读取操作经 `requireOwnedMedia()` 校验文件归属。

## 三、跨模块统一变量命名表

| 变量 | 含义 | 出处 |
|---|---|---|
| `mediaId` | 视频主键（`MediaFile.id`） | 全模块 |
| `uploadId` | 分片会话 ID（UUID） | `MediaService.initChunkedUpload` |
| `contentHash` | 视频 MD5（`media:md5:<mediaId>`） | `AnalysisTaskKeys.normalizeContentHash` |
| `goalDigest` | 目标文本 SHA-256 | `AnalysisTaskKeys.goalDigest` |
| `activeKey` | `analysis:active:<contentHash>:<goalDigest>` | `AnalysisTaskKeys.active` |
| `lockKey` | `lock:analysis:<contentHash>:<goalDigest>` | `AnalysisTaskKeys.lock` |
| `completedKey` | `analysis:completed:<mediaId>:<goalDigest>` | `AnalysisTaskKeys.completed` |

注：`activeKey`/`lockKey` 用 `contentHash`（跨相同内容去重），`completedKey` 用 `mediaId`（按具体媒资记完成），二者刻意不同，勿混。

## 四、组件在部署环境中的交互

- 上传链路：Client → App(:9090) `POST /upload-chunk` → 分片落应用本地临时目录 + Redis 记 `parts`；`/complete-upload` 合并 → MinIO 存视频 + MySQL 存 `MediaFile` + Redis 存 `media:md5:<id>`。
- 分析链路：Client → App `POST /analysis/ai`（限流+去重）→ RocketMQ(Broker) → 某 app 实例的消费者线程 → MinIO 取视频 → 本地 FFmpeg 抽音视频（吃 CPU）→ 阿里云 ASR / 本地 tesseract OCR（并行）→ Redis 存 checkpoint → MySQL 存结果。
- 协调基座：Redis 单实例承载限流桶、`active`/`lock`/`completed` 三套 key、checkpoint Hash、用户列表缓存；MQ 单 Broker 承载异步解耦与重试——两者都以单实例形式服务于整套部署。
- 外部依赖：ASR（阿里云 DashScope）、LLM/Embedding（SiliconFlow、BAAI/bge-m3）走公网，是唯一不可控延迟源。

## 五、部署与运维注意事项

1. 分片与各上传会话元数据分别暂存于应用本地临时目录（`java.io.tmpdir/video-chunks`）与 Redis，合并由持有该会话分片的实例完成，上传会话因此绑定到具体实例。
2. 全局限流为 10 次/分钟，所有用户共用同一配额，单用户高频请求会占满额度。
3. 跨用户不共享 VideoContext（Checkpoint 按 `mediaId` 存储，同一视频由两名用户分别上传会得到两个 `mediaId`，各自独立解析）。
4. Broker 以单 master、异步刷盘方式部署，宕机可能丢失未刷盘消息；`completedKey` 保证消费失败重投或重新提交时不会重复解析，可由重启后的消息重试恢复。
5. 消费者重试会重跑整条 pipeline，依赖 Checkpoint 跳过已构建的 VideoContext 以降低成本，首次构建失败的重试代价仍较大。
