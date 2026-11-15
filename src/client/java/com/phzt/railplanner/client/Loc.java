package com.phzt.railplanner.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 两串式本地化：调用点同时带着中文和英文，按游戏语言挑一个。
 *
 * <p>这个模组只有中文和英文两种文案，光为了「英文玩家看到的不是中文」去建一整套
 * 翻译键并不划算——调用点就一次，中英并排放在一起反而好改、好对照。缺省（拿不到
 * 语言、或者语言不是 zh*）走英文。
 */
public final class Loc {
    private Loc() {
    }

    private static Boolean chinese;

    public static boolean isChinese() {
        if (chinese == null) {
            String code = "en_us";
            try {
                code = Minecraft.getInstance().getLanguageManager().getSelected();
            } catch (Throwable ignored) {
                // 语言还没读出来（启动早期）就按英文走
            }
            chinese = code != null && code.toLowerCase(java.util.Locale.ROOT).startsWith("zh");
        }
        return chinese;
    }

    /** 按语言挑一串。 */
    public static String s(String zh, String en) {
        return isChinese() ? zh : en;
    }

    /** 同上，包成 Component。 */
    public static Component c(String zh, String en) {
        return Component.literal(s(zh, en));
    }
}
