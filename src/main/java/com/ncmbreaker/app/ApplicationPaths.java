package com.ncmbreaker.app;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ApplicationPaths {
    private ApplicationPaths() { }

    public static Path directory() {
        var launcher = System.getProperty("jpackage.app-path");
        if (launcher != null && !launcher.isBlank()) {
            return Path.of(launcher).toAbsolutePath().normalize().getParent();
        }
        try {
            var location = Path.of(ApplicationPaths.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toAbsolutePath().normalize();
            return Files.isDirectory(location) ? location : location.getParent();
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("无法确定程序所在目录", exception);
        }
    }
}
