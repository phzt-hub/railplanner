package com.phzt.railplanner.client.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
            return out;
        }
        try {
            String json = Files.readString(p, StandardCharsets.UTF_8);
            FileDto dto = GSON.fromJson(json, FileDto.class);
            if (dto != null && dto.routes != null) {
                for (RouteDto r : dto.routes) {
                    RailRoute route = new RailRoute(r.id, r.name, r.color);
                    route.radius = r.radius > 0 ? r.radius : 24.0;
                    if (r.anchors != null) {
                        for (int[] a : r.anchors) {
                            if (a != null && a.length >= 2) {
                                route.anchors.add(new Anchor(a[0], a[1]));
                            }
                        }
                    }
                    out.add(route);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("读取路线失败 {}: {}", p, e.toString());
        }
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
        dto.version = 1;
        dto.world = worldId;
        dto.routes = new ArrayList<>();
        for (RailRoute route : routes) {
            RouteDto r = new RouteDto();
            r.id = route.id;
            r.name = route.name;
            r.color = route.color;
            r.radius = route.radius;
            r.anchors = new ArrayList<>();
            for (Anchor a : route.anchors) {
                r.anchors.add(new int[]{a.x(), a.z()});
            }
            dto.routes.add(r);
        }
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, GSON.toJson(dto), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("保存路线失败 {}: {}", p, e.toString());
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
        List<int[]> anchors;
    }
}
