CHUNK FINDER (standalone Fabric mod, Minecraft 26.2)

GET THE JAR (no coding):
1. Create a free account at github.com and make a new repository.
2. Upload everything in this folder (keep the folders, including the hidden .github folder).
3. Open the "Actions" tab -> "Build mod" -> wait for the run to finish.
4. Download "ChunkFinder-jar" from the run, unzip it -> chunkfinder-1.0.0.jar
   (If the run shows a red X, open it and send the error text to Claude.)

USE IT:
1. Install Java 25 and Fabric Loader 0.19+ for Minecraft 26.2 (fabricmc.net/use).
2. Put Fabric API (0.153.0+26.2 or newer) and chunkfinder-1.0.0.jar in .minecraft/mods.
3. Launch the Fabric profile. Press K to toggle. Commands:
   /chunkfinder sensitivity 1-10
   /chunkfinder distance 2-32
   /chunkfinder geodes true|false
   /chunkfinder activity true|false
Purple boxes = geodes, orange = player-activity blocks.
