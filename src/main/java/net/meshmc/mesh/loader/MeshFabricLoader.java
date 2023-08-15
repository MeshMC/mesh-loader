package net.meshmc.mesh.loader;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import org.spongepowered.asm.mixin.Mixins;

import java.nio.file.Path;
import java.util.Arrays;

public class MeshFabricLoader implements ModInitializer, PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        ClassLoader classLoader = getClassLoader();
        String gameVersion = getGameVersion();
        Path gameFolder = getGameFolder();
        if(classLoader == null) throw new RuntimeException("Mesh Loader failed to find target classloader");
        if(gameVersion == null) throw new RuntimeException("Mesh Loader failed to find game version");
        if(gameFolder == null) throw new RuntimeException("Mesh Loader failed to find game folder");

        MeshLoader loader = MeshLoader.getOrCreateInstance(MeshLoader.Runtime.FABRIC, gameVersion, gameFolder);
        loader.getMods().stream().flatMap(mod -> Arrays.stream(mod.getMixins())).forEach(Mixins::addConfiguration);
        loader.load(classLoader);
    }

    @Override
    public void onInitialize() {
        MeshLoader.getInstance().init();
    }

    private static ClassLoader getClassLoader() {
        // loader v0.12.x
        Class<?> targetClass = null;
        try {
            targetClass = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncherBase");
        } catch(Exception ignored) {
            // loader v0.11.x
            try {
                targetClass = Class.forName("net.fabricmc.loader.launch.common.FabricLauncherBase");
            } catch(Exception ignored2) {
            }
        }

        if(targetClass != null) {
            try {
                Object launcher = targetClass.getMethod("getLauncher").invoke(null);
                return (ClassLoader) launcher.getClass().getMethod("getTargetClassLoader").invoke(launcher);
            } catch(Exception ignored) {
            }
        }
        return null;
    }

    private static String getGameVersion() {
        // loader v0.12.x
        Class<?> targetClass = null;
        try {
            targetClass = Class.forName("net.fabricmc.loader.impl.FabricLoaderImpl");
        } catch(Exception ignored) {
        }
        if(targetClass != null) {
            return FabricLoaderImpl.INSTANCE.getGameProvider().getNormalizedGameVersion();
        }

        // loader v0.11.x
        try {
            targetClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
        } catch(Exception ignored) {
        }
        if(targetClass != null) {
            try {
                return (String) Class.forName("net.fabricmc.loader.game.GameProvider").getMethod("getNormalizedGameVersion")
                .invoke(Class.forName("net.fabricmc.loader.FabricLoader").getMethod("getGameProvider")
                .invoke(targetClass.getMethod("getInstance").invoke(null)));
            } catch(Exception ignored) {
            }
        }

        return null;
    }

    private static Path getGameFolder() {
        // loader v0.12.x
        Class<?> targetClass = null;
        try {
            targetClass = Class.forName("net.fabricmc.loader.impl.FabricLoaderImpl");
        } catch(Exception ignored) {
        }
        if(targetClass != null) {
            return FabricLoaderImpl.INSTANCE.getGameDir();
        }

        // loader v0.11.x
        try {
            targetClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
        } catch(Exception ignored) {
        }
        if(targetClass != null) {
            try {
                return (Path) Class.forName("net.fabricmc.loader.FabricLoader").getMethod("getGameDir")
                                .invoke(targetClass.getMethod("getInstance").invoke(null));
            } catch (Exception ignored) {
            }
        }

        return null;
    }
}
