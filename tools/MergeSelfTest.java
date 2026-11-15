import com.phzt.railplanner.sync.Dto;
import com.phzt.railplanner.sync.Merge;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 三方合并的离线自测：不碰 Minecraft，直接 java 跑。 */
public final class MergeSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        freshBothPush();
        freshRemoteOnlyAdopts();
        localEditPushes();
        remoteEditAdopts();
        bothEditConflicts();
        localDeletePropagates();
        remoteDeleteRemovesLocal();
        remoteDeleteVsLocalEdit();
        localDeleteVsRemoteEdit();
        identicalNoop();
        settingsPushWhenRemoteEmpty();
        settingsAdoptWhenNoBase();
        settingsConflictResolvedLocal();
        System.out.println();
        System.out.println(failed == 0 ? "全部通过：" + passed + " 项" : "失败 " + failed + " 项（通过 " + passed + "）");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------ 场景

    static void freshBothPush() {
        Map<String, Dto.Route> local = map(route("a", 3), route("b", 2));
        Merge.Plan p = Merge.plan(local, new LinkedHashMap<>(), null, settings(), null);
        check("首次：本地两条全部推上去", p.push.size() == 2 && p.adopt.isEmpty() && !p.hasConflicts());
    }

    static void freshRemoteOnlyAdopts() {
        Dto.State remote = state(route("a", 3), route("b", 2));
        Merge.Plan p = Merge.plan(new LinkedHashMap<>(), new LinkedHashMap<>(), remote, settings(), null);
        check("首次：服务端两条全部拉下来", p.adopt.size() == 2 && p.push.isEmpty() && !p.hasConflicts());
    }

    static void localEditPushes() {
        Dto.Route before = route("a", 2);
        Dto.Route after = route("a", 4);
        Map<String, String> base = new LinkedHashMap<>();
        base.put("a", before.hash());
        Dto.State remote = state(before);
        Merge.Plan p = Merge.plan(map(after), base, remote, settings(), settings().hash());
        check("本地改了、服务端没动 → 推本地", p.push.size() == 1 && p.adopt.isEmpty() && !p.hasConflicts());
    }

    static void remoteEditAdopts() {
        Dto.Route before = route("a", 2);
        Dto.Route after = route("a", 4);
        Map<String, String> base = new LinkedHashMap<>();
        base.put("a", before.hash());
        Merge.Plan p = Merge.plan(map(before), base, state(after), settings(), settings().hash());
        check("服务端改了、本地没动 → 拉下来", p.adopt.size() == 1 && p.push.isEmpty() && !p.hasConflicts());
    }

    static void bothEditConflicts() {
        Dto.Route before = route("a", 2);
        Dto.Route localAfter = route("a", 4);
        Dto.Route remoteAfter = route("a", 6);
        Map<String, String> base = new LinkedHashMap<>();
        base.put("a", before.hash());
        Merge.Plan p = Merge.plan(map(localAfter), base, state(remoteAfter), settings(), settings().hash());
        boolean conflict = p.hasConflicts() && p.conflicts.size() == 1
                && p.conflicts.get(0).kind == Merge.Kind.BOTH_EDITED;

        Merge.Plan keepLocal = Merge.plan(map(localAfter), base, state(remoteAfter), settings(), settings().hash());
        keepLocal.choose(Map.of("a", Boolean.TRUE), true);
        boolean pushLocal = keepLocal.push.size() == 1 && keepLocal.adopt.isEmpty();

        Merge.Plan keepRemote = Merge.plan(map(localAfter), base, state(remoteAfter), settings(), settings().hash());
        keepRemote.choose(Map.of("a", Boolean.FALSE), true);
        boolean adoptRemote = keepRemote.adopt.size() == 1 && keepRemote.push.isEmpty();

        check("两边都改 → 冲突；选本地则推、选服务端则拉", conflict && pushLocal && adoptRemote);
    }

    static void localDeletePropagates() {
        Dto.Route before = route("a", 2);
        Map<String, String> base = new LinkedHashMap<>();
        base.put("a", before.hash());
        Merge.Plan p = Merge.plan(new LinkedHashMap<>(), base, state(before), settings(), settings().hash());
        check("本地删了、服务端没动 → 让服务端也删", p.pushDeletes.size() == 1 && !p.hasConflicts());
    }

    static void remoteDeleteRemovesLocal() {
        Dto.Route before = route("a", 2);
        Map<String, String> base = new LinkedHashMap<>();
        base.put("a", before.hash());
        Dto.State remote = new Dto.State();
        remote.deleted.put("a", 1L);
        Merge.Plan p = Merge.plan(map(before), base, remote, settings(), settings().hash());
        check("服务端删了、本地没动 → 本地也删", p.removeLocal.size() == 1 && !p.hasConflicts());
    }

    static void remoteDeleteVsLocalEdit() {
        Dto.Route before = route("a", 2);
        Dto.Route edited = route("a", 5);
        Map<String, String> base = new LinkedHashMap<>();
        base.put("a", before.hash());
        Dto.State remote = new Dto.State();
        remote.deleted.put("a", 1L);
        Merge.Plan p = Merge.plan(map(edited), base, remote, settings(), settings().hash());
        boolean conflict = p.hasConflicts() && p.conflicts.get(0).kind == Merge.Kind.REMOTE_DELETED;
        p.choose(Map.of("a", Boolean.TRUE), true);
        check("服务端删了但本地改过 → 冲突，选本地则重新推上去",
                conflict && p.push.size() == 1 && p.removeLocal.isEmpty());
    }

    static void localDeleteVsRemoteEdit() {
        Dto.Route before = route("a", 2);
        Dto.Route edited = route("a", 7);
        Map<String, String> base = new LinkedHashMap<>();
        base.put("a", before.hash());
        Merge.Plan p = Merge.plan(new LinkedHashMap<>(), base, state(edited), settings(), settings().hash());
        boolean conflict = p.hasConflicts() && p.conflicts.get(0).kind == Merge.Kind.LOCAL_DELETED;
        p.choose(Map.of("a", Boolean.FALSE), true);
        check("本地删了但服务端改过 → 冲突，选服务端则拉回来",
                conflict && p.adopt.size() == 1 && p.pushDeletes.isEmpty());
    }

    static void identicalNoop() {
        Dto.Route a = route("a", 3);
        Map<String, String> base = new LinkedHashMap<>();
        base.put("a", a.hash());
        Merge.Plan p = Merge.plan(map(a), base, state(a), settings(), settings().hash());
        check("两边一模一样 → 什么都不做", p.idle() && !p.hasConflicts());
    }

    static void settingsPushWhenRemoteEmpty() {
        Merge.Plan p = Merge.plan(new LinkedHashMap<>(), new LinkedHashMap<>(), null, settings(), null);
        check("服务端没有设置 → 推本地那份", p.pushSettings != null && p.adoptSettings == null);
    }

    static void settingsAdoptWhenNoBase() {
        Dto.Settings remote = settings();
        remote.worldRange = 512;
        Dto.State st = new Dto.State();
        st.settings = remote;
        Merge.Plan p = Merge.plan(new LinkedHashMap<>(), new LinkedHashMap<>(), st, settings(), null);
        check("没有基线但服务端有设置 → 装服务端那份", p.adoptSettings != null && p.pushSettings == null);
    }

    static void settingsConflictResolvedLocal() {
        Dto.Settings before = settings();
        Dto.Settings mine = settings();
        mine.worldRange = 128;
        Dto.Settings theirs = settings();
        theirs.worldRange = 1024;
        Dto.State st = new Dto.State();
        st.settings = theirs;
        Merge.Plan p = Merge.plan(new LinkedHashMap<>(), new LinkedHashMap<>(), st, mine, before.hash());
        boolean conflict = p.hasConflicts() && p.conflicts.get(0).settings;
        p.choose(new LinkedHashMap<>(), true);
        check("设置两边都改 → 冲突，选本地则推上去", conflict && p.pushSettings != null);
    }

    // ------------------------------------------------------------ 工具

    static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  ok   " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name);
        }
    }

    static Dto.Settings settings() {
        return new Dto.Settings();
    }

    static Dto.Route route(String id, int points) {
        Dto.Route r = new Dto.Route();
        r.id = id;
        r.name = "线 " + id;
        r.color = 0xFF00FF00;
        r.radius = 24;
        List<int[]> anchors = new ArrayList<>();
        for (int i = 0; i < points; i++) {
            anchors.add(new int[]{i * 10, i * 5});
        }
        r.anchors = anchors;
        return r;
    }

    static Map<String, Dto.Route> map(Dto.Route... routes) {
        Map<String, Dto.Route> out = new LinkedHashMap<>();
        for (Dto.Route r : routes) {
            out.put(r.id, r);
        }
        return out;
    }

    static Dto.State state(Dto.Route... routes) {
        Dto.State s = new Dto.State();
        s.worldKey = "mp_test";
        s.settings = settings();
        for (Dto.Route r : routes) {
            s.routes.add(r);
        }
        return s;
    }
}
