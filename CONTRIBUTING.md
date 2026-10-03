# 贡献指南

感谢有兴趣改进这个项目。它是一个刻意保持"零依赖、单文件、可读性优先"的小工具，
提交前请先看下面的约束。

## 目录约定

```
start.ps1              一键启动 / 停止（跨平台请用 node server/server.js）
server/server.js       Node.js 实现（主）
server/SpeedTestServer.java  Java 实现（备用）
web/index.html         测速页面（单文件，无构建步骤）
tools/JsCheck.java     开发期 JS 结构检查工具
```

**两个实现必须保持接口与计量口径完全一致。** 改动其中一个接口时，另一个必须同步改，
否则页面会在不同运行时下表现不一致。

## 硬性约束

1. **零第三方依赖。** Node 版只用内置模块（`http` / `fs` / `path` / `crypto` / `os`），
   Java 版只用 JDK 标准库。不要引入 `package.json` 依赖、Maven / Gradle、CDN 资源或前端框架。
2. **前端保持单文件。** 所有 HTML / CSS / JS 都在 `web/index.html` 里，不拆分、不压缩、不加构建步骤。
   服务端每次请求都从磁盘读取该文件，改完刷新页面即生效。
3. **不要提交运行时产物。** `classes/`、`*.class`、`server.log`、`server.err.log`、`.server.pid`
   已在 `.gitignore` 中忽略，请勿强制 `git add -f`。
4. **不要引入需要写盘的数据。** 测速负载必须在内存里生成。

## 改动测量逻辑时的要求

测量口径是这个项目的核心资产，改动前请确认：

- 字节数**不能**只依赖 JS 侧计数，必须能与服务端的累计字节数交叉校验；
- 时间窗口要么由服务端时钟锁定，要么明确使用客户端墙钟并保持一致；
- **预热流量绝不能计入结果**（所有接口都支持 `&warmup=1`）；
- 上传阶段与下载阶段的计数必须互相隔离；
- 大文件传输路径要有**内存上界**（不能把整段数据缓存在 JS 堆或服务端内存里）。

## 自测清单

提交前请在本地验证：

```bash
node --check server/server.js                       # Node 版语法
javac -encoding UTF-8 -d classes server/SpeedTestServer.java   # Java 版编译
```

端到端：

```bash
node server/server.js 8000
B=http://127.0.0.1:8000
SID=$(curl -s -X POST "$B/api/session?ms=3000" | sed 's/.*"sid":"\([^"]*\)".*/\1/')

# 1. 分片下载长度必须精确
curl -s -D - -o /dev/null "$B/api/download?sid=$SID&chunk=16777216" | grep -i content-length

# 2. 分片上传必须精确读满
head -c 8388608 /dev/urandom > /tmp/st.bin
curl -s -X POST -D - -o /dev/null --data-binary @/tmp/st.bin "$B/api/upload?sid=$SID&chunk=8388608" | grep -i x-bytes-read

# 3. 预热绝不能计数
W=$(curl -s -X POST "$B/api/session?ms=2000" | sed 's/.*"sid":"\([^"]*\)".*/\1/')
curl -s -o /dev/null "$B/api/download?sid=$W&chunk=8388608&warmup=1"
curl -s "$B/api/report?sid=$W"     # 期望 bytes 全为 0

# 4. 服务端报告的两个方向字节数应与客户端统计一致（差值为 0）
curl -s "$B/api/report?sid=$SID"
```

页面侧请用浏览器开发者工具确认「诊断」表里两侧字节数一致，且日志里没有
`signal is aborted without reason` 之类的异常。

## 报告问题

请附上：

- 用的是哪个运行时（`curl http://<ip>:8000/api/info` 的输出）；
- 浏览器与版本；
- 页面底部「诊断」表的整行内容；
- 「过程日志」里标红的行。

`server.log` / `server.err.log`（`SPEEDTEST_DEBUG=1` 时更详细）也很有帮助。
