package com.hoddmimes.emporia.server;

import java.io.InputStream;
import java.util.Properties;

/**
 * The build's version (V<version>-<build>, e.g. V1.0-001, see Server/version.properties) and date, from
 * the resource emporia-build.properties that the Gradle build generates (task generateBuildInfo).
 * "unknown" when it is missing, e.g. when run from classes that an IDE compiled without Gradle.
 */
public final class BuildInfo {

    private static final Properties PROPERTIES = new Properties();
    static {
        try (InputStream tIn = BuildInfo.class.getResourceAsStream("/emporia-build.properties")) {
            if (tIn != null) PROPERTIES.load(tIn);
        } catch (Exception e) {
            // no build information
        }
    }

    private BuildInfo() {}

    public static String version()   { return PROPERTIES.getProperty("version", "unknown"); }
    public static String buildDate() { return PROPERTIES.getProperty("buildDate", "unknown"); }

    public static String text() { return version() + ", built " + buildDate(); }
}
