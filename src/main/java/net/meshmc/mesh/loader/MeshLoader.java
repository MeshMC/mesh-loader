package net.meshmc.mesh.loader;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class MeshLoader {
    static final Logger LOGGER = LogManager.getLogger("Mesh Loader");

    public enum Runtime { FABRIC, FORGE }

    private static MeshLoader INSTANCE = null;

    private final Runtime runtime;
    private final String gameVersion;
    private final Path gameFolder;

    // list of all mods loaded (and later initialized) into the game
    private final List<Mod> mods = new ArrayList<>();

    private boolean loaded = false;
    private boolean initialized = false;

    private MeshLoader(Runtime runtime, String gameVersion, Path gameFolder) {
        this.runtime = runtime;
        this.gameVersion = gameVersion;
        this.gameFolder = gameFolder;
    }

    private void find(File directory, Consumer<? super Path> consumer) {
        try(DirectoryStream<Path> stream = Files.newDirectoryStream(directory.toPath(), "*.jar")) {
            stream.forEach(consumer);
        } catch(IOException ignored) {
        }
    }

    void load(ClassLoader classLoader) {
        if(loaded) return;

        long start = System.currentTimeMillis();

        // find mods folder
        File modFolder = new File(gameFolder.toFile(), "mods");
        File meshFolder = new File(gameFolder.toFile(), "mesh");
        File modMeshFolder = modFolder.isDirectory() ? new File(modFolder, "mesh") : null;

        // find all mod files (.jar)s
        List<Path> paths = new ArrayList<>();
        find(modFolder, paths::add);
        find(meshFolder, paths::add);
        if(modMeshFolder != null) find(modMeshFolder, paths::add);

        // try to remove mesh loader from lists of files to check
        try {
            paths.remove(new File(MeshLoader.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toPath());
        } catch(Exception ignored) {
        }

        // if no jars found, we are done loading early!
        if(paths.isEmpty()) {
            LOGGER.info("Mesh for {} {} found no mods in {} milliseconds!",
                runtime.name().toLowerCase(), gameVersion, System.currentTimeMillis() - start);
            return;
        }

        // load mods into classpath
        Method addURL;
        try {
            try { // fabric loader v0.14.x
                addURL = classLoader.getClass().getDeclaredMethod("addUrlFwd", URL.class);
            } catch(Exception ignored) { // fabric loader pre v0.14.x or forge
                if(classLoader instanceof URLClassLoader) addURL = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
                else addURL = classLoader.getClass().getDeclaredMethod("addURL", URL.class);
            }
            addURL.setAccessible(true);

            for(Path path: paths) {
                addURL.invoke(classLoader, path.toUri().toURL());
            }
        } catch(NoSuchMethodException | SecurityException e) {
            e.printStackTrace();
            LOGGER.info("Mesh Loader failed to find addURL method");
            return;
        } catch(IllegalAccessException | IllegalArgumentException | InvocationTargetException | MalformedURLException e) {
            e.printStackTrace();
            LOGGER.info("Mesh Loader failed to invoke addURL method");
            return;
        }

        // find mesh mod jsons
        Gson gson = new Gson();
        Reflections reflections = new Reflections(new ConfigurationBuilder()
            .setUrls(ClasspathHelper.forClassLoader())
            .setScanners(Scanners.Resources)
        );
        reflections.getResources(".*\\.mesh\\.json").forEach((resource) -> {
            InputStream is = ClasspathHelper.contextClassLoader().getResourceAsStream(resource);
            if(is == null) is = ClasspathHelper.staticClassLoader().getResourceAsStream(resource);
            if(is != null) {
                try {
                    mods.add(gson.fromJson(new InputStreamReader(is), Mod.class));
                } catch(Exception e) {
                    e.printStackTrace();
                }
            }
        });

        // Load version specific code for each mod
        String versionString = gameVersion.replaceAll("\\.", "_");
        String majorVersionString = versionString.substring(0, versionString.lastIndexOf("_")) + "_x";
        for(Mod mod: mods) {
            String path = "/" + mod.getId() + "-v" + versionString + "-" + mod.getVersion() + ".jar";
            InputStream is = MeshLoader.class.getResourceAsStream(path);
            if(is == null) is = MeshLoader.class.getResourceAsStream((path = "/" + mod.getId() + "-v" + majorVersionString + "-" + mod.getVersion() + ".jar"));
            if(is != null) {
                try {
                    addURL.invoke(classLoader, createTempFile(is).toURI().toURL());
                    mod.versionLoaded = true;
                    LOGGER.info("Loaded " + path);
                } catch(Exception e) {
                    e.printStackTrace();
                    LOGGER.info("Mesh Loader failed to load " + path);
                }
            }
        }

        LOGGER.info("Mesh for {} {} loaded {} mods in {} milliseconds!",
                runtime.name().toLowerCase(), gameVersion,
                mods.size(), System.currentTimeMillis() - start);

        loaded = true;
    }

    private static File createTempFile(InputStream is) throws Exception {
        // create temp file of jar to load from classpath
        File tempFile = File.createTempFile("mesh", ".jar");
        tempFile.deleteOnExit();

        // read jar from resources
        int read;
        byte[] buffer = new byte[64];
        FileOutputStream fos = new FileOutputStream(tempFile);
        while((read = is.read(buffer)) != -1) fos.write(buffer, 0, read);
        fos.close();
        is.close();
        return tempFile;
    }

    void init() {
        if(initialized || !loaded) return;

        long start = System.currentTimeMillis();

        // create map of version interfaces to version implementations
        HashMap<Class<?>, Class<?>> implMap = new HashMap<>();
        Reflections reflections = new Reflections(new ConfigurationBuilder()
            .setUrls(
                mods.stream().flatMap(mod -> Arrays.stream(mod.getInterfaces()))
                .flatMap(pkg -> ClasspathHelper.forPackage(pkg).stream())
                .collect(Collectors.toList())
            )
            .setScanners(Scanners.TypesAnnotated)
        );

        reflections.getTypesAnnotatedWith(Mod.Interface.class).forEach(clazz -> {
            if(clazz.getInterfaces().length == 1 && clazz.getConstructors().length == 1
                    && clazz.getConstructors()[0].getParameters().length == 0) {
               implMap.put(clazz.getInterfaces()[0], clazz);
            } else LOGGER.error("{} has an invalid use of @Mod.Interface", clazz.getName());
        });

        // do initialization for each mod
        for(Mod mod: mods) {
            for(String className: mod.getInitializers()) {
                Object initializer;
                try {
                    initializer = Class.forName(className).getConstructor().newInstance();
                } catch(Exception e) {
                    throw new RuntimeException("Mesh Loader failed to create initializer instance " + className);
                }

                // handle mod annotation directives
                for(Field field: initializer.getClass().getDeclaredFields()) {
                    if(field.isAnnotationPresent(Mod.Interface.class)) {
                        if(!field.getType().isInterface()) {
                            LOGGER.error("Field {} in {} must be an interface type", field.getName(), initializer.getClass());
                            continue;
                        }

                        Class<?> implClass = implMap.get(field.getType());
                        if(implClass == null) LOGGER.error("Mod interface {} in {} does not have an implementation", field.getName(), initializer.getClass().getName());
                        else try {
                            Object impl = implClass.getConstructors()[0].newInstance();

                            field.setAccessible(true);
                            if(Modifier.isStatic(field.getModifiers())) field.set(null, impl);
                            else field.set(initializer, impl);
                        } catch(Exception e) {
                            throw new RuntimeException(e);
                        }
                    } else if(field.isAnnotationPresent(Mod.Instance.class) && Modifier.isStatic(field.getModifiers())
                            && field.getType() == initializer.getClass()) {
                        try {
                            field.setAccessible(true);
                            field.set(null, initializer);
                        } catch(Exception ignored) {
                        }
                    }
                }

                if(initializer instanceof Mod.Initializer) ((Mod.Initializer) initializer).init(mod);
                else throw new RuntimeException("Failed to call initializer " + className + " in mod " + mod.getId());
            }
        }

        LOGGER.info("Mesh for {} {} initialized {} mods in {} milliseconds!",
                runtime.name().toLowerCase(), gameVersion,
                mods.size(), System.currentTimeMillis() - start);

        initialized = true;
    }

    public Runtime getRuntime() {
        return runtime;
    }

    public String getGameVersion() {
        return gameVersion;
    }

    public Path getGameFolder() {
        return gameFolder;
    }

    public List<Mod> getMods() {
        return mods;
    }

    public static MeshLoader getInstance() {
        return INSTANCE;
    }

    static MeshLoader getOrCreateInstance(Runtime runtime, String gameVersion, Path gameFolder) {
        if(INSTANCE == null) INSTANCE = new MeshLoader(runtime, gameVersion, gameFolder);
        return INSTANCE;
    }
}
