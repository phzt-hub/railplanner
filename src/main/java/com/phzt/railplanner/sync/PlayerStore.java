package com.phzt.railplanner.sync;

import com.phzt.railplanner.RailPlanner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * 服务端的玩家数据仓库：<b>一个 UUID 一个文件</b>，里面再按「哪个服务器/哪个存档」分桶。
 *
 * <p>位置：专用服务端是 {@code config/railplanner/players/<uuid>.json}；单人/局域网的集成服务端
 * 是 {@code <存档>/railplanner/players/<uuid>.json}（跟着存档走，拷存档就把数据一起带走）。</p>
 *
 * <p>只认玩家自己的 UUID：每条请求都用<b>连接里的那个玩家</b>的 UUID 定位文件，
 * 请求正文里不接受任何 UUID，也就没有「读到别人那份」的入口。</p>
 */
public final class PlayerStore {

    /** 删除记录保留 30 天：够两台机器轮流上线对账，久了就清掉免得文件长草。 */
    private static final long TOMBSTONE_TTL_MS = 30L * 24 * 3600 * 1000;
    private static final int TOMBSTONE_MAX = 2000;

    private static final Object LOCK = new Object();

    private PlayerStore() {
    }

    public static Path dir(MinecraftServer server) {
        Path base = server.isDedicatedServer()
                ? server.getServerDirectory().resolve("config")
                : server.getWorldPath(LevelResource.ROOT);
        return base.resolve("railplanner").resolve("players");
    }

    public static Path file(MinecraftServer server, UUID uuid) {
        return dir(server).resolve(uuid.toString() + ".json");
    }

    /** 世界 / 服务器身份的清洗：只留小写字母数字和 _.- ，最长 64。 */
    public static String cleanKey(String key) {
        if (key == null || key.isBlank()) {
            return "default";
        }
        String s = key.toLowerCase().replaceAll("[^a-z0-9_.-]", "_");
        return s.length() > 64 ? s.substring(0, 64) : s;
    }

    public static Dto.State load(MinecraftServer server, UUID uuid, String worldKey) {
        String key = cleanKey(worldKey);
        Dto.State state = new Dto.State();
        state.worldKey = key;
        synchronized (LOCK) {
            PlayerFile pf = read(server, uuid);
            if (pf == null) {
                return state;
            }
            state.settings = pf.settings;
            World w = pf.worlds == null ? null : pf.worlds.get(key);
            if (w != null) {
                state.routes = w.routes == null ? new ArrayList<>() : new ArrayList<>(w.routes);
                state.deleted = w.deleted == null ? new LinkedHashMap<>() : new LinkedHashMap<>(w.deleted);
                state.revision = w.revision;
            }
        }
        return state;
    }

    /** 把客户端推上来的改动落到这个玩家自己的文件里，返回新的版本号。 */
    public static int apply(MinecraftServer server, UUID uuid, Dto.Push push) {
        String key = cleanKey(push.worldKey);
        synchronized (LOCK) {
            PlayerFile pf = read(server, uuid);
            if (pf == null) {
                pf = new PlayerFile();
                pf.uuid = uuid.toString();
            }
            if (pf.worlds == null) {
                pf.worlds = new LinkedHashMap<>();
            }
            World w = pf.worlds.computeIfAbsent(key, k -> new World());
            if (w.routes == null) {
                w.routes = new ArrayList<>();
            }
            if (w.deleted == null) {
                w.deleted = new LinkedHashMap<>();
            }

            int upserts = 0;
            if (push.upserts != null) {
                for (Dto.Route r : push.upserts) {
                    if (r == null || r.id == null || r.id.isBlank()) {
                        continue;
                    }
                    boolean replaced = false;
                    for (int i = 0; i < w.routes.size(); i++) {
                        if (r.id.equals(w.routes.get(i).id)) {
                            w.routes.set(i, r);
                            replaced = true;
                            break;
                        }
                    }
                    if (!replaced) {
                        w.routes.add(r);
                    }
                    w.deleted.remove(r.id);
                    upserts++;
                }
            }

            int deletes = 0;
            if (push.deletes != null) {
                long now = System.currentTimeMillis();
                for (String id : push.deletes) {
                    if (id == null || id.isBlank()) {
                        continue;
                    }
                    w.routes.removeIf(r -> id.equals(r.id));
                    w.deleted.put(id, now);
                    deletes++;
                }
            }

            if (push.settings != null) {
                pf.settings = push.settings;
            }

            trim(w);
            w.revision = Math.max(0, w.revision) + 1;
            pf.updatedAt = System.currentTimeMillis();
            write(server, uuid, pf);
            RailPlanner.LOG.info("同步入库: {} world={} +{} 条 -{} 条 版本→{} 设置→{}",
                    uuid, key, upserts, deletes, w.revision, push.settings != null ? "是" : "否");
            return w.revision;
        }
    }

    private static void trim(World w) {
        long cut = System.currentTimeMillis() - TOMBSTONE_TTL_MS;
        w.deleted.entrySet().removeIf(e -> e.getValue() == null || e.getValue() < cut);
        if (w.deleted.size() > TOMBSTONE_MAX) {
            long[] times = w.deleted.values().stream().mapToLong(Long::longValue).toArray();
            java.util.Arrays.sort(times);
            long keepFrom = times[times.length - TOMBSTONE_MAX];
            w.deleted.entrySet().removeIf(e -> e.getValue() != null && e.getValue() < keepFrom);
        }
    }

    private static PlayerFile read(MinecraftServer server, UUID uuid) {
        Path p = file(server, uuid);
        if (!Files.isRegularFile(p)) {
            return null;
        }
        try {
            String json = Files.readString(p, StandardCharsets.UTF_8);
            PlayerFile pf = Dto.GSON.fromJson(json, PlayerFile.class);
            if (pf == null) {
                return null;
            }
            if (pf.worlds == null) {
                pf.worlds = new LinkedHashMap<>();
            }
            return pf;
        } catch (Exception e) {
            RailPlanner.LOG.warn("读取玩家数据失败 {}: {}", p, e.toString());
            return null;
        }
    }

    private static void write(MinecraftServer server, UUID uuid, PlayerFile pf) {
        Path p = file(server, uuid);
        try {
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.writeString(tmp, Dto.GSON.toJson(pf), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            RailPlanner.LOG.warn("写入玩家数据失败 {}: {}", p, e.toString());
        }
    }

    /** 落盘格式：一个玩家一份。 */
    static final class PlayerFile {
        int version = 1;
        String uuid;
        long updatedAt;
        Dto.Settings settings;
        Map<String, World> worlds = new LinkedHashMap<>();
    }

    static final class World {
        List<Dto.Route> routes = new ArrayList<>();
        Map<String, Long> deleted = new LinkedHashMap<>();
        int revision;
    }
}
