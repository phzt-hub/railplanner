package com.phzt.railplanner.client.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.phzt.railplanner.client.diag.Diag;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 按存档分文件的路线存储。 */
public final class RouteStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("railplanner");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private RouteStore() {
    }

    public static Path file(String worldId) {
        String safe = worldId == null || worldId.isBlank() ? "default" : worldId.replaceAll("[^a-zA-Z0-9._-]", "_");
        return FabricLoader.getInstance().getGameDir().resolve("railplanner").resolve(safe + ".json");
    }

    public static List<RailRoute> load(String worldId) {
        List<RailRoute> out = new ArrayList<>();
        Path p = file(worldId);
        if (!Files.isRegularFile(p)) {
            Diag.event("store", "读取 " + p.getFileName() + "：还没有存档文件（0 条）");
            return out;
        }
        try {
            String json = Files.readString(p, StandardCharsets.UTF_8);
            FileDto dto = GSON.fromJson(json, FileDto.class);
            if (dto != null && dto.routes != null) {
                for (RouteDto r : dto.routes) {
                    RailRoute route = new RailRoute(r.id, r.name, r.color);
                    route.radius = r.radius > 0 ? r.radius : 24.0;
                    // 老文件没有这一列：读出来是关，老线保持原样，要转得手动点「正交化」。
                    route.orthogonal = r.orthogonal;
                    // 同样：老文件没有这一列就是没缓和曲线，保持原线形。
                    route.easement = r.easement;
                    if (r.anchors != null) {
                        for (int i = 0; i < r.anchors.size(); i++) {
                            Anchor anchor = Anchor.fromArray(r.anchors.get(i));
                            if (anchor == null) {
                                continue;
                            }
                            // 名字和锚点分开存：老文件没有这一列，读出来就是没起名。
                            if (r.anchorNames != null && i < r.anchorNames.size()) {
                                anchor = anchor.withName(r.anchorNames.get(i));
                            }
                            route.anchors.add(anchor);
                        }
                    }
                    out.add(route);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("读取路线失败 {}: {}", p, e.toString());
            Diag.warn("读取路线失败 " + p + "：" + e, e);
        }
        Diag.event("store", "读取 " + p.getFileName() + " → " + out.size() + " 条线");
        return out;
    }

    /** 目录里的一份老格式文件：里面记着它自己的世界 id。 */
    public record Legacy(String world, Path path) {
    }

    /**
     * 列出 {@code railplanner/} 下所有老存档及其世界 id。
     *
     * <p>0.5.0 及以前世界的身份直接用 Xaero 的 id（单人像 {@code railplanner^us^test}，
     * 多人像 {@code Multiplayer_xxx}），文件名是它清洗后的样子。0.6.0 换成模组自己算的
     * {@code sp_/mp_} 身份，于是需要一次迁移——见 {@code Planner.migrateLegacy}。</p>
     */
    public static List<Legacy> scanLegacy() {
        List<Legacy> out = new ArrayList<>();
        Path dir = FabricLoader.getInstance().getGameDir().resolve("railplanner");
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (var entries = Files.list(dir)) {
            for (Path p : entries.toList()) {
                String name = p.getFileName().toString();
                if (!name.endsWith(".json") || !Files.isRegularFile(p)) {
                    continue;
                }
                String world = name.substring(0, name.length() - 5);
                try {
                    FileDto dto = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), FileDto.class);
                    if (dto != null && dto.world != null && !dto.world.isBlank()) {
                        world = dto.world;
                    }
                } catch (Exception ignored) {
                    // 坏文件就按文件名当世界 id，下面 load 会自己跳过
                }
                out.add(new Legacy(world, p));
            }
        } catch (IOException e) {
            LOGGER.warn("扫描老存档失败: {}", e.toString());
        }
        return out;
    }

    public static void save(String worldId, List<RailRoute> routes) {
        Path p = file(worldId);
        FileDto dto = new FileDto();
        dto.version = 5;
        dto.world = worldId;
        dto.routes = new ArrayList<>();
        for (RailRoute route : routes) {
            RouteDto r = new RouteDto();
            r.id = route.id;
            r.name = route.name;
            r.color = route.color;
            r.radius = route.radius;
            r.orthogonal = route.orthogonal;
            r.easement = route.easement;
            r.anchors = new ArrayList<>();
            r.anchorNames = new ArrayList<>();
            for (Anchor a : route.anchors) {
                r.anchors.add(a.toArray());
                r.anchorNames.add(a.name());
            }
            dto.routes.add(r);
        }
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, GSON.toJson(dto), StandardCharsets.UTF_8);
            Diag.once("store", "保存 " + p.getFileName() + "：" + routes.size() + " 条线");
        } catch (IOException e) {
            LOGGER.warn("保存路线失败 {}: {}", p, e.toString());
            Diag.warn("保存路线失败 " + p + "：" + e, e);
        }
    }

    static final class FileDto {
        int version;
        String world;
        List<RouteDto> routes;
    }

    static final class RouteDto {
        String id;
        String name;
        int color;
        double radius;
        /** 这一条线走正交布线（version 4 起）；老文件没有就是 false。 */
        boolean orthogonal;
        /** 这一条线每个弯插缓和曲线（version 5 起）；老文件没有就是 false。 */
        boolean easement;
        List<int[]> anchors;
        /** 与 anchors 同下标的点名字（version 3 起）。 */
        List<String> anchorNames;
    }
}
