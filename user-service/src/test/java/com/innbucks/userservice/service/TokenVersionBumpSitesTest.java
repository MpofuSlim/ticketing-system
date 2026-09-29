package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.User;
import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code users.token_version} has exactly one writer: {@link TokenVersionBumper}.
 *
 * <p>Every other bump site used to be a read-modify-write —
 * {@code user.setTokenVersion(user.getTokenVersion() + 1)} then a save — which
 * loses a bump when two writers race and lets a stale entity save put an old
 * version back (a login racing a deactivation revived the ended sessions). The
 * column is now {@code updatable = false} and only advanced by atomic
 * {@code UPDATE ... RETURNING}; this test fails the build the moment anyone
 * reintroduces the old pattern in {@code src/main}, or makes the column
 * writable again.
 */
class TokenVersionBumpSitesTest {

    private static final Path MAIN = Paths.get("src", "main", "java");
    private static final Pattern WRITE = Pattern.compile("\\.setTokenVersion\\s*\\(");
    private static final String ONLY_WRITER = "TokenVersionBumper.java";

    @Test
    void noSetTokenVersionOutsideTheBumper() throws IOException {
        assertThat(Files.isDirectory(MAIN))
                .as("run from the user-service module directory (surefire's default)")
                .isTrue();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().equals(ONLY_WRITER)) continue;
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).trim();
                    if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) continue;
                    if (WRITE.matcher(line).find()) {
                        offenders.add(MAIN.relativize(file) + ":" + (i + 1) + "  " + line);
                    }
                }
            }
        }
        assertThat(offenders)
                .as("bump token_version through TokenVersionBumper (atomic UPDATE ... RETURNING + "
                        + "after-commit publish), never with setTokenVersion")
                .isEmpty();
    }

    @Test
    void theBumperIsTheOneWriter_andStillUsesTheSetter() throws IOException {
        // Guards the guard: if the bumper moved or was renamed, the exclusion
        // above would silently cover nothing.
        try (Stream<Path> files = Files.walk(MAIN)) {
            Path bumper = files.filter(p -> p.getFileName().toString().equals(ONLY_WRITER))
                    .findFirst().orElseThrow();
            assertThat(WRITE.matcher(Files.readString(bumper)).find()).isTrue();
        }
    }

    @Test
    void theEntityColumnCannotBeWrittenByAnEntitySave() throws NoSuchFieldException {
        Column column = User.class.getDeclaredField("tokenVersion").getAnnotation(Column.class);
        assertThat(column).isNotNull();
        assertThat(column.name()).isEqualTo("token_version");
        assertThat(column.updatable())
                .as("a stale entity save must never write an old token_version back")
                .isFalse();
        assertThat(column.insertable()).isTrue();
    }

    @Test
    void aStaleSaveWritesOnlyTheColumnsItChanged() {
        // Without @DynamicUpdate every save rewrote every column from its
        // snapshot, so a password reset racing a deactivation wrote
        // active = true straight back. ConcurrentLoginAndDeactivateIT proves
        // the behaviour against Postgres; this pins the annotation.
        assertThat(User.class.isAnnotationPresent(org.hibernate.annotations.DynamicUpdate.class)).isTrue();
    }
}
