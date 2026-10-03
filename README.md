# 内网测速服务 · Intranet Bandwidth Testing Server

**English** | [English overview](README.en.md)

一个**零第三方依赖**的内网带宽测速 Web 服务。一台机器做服务端，局域网内任意设备（手机 / 平板 / 笔记本）
用浏览器打开就能测 **下载带宽、上传带宽、时延、抖动、丢包**。适合测 NAS、千兆 / 2.5G / 万兆交换机、
Wi-Fi 实际吞吐、网线质量、以及"换了网线/交换机到底有没有提升"这类问题。

服务端有两个实现，**接口与计量口径完全一致**，可随时切换：

| 运行时 | 入口 | 依赖 | 备注 |
| --- | --- | --- | --- |
| **Node.js**（默认） | [`server/server.js`](server/server.js) | Node 18+，**不需要 npm install** | 零依赖、免编译、启动瞬时 |
| Java 17+（备用） | [`server/SpeedTestServer.java`](server/SpeedTestServer.java) | 仅 JDK 标准库，无 Maven / 第三方 jar | 上传路径更省 CPU，追极限数字可用 |

---

## 快速开始

```powershell
git clone https://github.com/Allpics/Intranet-bandwidth-testing-server-instance.git
cd Intranet-bandwidth-testing-server-instance
.\start.ps1
```

输出：

```
 运行时 : Node.js
 node   : C:\Program Files\nodejs\node.exe
 版本   : v26.7.0

 服务已启动，PID 14128

 本机访问： http://127.0.0.1:8000/
 内网访问： http://192.168.1.4:8000/
```

然后在**要测速的那台设备**上打开 `http://192.168.1.4:8000/`，点「开始测速」。

> ⚠️ **必须用内网 IP 访问，不要用 `127.0.0.1`。** 回环地址测出来的是本机内存带宽，
> 不经过网线和交换机，没有参考价值。
>
> ⚠️ Windows 防火墙可能拦入站。其他设备打不开时，用管理员 PowerShell 跑一次
> `.\start.ps1 -Firewall` 放行。

### 命令行直接跑

```bash
# Node 版（推荐）
node server/server.js 8000

# Java 版
javac -encoding UTF-8 -d classes server/SpeedTestServer.java
java -cp classes SpeedTestServer 8000
# 参数均为：[端口] [绑定地址]
```

### `start.ps1` 参数

| 命令 | 说明 |
| --- | --- |
| `.\start.ps1` | 默认 Node 版、端口 8000、后台运行 |
| `.\start.ps1 -Port 8081` | 指定端口 |
| `.\start.ps1 -Runtime java` | 切换到 Java 版实现 |
| `.\start.ps1 -Foreground` | 前台运行，`Ctrl+C` 停止 |
| `.\start.ps1 -Firewall` | 添加入站放行规则（需管理员） |
| `.\start.ps1 -Stop` | 停止服务 |
| `.\start.ps1 -Clean` | 清空 `classes/` 重新编译（仅 Java 运行时） |

脚本会在 PATH 之外自动搜索 Node 的常见安装位置（`Program Files\nodejs`、
`LOCALAPPDATA\Programs\nodejs`、scoop、fnm 等），所以**刚装完 Node 没重开终端也能直接用**。

---

## 目录结构

```
.
├─ start.ps1                     # 一键启动 / 停止（自动挑运行时 + 打印内网访问地址）
├─ server/
│  ├─ server.js                  # Node.js 实现（主，零依赖）
│  ├─ SpeedTestServer.java       # Java 实现（备用，仅 JDK 标准库）
│  └─ README.md                  # 两个实现的结构说明与取舍
├─ web/
│  └─ index.html                 # 测速页面（单文件，无 CDN、无构建步骤）
├─ tools/
│  ├─ JsCheck.java               # 开发期小工具：检查页面内联 JS 结构与括号配对
│  └─ README.md                  # 工具说明
├─ .github/                      # Issue 模板
├─ CHANGELOG.md
├─ CONTRIBUTING.md
└─ LICENSE                       # Apache-2.0
```

运行时会生成 `classes/`、`server.log`、`server.err.log`、`.server.pid`，这些**已在 `.gitignore` 中忽略**，
不要提交。

---

## 测速流程

页面点「开始测速」后自动依次执行：

| 阶段 | 时长 | 做什么 |
| --- | --- | --- |
| ① 时延探测 | ~1 s | 16 次小请求，统计平均 RTT、抖动（相邻差值均值）、丢包率 |
| ② 链路预热 | 2.2 s × 2 | TCP 窗口爬升、网卡与浏览器网络栈进入稳定态，**不计入成绩** |
| ③ 上传测速 | 可选 5 / 10 / 15 / 20 s | N 条并行流反复 POST 固定大小分片 |
| ④ 下载测速 | 可选 5 / 10 / 15 / 20 s | N 条并行流反复 GET 固定长度分片 |
| ⑤ 汇总 | <1 s | 汇总客户端与服务端两侧字节数，交叉校验后展示 |

并发流可选 1 / 2 / 4 / 8 / 16；方向可选「上传+下载 / 仅下载 / 仅上传」。

---

## 测量机制：分片 + 浏览器原生传输

**数据搬运完全交给浏览器原生网络栈，JS 不参与热路径。** 这是这套实现与常见"测速网页"最大的区别：

| 方向 | 一次请求 | 字节数怎么来 |
| --- | --- | --- |
| 下载 | `GET /api/download?sid=..&chunk=16777216` | 服务端带精确 `Content-Length` 发送 N 字节；客户端 `arrayBuffer()` 拿到后核对长度再丢弃 |
| 上传 | `POST /api/upload?sid=..&chunk=8388608` | 请求体是固定大小 `Uint8Array`（带 `Content-Length`）；服务端精确读取 N 字节并回 `X-Bytes-Read` |

- **分片自适应**：起始 上传 8 MiB / 下载 16 MiB，按单片耗时在 2–64 MiB 之间自动加倍或减半
  （目标 30–600 ms/片），千兆到万兆都不用改配置。
- **速率公式**：`总字节 × 8 ÷ 客户端墙钟秒数 ÷ 1e6`。总字节取客户端实际收到/发出的字节，
  并用服务端计数交叉校验——页面底部「诊断」表会并列显示两侧数字，差值超过 1 MiB 自动告警。
- **内存有界**：下载每片到手即丢弃；上传复用固定缓冲池，长时间测试不会 OOM。

页面「下载实现」下拉框提供两条独立路径，用于对照排查：

| 选项 | 实现 | 何时用 |
| --- | --- | --- |
| 分片（推荐） | `fetch` + 固定长度分片，按 `Content-Length` 计数 | 默认 |
| 单流 XHR（备用） | `XMLHttpRequest` + `onprogress`，`responseType='blob'` | 分片路径异常时手动切换 |

分片模式一片都没拿到时会**自动**落到单流 XHR 探测一次，并在日志里打印
`HTTP 状态码 / Content-Length / progress 事件次数 / 服务端已发出字节`，
用来区分"请求没成功"和"数据没到浏览器"。

---

## HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/session?ms=10000` | 建会话，`ms` = 单阶段时长（1000–120000），返回 `sid` |
| `GET` | `/api/ping` | 204 空响应 + `X-Server-Time`，用于 RTT / 抖动 |
| `GET` | `/api/download?sid=&chunk=N` | **分片下载**：精确发送 N 字节（`Content-Length` + `X-Chunk-Bytes`） |
| `GET` | `/api/download?sid=&ms=N` | 兼容模式：按截止时间持续流（chunked） |
| `POST` | `/api/upload?sid=&chunk=N` | **分片上传**：精确接收 N 字节，返回 `X-Bytes-Read` |
| `POST` | `/api/upload?sid=` | 兼容模式：按截止时间持续收 |
| `GET` | `/api/report?sid=` | 该会话累计字节数、时间窗口、分片数 |
| `GET` | `/api/info` | 服务端能力与当前 `runtime` |
| 任意 | 以上任意接口加 `&warmup=1` | 预热流量，不计入统计 |

`/api/report` 响应：

```json
{
  "sid": "b2e3bb65...",
  "phaseMs": 10000,
  "up":   { "bytes": 16981458944, "ms": 9964.8, "mbps": 13634.157, "chunks": 8098 },
  "down": { "bytes": 36527005696, "ms": 0,      "mbps": 0,         "chunks": 2177 },
  "serverTime": 1790964584232
}
```

> 分片模式下下载窗口 `ms` 为 0 是正常现象：每个分片自带 `Content-Length`，
> 不存在"从第一字节到最后一字节"的连续窗口，速率由客户端墙钟计算。

### 命令行自查

```bash
B=http://127.0.0.1:8000
SID=$(curl -s -X POST "$B/api/session?ms=3000" | sed 's/.*"sid":"\([^"]*\)".*/\1/')

# 单分片下载 16 MiB，看 Content-Length 是否精确
curl -s -D - -o /dev/null "$B/api/download?sid=$SID&chunk=16777216"

# 分片上传 8 MiB
head -c 8388608 /dev/urandom > /tmp/st.bin
curl -s -X POST --data-binary @/tmp/st.bin "$B/api/upload?sid=$SID&chunk=8388608"

# 服务端计量（bytes 应等于上面两部分之和）
curl -s "$B/api/report?sid=$SID"
```

---

## 浏览器兼容性

| 浏览器 | 下载 | 上传 | 说明 |
| --- | --- | --- | --- |
| Chrome / Edge / Opera | ✅ | ✅ | 只用 `fetch` + `ArrayBuffer` + `AbortController` |
| Firefox | ✅ | ✅ | 同上，无需任何特殊特性 |
| Safari / iOS Safari | ✅ | ✅ | 同上 |
| IE | ❌ | ❌ | 没有 `fetch` / `Promise`，请升级浏览器 |

> 特别说明：本实现**不依赖** `ReadableStream` 作为 fetch 请求体，因此不存在
> "Safari / Firefox 测不了上传"的问题（该特性[至今只有 Chromium 系支持](https://caniuse.com/mdn-api_fetch_options_parameter_body_accepts_readablestream)）。

---

## 实测数据（回环，4 流 / 10 s，仅供对照）

| 方向 | Node.js 实现 | Java 实现 |
| --- | --- | --- |
| 下载 | ~29.2 Gbps（34.0 GiB / 10 s） | ~27 Gbps |
| 上传 | ~13.6 Gbps（15.8 GiB / 10 s） | ~46 Gbps |

两侧字节数偏差均为 **0 MiB**。回环数字只反映服务端实现开销，真实内网速率取决于网卡 / 交换机 /
Wi-Fi 空口，通常远低于此。

---

## 参数与调优

服务端常量（两个实现同名同义）：

| 常量 | 默认 | 说明 |
| --- | --- | --- |
| `DEFAULT_PORT` | 8000 | 默认端口，命令行第一个参数可覆盖 |
| `PAYLOAD_SIZE` / `IO_BUF` | 256 KiB | 负载块大小，也是共享负载缓冲区大小 |
| `MAX_PHASE_MS` | 120000 | 单阶段最长时长（防跑飞） |
| `MAX_CHUNK_BYTES` | 64 MiB | 单个分片请求上限 |
| `SESSION_TTL_MS` | 600000 | 会话保留 10 分钟，之后自动回收 |
| `HTTP_THREADS` | 64 | 仅 Java 版：工作线程数，需 ≥ 并发流 × 2 |

Node 版另有 `keepAliveTimeout = 30s`、`requestTimeout = 0`（长测速不被默认超时打断）、
`maxRequestsPerSocket = 0`（不主动断连接）。下载负载每次启动用 `crypto.randomBytes` 生成，
避免被中间设备当成重复内容做压缩 / 缓存优化。

调优建议：

- 万兆链路把并发流调到 8–16；单条 TCP 连接受窗口 / 时延限制，单流很难跑满。
- 服务端和客户端都别用 Wi-Fi，否则测的是空口速率。
- 下载负载是内存里的固定块，不读盘；上传受服务端接收速度影响，服务端同时在跑重负载会偏低。
- 端口避开系统保留段（`netsh int ipv4 show excludedportrange protocol=tcp`）。

---

## 常见问题 / 排错

| 现象 | 原因与处理 |
| --- | --- |
| 其他设备打不开页面 | 防火墙拦入站。管理员运行 `.\start.ps1 -Firewall` |
| 只能本机访问 | 页面地址用了 `127.0.0.1`，改用内网 IP |
| 端口被占用 | `.\start.ps1 -Port 8081`，或先 `.\start.ps1 -Stop` |
| 脚本说找不到 node | `.\start.ps1 -Runtime java`，或重开终端刷新 PATH |
| 服务起不来没提示 | `.\start.ps1 -Foreground` 看报错；后台模式看 `server.log` / `server.err.log` |
| 结果全是 `—` 或 0 | 看页面底部「诊断」表。两侧都为 0 → 请求没成功，查浏览器 F12 控制台；客户端有数、服务端为 0 → 服务端实现有问题 |
| 日志出现 `signal is aborted without reason` | 旧版本的中止信号时序 bug（已在 v1.1.0 修复），确认页面是最新版（Ctrl+F5） |
| 两侧字节数差异大 | 中间有代理 / 杀软改包，或分片被截断。关掉代理重测；页面差值超 1 MiB 会告警 |
| 想换一条下载实现 | 「下载实现」切到「单流 XHR（备用）」，与分片路径完全独立 |

---

## 安全说明

本服务**没有鉴权、没有限速**，任何能访问该端口的人都能跑满你的带宽。

- 只在**可信内网**使用。
- **不要**通过端口映射 / 反向代理暴露到公网。
- 用完 `.\start.ps1 -Stop` 停掉。

---

## 开发

- 修改前端：直接改 [`web/index.html`](web/index.html)，服务端每次都从磁盘读取，**刷新页面即生效**，无需构建。
- 修改 Node 服务端：改完重启进程即可。
- 修改 Java 服务端：`.\start.ps1 -Runtime java -Clean` 会重新编译。
- 检查页面内联 JS 结构（括号 / 字符串配对）：

  ```bash
  java tools/JsCheck.java <(sed -n '/<script>/,/<\/script>/p' web/index.html)
  ```

  或在 Windows PowerShell 下用 [`tools/README.md`](tools/README.md) 里的做法。

## 许可

[Apache License 2.0](LICENSE)
