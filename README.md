# bartershops

Technical documentation is maintained in [TF-Minecraft/Docs](https://github.com/TF-Minecraft/Docs/blob/main/projects/BarterShops/README.md).

Use that project index for setup, configuration, architecture, integration and testing guides. This repository contains the source and project-specific assets.

## Shared plugin dependencies

Build and release workflows install checksum-verified plugin releases through
[TLibs' shared installer](https://github.com/TF-Minecraft/TLibs/blob/main/DEPENDENCIES.md).
CI selects the latest published versions; local builds use the explicit Maven
version properties. Shared plugins use `provided` scope and remain separate
server plugins. Each build records exact versions and checksums in
`.build/plugin-dependencies.json` alongside its JAR.

From this checkout, with the TLibs repository next to it:

```sh
python3 ../tlibs/tools/install-plugins.py --pom pom.xml
```

Prepare any remaining third-party inputs with `.github/scripts/prepare-release.sh`
before running Maven. Any source-unavailable inputs remain private and checksum-pinned wherever declared; see the installer
documentation for authentication and reproducible rebuilds.
