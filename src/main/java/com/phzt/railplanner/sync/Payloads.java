package com.phzt.railplanner.sync;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 自定义通道只有两条：{@code railplanner:c2s} 和 {@code railplanner:s2c}。
 *
 * <p>正文一律是一段 JSON 字符串，操作码在 {@code op} 里。这样加一种请求只要加一个 DTO，
 * 不用再动网络层，也不会因为字段对不上把连接踢掉——解不开的正文按「这次同步失败」处理。</p>
 */
public final class Payloads {

    /** 单包上限（1 MiB）。几百条线的 JSON 也就几十 KB，留足余量。 */
    public static final int MAX = 1 << 20;

    public static final int C2S_HELLO = 1;
    public static final int C2S_PULL = 2;
    public static final int C2S_PUSH = 3;

    public static final int S2C_HELLO = 1;
    public static final int S2C_STATE = 2;
    public static final int S2C_ACK = 3;

    private Payloads() {
    }

    /** 客户端 → 服务端。 */
    public record C2S(int op, String body) implements CustomPacketPayload {

        public static final Type<C2S> TYPE = new Type<>(Identifier.fromNamespaceAndPath("railplanner", "c2s"));

        public static final StreamCodec<RegistryFriendlyByteBuf, C2S> CODEC = CustomPacketPayload.codec(
                (value, buf) -> {
                    buf.writeVarInt(value.op);
                    buf.writeUtf(value.body, MAX);
                },
                buf -> new C2S(buf.readVarInt(), buf.readUtf(MAX)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 服务端 → 客户端。 */
    public record S2C(int op, String body) implements CustomPacketPayload {

        public static final Type<S2C> TYPE = new Type<>(Identifier.fromNamespaceAndPath("railplanner", "s2c"));

        public static final StreamCodec<RegistryFriendlyByteBuf, S2C> CODEC = CustomPacketPayload.codec(
                (value, buf) -> {
                    buf.writeVarInt(value.op);
                    buf.writeUtf(value.body, MAX);
                },
                buf -> new S2C(buf.readVarInt(), buf.readUtf(MAX)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
