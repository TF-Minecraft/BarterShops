package net.tfminecraft.bartershops;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.logging.Logger;

/** Staff shop commands go to the server log and to {@code plugins/BarterShops/logs/staff-audit.log}. */
final class ShopAudit {
    private ShopAudit() {}

    static void record(Path file, Logger log, String actor, String action) {
        log.info("[Shop audit] " + actor + " " + action);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, Instant.now() + " " + actor + " " + action + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            log.warning("Could not write the shop audit log: " + ex.getMessage());
        }
    }
}
