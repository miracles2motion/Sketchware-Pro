package pro.sketchware.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Registry of the models Agent Mode knows about.
 *
 * <p>Backed by {@code .sketchware/ai/agent-config.json} so it stays
 * human-inspectable and survives an app update. This is the single source of
 * truth for "which model is active"; nothing else is persisted for it.
 */
public class AiModelConfig {

    /** Inference backend to use. */
    public enum Backend {
        CPU,
        GPU,
        NPU;

        public static Backend fromName(String name) {
            if (name == null) {
                return CPU;
            }
            for (Backend backend : values()) {
                if (backend.name().equalsIgnoreCase(name)) {
                    return backend;
                }
            }
            return CPU;
        }
    }

    /** One imported model. */
    public static class ModelEntry {
        /** Stable identifier, also used as the directory name. */
        public String id;
        /** Absolute path of the {@code .litertlm} file. */
        public String modelPath;
        /** Backend this model was last used with. */
        public String backend = Backend.CPU.name();
        /** Size of the model file in bytes. */
        public long sizeBytes;
        /** SHA-256 of the model file, used to detect a swapped model. */
        public String sha256;
        /** Epoch millis of the import. */
        public long importedAt;
        /** Set to false when the file disappeared or failed validation. */
        public boolean valid = true;
        /** Metadata read from the model file, for example its context size. */
        public String description;

        public ModelEntry() {
        }

        public ModelEntry(String id, String modelPath) {
            this.id = id;
            this.modelPath = modelPath;
        }

        public File file() {
            return modelPath == null ? null : new File(modelPath);
        }
    }

    /** The whole registry. */
    public static class Registry {
        /** Id of the model that should be used, or null when none is set up. */
        public String activeModelId;
        /** Backend override for the active model. */
        public String backend = Backend.CPU.name();
        /**
         * Set once a GPU initialisation failed, so the app does not keep
         * retrying a backend that does not work on this device.
         */
        public boolean gpuFailed;
        /** Every model that has been imported. */
        public List<ModelEntry> models = new ArrayList<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private Registry registry;

    /** Reads the registry from disk, returning an empty one when absent. */
    public static AiModelConfig load() {
        AiModelConfig config = new AiModelConfig();
        File file = AiPaths.configFile();
        if (file.exists()) {
            try (FileReader reader = new FileReader(file)) {
                Registry parsed = GSON.fromJson(reader, Registry.class);
                if (parsed != null) {
                    if (parsed.models == null) {
                        parsed.models = new ArrayList<>();
                    }
                    config.registry = parsed;
                }
            } catch (IOException | JsonSyntaxException e) {
                // A corrupt registry must not stop the app from starting; the
                // user can simply re-import the model.
                config.registry = new Registry();
            }
        }
        if (config.registry == null) {
            config.registry = new Registry();
        }
        return config;
    }

    /** Writes the registry back to disk. */
    public void save() {
        File file = AiPaths.configFile();
        try (FileWriter writer = new FileWriter(file)) {
            GSON.toJson(registry, writer);
        } catch (IOException e) {
            // Losing the registry only means the model has to be re-imported.
        }
    }

    public Registry registry() {
        return registry;
    }

    /** The model that is currently selected, or null when there is none. */
    public ModelEntry activeModel() {
        if (registry.activeModelId == null) {
            return null;
        }
        for (ModelEntry entry : registry.models) {
            if (registry.activeModelId.equals(entry.id)) {
                return entry;
            }
        }
        return null;
    }

    /** Adds a model, replacing an entry with the same id. */
    public void putModel(ModelEntry entry) {
        for (int i = 0; i < registry.models.size(); i++) {
            if (registry.models.get(i).id != null && registry.models.get(i).id.equals(entry.id)) {
                registry.models.set(i, entry);
                return;
            }
        }
        registry.models.add(entry);
    }

    /** Selects a model by id. */
    public void setActiveModel(String id) {
        registry.activeModelId = id;
    }

    /** Removes a model from the registry. */
    public void removeModel(String id) {
        registry.models.removeIf(entry -> entry.id != null && entry.id.equals(id));
        if (id != null && id.equals(registry.activeModelId)) {
            registry.activeModelId = null;
        }
    }

    /** The backend that should be used for the active model. */
    public Backend activeBackend() {
        if (registry.gpuFailed) {
            return Backend.CPU;
        }
        return Backend.fromName(registry.backend);
    }
}
