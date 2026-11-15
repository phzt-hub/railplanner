package com.phzt.railplanner.client.sync;

import java.net.InetSocketAddress;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.Connection;
import net.minecraft.world.level.storage.LevelResource;

/**
 * 给「这个世界」起一个稳定的身份，本地存档、服务端分桶、同步基线全都用它。
 *
 * <ul>
 *   <li>单人 / 局域网：{@code sp_<存档文件夹名>}</li>
 *   <li>多人：{@code mp_<主机名>} —— <b>只认主机名，不认端口</b>。
 *       同一个 IP 或域名下开在不同端口的服算同一个服务器，数据共用一份。</li>
 * </ul>
 */
public final class SyncKeys {

    private SyncKeys() {
    }

    public static String of(Minecraft mc) {
        IntegratedServer sp = mc.getSingleplayerServer();
        if (sp != null) {
            try {
                return "sp_" + clean(sp.getWorldPath(LevelResource.ROOT).getFileName().toString());
            } catch (Throwable ignored) {
                return "sp_unknown";
            }
        }
        String host = null;
        ClientPacketListener conn = mc.getConnection();
        if (conn != null) {
            Connection net = conn.getConnection();
            if (net != null && net.getRemoteAddress() instanceof InetSocketAddress addr) {
                host = addr.getHostString();
            }
            ServerData data = conn.getServerData();
            if ((host == null || host.isBlank()) && data != null) {
                host = hostOf(data.ip);
            }
        }
        if (host == null || host.isBlank()) {
            ServerData data = mc.getCurrentServer();
            if (data != null) {
                host = hostOf(data.ip);
            }
        }
        if (host == null || host.isBlank()) {
            return "mp_unknown";
        }
        return "mp_" + clean(host);
    }

    /** 去掉端口：{@code a.b:25565} → {@code a.b}，{@code [::1]:25565} → {@code ::1}，裸 IPv6 原样。 */
    public static String hostOf(String address) {
        if (address == null) {
            return null;
        }
        String s = address.trim();
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            return end > 0 ? s.substring(1, end) : s;
        }
        int first = s.indexOf(':');
        int last = s.lastIndexOf(':');
        if (last > 0 && first == last) {
            return s.substring(0, last);
        }
        return s;
    }

    static String clean(String s) {
        if (s == null || s.isBlank()) {
            return "unknown";
        }
        String out = s.toLowerCase().replaceAll("[^a-z0-9_.-]", "_");
        return out.length() > 64 ? out.substring(0, 64) : out;
    }
}
