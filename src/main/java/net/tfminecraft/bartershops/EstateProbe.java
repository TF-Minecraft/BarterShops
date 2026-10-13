package net.tfminecraft.bartershops;

/** Whether TLibs has the estate API (2.6.0). Older TLibs leaves shops with dead owners. */
final class EstateProbe {
    static final String[] CLASSES = {
        "net.tfminecraft.tlibs.character.estate.Estates",
        "net.tfminecraft.tlibs.character.estate.EstateParticipant",
        "net.tfminecraft.tlibs.character.estate.EstateDecision",
        "net.tfminecraft.tlibs.character.estate.Outcome",
        "net.tfminecraft.tlibs.character.estate.Holdings",
    };

    private EstateProbe() {}

    static boolean available(String... classes) {
        try {
            for (String name : classes) Class.forName(name);
            return true;
        } catch (ReflectiveOperationException | LinkageError ex) {
            return false;
        }
    }
}
