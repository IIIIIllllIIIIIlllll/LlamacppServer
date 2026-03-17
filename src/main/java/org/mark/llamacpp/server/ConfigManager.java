package org.mark.llamacpp.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置文件管理类，用于保存和加载模型信息及启动配置
 */
public class ConfigManager {
	
	private static final Logger logger = LoggerFactory.getLogger(ConfigManager.class);
	
    private static final ConfigManager INSTANCE = new ConfigManager();
    
    // 配置文件路径
    private static final String CONFIG_DIR = "config";
    private static final String MODELS_CONFIG_FILE = CONFIG_DIR + "/models.json";
    private static final String LAUNCH_CONFIG_FILE = CONFIG_DIR + "/launch_config.json";
    private static final String DEFAULT_CONFIG_NAME = "默认配置";
    
    private final Gson gson;

    private volatile List<Map<String, Object>> cachedModelsConfig = null;
    private volatile long cachedModelsConfigLastModified = -1L;

    private final Object modelsFileLock = new Object();
    private final Object launchFileLock = new Object();
    
    private ConfigManager() {
        // 创建Gson实例，设置美观格式化
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        
        // 确保配置目录存在
        ensureConfigDirectoryExists();
    }
    
    public static ConfigManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * 确保配置目录存在
     */
    private void ensureConfigDirectoryExists() {
        Path configPath = Paths.get(CONFIG_DIR);
        if (!Files.exists(configPath)) {
            try {
                Files.createDirectories(configPath);
                logger.info("创建配置目录: {}", CONFIG_DIR);
            } catch (IOException e) {
                logger.info("创建配置目录失败: {}", e);
            }
        }
    }
    
//    /**
//     * 保存模型列表信息到JSON文件
//     * @param models 模型列表
//     * @return 是否保存成功
//     */
//    public boolean saveModelsConfig(List<GGUFModel> models) {
//        synchronized (modelsFileLock) {
//            try {
//                List<Map<String, Object>> modelsData = models.stream()
//                    .map(this::modelToMap)
//                    .toList();
//                writeJsonFileAtomic(MODELS_CONFIG_FILE, modelsData);
//               logger.info("模型配置已保存到: {}", MODELS_CONFIG_FILE);
//                this.cachedModelsConfig = null;
//                this.cachedModelsConfigLastModified = -1L;
//                return true;
//            } catch (IOException e) {
//                logger.info("保存模型配置失败: {}", e);
//                return false;
//            }
//        }
//    }
    
    /**
     * 保存模型启动配置到JSON文件
     * @param modelId 模型ID
     * @param launchConfig 启动配置
     * @return 是否保存成功
     */
    public boolean saveLaunchConfig(String modelId, Map<String, Object> launchConfig) {
        return this.saveLaunchConfig(modelId, null, launchConfig, false);
    }

    /**
     * 	统一按“selectedConfig + configs”结构写入，兼容旧版单配置结构
     * @param modelId
     * @param configName
     * @param launchConfig
     * @param setSelected
     * @return
     */
    public boolean saveLaunchConfig(String modelId, String configName, Map<String, Object> launchConfig, boolean setSelected) {
        synchronized (launchFileLock) {
            try {
                Map<String, Map<String, Object>> allConfigs = loadAllLaunchConfigsUnsafe();
                Map<String, Object> entry = allConfigs.get(modelId);
                Map<String, Object> normalized = normalizeLaunchConfigEntry(entry);
                Map<String, Object> configs = getConfigItems(normalized);
                if (configs == null) {
                    configs = new HashMap<>();
                    normalized.put("configs", configs);
                }
                String targetConfigName = resolveTargetConfigName(configName, normalized);
                configs.put(targetConfigName, launchConfig == null ? new HashMap<>() : new HashMap<>(launchConfig));
                if (setSelected || !normalized.containsKey("selectedConfig")) {
                    normalized.put("selectedConfig", targetConfigName);
                }
                allConfigs.put(modelId, normalized);
                writeJsonFileAtomic(LAUNCH_CONFIG_FILE, allConfigs);
                logger.info("启动配置已保存到: {}", LAUNCH_CONFIG_FILE);
                return true;
            } catch (IOException e) {
                logger.info("保存启动配置失败: {}", e);
                return false;
            }
        }
    }
    
    /**
     * 加载模型列表信息
     * @return 模型列表数据，如果加载失败返回空列表
     */
    public List<Map<String, Object>> loadModelsConfig() {
        synchronized (modelsFileLock) {
            return loadModelsConfigUnsafe();
        }
    }

    public List<Map<String, Object>> loadModelsConfigCached() {
        synchronized (modelsFileLock) {
            File configFile = new File(MODELS_CONFIG_FILE);
            if (!configFile.exists()) {
                this.cachedModelsConfig = List.of();
                this.cachedModelsConfigLastModified = -1L;
                return List.of();
            }
            long lastModified = configFile.lastModified();
            List<Map<String, Object>> cached = this.cachedModelsConfig;
            if (cached != null && this.cachedModelsConfigLastModified == lastModified) {
                return cached;
            }
            List<Map<String, Object>> loaded = loadModelsConfigUnsafe();
            this.cachedModelsConfig = loaded;
            this.cachedModelsConfigLastModified = lastModified;
            return loaded;
        }
    }
    
    /**
     * 加载所有模型的启动配置
     * @return 所有启动配置的映射，如果加载失败返回空Map
     */
    public Map<String, Map<String, Object>> loadAllLaunchConfigs() {
        synchronized (launchFileLock) {
            Map<String, Map<String, Object>> allConfigs = loadAllLaunchConfigsUnsafe();
            // 关键注释：读取时自动升级旧格式，确保后续统一走新结构
            if (upgradeAllLaunchConfigEntriesIfNeeded(allConfigs)) {
                try {
                    writeJsonFileAtomic(LAUNCH_CONFIG_FILE, allConfigs);
                } catch (IOException e) {
                    logger.info("读取时升级启动配置格式失败: {}", e);
                }
            }
            return allConfigs;
        }
    }

    public Map<String, Object> getModelLaunchConfigBundle(String modelId) {
        synchronized (launchFileLock) {
            Map<String, Map<String, Object>> allConfigs = loadAllLaunchConfigsUnsafe();
            Map<String, Object> entry = allConfigs.get(modelId);
            Map<String, Object> normalized = normalizeLaunchConfigEntry(entry);
            if (entry != null && !normalized.equals(entry)) {
                allConfigs.put(modelId, normalized);
                try {
                    writeJsonFileAtomic(LAUNCH_CONFIG_FILE, allConfigs);
                } catch (IOException e) {
                    logger.info("读取模型配置时自动升级格式失败: {}", e);
                }
            }
            return normalized;
        }
    }

    public boolean deleteLaunchConfig(String modelId, String configName) {
        synchronized (launchFileLock) {
            try {
                Map<String, Map<String, Object>> allConfigs = loadAllLaunchConfigsUnsafe();
                Map<String, Object> entry = allConfigs.get(modelId);
                Map<String, Object> normalized = normalizeLaunchConfigEntry(entry);
                Map<String, Object> configs = getConfigItems(normalized);
                if (configs == null) {
                    configs = new HashMap<>();
                    normalized.put("configs", configs);
                }
                String targetName = configName == null ? "" : configName.trim();
                if (targetName.isEmpty()) {
                    Object selectedObj = normalized.get("selectedConfig");
                    targetName = selectedObj == null ? "" : String.valueOf(selectedObj).trim();
                }
                if (targetName.isEmpty()) {
                    targetName = DEFAULT_CONFIG_NAME;
                }
                configs.remove(targetName);
                // 关键注释：当全部配置被删除时自动回填默认配置，避免前端无可选项
                if (configs.isEmpty()) {
                    configs.put(DEFAULT_CONFIG_NAME, new HashMap<>());
                }
                String selected = String.valueOf(normalized.getOrDefault("selectedConfig", "")).trim();
                if (selected.isEmpty() || !configs.containsKey(selected)) {
                    selected = configs.keySet().iterator().next();
                }
                normalized.put("selectedConfig", selected);
                normalized.put("configs", configs);
                allConfigs.put(modelId, normalized);
                writeJsonFileAtomic(LAUNCH_CONFIG_FILE, allConfigs);
                logger.info("启动配置已删除并保存: {}", LAUNCH_CONFIG_FILE);
                return true;
            } catch (IOException e) {
                logger.info("删除启动配置失败: {}", e);
                return false;
            }
        }
    }
    
//    /**
//     * 将GGUFModel转换为可序列化的Map
//     * @param model GGUFModel对象
//     * @return 包含模型信息的Map
//     */
//    private Map<String, Object> modelToMap(GGUFModel model) {
//        Map<String, Object> modelMap = new HashMap<>();
//        
//        // 基本信息
//        modelMap.put("modelId", model.getModelId());
//        modelMap.put("path", model.getPath());
//        modelMap.put("size", model.getSize());
//        modelMap.put("favourite", model.isFavourite());
//        if (model.getAlias() != null && !model.getAlias().isEmpty()) {
//            modelMap.put("alias", model.getAlias());
//        }
//        
//        // 主模型信息
//        if (model.getPrimaryModel() != null) {
//            Map<String, Object> primaryModel = new HashMap<>();
//            primaryModel.put("fileName", model.getPrimaryModel().getFileName());
//            primaryModel.put("name", model.getPrimaryModel().getStringValue("general.name"));
//            primaryModel.put("architecture", model.getPrimaryModel().getStringValue("general.architecture"));
//            primaryModel.put("contextLength", model.getPrimaryModel().getIntValue(
//                model.getPrimaryModel().getStringValue("general.architecture") + ".context_length"));
//            primaryModel.put("embeddingLength", model.getPrimaryModel().getIntValue(
//                model.getPrimaryModel().getStringValue("general.architecture") + ".embedding_length"));
//            modelMap.put("primaryModel", primaryModel);
//        }
//        
//        // 多模态投影信息
//        if (model.getMmproj() != null) {
//            Map<String, Object> mmproj = new HashMap<>();
//            mmproj.put("fileName", model.getMmproj().getFileName());
//            mmproj.put("name", model.getMmproj().getStringValue("general.name"));
//            mmproj.put("architecture", model.getMmproj().getStringValue("general.architecture"));
//            modelMap.put("mmproj", mmproj);
//        }
//        
//        return modelMap;
//    }

    /**
     * 保存/更新模型别名到models.json
     */
    public boolean saveModelAlias(String modelId, String alias) {
        synchronized (modelsFileLock) {
            try {
                List<Map<String, Object>> models = new java.util.ArrayList<>(loadModelsConfigUnsafe());
                boolean found = false;
                for (Map<String, Object> m : models) {
                    Object id = m.get("modelId");
                    if (id != null && modelId.equals(String.valueOf(id))) {
                        m.put("alias", alias);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    Map<String, Object> minimal = new HashMap<>();
                    minimal.put("modelId", modelId);
                    minimal.put("alias", alias);
                    models.add(minimal);
                }
                writeJsonFileAtomic(MODELS_CONFIG_FILE, models);
                this.cachedModelsConfig = null;
                this.cachedModelsConfigLastModified = -1L;
                return true;
            } catch (IOException e) {
                logger.info("保存模型别名失败: {}", e);
                return false;
            }
        }
    }

    /**
     * 加载别名映射
     */
    public Map<String, String> loadAliasMap() {
        Map<String, String> aliases = new HashMap<>();
        List<Map<String, Object>> models = loadModelsConfigCached();
        for (Map<String, Object> m : models) {
            Object id = m.get("modelId");
            Object alias = m.get("alias");
            if (id != null && alias != null) {
                aliases.put(String.valueOf(id), String.valueOf(alias));
            }
        }
        return aliases;
    }

    public boolean saveModelFavourite(String modelId, boolean favourite) {
        synchronized (modelsFileLock) {
            try {
                List<Map<String, Object>> models = new java.util.ArrayList<>(loadModelsConfigUnsafe());
                boolean found = false;
                for (Map<String, Object> m : models) {
                    Object id = m.get("modelId");
                    if (id != null && modelId.equals(String.valueOf(id))) {
                        m.put("favourite", favourite);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    Map<String, Object> minimal = new HashMap<>();
                    minimal.put("modelId", modelId);
                    minimal.put("favourite", favourite);
                    models.add(minimal);
                }
                writeJsonFileAtomic(MODELS_CONFIG_FILE, models);
                this.cachedModelsConfig = null;
                this.cachedModelsConfigLastModified = -1L;
                return true;
            } catch (IOException e) {
                logger.info("保存模型喜好失败: " + e.getMessage());
                return false;
            }
        }
    }

    public Map<String, Boolean> loadFavouriteMap() {
        Map<String, Boolean> favourites = new HashMap<>();
        List<Map<String, Object>> models = loadModelsConfigCached();
        for (Map<String, Object> m : models) {
            Object id = m.get("modelId");
            Object fav = m.get("favourite");
            if (id == null || fav == null) continue;
            boolean v;
            if (fav instanceof Boolean) {
                v = (Boolean) fav;
            } else {
                v = Boolean.parseBoolean(String.valueOf(fav));
            }
            favourites.put(String.valueOf(id), v);
        }
        return favourites;
    }

    private List<Map<String, Object>> loadModelsConfigUnsafe() {
        File configFile = new File(MODELS_CONFIG_FILE);
        if (!configFile.exists()) {
            logger.info("模型配置文件不存在，返回空列表: {}", MODELS_CONFIG_FILE);
            return List.of();
        }

        try (FileReader reader = new FileReader(configFile)) {
            Type listType = new TypeToken<List<Map<String, Object>>>() {}.getType();
            List<Map<String, Object>> modelsData = gson.fromJson(reader, listType);
            logger.info("成功加载模型配置: {}", MODELS_CONFIG_FILE);
            return modelsData != null ? modelsData : List.of();
        } catch (IOException | JsonSyntaxException e) {
        	logger.info("加载模型配置失败: {}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, Map<String, Object>> loadAllLaunchConfigsUnsafe() {
        File configFile = new File(LAUNCH_CONFIG_FILE);
        if (!configFile.exists()) {
        	logger.info("启动配置文件不存在，返回空配置: {}", LAUNCH_CONFIG_FILE);
            return new HashMap<>();
        }

        try (FileReader reader = new FileReader(configFile)) {
            Type mapType = new TypeToken<Map<String, Map<String, Object>>>() {}.getType();
            Map<String, Map<String, Object>> configs = gson.fromJson(reader, mapType);
            return configs != null ? configs : new HashMap<>();
        } catch (IOException | JsonSyntaxException e) {
        	logger.info("加载启动配置失败: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private String resolveTargetConfigName(String configName, Map<String, Object> normalized) {
        String incoming = configName == null ? "" : configName.trim();
        if (!incoming.isEmpty()) return incoming;
        Object selected = normalized.get("selectedConfig");
        String selectedName = selected == null ? "" : String.valueOf(selected).trim();
        if (!selectedName.isEmpty()) return selectedName;
        return DEFAULT_CONFIG_NAME;
    }

    private boolean upgradeAllLaunchConfigEntriesIfNeeded(Map<String, Map<String, Object>> allConfigs) {
        if (allConfigs == null || allConfigs.isEmpty()) return false;
        boolean changed = false;
        for (Map.Entry<String, Map<String, Object>> modelEntry : allConfigs.entrySet()) {
            Map<String, Object> raw = modelEntry.getValue();
            Map<String, Object> normalized = normalizeLaunchConfigEntry(raw);
            if (raw == null || !normalized.equals(raw)) {
                modelEntry.setValue(normalized);
                changed = true;
            }
        }
        return changed;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getConfigItems(Map<String, Object> normalized) {
        if (normalized == null) return null;
        Object configsObj = normalized.get("configs");
        if (configsObj instanceof Map<?, ?>) {
            return (Map<String, Object>) configsObj;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeLaunchConfigEntry(Map<String, Object> entry) {
        Map<String, Object> normalized = new HashMap<>();
        Map<String, Object> configs = new HashMap<>();
        String selectedConfig = DEFAULT_CONFIG_NAME;

        if (entry != null && entry.get("configs") instanceof Map<?, ?>) {
            Map<?, ?> rawConfigs = (Map<?, ?>) entry.get("configs");
            for (Map.Entry<?, ?> configItem : rawConfigs.entrySet()) {
                if (configItem.getKey() == null) continue;
                String name = String.valueOf(configItem.getKey()).trim();
                if (name.isEmpty()) continue;
                Object configVal = configItem.getValue();
                if (configVal instanceof Map<?, ?>) {
                    configs.put(name, new HashMap<>((Map<String, Object>) configVal));
                } else {
                    configs.put(name, new HashMap<>());
                }
            }
            Object selected = entry.get("selectedConfig");
            if (selected != null) {
                String selectedRaw = String.valueOf(selected).trim();
                if (!selectedRaw.isEmpty() && configs.containsKey(selectedRaw)) {
                    selectedConfig = selectedRaw;
                }
            }
            if (!configs.containsKey(selectedConfig) && !configs.isEmpty()) {
                selectedConfig = configs.keySet().iterator().next();
            }
        } else if (entry != null) {
            configs.put(DEFAULT_CONFIG_NAME, new HashMap<>(entry));
            selectedConfig = DEFAULT_CONFIG_NAME;
        } else {
            configs.put(DEFAULT_CONFIG_NAME, new HashMap<>());
            selectedConfig = DEFAULT_CONFIG_NAME;
        }

        normalized.put("selectedConfig", selectedConfig);
        normalized.put("configs", configs);
        return normalized;
    }

    private void writeJsonFileAtomic(String filePath, Object data) throws IOException {
        Path target = Paths.get(filePath);
        Path parent = target.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try (FileWriter writer = new FileWriter(temp.toFile())) {
            gson.toJson(data, writer);
        }
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
