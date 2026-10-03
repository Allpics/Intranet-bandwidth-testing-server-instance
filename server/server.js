#!/usr/bin/env node
/*
 * LAN Speed Test Server (Node.js, 零依赖)
 * ===========================================================================
 * 只用 Node 内置模块（http / fs / path / crypto），不需要 npm install。
 *
 * 接口：
 *   POST /api/session?ms=10000          建会话，ms = 单阶段时长(1000~120000)
 *   GET  /api/ping                      空响应 + X-Server-Time，用于 RTT/抖动
 *   GET  /api/download?sid=&chunk=N     分片下载：精确发 N 字节(带 Content-Length)
 *   GET  /api/download?sid=&ms=N        兼容模式：按截止时间持续流(chunked)
 *   POST /api/upload?sid=&chunk=N       分片上传：精确收 N 字节，回 X-Bytes-Read
 *   POST /api/upload?sid=               兼容模式：按截止时间持续收
 *   GET  /api/report?sid=               会话累计字节数与时间窗口
 *   GET  /api/info                      服务端能力
 *   GET  /                              测速页面(web/index.html)
 *
 * 运行：node server/server.js [port] [host]
 * 环境：SPEEDTEST_DEBUG=1 打开调试日志
 */
'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const os = require('os');

/* ===================== 可调参数 ===================== */
const DEFAULT_PORT = 8000;
const DEFAULT_HOST = '0.0.0.0';
const PAYLOAD_SIZE = 256 * 1024;               // 共享负载块 256 KiB
const MAX_PHASE_MS = 120000;                   // 单阶段上限 120 s
const MAX_CHUNK_BYTES = 64 * 1024 * 1024;      // 单分片上限 64 MiB
const SESSION_TTL_MS = 10 * 60 * 1000;         // 会话保留 10 分钟
const SERVER_NAME = 'lan-speedtest-node/1.0';
const DEBUG = !!process.env.SPEEDTEST_DEBUG;

const PAYLOAD = crypto.randomBytes(PAYLOAD_SIZE);   // 每次启动随机，避免被中间设备识别/压缩
const WEB_DIR = path.join(__dirname, '..', 'web');
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.png': 'image/png',
};

/* ===================== 会话 ===================== */
/** @type {Map<string, any>} */
const sessions = new Map();

function newSession(phaseMs) {
  return {
    id: crypto.randomBytes(16).toString('hex'),
    phaseMs,
    createdAt: Date.now(),
    touched: Date.now(),
    upBytes: 0, downBytes: 0,
    upT0: 0, upT1: 0,          // 上传窗口（高精度单调时间，ms）
    downT0: 0, downT1: 0,      // 下载窗口（仅兼容模式使用）
    chunksUp: 0, chunksDown: 0,
  };
}

setInterval(() => {
  const now = Date.now();
  for (const [id, s] of sessions) if (now - s.touched > SESSION_TTL_MS) sessions.delete(id);
}, 60000).unref();

/* ===================== 小工具 ===================== */
const now = () => Number(process.hrtime.bigint() / 1000n) / 1000;   // ms，单调

function longParam(sp, key, def, min, max) {
  const raw = sp.get(key);
  if (raw === null || raw === '') return def;
  const v = Number(raw);
  if (!Number.isFinite(v)) return def;
  return Math.min(max, Math.max(min, Math.trunc(v)));
}

function cors(res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', '*');
  res.setHeader('Access-Control-Max-Age', '86400');
  res.setHeader('Server', SERVER_NAME);
}

function sendJson(res, code, obj) {
  const body = Buffer.from(JSON.stringify(obj), 'utf8');
  cors(res);
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': body.length,
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

function sendText(res, code, text) {
  const body = Buffer.from(text, 'utf8');
  cors(res);
  res.writeHead(code, {
    'Content-Type': 'text/plain; charset=utf-8',
    'Content-Length': body.length,
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

function mbps(bytes, ms) {
  if (!(bytes > 0) || !(ms > 0)) return 0;
  return Math.round((bytes * 8) / (ms / 1000) / 1e6 * 1000) / 1000;
}

/** 等 socket 把缓冲吐出去，避免一次性把 64 MiB 堆在内存里 */
function drained(res) {
  return new Promise(resolve => {
    const done = () => { res.off('drain', done); res.off('close', done); res.off('error', done); resolve(); };
    res.once('drain', done);
    res.once('close', done);
    res.once('error', done);
  });
}

/** 向响应写 total 字节负载，返回实际写出的字节数和是否被中断 */
async function writePayload(res, total, isAlive) {
  let written = 0;
  while (written < total) {
    if (!isAlive()) break;
    const n = Math.min(PAYLOAD_SIZE, total - written);
    const ok = n === PAYLOAD_SIZE ? res.write(PAYLOAD) : res.write(PAYLOAD.subarray(0, n));
    written += n;
    if (!ok) await drained(res);
  }
  return written;
}

function getSession(sp) {
  const sid = sp.get('sid');
  const s = sid ? sessions.get(sid) : null;
  if (s) s.touched = Date.now();
  return s || null;
}

/* ===================== 处理函数 ===================== */
function handleSession(req, res, sp) {
  const ms = longParam(sp, 'ms', 10000, 1000, MAX_PHASE_MS);
  const s = newSession(ms);
  sessions.set(s.id, s);
  sendJson(res, 200, { sid: s.id, phaseMs: ms, serverTime: Date.now() });
}

function handlePing(req, res) {
  cors(res);
  res.writeHead(204, { 'X-Server-Time': String(Date.now()), 'Cache-Control': 'no-store' });
  res.end();
}

function handleReport(req, res, sp) {
  const s = getSession(sp);
  if (!s) return sendJson(res, 404, { error: 'session not found' });
  const upMs = s.upT1 > s.upT0 ? Math.round((s.upT1 - s.upT0) * 1000) / 1000 : 0;
  const downMs = s.downT1 > s.downT0 ? Math.round((s.downT1 - s.downT0) * 1000) / 1000 : 0;
  sendJson(res, 200, {
    sid: s.id,
    phaseMs: s.phaseMs,
    up: { bytes: s.upBytes, ms: upMs, mbps: mbps(s.upBytes, upMs), chunks: s.chunksUp },
    down: { bytes: s.downBytes, ms: downMs, mbps: mbps(s.downBytes, downMs), chunks: s.chunksDown },
    serverTime: Date.now(),
  });
}

async function handleDownload(req, res, sp) {
  const s = getSession(sp);
  if (!s) return sendJson(res, 404, { error: 'session not found' });
  const warmup = sp.get('warmup') === '1';
  const chunk = longParam(sp, 'chunk', 0, 0, MAX_CHUNK_BYTES);
  const headOnly = req.method === 'HEAD';

  /* ---- 模式 A：固定分片（带 Content-Length，客户端只用原生下载 + 计数）---- */
  if (chunk > 0) {
    cors(res);
    res.writeHead(200, {
      'Content-Type': 'application/octet-stream',
      'Content-Length': chunk,
      'Cache-Control': 'no-store',
      'X-Chunk-Bytes': String(chunk),
      'X-Accel-Buffering': 'no',
    });
    if (headOnly) return res.end();

    let alive = true;
    const onGone = () => { alive = false; };
    res.once('close', onGone);
    const t0 = now();
    const written = await writePayload(res, chunk, () => alive && !res.writableEnded);
    res.off('close', onGone);
    if (written > 0 && !warmup) {
      s.downBytes += written;
      s.chunksDown++;
      if (DEBUG) process.stdout.write(`[debug] down chunk ${written}B in ${Math.round(now() - t0)}ms\n`);
    }
    if (alive && !res.writableEnded) res.end();
    return;
  }

  /* ---- 模式 B：截止时间持续流（兼容旧链接，chunked）---- */
  cors(res);
  res.writeHead(200, {
    'Content-Type': 'application/octet-stream',
    'Cache-Control': 'no-store',
    'X-Accel-Buffering': 'no',
  });
  if (headOnly) return res.end();

  let alive = true;
  res.once('close', () => { alive = false; });
  const isAlive = () => alive && !res.writableEnded;

  if (warmup) {
    const endAt = now() + 2500;
    while (now() < endAt && isAlive()) await writePayload(res, PAYLOAD_SIZE, isAlive);
  } else {
    if (!s.downT0) s.downT0 = now();
    const deadline = s.downT0 + s.phaseMs;
    let written = 0;
    while (now() < deadline && isAlive()) written += await writePayload(res, PAYLOAD_SIZE, isAlive);
    if (written > 0) {
      s.downBytes += written;
      s.chunksDown++;
      s.downT1 = now();
    }
  }
  if (isAlive()) res.end();
}

async function handleUpload(req, res, sp) {
  const s = getSession(sp);
  if (!s) { req.resume(); return sendJson(res, 404, { error: 'session not found' }); }
  const warmup = sp.get('warmup') === '1';
  const chunk = longParam(sp, 'chunk', 0, 0, MAX_CHUNK_BYTES);

  /* ---- 模式 A：固定分片，两端字节数都精确 ---- */
  if (chunk > 0) {
    let got = 0;
    let overflow = false;
    let ended = false;
    await new Promise(resolve => {
      req.on('data', b => {
        got += b.length;
        if (got > chunk && !overflow) {         // 客户端多发：吃到这里为止，断开避免污染
          overflow = true;
          req.destroy();
          resolve();
        }
      });
      req.on('end', () => { ended = true; resolve(); });
      req.on('close', resolve);
      req.on('error', resolve);
    });
    const counted = Math.min(got, chunk);
    if (!warmup && counted > 0) {
      const t = now();
      if (!s.upT0) s.upT0 = t;
      s.upT1 = t;                               // 最后一片读完的时刻 = 窗口结束
      s.upBytes += counted;
      s.chunksUp++;
    }
    if (ended && !res.writableEnded) {
      cors(res);
      res.writeHead(200, { 'X-Bytes-Read': String(counted), 'Content-Length': 0 });
      res.end();
    } else {
      try { res.destroy(); } catch (e) { /* 客户端已断开 */ }
    }
    return;
  }

  /* ---- 模式 B：截止时间持续收（兼容旧链接，chunked）---- */
  let deadline = 0;                             // 0 = 还没锁定窗口
  let counted = 0;
  let ended = false;
  await new Promise(resolve => {
    const finish = () => resolve();
    req.on('data', b => {
      const t = now();
      if (warmup) {
        if (!deadline) deadline = t + 2500;      // 预热固定 2.5 秒，不计数
      } else {
        if (!s.upT0) s.upT0 = t;
        if (!deadline) deadline = s.upT0 + s.phaseMs;
        counted += b.length;
        s.upT1 = t;
      }
      if (deadline && t >= deadline) { req.destroy(); finish(); }
    });
    req.on('end', () => { ended = true; finish(); });
    req.on('close', finish);
    req.on('error', finish);
  });
  if (!warmup && counted > 0) { s.upBytes += counted; s.chunksUp++; }
  if (ended && !res.writableEnded) {
    cors(res);
    res.writeHead(200, { 'X-Bytes-Read': String(counted), 'Content-Length': 0 });
    res.end();
  } else {
    try { res.destroy(); } catch (e) { /* 已断开 */ }
  }
}

/* ===================== 静态页面 ===================== */
function handleStatic(req, res, pathname) {
  let rel = decodeURIComponent(pathname);
  if (rel === '/' || rel === '') rel = '/index.html';
  const full = path.join(WEB_DIR, path.normalize(rel).replace(/^([/\\])+/, ''));
  if (!full.startsWith(WEB_DIR)) return sendText(res, 403, '403 Forbidden');

  fs.readFile(full, (err, data) => {
    if (err) return sendText(res, 404, '404 Not Found: ' + rel);
    cors(res);
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(full).toLowerCase()] || 'application/octet-stream',
      'Content-Length': data.length,
      'Cache-Control': 'no-cache',
    });
    if (req.method === 'HEAD') return res.end();
    res.end(data);
  });
}

/* ===================== 路由 ===================== */
const server = http.createServer((req, res) => {
  const u = new URL(req.url, 'http://localhost');
  const pathname = u.pathname;
  const sp = u.searchParams;

  res.on('error', () => { /* 客户端强断，忽略 */ });
  req.on('error', () => { /* 同上 */ });

  if (req.method === 'OPTIONS') {
    cors(res);
    res.writeHead(204);
    return res.end();
  }

  const route = () => {
    switch (pathname) {
      case '/api/session':
        if (req.method !== 'POST') return sendText(res, 405, 'POST only');
        return handleSession(req, res, sp);
      case '/api/ping':
        return handlePing(req, res);
      case '/api/report':
        return handleReport(req, res, sp);
      case '/api/download':
        return handleDownload(req, res, sp);
      case '/api/upload':
        if (req.method !== 'POST' && req.method !== 'PUT') return sendText(res, 405, 'POST only');
        return handleUpload(req, res, sp);
      case '/api/info':
        return sendJson(res, 200, {
          server: SERVER_NAME,
          runtime: `node ${process.version}`,
          maxPhaseMs: MAX_PHASE_MS,
          maxChunkBytes: MAX_CHUNK_BYTES,
          chunkedDownload: true,
          chunkedUpload: true,
        });
      default:
        return handleStatic(req, res, pathname);
    }
  };

  try {
    const r = route();
    if (r && typeof r.catch === 'function') {
      r.catch(e => {
        console.error('[warn]', req.method, pathname, '->', e && e.message);
        if (!res.headersSent) sendJson(res, 500, { error: 'internal' });
        else try { res.destroy(); } catch (e2) { /* 忽略 */ }
      });
    }
  } catch (e) {
    console.error('[warn]', req.method, pathname, '->', e && e.message);
    if (!res.headersSent) sendJson(res, 500, { error: 'internal' });
  }
});

/* ===================== 启动 ===================== */
server.keepAliveTimeout = 30000;
server.headersTimeout = 35000;
server.requestTimeout = 0;          // 长测速请求不能被默认超时打断
server.maxRequestsPerSocket = 0;    // 不主动断连接，减少握手开销

function localIPv4() {
  // 排掉 WSL / Hyper-V / VPN / 代理（Clash 等 fake-ip 用 198.18.0.0/15）的虚拟网卡，
  // 否则会把一堆访问不到的地址当成"内网地址"打印出来。
  const preferred = [];
  const others = [];
  for (const [name, list] of Object.entries(os.networkInterfaces())) {
    for (const ni of list || []) {
      if (ni.family !== 'IPv4' || ni.internal) continue;
      const ip = ni.address;
      if (ip.startsWith('169.254.') || ip.startsWith('198.18.') || ip.startsWith('198.19.')) continue;
      // 名字里带这些关键字的一般是虚拟网卡
      if (/vEthernet|WSL|Hyper-V|VirtualBox|VMware|Loopback|TAP|Tailscale|ZeroTier|Docker/i.test(name)) {
        others.push(ip);
      } else {
        preferred.push(ip);
      }
    }
  }
  const out = [...new Set(preferred)].concat([...new Set(others)]);
  return out.length ? out : ['127.0.0.1'];
}

function main() {
  let port = Number(process.argv[2] || DEFAULT_PORT);
  if (!Number.isFinite(port) || port <= 0) port = DEFAULT_PORT;
  const host = process.argv[3] || DEFAULT_HOST;

  server.on('error', err => {
    if (err.code === 'EADDRINUSE') {
      console.error(`\n[错误] 端口 ${port} 已被占用。换一个端口：node server/server.js 8081\n`);
      process.exit(2);
    }
    console.error('[错误]', err.message);
    process.exit(1);
  });

  server.listen(port, host, () => {
    const line = '='.repeat(62);
    console.log(line);
    console.log(' LAN Speed Test Server  (Node.js, zero dependency)');
    console.log(line);
    console.log(` runtime : node ${process.version}`);
    console.log(` listen  : ${host}:${port}`);
    for (const ip of localIPv4()) console.log(` access  : http://${ip}:${port}/`);
    console.log(` loopback: http://127.0.0.1:${port}/`);
    console.log(line);
    console.log(' 其他设备请用上面的 access 地址访问（需放行防火墙）。');
    console.log(' 停止服务：Ctrl + C');
    console.log(line);
  });

  const shutdown = () => {
    console.log('\n正在停止服务...');
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 1500).unref();
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

if (require.main === module) main();
module.exports = { server, sessions };
