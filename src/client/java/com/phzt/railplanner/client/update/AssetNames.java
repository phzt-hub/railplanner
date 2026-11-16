package com.phzt.railplanner.client.update;

import java.util.Locale;

/**
 * release 附件里哪个才是模组本体——只按文件名判断，不碰网络也不碰 gson，能单独自检。
 *
 * <p>同一个 release 里除了 {@code railplanner-<版本>.jar}，还挂着
 * {@code railplanner-<版本>-sources.jar} 源码包：它同样以 {@code .jar} 结尾，digest 与字节数
 * 也照样对得上，可换上去客户端根本起不来（主类不在里面）。0.8.2 按「第一个以 .jar 结尾的附件」
 * 挑，源码包排在前头，服务端整个起不来。</p>
 */
public final class AssetNames {

    /** 名字里带这些段落的不算本体：源码包、开发包、文档包、精简包、API 包。 */
    private static final String[] NOT_MOD = {"-sources", "-source", "-dev", "-javadoc", "-slim", "-api"};

    private AssetNames() {
    }

    /** 去掉版本号前面的 v / V。 */
    public static String stripPrefix(String tag) {
        String t = tag == null ? "" : tag.trim();
        if (!t.isEmpty() && (t.charAt(0) == 'v' || t.charAt(0) == 'V')) {
            return t.substring(1);
        }
        return t;
    }

    /** 本体 jar 的标准名字：{@code railplanner-<版本>.jar}。 */
    public static String exactName(String tag) {
        return "railplanner-" + stripPrefix(tag) + ".jar";
    }

    /** 这个附件名是不是本体 jar。 */
    public static boolean isModJar(String name) {
        if (name == null || !name.startsWith("railplanner") || !name.endsWith(".jar")) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String bad : NOT_MOD) {
            if (lower.contains(bad)) {
                return false;
            }
        }
        return true;
    }
}
