package com.phzt.railplanner.client.update;

import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipFile;

/**
 * 下到的 jar 里到底有没有能跑的模组——开包看，不看名字也不看 sha256。
 *
 * <p>sha256 与字节数只说明「和 GitHub 上那个附件一模一样」，说明不了那个附件是本体：
 * 源码包同样一字节不差。所以换 jar 之前开包确认 {@code fabric.mod.json} 与主类都在。</p>
 */
public final class JarCheck {

    private JarCheck() {
    }

    /** @param mainClassEntry 主类在包里的路径，如 {@code com/phzt/railplanner/RailPlanner.class} */
    public static boolean hasMod(Path jar, String mainClassEntry) {
        if (jar == null || mainClassEntry == null || mainClassEntry.isEmpty()) {
            return false;
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.getEntry("fabric.mod.json") != null && zip.getEntry(mainClassEntry) != null;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
