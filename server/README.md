# server/ —— 两个实现

| 文件 | 运行时 | 依赖 | 状态 |
| --- | --- | --- | --- |
| `server.js` | Node.js 18+ | 无（仅内置模块） | **默认**，`start.ps1` 不加参数就用它 |
| `SpeedTestServer.java` | Java 17+ | 无（仅 JDK 标准库，`com.sun.net.httpserver`） | 备用，`start.ps1 -Runtime java` |

两者**接口完全一致**，页面不需要任何改动即可在两个运行时之间切换：

```
POST /api/session?ms=10000
GET  /api/ping
GET  /api/download?sid=&chunk=N      # 分片模式（带 Content-Length）
GET  /api/download?sid=&ms=N         # 兼容模式（按截止时间流，chunked）
POST /api/upload?sid=&chunk=N        # 分片模式（精确读 N 字节）
POST /api/upload?sid=                # 兼容模式
GET  /api/report?sid=
GET  /api/info
GET  /                               # web/index.html
```

## 什么时候用哪个

- **Node.js**：默认选择。零依赖、免编译、启动瞬时，下载路径使用正确的背压处理
  （`res.write()` 返回值 + `await drain`），不会把 64 MiB 分片一次性堆进内存。
- **Java**：适合服务端 CPU 较弱又需要极限上传吞吐的场景（Java 版的上传路径实测更高，
  回环约 46 Gbps vs Node 版约 13.6 Gbps），或者机器上只有 JDK 没有 Node。

## 实现要点（改代码前请读）

### 共同约定

- **字节数**：分片模式下下载写出的字节数、上传读入的字节数都要累计到会话，
  并通过 `X-Chunk-Bytes` / `X-Bytes-Read` 与客户端对账。
- **预热隔离**：`&warmup=1` 的请求走独立分支，**绝不初始化测量窗口**（`upT0` / `downT0`），
  也绝不累加字节数。
- **窗口语义**：分片模式下上传窗口 = 第一片到达 → 最后一片读完；
  下载不存在连续窗口（每片自带 `Content-Length`），速率由客户端墙钟计算，
  因此 `/api/report` 的 `down.ms` 为 0 属正常。
- **不主动中止客户端**：响应发送完就结束，长请求不能被服务端的默认超时打断。

### Node.js 版

- `server.requestTimeout = 0`、`server.maxRequestsPerSocket = 0`、`keepAliveTimeout = 30s`。
- 负载 `PAYLOAD` 在启动时用 `crypto.randomBytes` 生成，避免被中间设备识别为重复内容做优化。
- 上传分片按 `chunk` 严格截断：客户端多发就 `req.destroy()` 并停止计数，避免污染统计。
- 会话表用 `setInterval(...).unref()` 定期回收，不会阻止进程退出。
- `SPEEDTEST_DEBUG=1` 打开逐分片调试日志。

### Java 版

- `HTTP_THREADS` 默认 64，必须 ≥ 并发流 × 2（下载流会占住工作线程整个窗口）。
- 上传窗口有一个"兜底封存线程"：即使某条流一直阻塞在 `read` 上，窗口到期也会写入 `upT1`。
- 编译：`javac -encoding UTF-8 -d classes server/SpeedTestServer.java`，
  然后 `java -cp classes SpeedTestServer 8000`。

## 常见改动指引

- **加大单分片上限**：改 `MAX_CHUNK_BYTES`，同时改 `web/index.html` 里 `CHUNK_MAX`
  （否则页面会请求超过服务端上限的分片，被截断到上限值，两边对不上）。
- **新增接口**：两个实现都要加，并同步更新根 `README.md` 的接口表。
- **改测量口径**：请先读根 `CONTRIBUTING.md` 里"改动测量逻辑时的要求"。
