package com.phzt.railplanner.sync;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 三方合并：本地 / 上次同步时的基线 / 服务端。
 *
 * <p>判据只有两条——「这一侧和基线比变了没有」「服务端和基线比变了没有」。
 * 只有一边变，就直接取变的那一边；两边都变且内容不同，才是一条冲突，交给人选。
 * 基线（{@code base}）是上一次同步成功时签收的内容指纹，删掉的线路记成「基线里没有」。</p>
 *
 * <p>三种冲突：两边都改了（{@link Kind#BOTH_EDITED}）、你删了但服务端改了
 * （{@link Kind#LOCAL_DELETED}）、服务端删了但你改了（{@link Kind#REMOTE_DELETED}）。</p>
 */
public final class Merge {

    public static final String SETTINGS_ID = "@settings";

    public enum Kind {
        /** 两边都动过同一条线。 */
        BOTH_EDITED,
        /** 本地删了，服务端还改了。 */
        LOCAL_DELETED,
        /** 服务端删了，本地还改了。 */
        REMOTE_DELETED
    }

    /** 一条需要玩家拍板的冲突。设置冲突用 {@link #settings} 标记，此时 route 字段为 null。 */
    public static final class Conflict {
        public final boolean settings;
        public final String id;
        public final String label;
        public final Kind kind;
        public final Dto.Route local;
        public final Dto.Route remote;
        public final Dto.Settings localSettings;
        public final Dto.Settings remoteSettings;

        Conflict(boolean settings, String id, String label, Kind kind,
                 Dto.Route local, Dto.Route remote, Dto.Settings localSettings, Dto.Settings remoteSettings) {
            this.settings = settings;
            this.id = id;
            this.label = label;
            this.kind = kind;
            this.local = local;
            this.remote = remote;
            this.localSettings = localSettings;
            this.remoteSettings = remoteSettings;
        }

        public int localPoints() {
            return local == null ? 0 : local.points();
        }

        public int remotePoints() {
            return remote == null ? 0 : remote.points();
        }

        public String localText() {
            return local == null ? "（已删）" : local.points() + " 点";
        }

        public String remoteText() {
            return remote == null ? "（已删）" : remote.points() + " 点";
        }

        public String kindText() {
            return switch (kind) {
                case BOTH_EDITED -> "两边都改过";
                case LOCAL_DELETED -> "你删了，服务端改过";
                case REMOTE_DELETED -> "服务端删了，你改过";
            };
        }
    }

    /** 一次合并要做的动作。服务端要什么、本地要装什么，全在这里。 */
    public static final class Plan {
        /** 要推给服务端的线路。 */
        public final List<Dto.Route> push = new ArrayList<>();
        /** 要服务端删掉的线路 id。 */
        public final List<String> pushDeletes = new ArrayList<>();
        /** 要从服务端拉下来装进本地的线路。 */
        public final Map<String, Dto.Route> adopt = new LinkedHashMap<>();
        /** 本地要删掉的线路 id（服务端删了，本地没改）。 */
        public final List<String> removeLocal = new ArrayList<>();
        /** 等玩家选的冲突。 */
        public final List<Conflict> conflicts = new ArrayList<>();
        /** 要推给服务端的设置；null 表示不动。 */
        public Dto.Settings pushSettings;
        /** 要装进本地的设置；null 表示不动。 */
        public Dto.Settings adoptSettings;

        public boolean hasConflicts() {
            return !conflicts.isEmpty();
        }

        /** 动作是不是全空——全空就不用发请求，也不用写盘。 */
        public boolean idle() {
            return push.isEmpty() && pushDeletes.isEmpty() && adopt.isEmpty()
                    && removeLocal.isEmpty() && pushSettings == null && adoptSettings == null;
        }

        public int conflicts() {
            return conflicts.size();
        }

        /**
         * 玩家选完之后的动作。{@code keepLocal} 里没有的 id 按「保留服务端」处理，
         * 这样界面上一路点「应用」也不会漏选——默认不偏向本地。
         */
        public void choose(Map<String, Boolean> keepLocal, boolean keepLocalSettings) {
            List<Conflict> list = new ArrayList<>(conflicts);
            conflicts.clear();
            for (Conflict c : list) {
                if (c.settings) {
                    if (keepLocalSettings) {
                        pushSettings = c.localSettings;
                    } else {
                        adoptSettings = c.remoteSettings;
                    }
                    continue;
                }
                boolean local = Boolean.TRUE.equals(keepLocal.get(c.id));
                if (local) {
                    if (c.local != null) {
                        push.add(c.local);
                    } else {
                        pushDeletes.add(c.id);
                    }
                } else {
                    if (c.remote != null) {
                        adopt.put(c.id, c.remote);
                    } else {
                        removeLocal.add(c.id);
                    }
                }
            }
        }
    }

    private Merge() {
    }

    /**
     * 算出这一次要做什么。
     *
     * @param local           本地现在的线路（id → 数据）
     * @param base            上次同步成功时的内容指纹（id → hash），第一次同步传空表
     * @param remote          服务端那一份，可以为 null（服务端从没收到过这个玩家的数据）
     * @param localSettings   本地设置
     * @param baseSettingsHash 上次同步成功时的设置指纹，第一次同步传 null
     */
    public static Plan plan(Map<String, Dto.Route> local, Map<String, String> base,
                            Dto.State remote, Dto.Settings localSettings, String baseSettingsHash) {
        Plan plan = new Plan();
        Map<String, Dto.Route> remoteRoutes = new LinkedHashMap<>();
        Map<String, Long> remoteDeleted = new LinkedHashMap<>();
        Dto.Settings remoteSettings = null;
        if (remote != null) {
            if (remote.routes != null) {
                for (Dto.Route r : remote.routes) {
                    if (r != null && r.id != null) {
                        remoteRoutes.put(r.id, r);
                    }
                }
            }
            if (remote.deleted != null) {
                remoteDeleted.putAll(remote.deleted);
            }
            remoteSettings = remote.settings;
        }

        Set<String> ids = new LinkedHashSet<>();
        ids.addAll(local.keySet());
        ids.addAll(base.keySet());
        ids.addAll(remoteRoutes.keySet());
        ids.addAll(remoteDeleted.keySet());

        for (String id : ids) {
            Dto.Route l = local.get(id);
            Dto.Route r = remoteRoutes.get(id);
            String b = base.get(id);
            boolean tombstone = remoteDeleted.containsKey(id);

            if (l != null && r != null) {
                if (l.hash().equals(r.hash())) {
                    continue;
                }
                boolean localChanged = b == null || !l.hash().equals(b);
                boolean remoteChanged = b == null || !r.hash().equals(b);
                if (!localChanged && remoteChanged) {
                    plan.adopt.put(id, r);
                } else if (localChanged && !remoteChanged) {
                    plan.push.add(l);
                } else {
                    plan.conflicts.add(new Conflict(false, id, name(l, r), Kind.BOTH_EDITED, l, r, null, null));
                }
                continue;
            }

            if (l != null) {
                // 服务端没有这条线
                if (tombstone) {
                    boolean localChanged = b == null || !l.hash().equals(b);
                    if (!localChanged) {
                        plan.removeLocal.add(id);
                    } else {
                        plan.conflicts.add(new Conflict(false, id, name(l, null), Kind.REMOTE_DELETED, l, null, null, null));
                    }
                } else {
                    plan.push.add(l);
                }
                continue;
            }

            // 本地没有这条线
            if (r != null) {
                if (b != null) {
                    boolean remoteChanged = !r.hash().equals(b);
                    if (!remoteChanged) {
                        plan.pushDeletes.add(id);
                    } else {
                        plan.conflicts.add(new Conflict(false, id, name(null, r), Kind.LOCAL_DELETED, null, r, null, null));
                    }
                } else {
                    plan.adopt.put(id, r);
                }
            }
            // 两边都没有：墓碑或已删，什么都不用做
        }

        planSettings(plan, localSettings, baseSettingsHash, remoteSettings);
        return plan;
    }

    private static void planSettings(Plan plan, Dto.Settings local, String baseHash, Dto.Settings remote) {
        if (local == null) {
            if (remote != null) {
                plan.adoptSettings = remote;
            }
            return;
        }
        String localHash = local.hash();
        if (remote == null) {
            // 服务端还没有这个玩家的设置：本地这份就是初始值
            plan.pushSettings = local;
            return;
        }
        String remoteHash = remote.hash();
        if (localHash.equals(remoteHash)) {
            return;
        }
        if (baseHash == null) {
            // 没有基线（换机、删过缓存）：服务端那份算共享真相，装下来
            plan.adoptSettings = remote;
            return;
        }
        boolean localChanged = !localHash.equals(baseHash);
        boolean remoteChanged = !remoteHash.equals(baseHash);
        if (localChanged && !remoteChanged) {
            plan.pushSettings = local;
        } else if (!localChanged && remoteChanged) {
            plan.adoptSettings = remote;
        } else {
            plan.conflicts.add(new Conflict(true, SETTINGS_ID, "世界显示设置", Kind.BOTH_EDITED,
                    null, null, local, remote));
        }
    }

    private static String name(Dto.Route a, Dto.Route b) {
        if (a != null && a.name != null && !a.name.isBlank()) {
            return a.name;
        }
        if (b != null && b.name != null && !b.name.isBlank()) {
            return b.name;
        }
        return "未命名";
    }
}