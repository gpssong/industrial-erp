package com.industrial.erp.modules.base.label;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 一次性 MySQL 容器 (docs/36 Task 6 Step 2)。
 *
 * <p>**只负责「把容器起起来 / 拆掉」，不负责测试语义。** 用 docker CLI 而不是
 * {@code org.testcontainers:mysql} 的原因是本机实测：testcontainers 1.19.7 里
 * {@code DockerClientProviderStrategy} 在配置的 API 版本为 UNKNOWN 时硬编码
 * {@code GET /v1.32/info}，而本机 Docker Engine 29.5.3 对 /v1.24 与 /v1.32 一律回 400
 * （/v1.40 才 200），{@code isDockerAvailable()} 因此恒为 false 并抛
 * {@code BadRequestException}；{@code DOCKER_API_VERSION}、{@code -Dapi.version}、
 * {@code DOCKER_HOST}、{@code TESTCONTAINERS_RYUK_DISABLED} 全部试过都改不了那一行请求，
 * 而 Docker Hub 在本机不可达、{@code ~/.m2} 里也没有更新的 testcontainers 包，
 * 又不许动 pom —— 所以容器供给这一段换成 docker CLI，**其余 brief 约束一条不动**。
 *
 * <p>三条纪律：
 * <ol>
 *   <li>命令一律走 {@link ProcessBuilder} 的 argv 列表，**永不拼 shell 字符串**；</li>
 *   <li>容器名带本次运行的唯一后缀，{@link #close()} 只删自己起的那一个，
 *       别的线程留下的 {@code p4} / {@code mysql-test} / {@code nginx-test} 之类绝不碰；</li>
 *   <li>Docker 不可用、镜像不在本地、起不来、连不上 —— 全部**抛异常把测试卡住**，
 *       不许静默跳过（brief Step 2 明确禁止 {@code disabledWithoutDocker}）。</li>
 * </ol>
 */
final class ThrowawayMysql implements AutoCloseable {

    /**
     * 本机已有的镜像（MySQL Community 8.0.46）。
     *
     * <p>不用 brief 写的 {@code mysql:8.0.36}：Docker Hub 从这台机器不可达
     * （registry-1.docker.io context deadline exceeded），那个 tag 本地没有、也拉不下来，
     * 而 {@code mysql:8.0} 在本地。两者都是 MySQL 8.0 系列，REPEATABLE READ、InnoDB 行锁、
     * {@code FOR UPDATE} 与 trigger 语义完全一致。
     */
    private static final String IMAGE = "mysql:8.0";
    static final String DATABASE = "label_p1_test";
    static final String USER = "root";
    /** 一次性容器专用口令：容器随测试销毁，库名 label_p1_test 不在任何环境里存在，不是任何生产/测试账号。 */
    static final String PASSWORD = "label_p1_throwaway_only";

    /** PATH 里找不到时的兜底位置（Docker Desktop 与 Homebrew 的常见安装点）。 */
    private static final List<String> DOCKER_FALLBACK_PATHS =
            List.of("/usr/local/bin/docker", "/opt/homebrew/bin/docker", "/usr/bin/docker");

    /**
     * docker 可执行文件的**绝对路径**。
     *
     * <p>不能只写 {@code "docker"} 交给 {@link ProcessBuilder}：它的 PATH 继承自启动 maven 的那个
     * shell，而本机 Docker Desktop 的软链在 {@code /usr/local/bin}，不在所有非交互 shell 的 PATH 里
     * （实测子 shell 里跑本 IT 得到 {@code error=2, No such file or directory}）。
     * 解析不到就抛「明确阻塞」—— 按 brief Step 2 依旧不许静默跳过。
     */
    private static final String DOCKER = findDocker();

    private final String containerName;
    /** 端口在 start() 时读一次就存下来：IT 里每条回读都新开连接，不该为此反复 exec docker port。 */
    private final int hostPort;

    private ThrowawayMysql(String containerName, int hostPort) {
        this.containerName = containerName;
        this.hostPort = hostPort;
    }

    static ThrowawayMysql start() {
        requireDocker();
        requireLocalImage();

        String name = "label-p1-mysql-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        // 结尾的 mysqld 是必需的：本机这个镜像的 Config.Cmd 是 ["--version"]，
        // 不显式给命令的话容器 190ms 就 0 退出，什么都不会被监听。
        Result run = exec(List.of(DOCKER, "run", "-d", "--name", name,
                "-p", "127.0.0.1::3306",
                "-e", "MYSQL_ROOT_PASSWORD=" + PASSWORD,
                "-e", "MYSQL_DATABASE=" + DATABASE,
                IMAGE, "mysqld"));
        if (run.exitCode != 0) {
            throw new IllegalStateException("Task 6 集成测试被阻塞：docker run 起不来一次性 MySQL（exit="
                    + run.exitCode + "）。输出：" + run.output);
        }
        try {
            int port = hostPort(name);
            awaitReachable(port);
            // 兜底拆除：IT 里只要容器起起来了，正常路径靠 close()；但 JVM 被 -Dmaven.failsafe.debug
            // 挂住、或后续用例抛错导致类没被加载完就退出时，shutdown hook 保证不留孤儿容器。
            Runtime.getRuntime().addShutdownHook(new Thread(
                    () -> removeQuietly(name), "throwaway-mysql-cleanup"));
            return new ThrowawayMysql(name, port);
        } catch (RuntimeException | AssertionError e) {
            removeQuietly(name);
            throw e;
        }
    }

    String jdbcUrl() {
        return "jdbc:mysql://127.0.0.1:" + hostPort + "/" + DATABASE
                // characterEncoding + connectionTimeZone 是为了 elements JSON 里的中文按 utf8mb4 往返，
                // 不许在 IT 里退化成一串「看着能用」的默认参数。
                + "?useSSL=false&allowPublicKeyRetrieval=true"
                + "&characterEncoding=UTF-8&connectionTimeZone=SERVER";
    }

    @Override
    public void close() {
        removeQuietly(containerName);
    }

    // ────────────────────────── 门禁 ──────────────────────────

    private static void requireDocker() {
        Result version = exec(List.of(DOCKER, "version", "--format", "{{.Server.Version}}"));
        if (version.exitCode != 0) {
            throw new IllegalStateException("Task 6 集成测试被阻塞：Docker 守护进程不可用"
                    + "（docker version exit=" + version.exitCode + "）。输出：" + version.output
                    + " —— 按 brief Step 2，这里必须失败，不许静默跳过。");
        }
    }

    private static void requireLocalImage() {
        Result inspect = exec(List.of(DOCKER, "image", "inspect", IMAGE));
        if (inspect.exitCode != 0) {
            // 故意先探测再 run：Docker Hub 本机不可达，run 会自己去拉一个永远拉不完的镜像把 CI 挂死。
            throw new IllegalStateException("Task 6 集成测试被阻塞：本机没有 " + IMAGE
                    + " 镜像，且 Docker Hub 从这台机器不可达（registry-1.docker.io 超时），"
                    + "无法拉取。请先本地准备该镜像再跑本 IT。探测输出：" + inspect.output);
        }
    }

    // ────────────────────────── 端口与就绪 ──────────────────────────

    private static int hostPort(String name) {
        Result port = exec(List.of(DOCKER, "port", name, "3306"));
        if (port.exitCode != 0) {
            throw new IllegalStateException("Task 6 集成测试被阻塞：docker port 读不到 " + name
                    + " 的宿主端口（exit=" + port.exitCode + "）。输出：" + port.output);
        }
        for (String line : port.output.split("\\R")) {
            int idx = line.lastIndexOf(':');
            if (idx < 0 || !line.startsWith("127.0.0.1")) continue;
            try {
                return Integer.parseInt(line.substring(idx + 1).trim());
            } catch (NumberFormatException ignored) {
                // 继续找下一行
            }
        }
        throw new IllegalStateException("Task 6 集成测试被阻塞：docker port 输出里没有 127.0.0.1 的端口映射，"
                + "实际输出：" + port.output);
    }

    /**
     * 用 JDBC 轮询等就绪 —— 端口在 mysqld 真正能接受查询**之前**就发布了，
     * 所以 {@code nc} 探活和固定 sleep 都会假阳性（前者连上就断、后者要么慢要么不稳）。
     */
    private static void awaitReachable(int port) {
        String url = "jdbc:mysql://127.0.0.1:" + port + "/" + DATABASE
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=2000&socketTimeout=5000";
        long deadline = System.currentTimeMillis() + 180_000L;
        String last = "(none)";
        int attempts = 0;
        while (System.currentTimeMillis() < deadline) {
            attempts++;
            try (Connection c = DriverManager.getConnection(url, USER, PASSWORD)) {
                if (c.isValid(2)) {
                    return;
                }
                last = "connection.isValid=false";
            } catch (Exception e) {
                last = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Task 6 集成测试被阻塞：等待 MySQL 就绪时被中断。", ie);
            }
        }
        throw new IllegalStateException("Task 6 集成测试被阻塞：180 秒内一次性 MySQL 没起来（端口 " + port
                + "，尝试 " + attempts + " 次，最后一次 " + last + "）。");
    }

    // ────────────────────────── 拆除 ──────────────────────────

    private static void removeQuietly(String name) {
        // 只删这一个名字；不用 docker ps 猜、不按镜像批量删、不 stop 别人的容器。
        // 拆除失败不往上抛：它发生在 close() / catch / shutdown hook 上，这时候测试已经在报
        // 真正的失败了，再叠一个「docker rm 失败」只会把根因盖住 —— 但**必须留一行 stderr**，
        // 否则这个带唯一名字的容器会一声不响地留在机器上（评审 Task 6 fix1 NEW-2）。
        try {
            Result removed = exec(List.of(DOCKER, "rm", "-f", name));
            // 「本来就没有」是正常收尾：close() 之后 shutdown hook 还会再删一次。
            if (removed.exitCode != 0 && !removed.output.contains("No such container")) {
                System.err.println("[ThrowawayMysql] 容器 " + name + " 没能删掉（docker rm exit="
                        + removed.exitCode + "），请手工清理。输出：" + removed.output);
            }
        } catch (RuntimeException e) {
            System.err.println("[ThrowawayMysql] 容器 " + name + " 拆除调用失败：" + e.getMessage()
                    + "，请手工清理 label-p1-mysql-* 。");
        }
    }

    // ────────────────────────── 进程 ──────────────────────────

    private static String findDocker() {
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(":")) {
                if (dir.isBlank()) {
                    continue;
                }
                Path candidate = Path.of(dir, "docker");
                if (Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            }
        }
        for (String fallback : DOCKER_FALLBACK_PATHS) {
            if (Files.isExecutable(Path.of(fallback))) {
                return fallback;
            }
        }
        throw new IllegalStateException("Task 6 集成测试被阻塞：PATH 与 " + DOCKER_FALLBACK_PATHS
                + " 里都没有可执行的 docker。请先启动 Docker Desktop（或装上 CLI）再跑本 IT —— "
                + "按 brief Step 2，这里必须失败，不许静默跳过。");
    }

    private static final class Result {
        final int exitCode;
        final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    /**
     * 单次 docker CLI 调用的硬上限。
     *
     * <p>没有这个看门狗时，守护进程卡住（Docker Desktop 正在唤醒 / VM 未就绪）会让读 stdout 的
     * {@code InputStream#read} 永远阻塞，整条 IT 静默挂死而不是明确失败。brief Step 2 要的是
     * 「抛异常把测试卡住」，挂死不算卡住。
     */
    private static final long EXEC_TIMEOUT_SECONDS = 60L;

    /** {@code Process#destroyForcibly()} 之后再给多少秒收尾。 */
    private static final long KILL_GRACE_SECONDS = 10L;

    private static Result exec(List<String> argv) {
        try {
            Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            Thread reader = startReader(process, output);
            if (!process.waitFor(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                // 看门狗自己也不能挂死：destroyForcibly 之后无界 waitFor，碰到不可中断睡眠的
                // docker CLI 就又回到「静默挂死而不是明确失败」，正是这个超时想消灭的东西。
                // 退不退得干净都不改变结论 —— 这条 IT 必须明确失败。
                boolean killed = process.waitFor(KILL_GRACE_SECONDS, TimeUnit.SECONDS);
                joinReader(reader);
                throw new IllegalStateException("Task 6 集成测试被阻塞：执行 " + argv + " 超过 "
                        + EXEC_TIMEOUT_SECONDS + " 秒没返回，已强制结束"
                        + (killed ? "" : "但 " + KILL_GRACE_SECONDS + " 秒后进程仍未退出")
                        + "。Docker 守护进程可能没在正常工作，"
                        + "请确认 Docker Desktop 已完全启动后重跑 —— 按 brief Step 2 这里必须失败，"
                        + "不许静默跳过。已读到的输出：" + snapshot(output));
            }
            joinReader(reader);
            return new Result(process.exitValue(), snapshot(output));
        } catch (IOException e) {
            throw new IllegalStateException("Task 6 集成测试被阻塞：执行 " + argv + " 失败："
                    + e.getMessage() + "（docker 不在 PATH 或守护进程未启动？）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Task 6 集成测试被阻塞：执行 " + argv + " 被中断。", e);
        }
    }

    /**
     * 输出必须在**后台线程**里读：主线程要拿 {@code waitFor} 的超时控制权，
     * 而进程被强杀后流会关闭，读线程自然收尾，不会留悬挂线程（所以设为 daemon）。
     */
    private static Thread startReader(Process process, StringBuilder sink) {
        Thread thread = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) >= 0) {
                    synchronized (sink) {
                        sink.append(new String(chunk, 0, n, StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // 强杀路径必然让读流报错；能读到多少算多少，超时分支自己有明确的报错文案。
            }
        }, "throwaway-mysql-stdout");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void joinReader(Thread thread) throws InterruptedException {
        thread.join(TimeUnit.SECONDS.toMillis(10));
    }

    private static String snapshot(StringBuilder sink) {
        synchronized (sink) {
            return sink.toString();
        }
    }
}
