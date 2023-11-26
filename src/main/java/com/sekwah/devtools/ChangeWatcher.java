package com.sekwah.devtools;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import static java.nio.file.FileVisitOption.FOLLOW_LINKS;
import static java.nio.file.StandardWatchEventKinds.*;

public class ChangeWatcher {


    private final WatchService watcher;
    private final Map<WatchKey, Path> keys;
    public static final Logger LOGGER = LogManager.getLogger("SekC Dev Tools: Change Watcher");
    private final Runnable runnable;
    private final boolean recursive;
    private final String fileExtension;

    // Tbh ive never actually used volatile before, though it avoids the thread using its local cache which seems pretty powerful! :)
    private volatile boolean closed = false;
    private final Object lock = new Object();

    public ChangeWatcher(File resourceFolder, Runnable runnable, boolean recursive, String fileExtension) throws IOException {
        this.watcher = FileSystems.getDefault().newWatchService();
        this.runnable = runnable;
        var watchPath = resourceFolder.toPath();
        this.keys = new HashMap<>();
        this.recursive = recursive;
        this.fileExtension = fileExtension;
        watchFolder(watchPath, recursive);
        LOGGER.info("Watching all files in {}", watchPath);
    }

    /**
     * Watch for file changes, with an option for recursive watching.
     * @param watchFolder the folder to watch
     * @param recursive whether to watch subdirectories recursively
     */
    private void watchFolder(final Path watchFolder, boolean recursive) throws IOException {
        if (recursive) {
            Files.walkFileTree(watchFolder, EnumSet.of(FOLLOW_LINKS), Integer.MAX_VALUE, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                    registerDirectory(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } else {
            registerDirectory(watchFolder);
        }
    }

    private void registerDirectory(Path dir) throws IOException {
        var key = dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
        var registered = keys.get(key);
        if (registered == null) {
            LOGGER.info("Watching: {}", dir);
        } else {
            if (!dir.equals(registered)) {
                LOGGER.info("Update: {} -> {}", registered, dir);
            }
        }
        keys.put(key, dir);
    }

    // Disable Watcher
    public void close() throws IOException {
        try {
            synchronized (lock) {
                closed = true;
                watcher.close();
            }
        } catch (IOException e) {
            LOGGER.error("Problem closing watcher", e);
        }
    }


    private static int ticksSinceLastReload = 0;
    public void processEvents() {
        synchronized (lock) {
            if(closed) return;
            ticksSinceLastReload++;
            WatchKey key;
            boolean shouldRun = false;
            do {
                try {
                    key = watcher.poll();
                } catch (ClosedWatchServiceException e) {
                    LOGGER.error("Watcher closed, if this error occurs outside of a reload then is an issue.");
                    return;
                }
                if(key == null) {
                    continue;
                }

                Path dir = keys.get(key);
                if (dir == null) {
                    LOGGER.info("Unexpected key triggered {}", dir);
                    continue;
                }

                for(WatchEvent<?> event: key.pollEvents()) {
                    var kind = event.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }

                    var context = event.context();
                    if(context instanceof Path path) {
                        var child = dir.resolve(path);

                        // Check if the file extension matches
                        if (this.fileExtension != null && !child.toString().endsWith("." + this.fileExtension)) {
                            continue; // Skip this file as it does not match the file extension
                        }

                        LOGGER.info("Event {} on {}", kind.name(), child);

                        if(kind == ENTRY_CREATE) {
                            try  {
                                if (recursive && Files.isDirectory(child)) {
                                    watchFolder(child, true);
                                }
                            } catch (IOException e) {
                                LOGGER.error("Problem watching new folder {}", dir);
                            }
                        } else if(kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                            System.out.println("Ticks since last reload: " + ticksSinceLastReload);
                            shouldRun = true;
                            LOGGER.info("File changed!!!! {}", dir);
                        }
                    }
                }
                boolean valid = key.reset();
                if(!valid) {
                    keys.remove(key);
                }
            } while(key != null);
            if(shouldRun) {
                if(ticksSinceLastReload > 20 * 3) {
                    ticksSinceLastReload = 0;
                    this.runnable.run();
                } else {
                    LOGGER.info("Update skipped due to being too soon");
                }
            }
        }
    }
}
