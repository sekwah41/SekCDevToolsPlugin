package com.sekwah.devtools;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;

public final class SekCDDevToolsPlugin extends JavaPlugin {

    ChangeWatcher changeWatcher;
    private int taskID = -1;

    private Logger logger = LogManager.getLogger();

    @Override
    public void onEnable() {
        // Plugin startup logic

        // Get the plugins folder
        var pluginsFolder = getDataFolder().getParentFile();

        try {
            changeWatcher = new ChangeWatcher(pluginsFolder, () -> {
                logger.info("Reloading plugins");
                Bukkit.reload();
            }, false, "jar");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        System.out.println("Plugins folder: " + pluginsFolder);

        this.taskID = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            if(changeWatcher != null)
                changeWatcher.processEvents();
        }, 0L, 1L);

        logger.info("SekC Dev Tools enabled");
    }

    @Override
    public void onDisable() {
        if (this.taskID != -1) {
            Bukkit.getScheduler().cancelTask(this.taskID);
        }

        try {
            changeWatcher.close();
            changeWatcher = null;
        } catch (IOException e) {
            logger.error("Failed to close change watcher", e);
        }
        // Plugin shutdown logic

        logger.info("SekC Dev Tools disabled");
    }
}
