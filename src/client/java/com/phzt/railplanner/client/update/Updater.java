package com.phzt.railplanner.client.update;

import com.phzt.railplanner.RailPlanner;
import com.phzt.railplanner.client.config.RailConfig;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 自动更新：下载 → 校验 → 就地替换 mods 里的 jar → 提示重启。
 *
 * <p>Fabric 的模组没法在游戏里换代码（类已经加载、mixin 已经织进去），所以能省掉的是
 * 「关游戏、开启动器、下载、解压、替换」这几步，最后仍然要重启一次。这个类负责前面全部。</p>
 *
 * <p>替换用「先写成 .new 再改名」：中途断网最多留下一个 .new，原 jar 不会被写坏。</p>
 */
public final class Updater {

    public enum State {
        /** 还没开始。 */
        IDLE,
        /** 正在下载。 */
        DOWNLOADING,
        /** 下载并校验通过，只差重启。 */
        INSTALLED,
        /** 出错，看 {@link #detail()}。 */
        FAILED
    }

    private static final Logger LOG = LoggerFactory.getLogger("railplanner/updater");
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread t = new Thread(runnable, "railplanner-download");
        t.setDaemon(true);
        return t;
    });

    private static volatile State state = State.IDLE;
    private static volatile String detail = "";
    private static volatile String version = "";

    private Updater() {
    }

    public static State state() {
        return state;
    }

    public static String detail() {
        return detail;
    }

    public static String version() {
        return version;
    }

    public static boolean installed() {
        return state == State.INSTALLED;
    }

    public static boolean downloading() {
        return state == State.DOWNLOADING;
    }

    static Path cacheDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("railplanner").resolve("updates");
    }

    static Path cacheFile(String ver) {
        return cacheDir().resolve("railplanner-" + ver.replaceAll("[^0-9A-Za-z._-]", "_") + ".jar");
    }

    /** 查到新版本时调用：开了自动更新就开始下载。 */
    public static void maybeAuto() {
        RailConfig cfg = RailConfig.get();
        if (!cfg.autoUpdate || state == State.DOWNLOADING || state == State.INSTALLED) {
            return;
        }
        if (!UpdateChecker.isNewer()) {
            return;
        }
        String ver = cfg.latestVersion;
        if (ver == null || ver.isBlank()) {
            return;
        }
        String url = cfg.pendingAssetUrl;
        if (url == null || url.isBlank()) {
            return;
        }
        download(ver, url, cfg.pendingSha256, cfg.pendingSize);
    }

    /** 设置页的「下载并安装」。 */
    public static void downloadNow() {
        RailConfig cfg = RailConfig.get();
        if (!UpdateChecker.isNewer()) {
            state = State.IDLE;
            detail = "已经是最新版";
            return;
        }
        download(cfg.latestVersion, cfg.pendingAssetUrl, cfg.pendingSha256, cfg.pendingSize);
    }

    private static void download(String ver, String url, String sha, long size) {
        if (state == State.DOWNLOADING) {
            return;
        }
        if (url == null || url.isBlank()) {
            fail("这个 release 没有可下载的 jar");
            return;
        }
        version = ver;
        state = State.DOWNLOADING;
        detail = "正在下载 " + ver;
        LOG.info("开始下载 {} → {}", ver, url);
        IO.submit(() -> {
            try {
                Path out = cacheFile(ver);
                Files.createDirectories(out.getParent());
                HttpClient http = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", "RailPlanner/" + UpdateChecker.currentVersion())
                        .timeout(Duration.ofMinutes(3))
                        .GET()
                        .build();
                HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    fail("下载失败 HTTP " + response.statusCode());
                    return;
                }
                try (InputStream in = response.body()) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
                long actual = Files.size(out);
                if (size > 0 && actual != size) {
                    fail("大小不对（" + actual + " ≠ " + size + "）");
                    return;
                }
                if (sha != null && !sha.isBlank()) {
                    String want = sha.startsWith("sha256:") ? sha.substring(7) : sha;
                    String got = sha256(out);
                    if (!got.equalsIgnoreCase(want)) {
                        fail("sha256 校验不通过");
                        return;
                    }
                    LOG.info("sha256 校验通过：{}", got);
                }
                LOG.info("下载完成：{}（{} 字节）", out, actual);
                install(out, ver);
            } catch (Throwable t) {
                fail(t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        });
    }

    /** 把下载好的 jar 顶掉 mods 里正在用的那个。 */
    static void install(Path jar, String ver) {
        Path target = currentJar();
        if (target == null) {
            fail("找不到 mods 里的 jar（开发环境不替换）");
            return;
        }
        try {
            Path temp = target.resolveSibling(target.getFileName() + ".new");
            Files.copy(jar, temp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            state = State.INSTALLED;
            detail = ver + " 已装好，重启生效";
            LOG.info("已替换 {} → {}（重启后生效）", target, ver);
        } catch (IOException e) {
            fail("替换失败（文件可能被占用）：" + e.getMessage());
        }
    }

    /** 当前跑着的 jar 在哪。开发环境是 classes 目录，返回 null。 */
    static Path currentJar() {
        Optional<ModContainer> mod = FabricLoader.getInstance().getModContainer(RailPlanner.MOD_ID);
        if (mod.isEmpty()) {
            return null;
        }
        for (Path path : mod.get().getRootPaths()) {
            if (path != null && path.toString().endsWith(".jar") && Files.isRegularFile(path)) {
                return path;
            }
        }
        return null;
    }

    /**
     * 用同一行启动命令把自己再拉起来，然后关掉当前这个。
     *
     * <p>子进程先 sleep 2 秒再 exec，给当前进程留出保存世界、释放文件锁的时间。</p>
     */
    public static boolean restart() {
        String cmd = ProcessHandle.current().info().commandLine().orElse("");
        if (cmd.isBlank()) {
            detail = "拿不到启动命令行，请手动重启";
            LOG.warn("重启失败：commandLine 为空");
            return false;
        }
        try {
            new ProcessBuilder("sh", "-c", "sleep 2; exec " + cmd).inheritIO().start();
            LOG.info("重启游戏：{}", cmd);
        } catch (IOException e) {
            detail = "重启失败：" + e.getMessage();
            LOG.warn("重启失败：{}", e.toString());
            return false;
        }
        Minecraft.getInstance().stop();
        return true;
    }

    private static void fail(String reason) {
        state = State.FAILED;
        detail = reason;
        LOG.warn("更新失败：{}", reason);
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 给界面用的一行状态。 */
    public static String statusText() {
        return switch (state) {
            case IDLE -> "未下载";
            case DOWNLOADING -> detail;
            case INSTALLED -> detail;
            case FAILED -> "更新失败：" + detail;
        };
    }
}
