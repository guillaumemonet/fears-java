package fears.menu;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Où les tests trouvent les ressources extraites : {@code -Dfears.ressources}, sinon le cache
 * du jeu ({@code ~/.fears/ressources}), sinon {@code ../extract/out}. {@code null} si rien.
 */
final class TestResources {

    private TestResources() {
    }

    static Path locate() {
        String configured = System.getProperty("fears.ressources");
        Path[] candidates = {
            configured != null ? Path.of(configured) : null,
            Path.of(System.getProperty("user.home"), ".fears", "ressources"),
            Path.of("../extract/out"),
        };
        for (Path candidate : candidates) {
            if (candidate != null && Files.isDirectory(candidate.resolve("menu"))
                    && Files.isDirectory(candidate.resolve("intro"))) {
                return candidate;
            }
        }
        return null;
    }
}
