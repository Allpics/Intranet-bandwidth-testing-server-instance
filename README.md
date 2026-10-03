# 内网测速服务（LAN Speed Test）

零第三方依赖的内网带宽测速 Web 服务：一台机器做服务端，局域网内任意设备用浏览器打开就能测
**下载 / 上传带宽 + 时延 / 抖动 / 丢包**。适合测 NAS、千兆/2.5G 交换机、Wi-Fi 实际吞吐、网线质量。

```
lan-speedtest/
├─ start.ps1                     # 一键启动 / 停止（自动挑运行时 + 打印内网访问地址）
├─ server/server.js              # 服务端 · Node.js 版（主实现，仅内置模块，无需 npm install）
├─ server/SpeedTestServer.java   # 服务端 · Java 版（备用，仅 JDK 标准库）
├─ web/index.html                # 测速页面（单文件，无 CDN、无构建）
├─ tools/JsCheck.java            # 开发期小工具：检查页面内联 JS 的括号/字符串配对
├─ server.log / server.err.log   # 运行日志（由 start.ps1 生成）
└─ classes/                      # Java 版编译产物（用 java 运行时才会生成）
```

## 两个实现，默认 Node

| 运行时 | 实现 | 依赖 | 回环实测（4 流 / 10 s） |
| --- | --- | --- | --- |
| **Node.js**（默认） | `server/server.js` | 只要 Node 18+，**不需要 npm install** | 下载 ~29 Gbps、上传 ~13.6 Gbps |
| Java 17+（备用） | `server/SpeedTestServer.java` | 只用 JDK 标准库，无 Maven/第三方 jar | 下载 ~27 Gbps、上传 ~46 Gbps |

两者接口、页面、计量口径完全一致，`/api/info` 会返回 `runtime` 字段标明当前用的是哪个。
换实现只改一个参数：`.\start.ps1 -Runtime java`。

## 快速开始

```powershell
cd lan-speedtest
.\start.ps1                     # 默认 Node 版，端口 8000，后台运行
```

输出示例：

```
 运行时 : Node.js
 node   : C:\Program Files\nodejs\node.exe
 版本   : v26.7.0

 服务已启动，PID 15572

 本机访问： http://127.0.0.1:8000/
 内网访问： http://192.168.1.4:8000/

 停止： .\start.ps1 -Stop
```

然后**在需要测速的客户端**（手机、笔记本、另一台 PC）浏览器里打开 `http://192.168.1.4:8000/`，
点「开始测速」即可。

> ⚠️ 关键点：一定要用**内网 IP** 访问，而不是 `127.0.0.1`。用本机回环测出来的数字只代表本机内存带宽，
> 没有经过网线和交换机，不是真实内网速率。

### 命令参数

| 命令 | 说明 |
| --- | --- |
| `.\start.ps1` | 默认 Node 版、端口 8000 启动 |
| `.\start.ps1 -Port 8081` | 指定端口 |
| `.\start.ps1 -Runtime java` | 换成 Java 版实现 |
| `.\start.ps1 -Foreground` | 当前窗口前台运行，`Ctrl+C` 停止 |
| `.\start.ps1 -Firewall` | 尝试添加防火墙入站放行规则（需管理员） |
| `.\start.ps1 -Stop` | 停止服务 |
| `.\start.ps1 -Clean` | 清掉 `classes/` 重新编译（仅 java 运行时） |

脚本会在 PATH 之外自动搜索 Node 的常见安装位置（`Program Files\nodejs`、`LOCALAPPDATA\Programs\nodejs`、
scoop、fnm 等），所以**刚装完 Node 没重开终端也能直接用**。

不用脚本也行：

```powershell
node server/server.js 8000            # Node 版：参数 端口 [绑定地址]
# 或 Java 版：
javac -encoding UTF-8 -d classes server/SpeedTestServer.java
java -cp classes SpeedTestServer 8000
```

## 测速流程（页面上会自动依次执行）

| 阶段 | 时长 | 说明 |
| --- | --- | --- |
| ① 时延探测 | ~1 s | 16 次小请求，取平均 RTT、抖动（相邻差值均值）、丢包率 |
| ② 链路预热 | 2.2 s × 2 | TCP 窗口爬升 + 网卡与浏览器网络栈进入稳定态，**不计入成绩** |
| ③ 上传测速 | 可选 5/10/15/20 s | N 条并行流反复 POST 固定大小分片 |
| ④ 下载测速 | 可选 5/10/15/20 s | N 条并行流反复 GET 固定长度分片 |
| ⑤ 汇总 | <1 s | 汇总客户端与两端字节数，展示并互相校验 |

并发流可选 1 / 2 / 4 / 8 / 16；方向可选「上传+下载 / 仅下载 / 仅上传」。

## 测量机制：分片 + 浏览器原生传输

页面**不再让 JS 逐块读取响应流**（`response.body.getReader()`），也不依赖「`ReadableStream` 作为 fetch 请求体」。
数据搬运完全交给浏览器原生网络栈，JS 只负责发请求、核对字节数、然后丢弃：

| 方向 | 一次请求 | 字节数怎么来 |
| --- | --- | --- |
| 下载 | `GET /api/download?sid=..&chunk=16777216` | 服务端带 `Content-Length` 精确发送 N 字节；客户端用 `arrayBuffer()` 拿到后核对长度 |
| 上传 | `POST /api/upload?sid=..&chunk=8388608` | 请求体是固定大小 `Uint8Array`（带 `Content-Length`）；服务端精确读取 N 字节 |

- **分片大小自适应**：起始 上传 8 MiB / 下载 16 MiB，按单片耗时在 2–64 MiB 之间自动加倍或减半
  （目标 30–600 ms/片），千兆到万兆都不用改配置。
- **速率公式**：`总字节 × 8 ÷ 客户端墙钟秒数 ÷ 1e6`，总字节取「客户端实际收到/发出的字节」，
  并用服务端计数交叉校验（页面底部「诊断」表）。
- **为什么这样更稳**：不再有"未知长度的流式 body"，也就没有 Safari/Firefox 不支持、
  代理/杀软缓冲、abort 时序、浏览器内部背压导致 JS 侧长时间读不到数据这些坑。

页面上的「下载实现」下拉框有两条独立路径：

| 选项 | 实现 | 何时用 |
| --- | --- | --- |
| 分片（推荐） | `fetch` + 固定长度分片，按 `Content-Length` 计数 | 默认 |
| 单流 XHR（备用） | `XMLHttpRequest` + `onprogress`，`responseType='blob'` | 分片路径异常时手动切换，或用来对照定位问题 |

分片模式一片都没拿到时会**自动**落到单流 XHR 探测一次，并在日志里打印
`HTTP 状态码 / Content-Length / progress 事件次数 / 服务端已发出字节`，用于判断
"请求没成功"还是"数据没到浏览器"。

> 一个已经修掉的坑（供排查参考）：早期版本在上传/下载阶段之间 `abort()` 并重建
> `AbortController`，而传给各阶段的 `signal` 是 `const`，于是指向了**已中止**的旧信号，
> 表现为 `下载 0 个请求、0 字节、signal is aborted without reason`。
> 现在每次测速只有一个中止控制器，只由「停止」按钮和收尾逻辑触发，阶段之间互不影响。

## 浏览器兼容性

| 浏览器 | 下载测速 | 上传测速 | 说明 |
| --- | --- | --- | --- |
| Chrome / Edge / Opera（含较旧版本） | ✅ | ✅ | 只用 `fetch` + `ArrayBuffer` + `AbortController` |
| Firefox | ✅ | ✅ | 同上，无需任何特殊特性 |
| Safari / iOS Safari | ✅ | ✅ | 同上 |
| IE | ❌ | ❌ | 没有 `fetch` / `Promise`，请升级浏览器 |

## 为什么结果值得信

1. **两端字节数都可精确核对**。下载由 `Content-Length` 与 `X-Chunk-Bytes` 双重声明，
   上传由固定长度请求体保证；页面底部会并列显示「客户端收到 / 服务端收到」两组数字，
   差值超过 1 MiB 会直接报警。实测两端差值 < 0.1%。
2. **吞吐不经过 JS 热路径**。JS 不逐块处理数据、不做流式拷贝，因此不受页面切后台、
   标签页节流、GC 抖动影响。
3. **计时用客户端墙钟，口径单一**。`performance.now()` 从发起第一片到收完最后一片，
   服务端同时记录上传窗口（首字节到最后一字节）供交叉验证。
4. **单向清零、互不污染**。上传阶段下载计数为 0，下载阶段上传计数为 0；
   `warmup=1` 的预热流量完全不计数。
5. **内存有界**。每片下载后立即丢弃，上传复用同一块 `Uint8Array`，长时间测试不会 OOM。

## 接口一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/session?ms=10000` | 建会话，`ms` 为单阶段时长（1000–120000），返回 `sid` |
| `GET` | `/api/ping` | 空响应 + `X-Server-Time`，用于 RTT 探测 |
| `GET` | `/api/download?sid=..&chunk=16777216` | **分片下载**：精确发送 `chunk` 字节（带 `Content-Length` 与 `X-Chunk-Bytes`） |
| `POST` | `/api/upload?sid=..&chunk=8388608` | **分片上传**：精确接收 `chunk` 字节，返回 `X-Bytes-Read` |
| `GET`/`POST` | `/api/download`、`/api/upload`（不带 `chunk`） | 兼容模式：按截止时间持续流式收发，`chunked` 传输 |
| `GET` | `/api/report?sid=..` | 返回该会话的权威计量结果（累计字节数等） |
| `GET` | `/api/info` | 服务端信息（版本 / 线程数 / 单阶段上限 / 分片上限 / 能力标记） |
| 任意 | 以上任意接口加 `&warmup=1` | 预热流量，不计入统计 |

`/api/report` 返回：

```json
{
  "sid": "d36493b1...",
  "phaseMs": 6000,
  "up":   { "bytes": 34184466200, "ms": 6013, "mbps": "45480.747" },
  "down": { "bytes": 0,           "ms": 0,    "mbps": "0" },
  "serverTime": 1790962398312
}
```

命令行自查（服务端启动后）：

```powershell
$b = 'http://127.0.0.1:8000'
$sid = (curl.exe -s -X POST "$b/api/session?ms=3000" | ConvertFrom-Json).sid

# 分片下载：一次 16 MiB，看 Content-Length 是否精确
curl.exe -s -D - -o NUL "$b/api/download?sid=$sid&chunk=16777216"

# 连续拉 10 片，量一下真实速率
curl.exe -s -o NUL -w "本次 %{size_download} 字节 / %{time_total}s\n" --max-time 60 `
  "$b/api/download?sid=$sid&chunk=67108864"

# 分片上传：POST 一个 8 MiB 文件
fsutil file createnew $env:TEMP\st.bin 8388608
curl.exe -s -X POST --data-binary "@$env:TEMP\st.bin" "$b/api/upload?sid=$sid&chunk=8388608"

# 服务端计量结果（bytes 应与上面两部分之和一致）
curl.exe -s "$b/api/report?sid=$sid"
```

## 参数与调优

服务端常量（两个实现同名同义）：

| 常量 | 默认 | 说明 |
| --- | --- | --- |
| `DEFAULT_PORT` | 8000 | 默认端口，也可用命令行第一个参数覆盖 |
| `PAYLOAD_SIZE` / `IO_BUF` | 256 KiB | 负载块大小，也是共享负载缓冲区大小 |
| `MAX_PHASE_MS` | 120000 | 单阶段最长时长（防跑飞） |
| `MAX_CHUNK_BYTES` | 64 MiB | 单个分片请求上限（前端自适应范围 2–64 MiB） |
| `SESSION_TTL_MS` | 600000 | 会话保留 10 分钟，之后自动回收 |
| `HTTP_THREADS` | 64 | 仅 Java 版：HTTP 工作线程数，需 ≥ 并发流 × 2 |

Node 版另有 `server.keepAliveTimeout = 30s`、`requestTimeout = 0`（长测速请求不被默认超时打断）、
`maxRequestsPerSocket = 0`（不主动断连接，减少握手开销）。下载负载每次启动用 `crypto.randomBytes` 生成，
避免被中间设备识别成重复内容做压缩/缓存优化。

前端分片初始值在 `web/index.html` 顶部：`UPLOAD_CHUNK = 8 MiB`、`DOWNLOAD_CHUNK = 16 MiB`，
运行中按单片耗时在 2–64 MiB 之间自动调整，无需手动改。

性能建议：

- 想压满 10G 链路时把并发流调到 8–16，单流受浏览器/单 TCP 连接限制跑不满。
- 服务端别用 Wi-Fi，用有线；客户端同理，否则测的是 Wi-Fi 空口速率。
- 测出来的**下载**受服务端磁盘/内存带宽影响很小（负载是内存里的固定块，不读盘）；
  **上传**受服务端接收速度影响，若服务端同时在跑重负载，结果会偏低。
- 想追求极限数字用 Java 版（上传路径更省 CPU）；想零环境负担 / 好维护用 Node 版。
- 端口选择：避开被系统保留的区间（`netsh int ipv4 show excludedportrange protocol=tcp`），
  换端口即可（两个实现都会在端口占用时打印明确错误）。

## 排错

| 现象 | 原因 / 处理 |
| --- | --- |
| 别的设备打不开页面 | Windows 防火墙拦了入站。管理员运行 `.\start.ps1 -Firewall`，或手动放行 TCP 端口 |
| 只能本机访问 | 页面地址用了 `127.0.0.1`，改用脚本打印的 `192.168.x.x` 地址 |
| `端口 8000 已被占用` | 换端口：`.\start.ps1 -Port 8081`；或先 `.\start.ps1 -Stop` |
| 脚本说找不到 node | 用 `.\start.ps1 -Runtime java`，或重开终端让 PATH 生效；脚本已自动搜索常见安装位置 |
| 服务起不来但没提示 | `.\start.ps1 -Foreground` 前台跑，直接看报错；后台模式看 `server.log` / `server.err.log` |
| 结果全是 `—` / 0 | 打开页面底部「诊断」表：若两组字节数都是 0，说明请求根本没成功，看浏览器控制台(F12)的红色报错；若客户端有数、服务端为 0，说明服务端实现有问题 |
| 日志出现 `signal is aborted without reason` / `下载 0 个请求` | 旧版本的 abort 时序 bug（已修）。确认页面是最新版：Ctrl+F5 强制刷新 |
| 想换一条下载实现 | 「下载实现」选「单流 XHR（备用）」，它走 `XMLHttpRequest` + `onprogress`，与分片路径完全独立 |
| 两组字节数差异大 | 中间有代理/杀软在改包，或分片被截断。关掉代理再测；表里差值超过 1 MiB 会自动报警 |
| 数字明显偏低 | 检查是不是走了 Wi-Fi、VPN 或代理；把并发流加大（千兆建议 4–8，万兆 8–16） |
| 单流跑不满 | 正常现象：单条 TCP 连接有窗口/时延上限，加并发流即可 |
| 控制台中文乱码 | 先执行 `chcp 65001`；仅影响横幅显示，不影响测速 |
| 想知道服务端到底收/发了多少 | `curl.exe -s "http://IP:8000/api/report?sid=xxx"`，或在页面诊断表对比 |

## 安全说明

本服务**没有鉴权、没有限速**，任何能访问到该端口的人都可以跑满你的带宽。请只在可信内网使用，
**不要**通过端口映射 / 反向代理暴露到公网。用完 `.\start.ps1 -Stop` 停掉即可。
