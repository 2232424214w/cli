package com.bettercli.cli;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.OSUtils;

import java.io.IOException;

/**
 * Creates the process system terminal for BetterCLI.
 *
 * <p>Windows notes (Java 22+ / IDE shells):
 * <ul>
 *   <li>Many IDEs export {@code TERM=dumb}, which makes JLine skip native WinVT and force
 *       {@code DumbTerminal}. We override the type to {@code windows-vtp} in that case.</li>
 *   <li>JLine JNI/FFM needs {@code --enable-native-access=ALL-UNNAMED} (set by the
 *       {@code bettercli} launcher).</li>
 * </ul>
 */
public final class SystemTerminalFactory {

    private SystemTerminalFactory() {
    }

    public static Terminal create() throws IOException {
        TerminalBuilder builder = TerminalBuilder.builder()
                .name("BetterCLI")
                .system(true)
                .jni(true)
                .ffm(true)
                .dumb(true);

        if (OSUtils.IS_WINDOWS) {
            // Prefer JNI: jline jdk11 classifier may ship ffm provider metadata without classes.
            builder.providers("jni,exec");
            if (shouldForceWindowsVtType()) {
                builder.type("windows-vtp");
            }
        }

        return builder.build();
    }

    static boolean shouldForceWindowsVtType() {
        String term = System.getenv("TERM");
        if (term == null || term.isBlank()) {
            return true;
        }
        String normalized = term.trim().toLowerCase();
        return "dumb".equals(normalized) || normalized.startsWith("dumb-color");
    }
}
