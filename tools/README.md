# tools/ —— 开发期小工具

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
