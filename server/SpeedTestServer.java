/*
 * LAN Speed Test Server —— 内网带宽测速服务端
 * ---------------------------------------------------------------------------
 * 零第三方依赖：只使用 JDK 自带的 com.sun.net.httpserver.HttpServer。
 *   - 上传测速：POST /api/upload?sid=<会话>          （chunked / 未知长度流式上传）
 *   - 下载测速：GET  /api/download?sid=<会话>&ms=..   （流式二进制，无长度上限）
 *   - 时延测量：GET  /api/ping                        （纳秒级占位响应）
 *   - 结果汇总：GET  /api/report?sid=<会话>
 *   - 建会话  ：POST /api/session?ms=..
 *   - 静态页面：GET  /
 *
 * 编译：javac -encoding UTF-8 -d classes server/SpeedTestServer.java
 * 运行：java -cp classes SpeedTestServer [port] [bindHost]
 */
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class SpeedTestServer {

    /* ===================== 可调参数 ===================== */
    static final int DEFAULT_PORT      = 8000;
    static final int HTTP_THREADS      = 64;          // 支持多并发流
    static final int IO_BUF            = 256 * 1024;  // 服务端读写缓冲 256 KiB
    static final long MAX_PHASE_MS     = 120_000L;    // 单阶段最长 120 秒，防跑飞
    static final long MAX_CHUNK_BYTES  = 64L * 1024 * 1024;  // 单个下载分片上限 64 MiB
    static final long SESSION_TTL_MS   = 10 * 60_000L;
    static final String SERVER_NAME    = "lan-speedtest/1.0";
    static final boolean DEBUG         = System.getenv("SPEEDTEST_DEBUG") != null;

    /* 一份固定的伪随机负载：每个流共享，避免伪造数据成为 CPU 瓶颈 */
    static final byte[] PAYLOAD = new byte[IO_BUF];

    /* ===================== 会话 ===================== */
    static final class Session {
        final String id;
        final long phaseMs;                       // 每个阶段的测量时长
        final long createdAt = System.currentTimeMillis();

        final AtomicLong upBytes   = new AtomicLong();
        final AtomicLong downBytes = new AtomicLong();
        final AtomicLong touched   = new AtomicLong(createdAt);

        volatile long upT0, upT1;                 // 上传阶段起止（服务端时钟）
        volatile long downT0, downT1;             // 下载阶段起止

        Session(String id, long phaseMs) {
            this.id = id;
            this.phaseMs = phaseMs;
        }

        void touch() { touched.set(System.currentTimeMillis()); }

        /** 返回阶段结束的绝对纳秒时间戳（由该阶段第一次读到/写出数据时确定） */
        long phaseDeadlineNanos(boolean upload, long t0) {
            return t0 + TimeUnit.MILLISECONDS.toNanos(phaseMs);
        }
    }

    static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    /* ===================== 工具 ===================== */
    static Map<String, String> splitQuery(String rawQuery) {
        Map<String, String> map = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return map;
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            try {
                if (eq < 0) {
                    map.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
                } else {
                    map.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            } catch (IllegalArgumentException ignore) {
                // 非法百分号编码，跳过该项
            }
        }
        return map;
    }

    static long longParam(Map<String, String> q, String key, long def, long min, long max) {
        try {
            String v = q.get(key);
            if (v == null || v.isEmpty()) return def;
            long parsed = Long.parseLong(v.trim());
            if (parsed < min) return min;
            if (parsed > max) return max;
            return parsed;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static void cors(Headers h) {
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        h.set("Access-Control-Allow-Headers", "*");
        h.set("Access-Control-Max-Age", "86400");
    }

    /** 无内容响应（ping / options 用），只写头，保持连接 */
    static void empty(HttpExchange x, int code, Headers extra) throws IOException {
        Headers h = x.getResponseHeaders();
        cors(h);
        if (extra != null) extra.forEach((k, vs) -> vs.forEach(v -> h.add(k, v)));
        h.set("Server", SERVER_NAME);
        h.set("Cache-Control", "no-store");
        x.sendResponseHeaders(code, -1);
        x.close();
    }

    static void json(HttpExchange x, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        Headers h = x.getResponseHeaders();
        cors(h);
        h.set("Content-Type", "application/json; charset=utf-8");
        h.set("Server", SERVER_NAME);
        h.set("Cache-Control", "no-store");
        x.sendResponseHeaders(code, b.length);
        try (OutputStream os = x.getResponseBody()) {
            os.write(b);
        }
        x.close();
    }

    static void text(HttpExchange x, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        Headers h = x.getResponseHeaders();
        cors(h);
        h.set("Content-Type", "text/plain; charset=utf-8");
        h.set("Server", SERVER_NAME);
        h.set("Cache-Control", "no-store");
        x.sendResponseHeaders(code, b.length);
        try (OutputStream os = x.getResponseBody()) {
            os.write(b);
        }
        x.close();
    }

    /* ===================== 静态页面 ===================== */
    static final Map<String, String> MIME = new HashMap<>();
    static {
        MIME.put("html", "text/html; charset=utf-8");
        MIME.put("js", "text/javascript; charset=utf-8");
        MIME.put("css", "text/css; charset=utf-8");
        MIME.put("json", "application/json; charset=utf-8");
        MIME.put("svg", "image/svg+xml");
        MIME.put("ico", "image/x-icon");
        MIME.put("png", "image/png");
    }

    static void serveStatic(HttpExchange x, String name) throws IOException {
        if (name == null || name.isEmpty() || name.equals("/")) name = "index.html";
        if (name.startsWith("/")) name = name.substring(1);

        byte[] body = null;
        // 1) 开发模式：源码目录（每次读取，改前端不用重编译）
        java.io.File dev = new java.io.File("web", name);
        if (dev.isFile()) body = java.nio.file.Files.readAllBytes(dev.toPath());
        // 2) 部署模式：打进 classpath 的资源
        if (body == null) {
            try (InputStream in = SpeedTestServer.class.getResourceAsStream("/web/" + name)) {
                if (in != null) body = in.readAllBytes();
            }
        }
        if (body == null) {
            text(x, 404, "404 Not Found: " + name);
            return;
        }
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot >= 0) ext = name.substring(dot + 1).toLowerCase();
        Headers h = x.getResponseHeaders();
        h.set("Content-Type", MIME.getOrDefault(ext, "application/octet-stream"));
        h.set("Server", SERVER_NAME);
        h.set("Cache-Control", "no-cache");
        if ("HEAD".equalsIgnoreCase(x.getRequestMethod())) {
            // HEAD 不能带响应体，用 -1 结束，避免 sendResponseHeaders 告警
            x.sendResponseHeaders(200, -1);
            x.close();
            return;
        }
        x.sendResponseHeaders(200, body.length);
        try (OutputStream os = x.getResponseBody()) {
            os.write(body);
        }
        x.close();
    }

    /* ===================== 测速处理 ===================== */
    static void handleSession(HttpExchange x) throws IOException {
        Map<String, String> q = splitQuery(x.getRequestURI().getRawQuery());
        long ms = longParam(q, "ms", 10_000L, 1_000L, MAX_PHASE_MS);
        String id = UUID.randomUUID().toString().replace("-", "");
        Session s = new Session(id, ms);
        SESSIONS.put(id, s);
        json(x, 200, "{\"sid\":\"" + id + "\",\"phaseMs\":" + ms
                + ",\"serverTime\":" + System.currentTimeMillis() + "}");
    }

    static void handlePing(HttpExchange x) throws IOException {
        long t = System.nanoTime();
        Headers extra = new Headers();
        extra.set("X-Server-Time", Long.toString(System.currentTimeMillis()));
        empty(x, 204, extra);
        // 让 JIT 不要把这个占位响应优化掉，也方便日志排查
        if (t == 0) System.nanoTime();
    }

    static void handleReport(HttpExchange x) throws IOException {
        Map<String, String> q = splitQuery(x.getRequestURI().getRawQuery());
        String sid = q.get("sid");
        Session s = sid == null ? null : SESSIONS.get(sid);
        if (s == null) {
            json(x, 404, "{\"error\":\"session not found\"}");
            return;
        }
        s.touch();
        long upMs   = s.upT1   > s.upT0   ? TimeUnit.NANOSECONDS.toMillis(s.upT1   - s.upT0)   : 0;
        long downMs = s.downT1 > s.downT0 ? TimeUnit.NANOSECONDS.toMillis(s.downT1 - s.downT0) : 0;
        long upBytes = s.upBytes.get(), downBytes = s.downBytes.get();

        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        sb.append("\"sid\":\"").append(s.id).append('"');
        sb.append(",\"phaseMs\":").append(s.phaseMs);
        sb.append(",\"up\":{\"bytes\":").append(upBytes).append(",\"ms\":").append(upMs)
          .append(",\"mbps\":").append(mbps(upBytes, upMs)).append('}');
        sb.append(",\"down\":{\"bytes\":").append(downBytes).append(",\"ms\":").append(downMs)
          .append(",\"mbps\":").append(mbps(downBytes, downMs)).append('}');
        sb.append(",\"serverTime\":").append(System.currentTimeMillis());
        sb.append('}');
        json(x, 200, sb.toString());
    }

    static String mbps(long bytes, long ms) {
        if (ms <= 0 || bytes <= 0) return "0";
        double bitsPerSec = bytes * 8.0 / (ms / 1000.0);
        return String.format(java.util.Locale.ROOT, "%.3f", bitsPerSec / 1_000_000.0);
    }

    /** 精确读取 want 字节并返回实际读到的字节数（客户端提前断开时小于 want） */
    static long readExactly(InputStream in, long want) {
        byte[] buf = new byte[64 * 1024];
        long got = 0L;
        try {
            while (got < want) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, want - got));
                if (n < 0) break;
                got += n;
            }
        } catch (IOException clientGone) {
            // 客户端中断（停止测速）：返回已读到的部分
        }
        return got;
    }

    static void handleUpload(HttpExchange x) throws IOException {
        Map<String, String> q = splitQuery(x.getRequestURI().getRawQuery());
        Session s = q.get("sid") == null ? null : SESSIONS.get(q.get("sid"));
        if (s == null) {
            // 尽早把请求体丢掉，避免客户端继续猛灌
            x.getRequestBody().close();
            json(x, 404, "{\"error\":\"session not found\"}");
            return;
        }
        s.touch();
        boolean warmup = "1".equals(q.get("warmup"));
        // 分片模式：本次请求固定接收 chunk 字节（0 = 传统"按截止时间持续收"模式）
        long chunk = longParam(q, "chunk", 0L, 0L, MAX_CHUNK_BYTES);

        InputStream in = x.getRequestBody();

        // ---- 模式 A：固定长度分片。两端字节数都确定，不再依赖任何计时近似 ----
        if (chunk > 0) {
            long counted = readExactly(in, chunk);
            if (!warmup) {
                long now = System.nanoTime();
                synchronized (s) {
                    if (s.upT0 == 0L) s.upT0 = now;
                    if (now > s.upT1) s.upT1 = now;
                }
                s.upBytes.addAndGet(counted);
            }
            try {
                Headers rh = x.getResponseHeaders();
                cors(rh);
                rh.set("Server", SERVER_NAME);
                rh.set("X-Bytes-Read", Long.toString(counted));
                x.sendResponseHeaders(200, -1);
            } catch (IOException ignore) { }
            x.close();
            return;
        }

        // ---- 模式 B：按截止时间持续收（传统流式上传）----
        long t0 = 0L, deadline = 0L;
        if (!warmup) {
            synchronized (s) {
                if (s.upT0 == 0L) s.upT0 = System.nanoTime();
                t0 = s.upT0;
                deadline = t0 + TimeUnit.MILLISECONDS.toNanos(s.phaseMs);
            }
            // 兜底：即使某条流一直阻塞在 read 上，窗口到期也会封存结束时间
            final Session bound = s;
            final long dl = deadline;
            Thread finisher = new Thread(() -> {
                long sleepMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(dl - System.nanoTime()) + 5);
                try { Thread.sleep(sleepMs); } catch (InterruptedException e) { return; }
                long end = System.nanoTime();
                if (end >= dl) {                     // 只有窗口真的到期才计数，避免把提前结束也钉成满窗
                    synchronized (bound) {
                        if (bound.upT1 == 0L || end > bound.upT1) bound.upT1 = end;
                    }
                }
            }, "upload-finish");
            finisher.setDaemon(true);
            finisher.start();
        }

        byte[] buf = new byte[64 * 1024];
        long counted = 0L;
        int r;
        long tRead = System.nanoTime();
        while ((warmup || System.nanoTime() < deadline) && (r = in.read(buf)) > 0) {
            if (!warmup) counted += r;
        }
        if (!warmup && counted > 0) {
            s.upBytes.addAndGet(counted);
            long now = System.nanoTime();
            // 客户端提前结束（浏览器通常跑满窗口）时，用实际读到的最后一刻封存窗口
            synchronized (s) {
                if (s.upT0 == 0L) s.upT0 = now;
                if (s.upT1 == 0L || now > s.upT1) s.upT1 = now;
            }
            if (DEBUG) {
                System.out.printf("[debug] upload sid=%s bytes=%d readLoop=%.1fms window=%.1fms%n",
                        s.id.substring(0, 8), counted, (now - tRead) / 1e6, (s.upT1 - s.upT0) / 1e6);
            }
        }
        try {
            Headers h = x.getResponseHeaders();
            cors(h);
            h.set("Server", SERVER_NAME);
            x.sendResponseHeaders(200, -1);
        } catch (IOException ignore) {
            // 客户端可能已经断开
        }
        x.close();
    }

    static void handleDownload(HttpExchange x) throws IOException {
        Map<String, String> q = splitQuery(x.getRequestURI().getRawQuery());
        Session s = q.get("sid") == null ? null : SESSIONS.get(q.get("sid"));
        if (s == null) {
            json(x, 404, "{\"error\":\"session not found\"}");
            return;
        }
        s.touch();
        boolean warmup = "1".equals(q.get("warmup"));
        // 分片模式：本次请求固定发送 chunk 字节（0 = 传统"按截止时间持续流"模式）
        long chunk = longParam(q, "chunk", 0L, 0L, MAX_CHUNK_BYTES);

        Headers h = x.getResponseHeaders();
        cors(h);
        h.set("Content-Type", "application/octet-stream");
        h.set("Server", SERVER_NAME);
        h.set("Cache-Control", "no-store");
        h.set("X-Accel-Buffering", "no");

        // ---- 模式 A：固定分片。带 Content-Length，浏览器原生下载，JS 不进热路径 ----
        if (chunk > 0) {
            h.set("X-Chunk-Bytes", Long.toString(chunk));
            if ("HEAD".equalsIgnoreCase(x.getRequestMethod())) {
                x.sendResponseHeaders(200, -1);
                x.close();
                return;
            }
            x.sendResponseHeaders(200, chunk);
            try (OutputStream os = x.getResponseBody()) {
                long left = chunk;
                while (left > 0) {
                    int n = (int) Math.min(left, PAYLOAD.length);
                    os.write(PAYLOAD, 0, n);
                    left -= n;
                }
                os.flush();
                if (!warmup) s.downBytes.addAndGet(chunk);
            } catch (IOException clientGone) {
                // 客户端中途断开（停止测速），已写出的字节能算多少算多少
            }
            x.close();
            return;
        }

        // ---- 模式 B：按截止时间持续流（兼容旧链接，chunked）----
        x.sendResponseHeaders(200, 0);
        OutputStream os = x.getResponseBody();
        long counted = 0L;
        try {
            if (warmup) {
                long warmEnd = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500L);
                while (System.nanoTime() < warmEnd) {
                    os.write(PAYLOAD);
                }
            } else {
                long deadline;
                synchronized (s) {
                    if (s.downT0 == 0L) s.downT0 = System.nanoTime();
                    deadline = s.downT0 + TimeUnit.MILLISECONDS.toNanos(s.phaseMs);
                }
                while (System.nanoTime() < deadline) {
                    os.write(PAYLOAD);
                    counted += PAYLOAD.length;
                }
                long end = System.nanoTime();
                s.downBytes.addAndGet(counted);
                synchronized (s) {
                    if (s.downT1 == 0L || end > s.downT1) s.downT1 = end;
                }
            }
            os.flush();
        } catch (IOException clientGone) {
            if (counted > 0) s.downBytes.addAndGet(counted);
        } finally {
            try { os.close(); } catch (IOException ignore) { }
            x.close();
        }
    }

    /* ===================== 路由 ===================== */
    static final class Router implements HttpHandler {
        @Override public void handle(HttpExchange x) throws IOException {
            String method = x.getRequestMethod();
            String path = x.getRequestURI().getPath();

            if ("OPTIONS".equals(method)) { empty(x, 204, null); return; }

            try {
                switch (path) {
                    case "/api/session":
                        if (!"POST".equals(method)) { text(x, 405, "POST only"); return; }
                        handleSession(x);
                        return;
                    case "/api/ping":
                        handlePing(x);
                        return;
                    case "/api/report":
                        handleReport(x);
                        return;
                    case "/api/upload":
                        if (!"POST".equals(method) && !"PUT".equals(method)) { text(x, 405, "POST only"); return; }
                        handleUpload(x);
                        return;
                    case "/api/download":
                        handleDownload(x);
                        return;
                    case "/api/info":
                        json(x, 200, "{\"server\":\"" + SERVER_NAME + "\",\"threads\":" + HTTP_THREADS
                                + ",\"maxPhaseMs\":" + MAX_PHASE_MS
                                + ",\"maxChunkBytes\":" + MAX_CHUNK_BYTES
                                + ",\"chunkedDownload\":true,\"chunkedUpload\":true}");
                        return;
                    default:
                        serveStatic(x, path);
                }
            } catch (Throwable t) {
                System.err.println("[warn] " + method + " " + path + " -> " + t);
                try { json(x, 500, "{\"error\":\"internal\"}"); } catch (IOException ignore) { }
            }
        }
    }

    /* ===================== 启动 ===================== */
    static List<String> localIPv4() {
        List<String> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface nic : Collections.list(nics)) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) continue;
                for (InetAddress addr : Collections.list(nic.getInetAddresses())) {
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        out.add(addr.getHostAddress());
                    }
                }
            }
        } catch (Exception ignore) { }
        if (out.isEmpty()) out.add("127.0.0.1");
        return out;
    }

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        String bind = "0.0.0.0";
        if (args.length > 0) {
            try { port = Integer.parseInt(args[0].trim()); }
            catch (NumberFormatException e) { System.err.println("端口非法，使用默认 " + DEFAULT_PORT); }
        }
        if (args.length > 1) bind = args[1].trim();

        // 填充负载
        new Random(20240607L).nextBytes(PAYLOAD);

        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(bind, port), 1024);
        } catch (BindException e) {
            System.err.println("端口 " + port + " 已被占用（或被系统保留）。换一个端口：java -cp classes SpeedTestServer 8081");
            System.exit(2);
            return;
        }
        ThreadPoolExecutor pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(HTTP_THREADS, r -> {
            Thread t = new Thread(r, "http-" + UUID.randomUUID().toString().substring(0, 4));
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.createContext("/", new Router());
        server.start();

        // 会话回收
        Thread gc = new Thread(() -> {
            while (true) {
                try { Thread.sleep(60_000L); } catch (InterruptedException e) { return; }
                long now = System.currentTimeMillis();
                SESSIONS.entrySet().removeIf(en ->
                        now - en.getValue().touched.get() > SESSION_TTL_MS);
            }
        }, "session-gc");
        gc.setDaemon(true);
        gc.start();

        String line = "==============================================================";
        System.out.println(line);
        System.out.println(" 内网测速服务已启动  (LAN Speed Test Server v1.0)");
        System.out.println(line);
        System.out.println(" 监听        : " + bind + ":" + port);
        for (String ip : localIPv4()) {
            System.out.println(" 本机访问    : http://" + ip + ":" + port + "/");
        }
        System.out.println(" 本机回环    : http://127.0.0.1:" + port + "/");
        System.out.println(line);
        System.out.println(" 其他内网设备用上面「本机访问」地址打开即可（需放行防火墙）。");
        System.out.println(" 停止服务：按 Ctrl + C");
        System.out.println(line);
        System.out.println(" 启动时间    : " + Instant.now());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n正在停止服务...");
            server.stop(0);
            pool.shutdownNow();
        }, "shutdown"));
    }
}
