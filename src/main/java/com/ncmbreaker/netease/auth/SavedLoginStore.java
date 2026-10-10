package com.ncmbreaker.netease.auth;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.ncmbreaker.netease.http.SessionState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;

final class SavedLoginStore {
    private static final Gson JSON = new Gson();
    private final Path directory = Path.of(System.getProperty("user.home"), ".ncm-breaker");
    private final Path file = directory.resolve("session.json");

    SessionState load() throws IOException {
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        secureDirectory();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 256 * 1024) {
            throw new IOException("保存的登录状态无法读取，请重新扫码。");
        }
        secure(file, false);
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            var state = JSON.fromJson(reader, SessionState.class);
            if (state == null || state.version() != 1 || state.deviceId() == null
                    || !state.deviceId().matches("[a-fA-F0-9]{32}")) {
                throw new IOException("保存的登录状态无法读取，请重新扫码。");
            }
            return state;
        } catch (JsonParseException | IllegalArgumentException exception) {
            throw new IOException("保存的登录状态无法读取，请重新扫码。");
        }
    }

    void save(SessionState state) throws IOException {
        secureDirectory();
        var temporary = Files.createTempFile(directory, "session-", ".tmp", permissions(false));
        try {
            secure(temporary, false);
            Files.writeString(temporary, JSON.toJson(state), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    void clear() throws IOException {
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        secureDirectory();
        Files.deleteIfExists(file);
    }

    private void secureDirectory() throws IOException {
        try {
            Files.createDirectory(directory, permissions(true));
        } catch (FileAlreadyExistsException ignored) {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("登录状态保存目录不可用。");
            }
        }
        secure(directory, true);
    }

    private FileAttribute<?> permissions(boolean folder) throws IOException {
        var home = directory.getParent();
        if (Files.getFileAttributeView(home, PosixFileAttributeView.class) != null) {
            return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(folder ? "rwx------" : "rw-------"));
        }
        if (Files.getFileAttributeView(home, AclFileAttributeView.class) != null) {
            var owner = home.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
            var entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (folder) entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
            var acl = List.of(entry.build());
            return new FileAttribute<List<AclEntry>>() {
                @Override public String name() { return "acl:acl"; }
                @Override public List<AclEntry> value() { return acl; }
            };
        }
        throw new IOException("当前文件系统无法保护登录凭据。");
    }

    private void secure(Path path, boolean folder) throws IOException {
        var attribute = permissions(folder);
        Files.setAttribute(path, attribute.name(), attribute.value(), LinkOption.NOFOLLOW_LINKS);
    }
}
