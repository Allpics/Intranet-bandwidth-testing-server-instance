# 更新日志

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [1.1.0] - 2026-10-03

### 修复

- **下载阶段全部请求被中止**（严重）。上传阶段结束时会 `abort()` 并重建 `AbortController`，
  而传给各阶段的 `signal` 是 `const`，重建后仍指向**已中止的旧信号**，
  导致下载的 4 条并行流在发出请求前就带着 `signal is aborted without reason` 失败，
  表现为「下载 0 个请求、0 字节」。
  现在每次测速只使用一个中止控制器，仅由「停止」按钮和收尾逻辑触发，阶段之间互不影响。
- **长度校验不再整体失败**：分片长度与 `Content-Length` 不符时（代理 / 杀软截断），
  改为"收到多少算多少 + 自动把分片减半重试"，而不是直接丢弃整个阶段。
- 预热（`warmup=1`）上传不再初始化测量窗口起点，避免把预热时长算进正式窗口。
- 修复 `HEAD` 请求对测速接口触发无意义响应体的告警。

### 新增

- **Node.js 实现**（`server/server.js`）作为默认运行时：零依赖、免编译，下载路径带完整背压处理
  （`res.write()` 返回值 + `drain`），负载每次启动由 `crypto.randomBytes` 生成。
- 页面新增**诊断表**：并列显示「客户端收到 / 服务端收到 / 分片数 / 耗时 / 速率 / 备注」，
  两侧差值超过 1 MiB 自动告警。
- 新增**单流 XHR 备用下载路径**（`XMLHttpRequest` + `onprogress`，`responseType='blob'`），
  分片模式失败时自动降级探测，并打印 HTTP 状态码、`Content-Length`、progress 事件次数、
  服务端已发出字节，用于区分"请求没成功"与"数据没到浏览器"。
- `start.ps1` 支持 `-Runtime node|java`，并在 PATH 之外自动搜索 Node 的常见安装位置；
  后台运行时日志落盘到 `server.log` / `server.err.log`。
- `/api/info` 返回 `runtime` 与能力标记，页面据此提示版本不匹配。

### 变更

- **测量机制重写**：不再让 JS 逐块读取响应流（`response.body.getReader()`），
  也不依赖 `ReadableStream` 作为 fetch 请求体——改为"分片 + 浏览器原生传输"，
  分片大小按耗时在 2–64 MiB 间自适应。Safari / Firefox 因此也能正常测上传。

## [1.0.0] - 2026-10-02

### 新增

- 首个版本：Java 单文件服务端（`com.sun.net.httpserver`，零第三方依赖）。
- 测速页面：表盘、时延柱状图、历史记录、深浅主题，全部内联，无 CDN、无构建步骤。
- 接口：`/api/session`、`/api/ping`、`/api/download`、`/api/upload`、`/api/report`、`/api/info`。
- 计量口径：字节数由服务端统计，时间窗口由服务端时钟锁定，预热流量不计数。
- `start.ps1` 一键启动 / 停止，自动编译并打印内网访问地址。
