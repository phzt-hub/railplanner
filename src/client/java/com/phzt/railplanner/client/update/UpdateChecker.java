package com.phzt.railplanner.client.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.phzt.railplanner.client.config.RailConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 向 GitHub 查这个仓库的最新 release，比自己新就在聊天栏说一声。
 *
 * <p>只发一个 GET，不传任何本地数据；带 ETag，命中缓存时 GitHub 回 304、不计入
 * 未登录接口的 60 次/小时限流。</p>
 */
public final class UpdateChecker {

    public enum Status {
        NEVER, CHECKING, UP_TO_DATE, NEWER, FAILED
    }

    private static final Logger LOG = LoggerFactory.getLogger("railplanner/update");

    public static final String REPO = "phzt-hub/railplanner";
    public static final String RELEASES_PAGE = "https://github.com/" + REPO + "/releases";
    private static final String API_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "railplanner-update");
        t.setDaemon(true);
        return t;
    });

    private static HttpClient http;

    private static volatile Status status = Status.NEVER;
    private static volatile String detail = "";
    private static volatile boolean inFlight;
    private static String notifiedVersion = "";
    private static int joinDelay = -1;
    private static int tickCounter;

    private UpdateChecker() {
    }

    public static void init() {
        primeFromCache();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            joinDelay = 60;
            renotifyIfPending(client);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> joinDelay = -1);
    }

    /** 重启游戏后先用上次存下来的结果把状态填上，免得设置页明明有「上次检查」却写「还没查过」。 */
    private static void primeFromCache() {
        RailConfig cfg = RailConfig.get();
        if (cfg.lastCheckEpochMs <= 0 || cfg.latestVersion == null || cfg.latestVersion.isEmpty()) {
            return;
        }
        detail = cfg.latestVersion;
        status = Version.isNewer(cfg.latestVersion, currentVersion()) ? Status.NEWER : Status.UP_TO_DATE;
    }

    public static Status status() {
        return status;
    }

    /** 线上那份比自己新。 */
    public static boolean isNewer() {
        return status == Status.NEWER;
    }

    public static String detail() {
        return detail;
    }

    public static long lastCheckEpochMs() {
        return RailConfig.get().lastCheckEpochMs;
    }

    public static String currentVersion() {
        return FabricLoader.getInstance().getModContainer("railplanner")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("0.0.0");
    }

    public static void tick(Minecraft mc) {
        if (joinDelay > 0) {
            joinDelay--;
            if (joinDelay == 0) {
                checkIfDue(false);
            }
        }
        if (++tickCounter % 6000 == 0) {
            checkIfDue(false);
        }
    }

    /** 设置页的「立即检查」：不看间隔，也不看开关。 */
    public static void checkNow() {
        checkIfDue(true);
    }

    private static void checkIfDue(boolean manual) {
        RailConfig cfg = RailConfig.get();
        if (!manual) {
            if (!cfg.checkUpdates) {
                return;
            }
            long due = cfg.lastCheckEpochMs + cfg.intervalHours * 3_600_000L;
            if (cfg.lastCheckEpochMs > 0 && System.currentTimeMillis() < due) {
                return;
            }
        }
        start();
    }

    private static synchronized void start() {
        if (inFlight) {
            return;
        }
        inFlight = true;
        status = Status.CHECKING;
        detail = "";
        String etag = RailConfig.get().lastEtag;
        String current = currentVersion();
        LOG.info("检查更新：本地 {}，缓存标记 {}", current, etag == null || etag.isEmpty() ? "无" : "有");
        CompletableFuture.runAsync(() -> fetch(etag, current), IO);
    }

    private static void fetch(String etag, String current) {
        int code;
        String body = null;
        String newEtag = etag;
        String error = null;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(API_URL))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "RailPlanner/" + current)
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .timeout(Duration.ofSeconds(12))
                    .GET();
            if (etag != null && !etag.isEmpty()) {
                builder.header("If-None-Match", etag);
            }
            HttpResponse<String> response = client().send(builder.build(), HttpResponse.BodyHandlers.ofString());
            code = response.statusCode();
            body = response.body();
            String header = response.headers().firstValue("ETag").orElse(null);
            if (header != null && !header.isEmpty()) {
                newEtag = header;
            }
        } catch (IOException | RuntimeException e) {
            code = -1;
            error = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            code = -1;
            error = "interrupted";
        }
        int finalCode = code;
        String finalBody = body;
        String finalEtag = newEtag;
        String finalError = error;
        Minecraft.getInstance().execute(() -> finish(finalCode, finalBody, finalEtag, current, finalError));
    }

    private static HttpClient client() {
        if (http == null) {
            http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }
        return http;
    }

    private static void finish(int code, String body, String etag, String current, String error) {
        RailConfig cfg = RailConfig.get();
        cfg.lastCheckEpochMs = System.currentTimeMillis();
        if (etag != null) {
            cfg.lastEtag = etag;
        }
        inFlight = false;
        if (code == 200 && body != null) {
            try {
                JsonObject release = JsonParser.parseString(body).getAsJsonObject();
                String tag = release.has("tag_name") ? release.get("tag_name").getAsString() : "";
                String url = release.has("html_url") ? release.get("html_url").getAsString() : RELEASES_PAGE;
                cfg.latestVersion = tag;
                cfg.latestUrl = url;
                readAsset(release, cfg);
                evaluate(tag, url, current);
                // 有新版本且开了自动更新：后台下载、校验、替换，然后设置页上会多出「立即重启」
                Updater.maybeAuto();
            } catch (RuntimeException e) {
                status = Status.FAILED;
                detail = "响应不是合法 JSON";
            }
        } else if (code == 304) {
            String url = cfg.latestUrl.isEmpty() ? RELEASES_PAGE : cfg.latestUrl;
            evaluate(cfg.latestVersion, url, current);
        } else if (code == 404) {
            status = Status.FAILED;
            detail = "404 仓库或 release 不存在";
        } else if (code == 403 || code == 429) {
            status = Status.FAILED;
            detail = "接口限流 " + code;
        } else if (code < 0) {
            status = Status.FAILED;
            detail = error == null ? "网络错误" : error;
        } else {
            status = Status.FAILED;
            detail = "HTTP " + code;
        }
        cfg.save();
        LOG.info("检查完成：HTTP {}，线上 {}，状态 {} {}", code, cfg.latestVersion,
                status, detail.isEmpty() ? "" : "— " + detail);
    }

    /** 从 release 的 assets 里挑出模组 jar，记下地址、大小和 sha256。 */
    private static void readAsset(JsonObject release, RailConfig cfg) {
        cfg.pendingAssetUrl = "";
        cfg.pendingSha256 = "";
        cfg.pendingSize = 0;
        if (!release.has("assets") || !release.get("assets").isJsonArray()) {
            return;
        }
        for (JsonElement element : release.getAsJsonArray("assets")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject asset = element.getAsJsonObject();
            String name = asset.has("name") ? asset.get("name").getAsString() : "";
            if (!name.startsWith("railplanner") || !name.endsWith(".jar")) {
                continue;
            }
            cfg.pendingAssetUrl = asset.has("browser_download_url")
                    ? asset.get("browser_download_url").getAsString() : "";
            cfg.pendingSha256 = asset.has("digest") ? asset.get("digest").getAsString() : "";
            cfg.pendingSize = asset.has("size") ? asset.get("size").getAsLong() : 0;
            return;
        }
    }

    private static void evaluate(String tag, String url, String current) {
        if (tag == null || tag.isEmpty()) {
            status = Status.FAILED;
            detail = "没有可用的版本号";
            return;
        }
        if (Version.isNewer(tag, current)) {
            status = Status.NEWER;
            detail = tag;
            notifyAvailable(tag, current, url);
        } else {
            status = Status.UP_TO_DATE;
            detail = tag;
        }
    }

    /** 进世界时如果上次已经查到了新版本但没提示过，补一条。 */
    private static void renotifyIfPending(Minecraft mc) {
        if (status == Status.NEWER && !detail.isEmpty() && !detail.equals(notifiedVersion)) {
            String url = RailConfig.get().latestUrl.isEmpty() ? RELEASES_PAGE : RailConfig.get().latestUrl;
            notifyAvailable(detail, currentVersion(), url);
        }
    }

    private static void notifyAvailable(String tag, String current, String url) {
        if (tag.equals(notifiedVersion)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            return;
        }
        notifiedVersion = tag;
        Component line = Component.translatable("railplanner.chat.prefix").withStyle(ChatFormatting.GOLD)
                .append(Component.translatable("railplanner.update.available", tag, current)
                        .withStyle(ChatFormatting.YELLOW))
                .append(Component.translatable("railplanner.update.open")
                        .withStyle(Style.EMPTY
                                .withColor(ChatFormatting.AQUA)
                                .withUnderlined(true)
                                .withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))));
        mc.gui.hud.getChat().addClientSystemMessage(line);
        LOG.info("发现新版本 {}（当前 {}）：{}", tag, current, url);
    }
}
