package com.phzt.railplanner.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 传输与落盘用的数据模型。整个类不碰任何 Minecraft 类型，所以可以脱离游戏单独编译、单测。
 *
 * <p>内容指纹 {@code hash()} 一律不含 {@code updatedAt}：时间戳只用来给人看「什么时候改的」，
 * 参与比较会让「同一份数据换个时间戳」被误判成改动。</p>
 */
public final class Dto {

    /** 协议版本。客户端和服务端对不上就不同步，各留各的本地数据。 */
    public static final int PROTO = 1;

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final Gson COMPACT = new Gson();

    private Dto() {
    }

    /** 一条线路。字段和本地存档 json 一一对应，所以老存档可以直接当传输格式用。 */
    public static final class Route {
        public String id;
        public String name;
        public int color;
        public double radius = 24.0;
        public long updatedAt;
        public List<int[]> anchors = new ArrayList<>();

        public Route() {
        }

        public int points() {
            return anchors == null ? 0 : anchors.size();
        }

        /** 内容指纹：由 id / 名字 / 颜色 / 半径 / 锚点决定。 */
        public String hash() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("color", color);
            m.put("radius", clean(radius));
            m.put("anchors", anchors);
            return sha256(COMPACT.toJson(m));
        }
    }

    /** 同步过去的那部分设置：都是「世界里画成什么样」，不是本机偏好。 */
    public static final class Settings {
        public boolean worldLines = true;
        public boolean worldXray;
        public double worldRange = 256.0;
        public boolean worldSurfaces = true;
        public boolean worldAnchors = true;

        public String hash() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lines", worldLines);
            m.put("xray", worldXray);
            m.put("range", clean(worldRange));
            m.put("surfaces", worldSurfaces);
            m.put("anchors", worldAnchors);
            return sha256(COMPACT.toJson(m));
        }

        public boolean same(Settings other) {
            return other != null && hash().equals(other.hash());
        }
    }

    /** 服务端某一个世界（一个服务器）里这个玩家的全部数据。 */
    public static final class State {
        public String worldKey;
        public List<Route> routes = new ArrayList<>();
        public Map<String, Long> deleted = new LinkedHashMap<>();
        /** 这个玩家的世界显示设置（全局一份，不分世界）。 */
        public Settings settings;
        public int revision;
    }

    public static final class Hello {
        public int proto = PROTO;
        public String client;
    }

    public static final class HelloAck {
        public boolean sync;
        public int proto = PROTO;
        public String server;
    }

    public static final class Pull {
        public String worldKey;
    }

    public static final class Push {
        public String worldKey;
        public List<Route> upserts = new ArrayList<>();
        public List<String> deletes = new ArrayList<>();
        public Settings settings;
    }

    public static final class Ack {
        public String worldKey;
        public int revision;
        public boolean ok = true;
    }

    /** 双精度在 json 里可能出现 24.0 / 2.4E1 两种写法，指纹前先归一。 */
    static double clean(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }

    static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}