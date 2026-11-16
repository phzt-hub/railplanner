import com.phzt.railplanner.client.update.AssetNames;
import com.phzt.railplanner.client.update.JarCheck;
import java.nio.file.Files;
import java.nio.file.Path;

/** 更新包自检：release 附件怎么挑、下到的 jar 里有没有主类。不启游戏。 */
public class AssetCheck {
    static int fails = 0;
    static final String MAIN = "com/phzt/railplanner/RailPlanner.class";

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + "   " + detail);
        if (!ok) fails++;
    }

    public static void main(String[] args) {
        // 挑附件：名字像 .jar 的源码包不能当成本体
        check("本体 jar 认得出", AssetNames.isModJar("railplanner-0.8.2.jar"), "");
        check("源码包挑不出", !AssetNames.isModJar("railplanner-0.8.2-sources.jar"), "");
        check("开发包挑不出", !AssetNames.isModJar("railplanner-0.8.2-dev.jar"), "");
        check("只认 railplanner 开头", !AssetNames.isModJar("lithium-fabric-0.26.2+mc26.3.jar"), "");
        check("只认 .jar", !AssetNames.isModJar("railplanner_updater-1.0.2.mcdr"), "");
        check("标准名", "railplanner-0.8.2.jar".equals(AssetNames.exactName("v0.8.2")), AssetNames.exactName("v0.8.2"));
        check("标准名（无 v）", "railplanner-0.8.2.jar".equals(AssetNames.exactName("0.8.2")), AssetNames.exactName("0.8.2"));

        // 开包验主类：本体里有，源码包里必然没有（0.8.2 那次就是它顶掉了能跑的 jar）
        for (String[] pair : new String[][]{
                {"build/libs/railplanner-0.8.2.jar", "true"},
                {"build/libs/railplanner-0.8.2-sources.jar", "false"}}) {
            Path p = Path.of(pair[0]);
            if (!Files.isRegularFile(p)) {
                check("有 " + pair[0], false, "文件不存在，先 ./gradlew25 build");
                continue;
            }
            boolean want = Boolean.parseBoolean(pair[1]);
            check(p.getFileName() + " 主类" + (want ? "在" : "不在"), JarCheck.hasMod(p, MAIN) == want, "");
        }

        System.out.println(fails == 0 ? "\nALL PASS" : "\n" + fails + " FAILED");
        if (fails != 0) {
            System.exit(1);
        }
    }
}
