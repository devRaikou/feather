package probe;

import com.grinderwolf.swm.api.world.SlimeChunk;
import com.grinderwolf.swm.api.world.properties.SlimeProperties;
import com.grinderwolf.swm.api.world.properties.SlimePropertyMap;
import com.grinderwolf.swm.nms.CraftSlimeWorld;
import com.grinderwolf.swm.plugin.SWMPlugin;
import com.grinderwolf.swm.plugin.loaders.LoaderUtils;
import com.grinderwolf.swm.plugin.world.importer.WorldImporter;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;

public final class LazyWorldProbe extends JavaPlugin {
    private record Chest(int x, int y, int z, int data) {}
    private record Snapshot(byte[] bytes, List<Chest> chests) {}

    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                Snapshot snapshot = exercise();
                // Release the previous world's main-thread frames before reopening,
                // matching an editor closing a session and opening it again later.
                Bukkit.getScheduler().runTaskLater(this, () -> verifyReopened(snapshot), 2L);
            } catch (Throwable failure) {
                fail(failure);
                Bukkit.shutdown();
            }
        });
    }

    private void verifyReopened(Snapshot snapshot) {
        try {
            System.gc();
            getLogger().info("PROBE_REOPEN heapUsed=" + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()));
            CraftSlimeWorld reopened = LoaderUtils.deserializeWorld(null, "probe-reopened", snapshot.bytes(), new SlimePropertyMap(), true);
            for (int i = 0; i < snapshot.chests().size(); i++) {
                Chest chest = snapshot.chests().get(i);
                SlimeChunk chunk = reopened.getChunk(chest.x() >> 4, chest.z() >> 4);
                var section = chunk.getSections()[chest.y() >> 4];
                int index = (chest.y() & 15) << 8 | (chest.z() & 15) << 4 | chest.x() & 15;
                require((section.getBlocks()[index] & 255) == 130, "Saved chest block changed at " + chest);
                require(section.getData().get(index) == (i == 0 ? editedDirection(chest) : chest.data()), "Saved chest orientation changed at " + chest);
                String tileId = chunk.getTileEntities().stream()
                        .filter(tile -> tile.getIntValue("x").orElse(0) == chest.x()
                                && tile.getIntValue("y").orElse(0) == chest.y()
                                && tile.getIntValue("z").orElse(0) == chest.z())
                        .map(tile -> tile.getStringValue("id").orElse("")).findFirst().orElse("");
                require(tileId.equals("EnderChest"), "Saved tile entity changed at " + chest + ": " + tileId);
            }
            String result = "PASS: large world generated on demand; repeated reads reuse chunks; edited and untouched chest blocks, directions and tile entities survive save/reopen; enders=" + snapshot.chests().size();
            getLogger().info(result);
            Files.writeString(Path.of("probe-result.txt"), result);
        } catch (Throwable failure) {
            fail(failure);
        } finally {
            Bukkit.shutdown();
        }
    }

    private void fail(Throwable failure) {
        getLogger().log(Level.SEVERE, "PROBE_FAILED", failure);
        try { Files.writeString(Path.of("probe-result.txt"), "FAIL: " + failure); } catch (Exception ignored) {}
    }

    private static int editedDirection(Chest chest) {
        return chest.data() == 2 ? 3 : 2;
    }

    private Snapshot exercise() throws Exception {
        CraftSlimeWorld slime = readSource();
        List<Chest> chests = new ArrayList<>();
        for (SlimeChunk chunk : slime.getChunks().values()) {
            for (var tile : chunk.getTileEntities()) {
                if (!tile.getStringValue("id").orElse("").equals("EnderChest")) continue;
                int x = tile.getIntValue("x").get();
                int y = tile.getIntValue("y").get();
                int z = tile.getIntValue("z").get();
                int index = (y & 15) << 8 | (z & 15) << 4 | x & 15;
                chests.add(new Chest(x, y, z, chunk.getSections()[y >> 4].getData().get(index)));
            }
        }
        chests.sort(Comparator.comparingInt(Chest::x).thenComparingInt(Chest::z).thenComparingInt(Chest::y));
        int expectedEnders = Integer.getInteger("probe.expectedEnders", 68);
        require(chests.size() == expectedEnders && expectedEnders > 1,
                "Expected " + expectedEnders + " ender chests, got " + chests.size());
        getLogger().info("PROBE_SOURCE chunks=" + slime.getChunks().size() + " enders=" + chests.size() + " heapMax=" + Runtime.getRuntime().maxMemory());

        SWMPlugin feather = (SWMPlugin) Bukkit.getPluginManager().getPlugin("feather");
        feather.generateWorld(slime);
        World world = Bukkit.getWorld(slime.getName());
        require(world != null, "Generated world is missing");
        world.setAutoSave(false);
        world.setKeepSpawnInMemory(false);
        try {
            long converted = convertedChunks(slime);
            getLogger().info("PROBE_GENERATED chunks=" + slime.getChunks().size() + " converted=" + converted + " heapUsed=" + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()));
            require(converted < 1024, "World generation eagerly converted " + converted + " chunks");
            for (int i : new int[]{0, chests.size() / 2}) {
                Chest chest = chests.get(i);
                var block = world.getBlockAt(chest.x(), chest.y(), chest.z());
                require(block.getTypeId() == 130 && block.getData() == chest.data(), "Runtime ender chest changed");
                SlimeChunk cached = slime.getChunk(chest.x() >> 4, chest.z() >> 4);
                require(cached.getClass().getSimpleName().equals("NMSSlimeChunk"), "Requested chunk was not converted");
                world.getChunkAt(chest.x() >> 4, chest.z() >> 4);
                require(cached == slime.getChunk(chest.x() >> 4, chest.z() >> 4), "Repeated read recreated a chunk");
            }
            Chest edited = chests.get(0);
            world.getBlockAt(edited.x(), edited.y(), edited.z()).setData((byte) editedDirection(edited), false);
            getLogger().info("PROBE_VISITED converted=" + convertedChunks(slime));
            byte[] bytes = slime.serialize();
            getLogger().info("PROBE_SAVED bytes=" + bytes.length);
            return new Snapshot(bytes, chests);
        } finally {
            require(Bukkit.unloadWorld(world, false), "Could not unload probe world");
        }
    }

    private CraftSlimeWorld readSource() throws Exception {
        CraftSlimeWorld source = WorldImporter.readFromDirectory(Path.of(System.getProperty("probe.map")).toFile());
        CraftSlimeWorld runtime = (CraftSlimeWorld) source.clone("__feather_lazy_probe__");
        runtime.getPropertyMap().setString(SlimeProperties.WORLD_TYPE, "flat");
        runtime.getPropertyMap().setBoolean(SlimeProperties.ALLOW_MONSTERS, false);
        runtime.getPropertyMap().setBoolean(SlimeProperties.ALLOW_ANIMALS, false);
        return runtime;
    }

    private static long convertedChunks(CraftSlimeWorld world) {
        return world.getChunks().values().stream().filter(chunk -> chunk.getClass().getSimpleName().equals("NMSSlimeChunk")).count();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
