package chunkfinder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chunk Finder - standalone Fabric client mod for Minecraft 26.2 (Mojang mappings).
 * Toggle with K, or /chunkfinder (toggle | sensitivity <1-10> | distance <2-32> | geodes <bool> | activity <bool>).
 */
public class ChunkFinderMod implements ClientModInitializer {

    // ---------------- Config ----------------
    public static class Config {
        public boolean enabled = true;
        public int sensitivity = 5;          // 1-10, higher = flags with less evidence
        public int simulationDistance = 8;   // chunk radius to scan
        public boolean geodes = true;
        public boolean activity = true;
        public int chunksPerTick = 2;
        public int boxY = -1;                // -1 = follow player's feet
        public int boxHeight = 1;

        static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
        static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("chunkfinder.json"); }

        static Config load() {
            try {
                if (Files.exists(file())) return GSON.fromJson(Files.readString(file()), Config.class);
            } catch (Exception ignored) {}
            return new Config();
        }
        void save() {
            try { Files.writeString(file(), GSON.toJson(this)); } catch (IOException ignored) {}
        }
    }

    public static Config CFG;
    private enum Kind { GEODE, ACTIVITY, BOTH }

    private static final Map<ChunkPos, Kind> flagged = new ConcurrentHashMap<>();
    private static final Set<ChunkPos> scanned = new HashSet<>();
    private static KeyMapping toggleKey;

    private static final int PURPLE = 0xFFAA5AFF; // geodes
    private static final int ORANGE = 0xFFFFAA28; // player activity / both

    // ---------------- Init ----------------
    @Override
    public void onInitializeClient() {
        CFG = Config.load();

        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.chunkfinder.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        LevelRenderEvents.BEFORE_GIZMOS.register(ctx -> render());
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(
            ClientCommands.literal("chunkfinder")
                .then(ClientCommands.literal("toggle").executes(c -> {
                    CFG.enabled = !CFG.enabled; changed();
                    c.getSource().sendFeedback(Component.literal("Chunk Finder " + (CFG.enabled ? "ON" : "OFF")));
                    return 1;
                }))
                .then(ClientCommands.literal("sensitivity").then(ClientCommands.argument("value", IntegerArgumentType.integer(1, 10)).executes(c -> {
                    CFG.sensitivity = IntegerArgumentType.getInteger(c, "value"); changed();
                    c.getSource().sendFeedback(Component.literal("Sensitivity: " + CFG.sensitivity));
                    return 1;
                })))
                .then(ClientCommands.literal("distance").then(ClientCommands.argument("value", IntegerArgumentType.integer(2, 32)).executes(c -> {
                    CFG.simulationDistance = IntegerArgumentType.getInteger(c, "value"); changed();
                    c.getSource().sendFeedback(Component.literal("Simulation distance: " + CFG.simulationDistance + " chunks"));
                    return 1;
                })))
                .then(ClientCommands.literal("geodes").then(ClientCommands.argument("value", BoolArgumentType.bool()).executes(c -> {
                    CFG.geodes = BoolArgumentType.getBool(c, "value"); changed();
                    c.getSource().sendFeedback(Component.literal("Geodes: " + CFG.geodes));
                    return 1;
                })))
                .then(ClientCommands.literal("activity").then(ClientCommands.argument("value", BoolArgumentType.bool()).executes(c -> {
                    CFG.activity = BoolArgumentType.getBool(c, "value"); changed();
                    c.getSource().sendFeedback(Component.literal("Player activity: " + CFG.activity));
                    return 1;
                })))
        ));
    }

    /** Save config and force a rescan so new settings apply immediately. */
    private static void changed() {
        CFG.save();
        flagged.clear();
        scanned.clear();
    }

    // ---------------- Scanning ----------------
    private void onTick(Minecraft mc) {
        while (toggleKey.consumeClick()) {
            CFG.enabled = !CFG.enabled; changed();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("Chunk Finder " + (CFG.enabled ? "ON" : "OFF")));
        }
        if (!CFG.enabled || mc.level == null || mc.player == null) return;

        int dist = CFG.simulationDistance;
        ChunkPos center = mc.player.chunkPosition();

        flagged.keySet().removeIf(p -> cheb(p, center) > dist);
        scanned.removeIf(p -> cheb(p, center) > dist);

        List<ChunkPos> pending = new ArrayList<>();
        for (int dx = -dist; dx <= dist; dx++)
            for (int dz = -dist; dz <= dist; dz++) {
                ChunkPos p = new ChunkPos(center.x() + dx, center.z() + dz);
                if (!scanned.contains(p) && mc.level.getChunkSource().getChunkNow(p.x(), p.z()) != null) pending.add(p);
            }
        pending.sort(Comparator.comparingInt(p -> cheb(p, center)));

        for (int i = 0; i < Math.min(CFG.chunksPerTick, pending.size()); i++) {
            ChunkPos pos = pending.get(i);
            LevelChunk chunk = mc.level.getChunkSource().getChunkNow(pos.x(), pos.z());
            scanned.add(pos);
            if (chunk != null) scan(chunk, pos);
        }
    }

    private static int weightGeode(BlockState st) {
        if (st.is(Blocks.BUDDING_AMETHYST)) return 6;
        if (st.is(Blocks.AMETHYST_CLUSTER) || st.is(Blocks.LARGE_AMETHYST_BUD)) return 2;
        if (st.is(Blocks.AMETHYST_BLOCK) || st.is(Blocks.MEDIUM_AMETHYST_BUD) || st.is(Blocks.SMALL_AMETHYST_BUD)) return 1;
        return 0;
    }

    private static int weightActivity(BlockState st) {
        if (st.is(Blocks.CHEST) || st.is(Blocks.TRAPPED_CHEST) || st.is(Blocks.BARREL) || st.is(Blocks.ENDER_CHEST)
            || st.is(Blocks.FURNACE) || st.is(Blocks.BLAST_FURNACE) || st.is(Blocks.SMOKER)
            || st.is(Blocks.HOPPER) || st.is(Blocks.DROPPER) || st.is(Blocks.DISPENSER) || st.is(Blocks.OBSERVER)
            || st.is(Blocks.PISTON) || st.is(Blocks.STICKY_PISTON) || st.is(Blocks.CRAFTING_TABLE)
            || st.is(Blocks.ENCHANTING_TABLE) || st.is(Blocks.ANVIL) || st.is(Blocks.BREWING_STAND)
            || st.is(Blocks.BEACON) || st.is(Blocks.RESPAWN_ANCHOR)
            || st.getBlock() instanceof BedBlock || st.getBlock() instanceof ShulkerBoxBlock) return 3;
        return 0;
    }

    private void scan(LevelChunk chunk, ChunkPos pos) {
        int geode = 0, act = 0;
        boolean wantG = CFG.geodes, wantA = CFG.activity;

        for (LevelChunkSection section : chunk.getSections()) {
            if (section == null || section.hasOnlyAir()) continue;
            boolean g = wantG && section.maybeHas(s -> weightGeode(s) > 0);
            boolean a = wantA && section.maybeHas(s -> weightActivity(s) > 0);
            if (!g && !a) continue;

            for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
                BlockState st = section.getBlockState(x, y, z);
                if (g) geode += weightGeode(st);
                if (a) act += weightActivity(st);
            }
        }

        // Sensitivity 10 -> threshold 2, sensitivity 1 -> threshold 20
        int threshold = Math.max(2, 22 - CFG.sensitivity * 2);
        boolean gHit = geode >= threshold, aHit = act >= threshold;
        if (gHit && aHit) flagged.put(pos, Kind.BOTH);
        else if (gHit) flagged.put(pos, Kind.GEODE);
        else if (aHit) flagged.put(pos, Kind.ACTIVITY);
        else flagged.remove(pos);
    }

    // ---------------- Rendering ----------------
    private static void render() {
        Minecraft mc = Minecraft.getInstance();
        if (!CFG.enabled || flagged.isEmpty() || mc.player == null) return;

        // One shared Y slab so every highlight lines up on the same plane.
        double y1 = CFG.boxY == -1 ? mc.player.getBlockY() : CFG.boxY;
        double y2 = y1 + CFG.boxHeight;

        for (Map.Entry<ChunkPos, Kind> e : flagged.entrySet()) {
            // Snapped to the chunk grid: exactly 16x16 blocks.
            double x1 = e.getKey().getMinBlockX(), z1 = e.getKey().getMinBlockZ();
            double x2 = x1 + 16, z2 = z1 + 16;
            int color = e.getValue() == Kind.GEODE ? PURPLE : ORANGE;
            Gizmos.cuboid(new AABB(x1, y1, z1, x2, y2, z2), GizmoStyle.stroke(color));
        }
    }

    private static int cheb(ChunkPos a, ChunkPos b) {
        return Math.max(Math.abs(a.x() - b.x()), Math.abs(a.z() - b.z()));
    }
}
