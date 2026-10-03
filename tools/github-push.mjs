#!/usr/bin/env node
/*
 * tools/github-push.mjs —— 用 GitHub REST API 把当前目录同步到仓库（不依赖 git 凭据）
 * ===========================================================================
 * 用途：在 git 凭据不可用（或不想配置 git）的环境下，直接把工作区文件推到 GitHub。
 *      CI 之外的机器、临时改了页面想立刻发布，都可以用它。
 *
 * 用法：
 *   set GITHUB_TOKEN=ghp_xxx            # 需要 repo（私有）或 public_repo 权限
 *   node tools/github-push.mjs --message "fix: 修正下载分片校验" --dry-run
 *   node tools/github-push.mjs --message "fix: 修正下载分片校验"
 *
 * 参数：
 *   --repo owner/name      默认 Allpics/Intranet-bandwidth-testing-server-instance
 *   --branch name          默认 main
 *   --message text         提交信息（必填）
 *   --dry-run              只打印将要变更的文件，不实际提交
 *   --token xxx            也可改用环境变量 GITHUB_TOKEN
 *
 * 安全提示：这是"整目录覆盖"式的推送 —— 本地没有的文件会被视为删除。
 *          只会用于二进制内容完全可控的场景，别拿它同步有他人协作的分支。
 */
import fs from 'node:fs';
import path from 'node:path';
import https from 'node:https';

const ARGS = process.argv.slice(2);
function arg(name, def) {
  const i = ARGS.indexOf('--' + name);
  if (i < 0) return def;
  const v = ARGS[i + 1];
  return (!v || v.startsWith('--')) ? true : v;
}
const DRY = ARGS.includes('--dry-run');
const REPO = arg('repo', 'Allpics/Intranet-bandwidth-testing-server-instance');
const BRANCH = arg('branch', 'main');
const MESSAGE = arg('message', null);
const TOKEN = arg('token', process.env.GITHUB_TOKEN || process.env.GH_TOKEN);

if (!MESSAGE || MESSAGE === true) {
  console.error('缺少 --message "提交信息"');
  process.exit(2);
}
if (!TOKEN || TOKEN === true) {
  console.error('缺少 token：设置环境变量 GITHUB_TOKEN，或用 --token 传入');
  process.exit(2);
}

const API = 'api.github.com';

function api(method, urlPath, body) {
  return new Promise((resolve, reject) => {
    const payload = body ? Buffer.from(JSON.stringify(body)) : null;
    const req = https.request({
      method,
      host: API,
      path: urlPath,
      headers: Object.assign({
        'user-agent': 'lan-speedtest-push',
        'accept': 'application/vnd.github+json',
        'authorization': 'Bearer ' + TOKEN,
        'x-github-api-version': '2022-11-28',
      }, payload ? { 'content-type': 'application/json', 'content-length': payload.length } : {}),
    }, res => {
      let data = '';
      res.setEncoding('utf8');
      res.on('data', d => data += d);
      res.on('end', () => {
        let parsed = null;
        try { parsed = data ? JSON.parse(data) : null; } catch { parsed = data; }
        if (res.statusCode >= 200 && res.statusCode < 300) resolve(parsed);
        else reject(new Error(`HTTP ${res.statusCode} ${method} ${urlPath}: ${typeof parsed === 'string' ? parsed : JSON.stringify(parsed)}`));
      });
    });
    req.on('error', reject);
    req.setTimeout(60000, () => { req.destroy(new Error('请求超时')); });
    if (payload) req.write(payload);
    req.end();
  });
}

/* ---------- 收集文件（跳过 .git、忽略项、日志、产物） ---------- */
const IGNORE_DIRS = new Set(['.git', 'node_modules', 'classes', '__pycache__', '.venv', 'venv', '.vscode', '.idea']);
const IGNORE_FILES = new Set(['server.log', 'server.err.log', '.server.pid', 'package-lock.json', '.DS_Store', 'Thumbs.db']);
const IGNORE_EXT = new Set(['.class', '.pyc', '.log']);

function collect(dir, base = '') {
  const out = [];
  for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
    const rel = base ? base + '/' + ent.name : ent.name;
    if (ent.isDirectory()) {
      if (IGNORE_DIRS.has(ent.name)) continue;
      out.push(...collect(path.join(dir, ent.name), rel));
    } else if (ent.isFile()) {
      if (IGNORE_FILES.has(ent.name)) continue;
      if (IGNORE_EXT.has(path.extname(ent.name).toLowerCase())) continue;
      out.push({ rel, abs: path.join(dir, ent.name) });
    }
  }
  return out;
}

async function main() {
  const root = process.cwd();
  const files = collect(root).sort((a, b) => a.rel.localeCompare(b.rel));
  console.log(`仓库    : ${REPO}`);
  console.log(`分支    : ${BRANCH}`);
  console.log(`待推送  : ${files.length} 个文件`);

  // 1) 远端分支当前指向
  const ref = await api('GET', `/repos/${REPO}/git/ref/heads/${BRANCH}`);
  const parentSha = ref.object.sha;
  console.log(`父提交  : ${parentSha.slice(0, 8)}`);

  // 2) 逐个创建 blob（内容寻址，未变化的文件不会重复占空间）
  const entries = [];
  for (const f of files) {
    const content = fs.readFileSync(f.abs);
    if (DRY) { entries.push({ path: f.rel, size: content.length }); continue; }
    const blob = await api('POST', `/repos/${REPO}/git/blobs`, {
      content: content.toString('base64'),
      encoding: 'base64',
    });
    entries.push({ path: f.rel, mode: '100644', type: 'blob', sha: blob.sha, size: content.length });
    process.stdout.write(`\r  已上传 ${entries.length}/${files.length}`);
  }
  if (DRY) {
    console.log('\n--dry-run：以下文件将被写入（未实际提交）');
    for (const e of entries) console.log(`  ${e.path.padEnd(46)} ${e.size} B`);
    return;
  }
  console.log('');

  // 3) 建 tree / commit / 更新分支
  const tree = await api('POST', `/repos/${REPO}/git/trees`, { tree: entries.map(({ size, ...e }) => e) });
  const commit = await api('POST', `/repos/${REPO}/git/commits`, {
    message: MESSAGE, tree: tree.sha, parents: [parentSha],
  });
  await api('PATCH', `/repos/${REPO}/git/refs/heads/${BRANCH}`, { sha: commit.sha, force: false });

  console.log(`已推送  : ${commit.sha.slice(0, 8)}  ${MESSAGE}`);
  console.log(`查看    : https://github.com/${REPO}/commit/${commit.sha}`);
}

main().catch(e => { console.error('\n失败：' + e.message); process.exit(1); });
