package org.mark.llamacpp.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.mark.llamacpp.gguf.GGUFBundle;
import org.mark.llamacpp.gguf.GGUFMetaData;
import org.mark.llamacpp.gguf.GGUFModel;
import org.mark.llamacpp.gguf.GGUFMetaDataReader;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.struct.ModelPathConfig;
import org.mark.llamacpp.server.struct.ModelPathDataStruct;
import org.mark.llamacpp.server.tools.CommandLineRunner;
import org.mark.llamacpp.server.tools.ParamTool;
import org.mark.llamacpp.server.tools.PortChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 	
 */
public class LlamaServerManager {
	
	/**
	 * 	
	 */
	private static final Logger logger = LoggerFactory.getLogger(LlamaServerManager.class);

	/**
	 * 	
	 */
	private static final Gson gson = new Gson();
	
	/**
	 * 	这是锁。
	 */
	private static final ConcurrentHashMap<String, Object> CAPABILITIES_FILE_LOCKS = new ConcurrentHashMap<>();
	
	/**
	 * 	
	 */
	private final ConfigManager configManager = ConfigManager.getInstance();
	
	/**
	 * 	单例
	 */
	private static final LlamaServerManager INSTANCE = new LlamaServerManager();

	/**
	 * 	获取单例
	 * @return
	 */
	public static LlamaServerManager getInstance() {
		return INSTANCE;
	}

    /**
     * 存放模型的路径（支持多个根目录）。
     */
    private List<ModelPathDataStruct> modelPaths = new ArrayList<>();
	
	
	/**
	 * 	所有GGUF模型的列表
	 */
	private List<GGUFModel> list = new LinkedList<>();
	
	/**
	 * 已加载的模型进程列表
	 */
	private Map<String, LlamaCppProcess> loadedProcesses = new LinkedHashMap<>();
	
	private final Object processLock = new Object();
	
	private Map<String, LlamaCppProcess> loadingProcesses = new HashMap<>();
	
	private Map<String, Future<?>> loadingTasks = new HashMap<>();
	
	private Set<String> canceledLoadingModels = new HashSet<>();
	
	/**
	 * 端口计数器，用于递增分配端口
	 */
	private AtomicInteger portCounter = new AtomicInteger(8081);
	
	/**
	 * 模型ID到端口映射
	 */
	private Map<String, Integer> modelPorts = new HashMap<>();

	private final Map<String, JsonObject> loadedModelInfos = new ConcurrentHashMap<>();
	
	/**
	 * 	正在加载中的模型。
	 */
	private Set<String> loadingModels = new HashSet<>();
	
	/**
	 * 线程池，用于异步执行模型加载任务
	 */
	private final ExecutorService executorService = Executors.newSingleThreadExecutor();
	
	/**
	 *
	 */
	private LlamaServerManager() {
		// 尝试从配置文件加载设置
		this.loadSettingsFromFile();
	}
	
	/**
	 * 从JSON文件加载设置
	 */
    private void loadSettingsFromFile() {
        try {
			try {
				Path configFile = LlamaServer.getModelPathConfigPath();
				ModelPathConfig cfg = LlamaServer.readModelPathConfig(configFile);
				
				List<ModelPathDataStruct> paths = new ArrayList<>();
				if (cfg != null && cfg.getItems() != null) {
					for (ModelPathDataStruct item : cfg.getItems()) {
						if (item == null || item.getPath() == null) continue;
						String p = item.getPath().trim();
						if (p.isEmpty()) continue;
						// 判断路径是否存在
						paths.add(item);
					}
				}
				if (!paths.isEmpty()) {
					this.modelPaths = paths;
					return;
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		} catch (Exception e) {
			e.printStackTrace();
			System.err.println("从配置文件加载设置失败，使用默认设置: " + e.getMessage());
		}
	}

    /**
     * 	设置模型路径列表
     * @param paths
     */
    public void setModelPaths(List<ModelPathDataStruct> paths) {
        this.modelPaths = new ArrayList<>();
        if (paths != null) {
            for (ModelPathDataStruct p : paths) {
                if (p != null && !p.getPath().trim().isEmpty()) this.modelPaths.add(p);
            }
        }
    }

    /**
     * 	获取当前设定的模型路径列表
     * @return
     */
    public List<ModelPathDataStruct> getModelPaths() {
        return new ArrayList<>(this.modelPaths);
    }
	
	/**
	 * 	获取模型列表。
	 * @return
	 */
	public List<GGUFModel> listModel() {
		return this.listModel(false);
	}
	
	
	/**
	 * 	获取模型列表 
	 * @param reload 是否重新加载
	 * @return
	 */
    public List<GGUFModel> listModel(boolean reload) {
        synchronized (this.list) {
            // 如果列表是空的，就去检索
            if(this.list.size() == 0 || reload) {
                this.list.clear();
                // 新建一个临时集合
                List<ModelPathDataStruct> list = new ArrayList<>(this.modelPaths);
                // 扫描默认目录
                list.add(new ModelPathDataStruct(LlamaServer.getDefaultModelsPath(), "", ""));
                
                for (ModelPathDataStruct root : list) {
                    if (root == null || root.getPath().trim().isEmpty()) continue;
                    Path modelDir = Paths.get(root.getPath().trim());
                    if (!Files.exists(modelDir) || !Files.isDirectory(modelDir)) {
                        continue;
                    }
                    try (Stream<Path> paths = Files.walk(modelDir)) {
                        List<Path> files = paths.filter(Files::isDirectory).sorted().toList();
                        for (Path e : files) {
                            GGUFModel model = this.handleDirectory(e);
                            if (model != null) this.list.add(model);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                List<Map<String, Object>> persisted = this.configManager.loadModelsConfigCached();
                Map<String, String> aliasMap = new HashMap<>();
                Map<String, Boolean> favouriteMap = new HashMap<>();
                for (Map<String, Object> rec : persisted) {
                    if (rec == null) continue;
                    Object id = rec.get("modelId");
                    if (id == null) continue;
                    String modelId = String.valueOf(id);
                    Object alias = rec.get("alias");
                    if (alias != null) {
                        String a = String.valueOf(alias);
                        if (!a.isEmpty()) aliasMap.put(modelId, a);
                    }
                    Object fav = rec.get("favourite");
                    if (fav != null) {
                        boolean v;
                        if (fav instanceof Boolean) {
                            v = (Boolean) fav;
                        } else {
                            v = Boolean.parseBoolean(String.valueOf(fav));
                        }
                        favouriteMap.put(modelId, v);
                    }
                }
                for (GGUFModel m : this.list) {
                    String alias = aliasMap.get(m.getModelId());
                    if (alias != null && !alias.isEmpty()) {
                        m.setAlias(alias);
                    }
                    Boolean fav = favouriteMap.get(m.getModelId());
                    if (fav != null) {
                        m.setFavourite(fav);
                    }
                }
				this.ensureCapabilitiesFilesExistForCurrentList();
            }
            // 如果集合不是空的，就直接返回。
            else {
				this.ensureCapabilitiesFilesExistForCurrentList();
                return this.list;
            }
        }
        return this.list;
    }
    
    /**
     * 	锁定文件。
     * @param modelId
     * @return
     */
	private Object lockForCapabilitiesFile(String modelId) {
		if (modelId == null) {
			return this;
		}
		return CAPABILITIES_FILE_LOCKS.computeIfAbsent(modelId, k -> new Object());
	}
	
	/**
	 * 	获取文件路径
	 * @param modelId
	 * @return
	 * @throws Exception
	 */
	private Path resolveCapabilitiesFilePath(String modelId) throws Exception {
		String currentDir = System.getProperty("user.dir");
		Path configDir = Paths.get(currentDir, "config" + File.separator + "capabilities");
		if (!Files.exists(configDir)) {
			Files.createDirectories(configDir);
		}
		String baseName = modelId == null ? "" : modelId.trim();
		baseName = baseName.replace('\\', '_').replace('/', '_');
		if (baseName.isEmpty()) {
			throw new IllegalArgumentException("modelId不能为空");
		}
		String fileName = baseName.endsWith(".json") ? baseName : (baseName + ".json");
		return configDir.resolve(fileName);
	}

	private static String safeLower(String s) {
		return s == null ? "" : s.trim().toLowerCase();
	}

	private static boolean containsAny(String haystackLower, String... needlesLower) {
		if (haystackLower == null || haystackLower.isEmpty() || needlesLower == null || needlesLower.length == 0) {
			return false;
		}
		for (String n : needlesLower) {
			if (n == null || n.isEmpty()) continue;
			if (haystackLower.contains(n)) return true;
		}
		return false;
	}

	private Map<String, Object> resolveModelType(File primaryFile, GGUFMetaData primaryMeta, GGUFModel model) {
		String fileName = primaryFile == null ? "" : primaryFile.getName();
		String architecture = primaryMeta == null ? "" : primaryMeta.getArchitecture();
		String baseName = primaryMeta == null ? "" : primaryMeta.getBaseName();
		String name = primaryMeta == null ? "" : primaryMeta.getName();
		String modelName = model == null ? "" : model.getName();

		String combined = String.join(" ",
				safeLower(fileName),
				safeLower(architecture),
				safeLower(baseName),
				safeLower(name),
				safeLower(modelName));

		boolean rerank = containsAny(combined,
				"rerank", "re-rank", "reranker", "ranker", "cross-encoder", "crossencoder", "cross_encoder");

		boolean embedding = false;
		if (!rerank) {
			embedding = containsAny(combined,
					"embedding", "embeddings", "text-embedding", "text_embedding", "embed", "e5", "gte", "jina", "nomic", "mxbai", "arctic-embed", "bge");
		}
		String archLower = safeLower(architecture);
		if (!rerank && !embedding) {
			if (containsAny(archLower, "bert", "roberta", "xlm-roberta", "xlm_roberta")) {
				embedding = true;
			}
		}

		String chatTemplate = "";
		try {
			if (primaryFile != null && primaryFile.exists() && primaryFile.isFile()) {
				Map<String, Object> full = GGUFMetaDataReader.read(primaryFile);
				Object tpl = full == null ? null : full.get("tokenizer.chat_template");
				if (tpl != null) chatTemplate = String.valueOf(tpl);
			}
		} catch (Exception ignore) {
		}

		String tplLower = safeLower(chatTemplate);
		boolean tools = containsAny(tplLower, "tool_call", "tool_calls", "tools", "mcp", "function");
		if (!tools && tplLower.contains("tool")) {
			tools = true;
		}
		boolean thinking = containsAny(tplLower, "enable_thinking", "thinking");
		if (rerank || embedding) {
			tools = false;
			thinking = false;
		}

		Map<String, Object> out = new HashMap<>();
		out.put("rerank", rerank);
		out.put("embedding", embedding);
		out.put("tools", tools);
		out.put("thinking", thinking);
		return out;
	}
	
	/**
	 * 	确保模型的能力信息文件存在。
	 * @param model
	 * @param primaryFile
	 * @param primaryMeta
	 */
	private void ensureCapabilitiesFileExists(GGUFModel model, File primaryFile, GGUFMetaData primaryMeta) {
		if (model == null) return;
		String modelId = model.getModelId();
		if (modelId == null || modelId.trim().isEmpty()) return;
		try {
			Path filePath = this.resolveCapabilitiesFilePath(modelId);
			synchronized (this.lockForCapabilitiesFile(modelId)) {
				if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
					return;
				}
				Map<String, Object> caps = this.resolveModelType(primaryFile, primaryMeta, model);
				Map<String, Object> payload = new HashMap<>();
				payload.put("modelId", modelId);
				payload.put("updatedAt", System.currentTimeMillis());
				payload.put("autoGenerated", true);
				if (caps != null) payload.putAll(caps);
				String json = gson.toJson(payload);
				Files.write(filePath, json.getBytes(StandardCharsets.UTF_8));
			}
		} catch (Exception ignore) {
			ignore.printStackTrace();
		}
	}

	private void ensureCapabilitiesFilesExistForCurrentList() {
		for (GGUFModel m : this.list) {
			this.ensureCapabilitiesFileExistsForModel(m);
		}
	}
	
	private void ensureCapabilitiesFileExistsForModel(GGUFModel model) {
		if (model == null) return;
		GGUFMetaData primary = model.getPrimaryModel();
		if (primary == null) return;
		String fp = primary.getFilePath();
		if (fp == null || fp.trim().isEmpty()) return;
		File primaryFile = new File(fp);
		if (!primaryFile.exists() || !primaryFile.isFile()) return;
		this.ensureCapabilitiesFileExists(model, primaryFile, primary);
	}
	
	private void ensureCapabilitiesFileExistsForModelId(String modelId) {
		String id = modelId == null ? "" : modelId.trim();
		if (id.isEmpty()) return;
		GGUFModel model = this.findModelById(id);
		this.ensureCapabilitiesFileExistsForModel(model);
	}
	
	private JsonObject readCapabilitiesFileIfExists(String modelId) {
		String id = modelId == null ? "" : modelId.trim();
		if (id.isEmpty()) {
			return null;
		}
		try {
			Path filePath = this.resolveCapabilitiesFilePath(id);
			synchronized (this.lockForCapabilitiesFile(id)) {
				if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
					try {
						String json = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
						return gson.fromJson(json, JsonObject.class);
					} catch (Exception ignore) {
						return null;
					}
				}
			}
			return null;
		} catch (Exception ignore) {
			return null;
		}
	}
	
	private static JsonObject buildDefaultCapabilities(String modelId) {
		String id = modelId == null ? "" : modelId.trim();
		if (id.isEmpty()) {
			return null;
		}
		JsonObject fallback = new JsonObject();
		fallback.addProperty("modelId", id);
		fallback.addProperty("tools", false);
		fallback.addProperty("thinking", false);
		fallback.addProperty("rerank", false);
		fallback.addProperty("embedding", false);
		return fallback;
	}
	
	public JsonObject getModelCapabilities(String modelId) {
		String id = modelId == null ? "" : modelId.trim();
		if (id.isEmpty()) {
			return null;
		}
		
		JsonObject out = this.readCapabilitiesFileIfExists(id);
		if (out != null) {
			return out;
		}
		
		this.ensureCapabilitiesFileExistsForModelId(id);
		
		out = this.readCapabilitiesFileIfExists(id);
		if (out != null) {
			return out;
		}
		
		return buildDefaultCapabilities(id);
	}

	public JsonObject getModelCapabilitiesSummary(String modelId) throws Exception {
		String id = modelId == null ? "" : modelId.trim();
		if (id.isEmpty()) {
			throw new IllegalArgumentException("缺少必需的modelId参数");
		}
		JsonObject caps = this.getModelCapabilities(id);
		if (caps == null) {
			throw new IllegalStateException("获取模型能力配置失败");
		}
		Path filePath = this.resolveCapabilitiesFilePath(id);
		JsonObject out = new JsonObject();
		out.addProperty("modelId", id);
		out.addProperty("tools", ParamTool.parseJsonBoolean(caps, "tools", false));
		out.addProperty("thinking", ParamTool.parseJsonBoolean(caps, "thinking", false));
		out.addProperty("rerank", ParamTool.parseJsonBoolean(caps, "rerank", false));
		out.addProperty("embedding", ParamTool.parseJsonBoolean(caps, "embedding", false));
		out.addProperty("file", filePath.toString());
		return out;
	}

	public JsonObject setModelCapabilities(String modelId, JsonObject capabilities) throws Exception {
		String id = modelId == null ? "" : modelId.trim();
		if (id.isEmpty()) {
			throw new IllegalArgumentException("缺少必需的modelId参数");
		}
		JsonObject capsObj = capabilities == null ? new JsonObject() : capabilities;
		boolean tools = ParamTool.parseJsonBoolean(capsObj, "tools", false);
		boolean thinking = ParamTool.parseJsonBoolean(capsObj, "thinking", false);
		boolean rerank = ParamTool.parseJsonBoolean(capsObj, "rerank", false);
		boolean embedding = ParamTool.parseJsonBoolean(capsObj, "embedding", false);

		if (embedding && rerank) {
			rerank = false;
		}
		boolean nonChat = rerank || embedding;
		if (nonChat) {
			tools = false;
			thinking = false;
		} else if (tools || thinking) {
			rerank = false;
			embedding = false;
		}

		JsonObject saved = new JsonObject();
		saved.addProperty("modelId", id);
		saved.addProperty("tools", tools);
		saved.addProperty("thinking", thinking);
		saved.addProperty("rerank", rerank);
		saved.addProperty("embedding", embedding);
		saved.addProperty("updatedAt", System.currentTimeMillis());

		Path filePath = this.resolveCapabilitiesFilePath(id);
		synchronized (this.lockForCapabilitiesFile(id)) {
			Files.write(filePath, saved.toString().getBytes(StandardCharsets.UTF_8));
		}

		JsonObject out = new JsonObject();
		out.addProperty("modelId", id);
		out.addProperty("saved", true);
		JsonObject outCaps = new JsonObject();
		outCaps.addProperty("tools", tools);
		outCaps.addProperty("thinking", thinking);
		outCaps.addProperty("rerank", rerank);
		outCaps.addProperty("embedding", embedding);
		out.add("capabilities", outCaps);
		out.addProperty("file", filePath.toString());
		return out;
	}

    /**
     * 	处理这个路径的文件夹，找到可用的GGUF文件。
     * 	使用GGUFBundle来处理文件分组和识别
     * @param path
     * @return
     */
	private synchronized GGUFModel handleDirectory(Path path) {
		File dir = path.toFile();
		if (dir.getName().startsWith("."))
			return null;

		if (dir == null || !dir.isDirectory()) {
			System.err.println("Invalid directory: " + path);
			return null;
		}
		
		File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".gguf"));
		if (files == null || files.length == 0) {
			// System.err.println("No GGUF files found in directory: " + path);
			return null;
		}

		// 寻找最佳的种子文件来初始化GGUFBundle
		File seedFile = null;
		
		// 1. 尝试找到分卷的第一卷 (匹配 *-00001-of-*.gguf)
		for(File f : files) {
			String name = f.getName().toLowerCase();
			if(name.matches(".*-00001-of-\\d{5}\\.gguf$")) {
				seedFile = f;
				break;
			}
		}
		
		// 2. 如果没找到明确的第一卷，找一个不含mmproj的文件
		if(seedFile == null) {
			for(File f : files) {
				String name = f.getName().toLowerCase();
				if(!name.contains("mmproj")) {
					seedFile = f;
					break;
				}
			}
		}
		
		// 3. 实在不行，就用第一个文件
		if(seedFile == null && files.length > 0) {
			seedFile = files[0];
		}
		
		if(seedFile == null) return null;
		
		try {
			GGUFBundle bundle = new GGUFBundle(seedFile);
			
			GGUFModel model = new GGUFModel(dir.getName(), dir.getAbsolutePath());
			model.setAlias(dir.getName());
			
			// 处理主模型文件
			File primaryFile = bundle.getPrimaryFile();
			GGUFMetaData primaryMeta = null;
			if(primaryFile != null && primaryFile.exists()) {
				GGUFMetaData md = GGUFMetaData.readFile(primaryFile);
				if (md != null) {
					primaryMeta = md;
					model.setPrimaryModel(md);
					// 将主模型元数据也添加到列表中，保持兼容性
					model.addMetaData(md);
				}
			}
			
			// 处理mmproj文件
			File mmprojFile = bundle.getMmprojFile();
			if(mmprojFile != null && mmprojFile.exists()) {
				GGUFMetaData md = GGUFMetaData.readFile(mmprojFile);
				if (md != null) {
					model.setMmproj(md);
					model.addMetaData(md);
				}
			}
			
			// 优化：不再读取所有分卷文件的元数据
			// 分卷文件的元数据通常与主文件相同，或者只包含张量信息
			// 逐个读取会导致严重的IO性能问题
			/*
			List<File> splitFiles = bundle.getSplitFiles();
			if(splitFiles != null) {
				for(File f : splitFiles) {
					if(f.exists()) {
						GGUFMetaData md = GGUFMetaData.readFile(f);
						model.addMetaData(md);
					}
				}
			}
			*/
			
			model.setSize(bundle.getTotalFileSize());
			
			// 如果没有PrimaryModel，尝试从metaDataList中找一个
			if(model.getPrimaryModel() == null && !model.getMetaDataList().isEmpty()) {
				for(GGUFMetaData md : model.getMetaDataList()) {
					if("model".equals(md.getStringValue("general.type"))) {
						model.setPrimaryModel(md);
						break;
					}
				}
			}
			
			if (primaryFile != null && primaryFile.exists() && primaryFile.isFile()) {
				this.ensureCapabilitiesFileExists(model, primaryFile, primaryMeta != null ? primaryMeta : model.getPrimaryModel());
			}
			
			return model;
			
		} catch (Exception e) {
			System.err.println("处理目录失败 " + path + ": " + e.getMessage());
			return null;
		}
	}

	/**
	 *
	 * @param modelId
	 * @return
	 */
	public GGUFModel findModelById(String modelId) {
		for(GGUFModel e : this.list) {
			if(e.getModelId().equals(modelId))
				return e;
		}
		return null;
	}
	
	/**
	 * 获取下一个可用端口
	 * 使用PortChecker工具类检查端口是否真正可用
	 * @return 下一个可用端口号
	 */
	private synchronized int getNextAvailablePort() {
		int candidatePort = this.portCounter.get();
		try {
			// 使用PortChecker查找下一个可用端口
			int availablePort = PortChecker.findNextAvailablePort(candidatePort);
			
			// 更新端口计数器，确保下次从更高的端口开始
			this.portCounter.set(availablePort + 1);
			
			return availablePort;
		} catch (IllegalStateException e) {
			// 如果在有效范围内找不到可用端口，回退到原来的简单递增方式
			// 并打印警告信息
			System.err.println("警告: 无法找到可用端口，回退到简单递增方式。错误信息: " + e.getMessage());
			return this.portCounter.getAndIncrement();
		}
	}
	
	/**
	 * 获取已加载的模型进程列表
	 * @return 已加载的模型进程列表
	 */
	public Map<String, LlamaCppProcess> getLoadedProcesses() {
		synchronized (this.processLock) {
			return new HashMap<>(this.loadedProcesses);
		}
	}
	
	/**
	 * 	获取第一个已经加载的模型的名字。
	 * @return
	 */
	public String getFirstModelName() {
		synchronized (this.processLock) {
			if (this.loadedProcesses.isEmpty()) {
				return null;
			}
			Map.Entry<String, LlamaCppProcess> firstEntry = this.loadedProcesses.entrySet().iterator().next();
			return firstEntry.getKey();
		}
	}
	
	/**
	 * 	获取指定模型的启动参数。
	 * @param modelId
	 * @return
	 */
	public String getModelStartCmd(String modelId) {
		LlamaCppProcess process;
		synchronized (this.processLock) {
			process = this.loadedProcesses.get(modelId);
		}
		if (process == null) return "";
		return process.getCmd();
	}
	
	/**
	 * 获取模型对应的端口
	 * @param modelId 模型ID
	 * @return 端口号，如果模型未加载则返回null
	 */
	public Integer getModelPort(String modelId) {
		synchronized (this.processLock) {
			return this.modelPorts.get(modelId);
		}
	}
	
	/**
	 * 停止并移除已加载的模型
	 * @param modelId 模型ID
	 * @return 是否成功停止
	 */
	public boolean stopModel(String modelId) {
		String id = modelId == null ? "" : modelId.trim();
		LlamaCppProcess process;
		Future<?> task;
		synchronized (this.processLock) {
			process = this.loadedProcesses.get(id);
			task = null;
		}
		if (process != null) {
			boolean stopped = process.stop();
			if (stopped) {
				synchronized (this.processLock) {
					this.loadedProcesses.remove(id);
					this.modelPorts.remove(id);
				}
				this.loadedModelInfos.remove(id);
			}
			return stopped;
		}
		
		boolean loading = this.isLoading(id);
		synchronized (this.processLock) {
			process = this.loadingProcesses.get(id);
			task = this.loadingTasks.get(id);
			if (process != null || task != null || loading) {
				this.canceledLoadingModels.add(id);
			}
		}
		
		boolean stopped = false;
		if (process != null) {
			stopped = process.stop();
		} else if (task != null) {
			stopped = true;
		} else if (loading) {
			stopped = true;
		}
		if (task != null) {
			task.cancel(true);
		}
		if (stopped) {
			synchronized (this.processLock) {
				this.loadingProcesses.remove(id);
				this.loadingTasks.remove(id);
				this.modelPorts.remove(id);
			}
			synchronized (this.loadingModels) {
				this.loadingModels.remove(id);
			}
			this.loadedModelInfos.remove(id);
		}
		return stopped;
	}
	
	/**
	 * 	检查指定ID的模型是否处于加载状态。
	 * @param modelId
	 * @return
	 */
	public boolean isLoading(String modelId) {
		synchronized (this.loadingModels) {
			return this.loadingModels.contains(modelId);
		}
	}
	
	/**
	 * 	通过CMD命令启动llama-server进程
	 * @param modelId
	 * @param llamaBinPath
	 * @param device
	 * @param mg
	 * @param enbaleVision
	 * @param cmd
	 * @param extraParams
	 * @param chatTemplateFilePath
	 * @return
	 */
	public boolean loadModelAsyncFromCmd(String modelId, String llamaBinPath, List<String> device, Integer mg, boolean enbaleVision, String cmd, String extraParams, String chatTemplateFilePath) {
		Map<String, Object> launchConfig = new HashMap<>();
		launchConfig.put("llamaBinPath", llamaBinPath);
		launchConfig.put("device", device);
		launchConfig.put("mg", mg);
		launchConfig.put("cmd", cmd);
		launchConfig.put("extraParams", extraParams);
		launchConfig.put("enableVision", enbaleVision);
		
		if (chatTemplateFilePath != null && !chatTemplateFilePath.trim().isEmpty()) {
			launchConfig.put("chatTemplateFile", chatTemplateFilePath);
		}
		this.configManager.saveLaunchConfig(modelId, launchConfig);

		synchronized (this.processLock) {
			if (this.loadedProcesses.containsKey(modelId)) {
				LlamaServer.sendModelLoadEvent(modelId, false, "模型已经加载");
				return false;
			}
		}

		GGUFModel targetModel = this.findModelById(modelId);
		if (targetModel == null) {
			LlamaServer.sendModelLoadEvent(modelId, false, "未找到ID为 " + modelId + " 的模型");
			return false;
		}

		if (llamaBinPath == null || llamaBinPath.trim().isEmpty()) {
			LlamaServer.sendModelLoadEvent(modelId, false, "未提供llamaBinPath");
			return false;
		}

		synchronized (this.loadingModels) {
			if (this.loadingModels.contains(targetModel.getModelId())) {
				LlamaServer.sendModelLoadEvent(modelId, false, "该模型正在加载中");
				return false;
			}
			this.loadingModels.add(targetModel.getModelId());
		}

		final String cmdSafe = cmd == null ? "" : cmd.trim();
		final String extraSafe = extraParams == null ? "" : extraParams.trim();
		final String binSafe = llamaBinPath.trim();
		final List<String> devSafe = device;
		final Integer mgSafe = mg;
		final String chatTemplateFileSafe = chatTemplateFilePath == null ? "" : chatTemplateFilePath;

		try {
			Future<?> future = this.executorService.submit(() -> {
				this.loadModelInBackgroundFromCmd(modelId, targetModel, binSafe, devSafe, mgSafe, enbaleVision, cmdSafe, extraSafe, chatTemplateFileSafe);
			});
			synchronized (this.processLock) {
				this.loadingTasks.put(modelId, future);
			}
			if (this.isLoadCanceled(modelId)) {
				future.cancel(true);
			}
			return true;
		} catch (Exception e) {
			synchronized (this.loadingModels) {
				this.loadingModels.remove(targetModel.getModelId());
			}
			LlamaServer.sendModelLoadEvent(modelId, false, "提交加载任务失败: " + e.getMessage());
			return false;
		}
	}
	
//	/**
//	 * 在后台线程中执行模型加载
//	 */
//    private synchronized void loadModelInBackground(String modelId, GGUFModel targetModel, ModelLaunchOptions options) {
//
//        // 获取下一个可用端口
//        int port = this.getNextAvailablePort();
//
//        List<String> command = options.toCmdLine(targetModel, port);
//
//        // 构建完整的命令字符串
//        String commandStr = String.join(" ", command);
//		
//		// 创建并启动LlamaCppProcess
//		String processName = "llama-server-" + modelId;
//		LlamaCppProcess process = new LlamaCppProcess(processName, commandStr);
//		
//		System.out.println("启动命令：" + commandStr);
//		
//		// 使用CountDownLatch来同步等待加载结果
//		CountDownLatch latch = new CountDownLatch(1);
//		AtomicBoolean loadSuccess = new AtomicBoolean(false);
//		
//		// 设置输出处理器，接受llamacpp运行状态，然后判断特定的内容。
//        process.setOutputHandler(line -> {
//            //	判断是否加载成功。
//            //	1.这是成功了
//            if(line.contains("srv  update_slots: all slots are idle")) {
//                loadSuccess.set(true);
//                latch.countDown();
//            }
//            //	2.这是失败了
//            if(line.contains("main: exiting due to model loading error")) {
//                loadSuccess.set(false);
//                latch.countDown();
//            }
//            //	3.检测进程异常终止
//            if(line.contains("Inferior") && line.contains("detached")) {
//                // 检测到进程异常终止，如 [Inferior 1 (process 6869) detached]
//                System.err.println("检测到模型进程异常终止: " + line);
//                
//                // 设置加载失败状态
//                loadSuccess.set(false);
//                
//                // 从已加载进程列表中移除
//                this.loadedProcesses.remove(modelId);
//                this.modelPorts.remove(modelId);
//                
//                // 通过WebSocket广播模型停止事件
//                LlamaServer.sendModelStopEvent(modelId, false, "模型进程异常终止: " + line);
//                
//                // 唤醒等待的线程
//                latch.countDown();
//            }
//            //	4.检测到参数错误
//            if(line.startsWith("error")) {
//            	 System.err.println("检测到模型进程异常终止: " + line);
//                 // 设置加载失败状态
//                 loadSuccess.set(false);
//                 
//                 // 从已加载进程列表中移除
//                 this.loadedProcesses.remove(modelId);
//                 this.modelPorts.remove(modelId);
//                 
//                 // 通过WebSocket广播模型停止事件
//                 //LlamaServer.sendModelStopEvent(modelId, false, "模型启动失败: " + line);
//                 
//                 // 唤醒等待的线程
//                 latch.countDown();
//            }
//        });
//		
//		// 启动进程
//		boolean started = process.start();
//		if (!started) {
//			System.err.println("启动模型 " + modelId + " 失败");
//			// 发送WebSocket事件通知启动失败
//			LlamaServer.sendModelLoadEvent(modelId, false, "启动模型进程失败");
//			return;
//		}else {
//			// 设置模型的状态为启动中
//			synchronized (this.loadingModels) {
//				this.loadingModels.add(targetModel.getModelId());
//			}
//		}
//		
//		// 等待进程加载完成，超时时间10分钟
//		try {
//			boolean timeout = !latch.await(10, TimeUnit.MINUTES);
//			
//			if (timeout) {
//				System.err.println("加载模型 " + modelId + " 超时");
//				// 停止进程
//				process.stop();
//				// 发送WebSocket事件通知加载超时
//				LlamaServer.sendModelLoadEvent(modelId, false, "模型加载超时");
//				return;
//			}
//			
//			if (loadSuccess.get()) {
//				// 保存进程信息
//				this.loadedProcesses.put(modelId, process);
//				this.modelPorts.put(modelId, port);
//				System.out.println("成功启动模型 " + modelId + "，端口: " + port + "，PID: " + process.getPid());
//				// 发送WebSocket事件通知加载成功
//				LlamaServer.sendModelLoadEvent(modelId, true, "模型加载成功，端口: " + port);
//			} else {
//				System.err.println("加载模型 " + modelId + " 失败");
//				// 停止进程
//				process.stop();
//				// 发送WebSocket事件通知加载失败
//				LlamaServer.sendModelLoadEvent(modelId, false, "模型加载失败");
//			}
//		} catch (InterruptedException e) {
//			System.err.println("等待模型加载时被中断: " + e.getMessage());
//			Thread.currentThread().interrupt();
//			// 停止进程
//			process.stop();
//			// 发送WebSocket事件通知加载被中断
//			LlamaServer.sendModelLoadEvent(modelId, false, "模型加载被中断");
//		}finally {
//			synchronized (this.loadingModels) {
//				this.loadingModels.remove(targetModel.getModelId());
//			}
//		}
//	}

	
	/**
	 * 	后台启动llama-server进程。
	 * @param modelId
	 * @param targetModel
	 * @param llamaBinPath
	 * @param device
	 * @param mg
	 * @param enableVision
	 * @param cmd
	 * @param extraParams
	 * @param chatTemplateFilePath
	 */
	private void loadModelInBackgroundFromCmd(String modelId, GGUFModel targetModel, String llamaBinPath, List<String> device,
			Integer mg, boolean enableVision, String cmd, String extraParams, String chatTemplateFilePath) {
		try {
			if (this.isLoadCanceled(modelId)) {
				return;
			}
			int port = this.getNextAvailablePort();
			String commandStr = buildCommandStr(targetModel, port, llamaBinPath, device, mg, enableVision, cmd, extraParams, chatTemplateFilePath);
			String processName = "llama-server-" + modelId;
			LlamaCppProcess process = new LlamaCppProcess(processName, commandStr);

			System.out.println("启动命令：" + commandStr);

			CountDownLatch latch = new CountDownLatch(1);
			AtomicBoolean loadSuccess = new AtomicBoolean(false);

			process.setOutputHandler(line -> {
				if (line.contains("srv  update_slots: all slots are idle")) {
					loadSuccess.set(true);
					latch.countDown();
				}
				if (line.contains("main: exiting due to model loading error")) {
					loadSuccess.set(false);
					latch.countDown();
				}
				if (line.contains("Inferior") && line.contains("detached")) {
					System.err.println("检测到模型进程异常终止: " + line);
					loadSuccess.set(false);
					synchronized (this.processLock) {
						this.loadedProcesses.remove(modelId);
						this.modelPorts.remove(modelId);
					}
					LlamaServer.sendModelStopEvent(modelId, false, "模型进程异常终止: " + line);
					latch.countDown();
				}
				if (line.startsWith("error")) {
					System.err.println("检测到模型进程异常终止: " + line);
					loadSuccess.set(false);
					synchronized (this.processLock) {
						this.loadedProcesses.remove(modelId);
						this.modelPorts.remove(modelId);
					}
					latch.countDown();
				}
			});

			boolean started = process.start();
			if (!started) {
				if (this.isLoadCanceled(modelId)) {
					return;
				}
				LlamaServer.sendModelLoadEvent(modelId, false, "启动模型进程失败");
				return;
			}
			
			synchronized (this.processLock) {
				this.loadingProcesses.put(modelId, process);
			}
			
			if (this.isLoadCanceled(modelId)) {
				process.stop();
				return;
			}
			LlamaServer.sendModelLoadStartEvent(modelId, port, "模型启动中");

			try {
				boolean timeout = !latch.await(10, TimeUnit.MINUTES);
				if (timeout) {
					process.stop();
					if (this.isLoadCanceled(modelId)) {
						return;
					}
					LlamaServer.sendModelLoadEvent(modelId, false, "模型加载超时");
					return;
				}
				
				if (this.isLoadCanceled(modelId)) {
					process.stop();
					return;
				}

				if (loadSuccess.get()) {
					synchronized (this.processLock) {
						this.loadedProcesses.put(modelId, process);
						this.modelPorts.put(modelId, port);
					}
					LlamaServer.sendModelLoadEvent(modelId, true, "模型加载成功", port);
					// 这里请求一次
					try {
						JsonObject slotsResponse = this.handleModelSlotsGet(modelId);
						int ctxSize = 0;
						if (slotsResponse != null && slotsResponse.has("slots") && slotsResponse.get("slots").isJsonArray()) {
							JsonArray slots = slotsResponse.getAsJsonArray("slots");
							if (slots.size() > 0 && slots.get(0).isJsonObject()) {
								JsonObject slot0 = slots.get(0).getAsJsonObject();
								if (slot0.has("n_ctx") && !slot0.get("n_ctx").isJsonNull()) {
									ctxSize = (int) Math.round(slot0.get("n_ctx").getAsDouble());
								}
							}
						}
						process.setCtxSize(ctxSize);
					}catch (Exception e) {
						e.printStackTrace();
						process.setCtxSize(0);
					}
					// 这里再请求一次
					try {
						JsonObject slotsResponse = this.handleModelSlotsGet(modelId);
						int ctxSize = 0;
						if (slotsResponse != null && slotsResponse.has("slots") && slotsResponse.get("slots").isJsonArray()) {
							JsonArray slots = slotsResponse.getAsJsonArray("slots");
							if (slots.size() > 0 && slots.get(0).isJsonObject()) {
								JsonObject slot0 = slots.get(0).getAsJsonObject();
								if (slot0.has("n_ctx") && !slot0.get("n_ctx").isJsonNull()) {
									ctxSize = (int) Math.round(slot0.get("n_ctx").getAsDouble());
								}
							}
						}
						process.setCtxSize(ctxSize);
					}catch (Exception e) {
						e.printStackTrace();
						process.setCtxSize(0);
					}
					try {
						this.handleModelInfo(modelId);
					} catch (Exception e) {
						logger.info("获取/v1/models信息失败: " + modelId, e);
					}
				} else {
					process.stop();
					if (this.isLoadCanceled(modelId)) {
						return;
					}
					LlamaServer.sendModelLoadEvent(modelId, false, "模型加载失败");
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				process.stop();
				if (this.isLoadCanceled(modelId)) {
					return;
				}
				LlamaServer.sendModelLoadEvent(modelId, false, "模型加载被中断");
			}
		} finally {
			synchronized (this.processLock) {
				this.loadingProcesses.remove(modelId);
				this.loadingTasks.remove(modelId);
				this.canceledLoadingModels.remove(modelId);
			}
			synchronized (this.loadingModels) {
				this.loadingModels.remove(targetModel.getModelId());
			}
		}
	}
	
	private boolean isLoadCanceled(String modelId) {
		synchronized (this.processLock) {
			return this.canceledLoadingModels.contains(modelId);
		}
	}
	
	/**
	 * 	
	 * @param targetModel
	 * @param port
	 * @param llamaBinPath
	 * @param device
	 * @param mg
	 * @param cmd
	 * @param extraParams
	 * @param chatTemplateFilePath
	 * @return
	 */
	private String buildCommandStr(GGUFModel targetModel, int port, String llamaBinPath, List<String> device, Integer mg, boolean enableVision, String cmd, String extraParams, String chatTemplateFilePath) {
		StringBuilder sb = new StringBuilder();
		String allArgs = "";
		if (cmd != null && !cmd.trim().isEmpty()) allArgs = cmd.trim();
		if (extraParams != null && !extraParams.trim().isEmpty()) {
			String e = extraParams.trim();
			allArgs = allArgs.isEmpty() ? e : (allArgs + " " + e);
		}

		String exe = llamaBinPath + File.separator + "llama-server";
		sb.append(ParamTool.quoteIfNeeded(exe));

		sb.append(" -m ");
		String modelFile = targetModel.getPath() + "/" + targetModel.getPrimaryModel().getFileName();
		sb.append(ParamTool.quoteIfNeeded(modelFile));

		sb.append(" --port ");
		sb.append(port);
		
		//	确认启用视觉
		if(enableVision) {
			if (targetModel.getMmproj() != null && !cmdHasFlag(allArgs, "--mmproj") && !cmdHasFlag(allArgs, "--no-mmproj")) {
				sb.append(" --mmproj ");
				String mmprojFile = targetModel.getPath() + "/" + targetModel.getMmproj().getFileName();
				sb.append(ParamTool.quoteIfNeeded(mmprojFile));
			}	
		}

		if (device != null && !device.isEmpty()) {
			if (device.size() == 1) {
				if (!"All".equals(device.get(0))) {
					sb.append(" -sm none -dev ");
					sb.append(ParamTool.quoteIfNeeded(device.get(0)));
					sb.append(" -mg ");
					sb.append(mg != null ? String.valueOf(mg) : "0");
				}
			} else {
				sb.append(" -dev ");
				sb.append(ParamTool.quoteIfNeeded(String.join(",", device)));
			}
		}

		if (cmd != null && !cmd.trim().isEmpty()) {
			sb.append(' ');
			sb.append(cmd.trim());
		}
		if (extraParams != null && !extraParams.trim().isEmpty()) {
			sb.append(' ');
			sb.append(extraParams.trim());
		}
		if (chatTemplateFilePath != null && !chatTemplateFilePath.trim().isEmpty() && !cmdHasFlag(allArgs, "--chat-template-file") && !cmdHasFlag(allArgs, "--chat-template")) {
			sb.append(" --chat-template-file ");
			sb.append(ParamTool.quoteIfNeeded(chatTemplateFilePath.trim()));
		}

		if (!cmdHasFlag(allArgs, "--no-webui") && !cmdHasFlag(allArgs, "--webui")) {
			sb.append(" --no-webui");
		}
		if (!cmdHasFlag(allArgs, "--metrics")) {
			sb.append(" --metrics");
		}
		if (!cmdHasFlag(allArgs, "--slot-save-path")) {
			sb.append(" --slot-save-path ");
			sb.append(ParamTool.quoteIfNeeded(LlamaServer.getCachePath().toFile().getAbsolutePath()));
		}
		if (!cmdHasFlag(allArgs, "--cache-ram")) {
			sb.append(" --cache-ram -1");
		}
		sb.append(" --alias ").append(targetModel.getModelId());
		
		sb.append(" --timeout 36000");
		
		return sb.toString().trim();
	}

	/**
	 * 	判断是否包含某个字段。
	 * @param cmd
	 * @param flag
	 * @return
	 */
	private boolean cmdHasFlag(String cmd, String flag) {
		if (cmd == null || flag == null || flag.trim().isEmpty()) {
			return false;
		}
		String f = flag.trim();
		String s = " " + cmd.trim() + " ";
		return s.contains(" " + f + " ") || s.contains(" " + f + "=");
	}
	
	//##########################################################################################

	private static final class HttpResult {
		private final int statusCode;
		private final String body;

		private HttpResult(int statusCode, String body) {
			this.statusCode = statusCode;
			this.body = body == null ? "" : body;
		}
	}

	private int requireLoadedModelPort(String modelId) {
		String id = modelId == null ? "" : modelId.trim();
		if (id.isEmpty()) {
			throw new IllegalArgumentException("缺少必需的modelId参数");
		}
		if (!this.getLoadedProcesses().containsKey(id)) {
			throw new IllegalArgumentException("模型未加载: " + id);
		}
		Integer port = this.getModelPort(id);
		if (port == null) {
			throw new IllegalStateException("未找到模型端口: " + id);
		}
		return port.intValue();
	}

	private static String readAll(BufferedReader br) throws IOException {
		if (br == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		String line;
		while ((line = br.readLine()) != null) {
			sb.append(line);
		}
		return sb.toString();
	}

	private HttpResult callLocalModelEndpoint(int port, String method, String endpoint, JsonObject body, int connectTimeoutMs, int readTimeoutMs) throws Exception {
		String urlStr = String.format("http://localhost:%d%s", port, endpoint);
		URL url = URI.create(urlStr).toURL();
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		try {
			connection.setRequestMethod(method);
			connection.setConnectTimeout(connectTimeoutMs);
			connection.setReadTimeout(readTimeoutMs);
			if (body != null) {
				connection.setDoOutput(true);
				connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
				byte[] input = body.toString().getBytes(StandardCharsets.UTF_8);
				try (OutputStream os = connection.getOutputStream()) {
					os.write(input, 0, input.length);
				}
			}
			int code = connection.getResponseCode();
			boolean ok = code >= 200 && code < 300;
			try (BufferedReader br = new BufferedReader(new InputStreamReader(ok ? connection.getInputStream() : connection.getErrorStream(), StandardCharsets.UTF_8))) {
				return new HttpResult(code, readAll(br));
			} catch (Exception e) {
				return new HttpResult(code, "");
			}
		} finally {
			try {
				connection.disconnect();
			} catch (Exception ignore) {
			}
		}
	}

	private Object tryParseJson(String body) {
		if (body == null || body.isBlank()) {
			return null;
		}
		try {
			return gson.fromJson(body, Object.class);
		} catch (Exception e) {
			return null;
		}
	}

	private JsonObject tryParseJsonObject(String body) {
		if (body == null || body.isBlank()) {
			return null;
		}
		try {
			JsonElement el = JsonParser.parseString(body);
			if (el == null || el.isJsonNull()) {
				return null;
			}
			if (el.isJsonObject()) {
				return el.getAsJsonObject();
			}
			if (el.isJsonArray()) {
				JsonObject wrapped = new JsonObject();
				wrapped.add("slots", el.getAsJsonArray());
				return wrapped;
			}
			return null;
		} catch (Exception e) {
			return null;
		}
	}
	
	
	public JsonObject handleModelInfo(String modelId) {
		try {
			String id = modelId == null ? "" : modelId.trim();
			int port = this.requireLoadedModelPort(id);
			HttpResult r = this.callLocalModelEndpoint(port, "GET", "/v1/models", null, 30000, 30000);
			if (r.statusCode < 200 || r.statusCode >= 300) {
				throw new RuntimeException("获取模型信息失败: " + r.body);
			}
			JsonObject root = this.tryParseJsonObject(r.body);
			if (root == null) {
				throw new RuntimeException("获取模型信息失败: 返回不是JSON对象");
			}

			JsonArray models = root.has("models") && root.get("models").isJsonArray() ? root.getAsJsonArray("models") : new JsonArray();
			JsonArray data = root.has("data") && root.get("data").isJsonArray() ? root.getAsJsonArray("data") : new JsonArray();

			Map<String, JsonObject> dataById = new LinkedHashMap<>();
			for (JsonElement el : data) {
				if (el == null || el.isJsonNull() || !el.isJsonObject()) {
					continue;
				}
				JsonObject obj = el.getAsJsonObject();
				String key = jsonString(obj, "id");
				if (!key.isEmpty()) {
					dataById.put(key, obj);
				}
			}

			Set<String> used = new HashSet<>();
			JsonArray items = new JsonArray();
			for (JsonElement el : models) {
				if (el == null || el.isJsonNull() || !el.isJsonObject()) {
					continue;
				}
				JsonObject modelObj = el.getAsJsonObject();
				String key = jsonString(modelObj, "model");
				if (key.isEmpty()) {
					key = jsonString(modelObj, "name");
				}

				JsonObject item = new JsonObject();
				if (!key.isEmpty()) {
					item.addProperty("id", key);
				}
				item.add("model", modelObj.deepCopy());

				JsonObject dataObj = !key.isEmpty() ? dataById.get(key) : null;
				if (dataObj != null) {
					item.add("data", dataObj.deepCopy());
					used.add(key);
				}
				items.add(item);
			}

			for (Map.Entry<String, JsonObject> e : dataById.entrySet()) {
				String key = e.getKey();
				if (key == null || key.isBlank() || used.contains(key)) {
					continue;
				}
				JsonObject item = new JsonObject();
				item.addProperty("id", key);
				item.add("data", e.getValue().deepCopy());
				items.add(item);
			}

			JsonObject out = new JsonObject();
			out.addProperty("modelId", id);
			out.addProperty("port", port);
			out.addProperty("fetchedAt", System.currentTimeMillis());
			out.add("items", items);

			this.loadedModelInfos.put(id, out);
			return out;
		} catch (Exception e) {
			logger.info("获取模型信息时发生错误", e);
			throw new RuntimeException("获取模型信息失败: " + e.getMessage(), e);
		}
	}

	public JsonObject getLoadedModelInfo(String modelId) {
		String id = modelId == null ? "" : modelId.trim();
		if (id.isEmpty()) {
			return null;
		}
		JsonObject found = this.loadedModelInfos.get(id);
		return found == null ? null : found.deepCopy();
	}

	private static String jsonString(JsonObject obj, String key) {
		if (obj == null || key == null || key.isBlank()) {
			return "";
		}
		if (!obj.has(key) || obj.get(key).isJsonNull()) {
			return "";
		}
		try {
			return obj.get(key).getAsString().trim();
		} catch (Exception ignore) {
			return "";
		}
	}
	
	/**
	 * 	获取Slots信息
	 * @param modelId
	 * @return
	 */
	public JsonObject handleModelSlotsGet(String modelId) {
		try {
			int port = this.requireLoadedModelPort(modelId);
			HttpResult r = this.callLocalModelEndpoint(port, "GET", "/slots", null, 30000, 30000);
			if (r.statusCode >= 200 && r.statusCode < 300) {
				JsonObject parsed = this.tryParseJsonObject(r.body);
				return parsed != null ? parsed : new JsonObject();
			}
			throw new RuntimeException("获取slots失败: " + r.body);
		} catch (Exception e) {
			logger.info("获取slots时发生错误", e);
			throw new RuntimeException("获取slots失败: " + e.getMessage(), e);
		}
	}
	
	/**
	 * 	
	 * @param modelId
	 * @param slot
	 * @param fileName
	 * @return
	 */
	public ApiResponse handleModelSlotsSave(String modelId, int slot, String fileName) {
		try {
			int port = this.requireLoadedModelPort(modelId);
			String endpoint = String.format("/slots/%d?action=save", slot);
			JsonObject body = new JsonObject();
			body.addProperty("filename", fileName);
			HttpResult r = this.callLocalModelEndpoint(port, "POST", endpoint, body, 36000 * 1000, 36000 * 1000);
			if (r.statusCode >= 200 && r.statusCode < 300) {
				Object parsed = this.tryParseJson(r.body);
				Map<String, Object> data = new HashMap<>();
				data.put("modelId", modelId);
				data.put("result", parsed);
				return ApiResponse.success(data);
			}
			return ApiResponse.error("保存slot失败: " + r.body);
		} catch (Exception e) {
			logger.info("保存slot缓存时发生错误", e);
			return ApiResponse.error("保存slot失败: " + e.getMessage());
		}
	}
	
	/**
	 * 	
	 * @param modelId
	 * @param slot
	 * @param fileName
	 * @return
	 */
	public ApiResponse handleModelSlotsLoad(String modelId, int slot, String fileName) {
		try {
			int port = this.requireLoadedModelPort(modelId);
			String endpoint = String.format("/slots/%d?action=restore", slot);
			JsonObject body = new JsonObject();
			body.addProperty("filename", fileName);
			HttpResult r = this.callLocalModelEndpoint(port, "POST", endpoint, body, 36000 * 1000, 36000 * 1000);
			if (r.statusCode >= 200 && r.statusCode < 300) {
				Object parsed = this.tryParseJson(r.body);
				Map<String, Object> data = new HashMap<>();
				data.put("modelId", modelId);
				data.put("result", parsed);
				return ApiResponse.success(data);
			}
			return ApiResponse.error("加载slot失败: " + r.body);
		} catch (Exception e) {
			logger.info("加载slot缓存时发生错误", e);
			return ApiResponse.error("加载slot失败: " + e.getMessage());
		}
	}
	
	
	/**
	 * 	查找可用的计算设备
	 * @param llamaBinPath
	 * @return
	 */
	public List<String> handleListDevices(String llamaBinPath) {
		List<String> list = new ArrayList<>(8);
		
		String executableName = "llama-bench";
		// 拼接完整命令路径
		String command = llamaBinPath.trim();
		command += File.separator;
		
		command += executableName + " --list-devices";
		
		// 执行命令
		String osName = System.getProperty("os.name");
		String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
		int timeoutSeconds = os.contains("win") ? 30 : 5;
		CommandLineRunner.CommandResult result = CommandLineRunner.execute(command, timeoutSeconds);
		// 根据list device的返回结果。拼凑设备
		String rawOut = result.getOutput() == null ? "" : result.getOutput();
		String rawErr = result.getError() == null ? "" : result.getError();
		String raw = rawOut.contains("Available devices") ? rawOut : (rawErr.isBlank() ? rawOut : rawErr);

		String[] lines = raw.split("\\R");
		int start = 0;
		for (int i = 0; i < lines.length; i++) {
			if (lines[i] != null && lines[i].contains("Available devices")) {
				start = i + 1;
				break;
			}
		}
		for (int i = start; i < lines.length; i++) {
			String line = lines[i];
			if (line == null) {
				continue;
			}
			String v = line.trim();
			if (!v.isEmpty()) {
				list.add(v);
			}
		}
		return list;
	}
	
	/**
	 * 	调用llama-fit-params
	 * @param llamaBinPath
	 * @param devices
	 */
	public void handleFitParam(String llamaBinPath, List<String> devices) {
		
		
		
		return;
	}
	
	/**
	 * 	用户估算显存。
	 * @param llamaBinPath
	 * @param modelId
	 * @param enableVision
	 * @param cmd
	 * @return
	 */
	public String handleFitParam(String llamaBinPath, String modelId, boolean enableVision, List<String> cmd) {
		String[] keysParam = {"--ctx-size", "--flash-attn", "--batch-size", "--ubatch-size", "--parallel", "--kv-unified", "--cache-type-k", "--cache-type-v"};
		Map<String, String> cmdMap = new HashMap<>();
		for(int i = 0; i < cmd.size(); i++) {
			String param = cmd.get(i);
			if(param.startsWith("--") && i + 1 < cmd.size()) {
				if(!cmd.get(i + 1).startsWith("--")) {
					cmdMap.put(param, cmd.get(i + 1));
					i += 1;
				}else {
					cmdMap.put(param, param);
				}
			}
		}
		GGUFModel model = this.findModelById(modelId);
		if(model == null) return null;
		
		String executableName = "llama-fit-params";
		// 拼接完整命令路径
		String command = llamaBinPath.trim() + File.separator + executableName;
		command += " --model " + model.getPrimaryModel().getFilePath();
		
		for(String key : keysParam) {
			// 如果有这个参数
			if(cmdMap.containsKey(key)) {
				String value = cmdMap.get(key);
				if(key.equals(value)) {
					command += key;
				}else {
					command += " " + key + " " + value;
				}
			}
		}
		// 这部分代码是错误的，但是还是留在这里吧。
//		// 如果启用视觉模块
//		if(enableVision) {
//			command += " --mmproj ";
//			String mmprojFile = model.getPath() + "/" + model.getMmproj().getFileName();
//			command += ParamTool.quoteIfNeeded(mmprojFile);
//		}
		
		// 执行命令
		CommandLineRunner.CommandResult result = CommandLineRunner.execute(command, 30);
		String output = result.getError();
		return output;
	}
	
	/**
	 * 	停止所有模型进程并退出Java进程
	 */
	public void shutdownAll() {
		System.out.println("开始停止所有模型进程...");
		Map<String, LlamaCppProcess> processes;
		synchronized (this.processLock) {
			processes = new HashMap<>(this.loadedProcesses);
		}
		for (Map.Entry<String, LlamaCppProcess> entry : processes.entrySet()) {
			String modelId = entry.getKey();
			LlamaCppProcess process = entry.getValue();

			System.out.println("正在停止模型进程: " + modelId);
			boolean stopped = process.stop();
			if (stopped) {
				System.out.println("成功停止模型进程: " + modelId);
			} else {
				System.err.println("停止模型进程失败: " + modelId);
			}
		}

		synchronized (this.processLock) {
			this.loadedProcesses.clear();
			this.modelPorts.clear();
		}

		this.executorService.shutdown();
	}
	
}
