package com.phzt.railplanner.client.update;

/** 版本号比较：0.2.0 / v0.2.0 / 0.2.0-beta.1。 */
public final class Version {

    private final int[] parts;
    private final String suffix;

    private Version(int[] parts, String suffix) {
        this.parts = parts;
        this.suffix = suffix;
    }

    public static Version parse(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (!s.isEmpty() && (s.charAt(0) == 'v' || s.charAt(0) == 'V')) {
            s = s.substring(1);
        }
        int plus = s.indexOf('+');
        if (plus >= 0) {
            s = s.substring(0, plus);
        }
        String suffix = "";
        int dash = s.indexOf('-');
        if (dash >= 0) {
            suffix = s.substring(dash + 1);
            s = s.substring(0, dash);
        }
        String[] bits = s.isEmpty() ? new String[0] : s.split("\\.");
        int[] parts = new int[Math.max(3, bits.length)];
        for (int i = 0; i < bits.length; i++) {
            parts[i] = digits(bits[i]);
        }
        return new Version(parts, suffix);
    }

    private static int digits(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                break;
            }
            n = n * 10 + (c - '0');
        }
        return n;
    }

    /** 只认数字优先级，够用；返回 &gt;0 表示 a 比 b 新。 */
    public static int compare(String a, String b) {
        Version x = parse(a);
        Version y = parse(b);
        int n = Math.max(x.parts.length, y.parts.length);
        for (int i = 0; i < n; i++) {
            int p = i < x.parts.length ? x.parts[i] : 0;
            int q = i < y.parts.length ? y.parts[i] : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        boolean xPre = !x.suffix.isEmpty();
        boolean yPre = !y.suffix.isEmpty();
        if (xPre != yPre) {
            return xPre ? -1 : 1;
        }
        return x.suffix.compareTo(y.suffix);
    }

    public static boolean isNewer(String candidate, String current) {
        return compare(candidate, current) > 0;
    }

    private Version() {
        this(new int[0], "");
    }
}
