import com.phzt.railplanner.client.update.Version;

/** 版本号比较自检：不启游戏，只跑 update/Version。 */
public class VersionCheck {
    static int fails = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + "   " + detail);
        if (!ok) fails++;
    }

    static void eq(String a, String b) {
        int got = Version.compare(a, b);
        check(a + " == " + b, got == 0, "compare=" + got);
    }

    static void gt(String a, String b) {
        int got = Version.compare(a, b);
        check(a + " > " + b, got > 0, "compare=" + got);
        int rev = Version.compare(b, a);
        check(b + " < " + a, rev < 0, "compare=" + rev);
    }

    public static void main(String[] args) {
        eq("0.1.0", "0.1.0");
        eq("v0.2.0", "0.2.0");
        eq("1.0", "1.0.0");
        eq("0.2.0+build7", "0.2.0");
        eq("", "0.0.0");

        gt("0.2.0", "0.1.0");
        gt("0.10.0", "0.9.9");     // 数字比较，不是字符串比较
        gt("0.1.10", "0.1.9");
        gt("1.0.0", "0.99.99");
        gt("0.2.0", "0.2.0-beta.1"); // 正式版高于预发布
        gt("0.2.0-beta.2", "0.2.0-beta.1");

        check("isNewer(v0.2.0, 0.1.0)", Version.isNewer("v0.2.0", "0.1.0"), "");
        check("!isNewer(0.2.0, v0.2.0)", !Version.isNewer("0.2.0", "v0.2.0"), "");
        check("!isNewer(0.1.0, 0.2.0)", !Version.isNewer("0.1.0", "0.2.0"), "");
        check("!isNewer(0.1.9, 0.2.0)", !Version.isNewer("0.1.9", "0.2.0"), "");
        check("isNewer(0.2.0, 0.1.9)", Version.isNewer("0.2.0", "0.1.9"), "");
        check("isNewer(0.2.0, 0.2.0-beta.1)", Version.isNewer("0.2.0", "0.2.0-beta.1"), "");

        System.out.println(fails == 0 ? "\nALL PASS" : "\n" + fails + " FAILED");
        if (fails != 0) {
            System.exit(1);
        }
    }
}
