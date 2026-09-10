package com.grinderwolf.swm.plugin.world;

import com.grinderwolf.swm.api.exceptions.UnknownWorldException;
import com.grinderwolf.swm.api.loaders.SlimeLoader;
import com.grinderwolf.swm.api.world.SlimeWorld;
import com.grinderwolf.swm.plugin.SWMPlugin;
import com.grinderwolf.swm.plugin.log.Logging;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;

import java.io.IOException;

public class WorldUnlocker implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        SlimeWorld world = SWMPlugin.getInstance().getNms().getSlimeWorld(event.getWorld());

        if (world != null && !world.isReadOnly() && world.getLoader() != null) {
            // Keep only the unlock coordinates in queued/retried tasks. Capturing
            // the SlimeWorld also retains every chunk after the world is unloaded.
            SlimeLoader loader = world.getLoader();
            String worldName = world.getName();
            Bukkit.getScheduler().runTaskAsynchronously(SWMPlugin.getInstance(), () -> unlockWorld(loader, worldName));
        }
    }

    private void unlockWorld(SlimeLoader loader, String worldName) {
        try {
            loader.unlockWorld(worldName);
        } catch (IOException ex) {
            Logging.error("Failed to unlock world " + worldName + ". Retrying in 5 seconds. Stack trace:");
            ex.printStackTrace();

            Bukkit.getScheduler().runTaskLaterAsynchronously(SWMPlugin.getInstance(), () -> unlockWorld(loader, worldName), 100);
        } catch (UnknownWorldException ignored) {

        }
    }
}
