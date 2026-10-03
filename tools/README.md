# tools/ —— 开发与运维小工具

| 文件 | 用途 |
| --- | --- |
| `JsCheck.java` | 检查 `web/index.html` 内联 JS 的结构完整性（括号 / 字符串配对） |
| `github-push.mjs` | 用 GitHub REST API 把当前目录同步到仓库，**不依赖 git 凭据** |

---

## `github-push.mjs`

在没有 git 凭据、或不想配置 git 的环境里（临时机器、CI、只想快速发布一次改动），
可以直接用 GitHub API 把工作区推到仓库。

```bash
# 需要一个有 repo / public_repo 权限的 token
export GITHUB_TOKEN=ghp_xxx

# 先干跑，看会写哪些文件
node tools/github-push.mjs --message "docs: 更新说明" --dry-run

# 真正提交
node tools/github-push.mjs --message "docs: 更新说明"
```

| 参数 | 说明 |
| --- | --- |
| `--message "..."` | 提交信息（必填） |
| `--repo owner/name` | 默认本仓库 |
| `--branch main` | 目标分支 |
| `--token xxx` | 也可用环境变量 `GITHUB_TOKEN` / `GH_TOKEN` |
| `--dry-run` | 只列出将要写入的文件，不提交 |

它会自动跳过 `.git`、`node_modules`、`classes`、`__pycache__`、`*.class`、`*.log`、
`server.log`、`server.err.log`、`.server.pid` 等不该进仓库的内容。

> ⚠️ 这是**整目录覆盖**式同步：本地不存在的文件会被视为删除。
> 请只在你是唯一维护者、且本地内容是最新的时候使用；多人协作的分支请走 `git push`。

---

## `JsCheck.java`

检查 `web/index.html` 内联 JS 的**结构完整性**：字符串 / 模板串 / 注释状态机 + 括号配对。

它**不做**语法解析（那不是它的职责），但能抓住手工编辑时最常见的错误：
漏一个 `}`、多一个 `)`、字符串没闭合、模板串里引号不配对——这些在浏览器里
通常表现为"整页白屏、控制台只有一行报错"，定位起来反而慢。

### 用法

```bash
# 1) 把页面里的内联脚本抽出来
node -e "
const fs=require('fs');
const html=fs.readFileSync('web/index.html','utf8');
const m=html.match(/<script>([\s\S]*?)<\/script>/);
fs.writeFileSync(process.env.TEMP+'/app.js', m[1]);
"

# 2) 检查（Java 11+ 可直接跑单文件）
java tools/JsCheck.java "$TEMP/app.js"     # Windows PowerShell: $env:TEMP
```

输出示例：

```
行数=816  字符数=40655  语法结构错误=0
```

有错误时会逐条打印行号与不匹配的括号，并以退出码 1 结束，方便接到 CI 里。

### 为什么不用 ESLint / Node 解析器

这个项目要求**零依赖**，且要在只有 JDK 的机器上也能自查。所以工具本身也只用 JDK 标准库，
用 `java` 的单文件模式直接执行，不需要编译。
