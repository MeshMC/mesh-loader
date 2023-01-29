package net.meshmc.mesh.loader;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Represents mesh mod information read by Gson from a *.mesh.json file
public class Mod {
    public interface Initializer {
        void init(Mod mod);
    }

    // used to mark version specific fields in the mod instance
    // and used to initialize version specific interfaces to that field
    @Target({ElementType.FIELD, ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Interface {
    }

    // used to mark the static instance field in a mod initializer
    // instance field is set when the mod initializer is loader (before initialization and after all mod loading)
    @Target({ElementType.FIELD})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Instance {
    }

    private String id;
    private String version;
    private String name;
    private String description;
    private String[] authors;
    private String website;
    private String[] initializers; // array of class names implementing Initializer
    private String[] interfaces; // packages to search for mod interfaces in
    private String[] mixins; // mixins to add after loading version specific code

    transient boolean versionLoaded = false;

    public String getId() {
        return id;
    }

    public String getVersion() {
        return version;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String[] getAuthors() {
        return authors;
    }

    public String getWebsite() {
        return website;
    }

    public String[] getInitializers() {
        return initializers;
    }

    public String[] getInterfaces() {
        return interfaces;
    }

    public String[] getMixins() {
        return mixins;
    }

    public boolean isVersionLoaded() {
        return versionLoaded;
    }
}
