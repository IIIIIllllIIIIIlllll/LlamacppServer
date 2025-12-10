package org.mark.llamacpp.server;

import java.io.File;
import java.io.IOException;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.mark.llamacpp.gguf.GGUFBundle;
import org.mark.llamacpp.gguf.GGUFMetaData;
import org.mark.llamacpp.gguf.GGUFModel;
import org.mark.llamacpp.server.struct.ModelLaunchOptions;
import org.mark.llamacpp.server.tools.PortChecker;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class LlamaServerManager {

	/**
	 * 	
	 */
	private static final Gson gson = new Gson();
	
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
    private List<String> modelPaths = new ArrayList<>(java.util.Arrays.asList("Y:\\Models"));
	
	
	/**
	 * 	所有GGUF模型的列表
	 */
	private List<GGUFModel> list = new LinkedList<>();
	
	/**
	 * 已加载的模型进程列表
	 */
	private Map<String, LlamaCppProcess> loadedProcesses = new LinkedHashMap<>();
	
	/**
	 * 端口计数器，用于递增分配端口
	 */
	private AtomicInteger portCounter = new AtomicInteger(8081);
	
	/**
	 * 模型ID到端口映射
	 */
	private Map<String, Integer> modelPorts = new HashMap<>();
	
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
			// 获取当前工作目录
			String currentDir = System.getProperty("user.dir");
			Path configDir = Paths.get(currentDir, "config");
			Path settingsPath = configDir.resolve("settings.json");
			
			// 检查文件是否存在
			if (Files.exists(settingsPath)) {
				// 读取文件内容
				String json = new String(Files.readAllBytes(settingsPath), StandardCharsets.UTF_8);
				
				// 解析JSON
				JsonObject settings = gson.fromJson(json, JsonObject.class);
				
                if (settings.has("modelPaths") && settings.get("modelPaths").isJsonArray()) {
                    List<String> paths = new ArrayList<>();
                    settings.get("modelPaths").getAsJsonArray().forEach(e -> {
                        String p = e.getAsString();
                        if (p != null && !p.trim().isEmpty()) paths.add(p.trim());
                    });
                    if (!paths.isEmpty()) this.modelPaths = paths;
                } else if (settings.has("modelPath")) {
                    String p = settings.get("modelPath").getAsString();
                    if (p != null && !p.trim().isEmpty()) this.modelPaths = new ArrayList<>(java.util.Arrays.asList(p.trim()));
                }
				
				
				System.out.println("已从配置文件加载设置: " + settingsPath.toString());
			} else {
				System.out.println("配置文件不存在，使用默认设置: " + settingsPath.toString());
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
    public void setModelPaths(List<String> paths) {
        this.modelPaths = new ArrayList<>();
        if (paths != null) {
            for (String p : paths) {
                if (p != null && !p.trim().isEmpty()) this.modelPaths.add(p.trim());
            }
        }
        if (this.modelPaths.isEmpty()) this.modelPaths.add("Y:\\Models");
    }

    /**
     * 	获取当前设定的模型路径列表
     * @return
     */
    public List<String> getModelPaths() {
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
                for (String root : this.modelPaths) {
                    if (root == null || root.trim().isEmpty()) continue;
                    Path modelDir = Paths.get(root.trim());
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
                // 合并别名：从已保存的配置加载别名并应用到当前列表
                Map<String, String> aliasMap = this.configManager.loadAliasMap();
                for (GGUFModel m : this.list) {
                    String alias = aliasMap.get(m.getModelId());
                    if (alias != null && !alias.isEmpty()) {
                        m.setAlias(alias);
                    }
                }
                // 保存模型信息到配置文件
                this.configManager.saveModelsConfig(this.list);
            }
            // 如果集合不是空的，就直接返回。
            else {
                return this.list;
            }
        }
        return this.list;
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
			if(primaryFile != null && primaryFile.exists()) {
				GGUFMetaData md = GGUFMetaData.readFile(primaryFile);
				model.setPrimaryModel(md);
				// 将主模型元数据也添加到列表中，保持兼容性
				model.addMetaData(md);
			}
			
			// 处理mmproj文件
			File mmprojFile = bundle.getMmprojFile();
			if(mmprojFile != null && mmprojFile.exists()) {
				GGUFMetaData md = GGUFMetaData.readFile(mmprojFile);
				model.setMmproj(md);
				model.addMetaData(md);
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
		return new HashMap<>(this.loadedProcesses);
	}
	
	/**
	 * 	获取第一个已经加载的模型的名字。
	 * @return
	 */
	public String getFirstModelName() {
		if (this.loadedProcesses.isEmpty()) {
			return null;
		}
		Map.Entry<String, LlamaCppProcess> firstEntry = this.loadedProcesses.entrySet().iterator().next();
		return firstEntry.getKey();
	}
	
	/**
	 * 获取模型对应的端口
	 * @param modelId 模型ID
	 * @return 端口号，如果模型未加载则返回null
	 */
	public Integer getModelPort(String modelId) {
		return this.modelPorts.get(modelId);
	}
	
	/**
	 * 停止并移除已加载的模型
	 * @param modelId 模型ID
	 * @return 是否成功停止
	 */
	public synchronized boolean stopModel(String modelId) {
		LlamaCppProcess process = this.loadedProcesses.get(modelId);
		if (process != null) {
			boolean stopped = process.stop();
			if (stopped) {
				this.loadedProcesses.remove(modelId);
				this.modelPorts.remove(modelId);
			}
			return stopped;
		}
		return false;
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
	 * 异步加载指定的模型
	 *
	 * @param modelId 模型ID
	 * @param ctxSize 上下文大小
	 * @param batchSize 批处理大小
	 * @param ubatchSize 微批处理大小
	 * @param noMmap 是否禁用内存映射
	 * @param mlock 是否锁定内存
	 * @return 是否成功启动加载任务
	 */
    public synchronized boolean loadModelAsync(String modelId, ModelLaunchOptions options) {

        Map<String, Object> launchConfig = options.toConfigMap();
        this.configManager.saveLaunchConfig(modelId, launchConfig);
		
		// 检查模型是否已经加载
		if (this.loadedProcesses.containsKey(modelId)) {
			System.err.println("模型 " + modelId + " 已经加载");
			// 发送WebSocket事件通知模型已加载
			LlamaServer.sendModelLoadEvent(modelId, false, "模型已经加载");
			return false;
		}
		
		// 查找指定的模型
		GGUFModel targetModel = this.findModelById(modelId);
		
        if (targetModel == null) {
            System.err.println("未找到ID为 " + modelId + " 的模型");
            // 发送WebSocket事件通知模型未找到
            LlamaServer.sendModelLoadEvent(modelId, false, "未找到ID为 " + modelId + " 的模型");
            return false;
        }

        if (options.llamaBinPath == null || options.llamaBinPath.trim().isEmpty()) {
            LlamaServer.sendModelLoadEvent(modelId, false, "未提供llamaBinPath");
            return false;
        }
		// 如果这个模型已经在加载中 
		synchronized (this.loadingModels) {
			if(this.loadingModels.contains(targetModel.getModelId())) {
				LlamaServer.sendModelLoadEvent(modelId, false, "该模型正在加载中");
				return false;
			}
		}
        this.executorService.submit(() -> {
            this.loadModelInBackground(modelId, targetModel, options);
        });
		
		return true; // 表示成功提交加载任务
	}
	
	/**
	 * 在后台线程中执行模型加载
	 */
    private synchronized void loadModelInBackground(String modelId, GGUFModel targetModel, ModelLaunchOptions options) {

        // 获取下一个可用端口
        int port = this.getNextAvailablePort();

        List<String> command = options.toCmdLine(targetModel, port);

        // 构建完整的命令字符串
        String commandStr = String.join(" ", command);
		
		// 创建并启动LlamaCppProcess
		String processName = "llama-server-" + modelId;
		LlamaCppProcess process = new LlamaCppProcess(processName, commandStr);
		
		System.out.println("启动命令：" + commandStr);
		
		// 使用CountDownLatch来同步等待加载结果
		CountDownLatch latch = new CountDownLatch(1);
		AtomicBoolean loadSuccess = new AtomicBoolean(false);
		
		// 设置输出处理器，接受llamacpp运行状态，然后判断特定的内容。
        process.setOutputHandler(line -> {
            //	判断是否加载成功。
            //	1.这是成功了
            if(line.contains("srv  update_slots: all slots are idle")) {
                loadSuccess.set(true);
                latch.countDown();
            }
            //	2.这是失败了
            if(line.contains("main: exiting due to model loading error")) {
                loadSuccess.set(false);
                latch.countDown();
            }
            //	3.检测进程异常终止
            if(line.contains("Inferior") && line.contains("detached")) {
                // 检测到进程异常终止，如 [Inferior 1 (process 6869) detached]
                System.err.println("检测到模型进程异常终止: " + line);
                
                // 设置加载失败状态
                loadSuccess.set(false);
                
                // 从已加载进程列表中移除
                this.loadedProcesses.remove(modelId);
                this.modelPorts.remove(modelId);
                
                // 通过WebSocket广播模型停止事件
                LlamaServer.sendModelStopEvent(modelId, false, "模型进程异常终止: " + line);
                
                // 唤醒等待的线程
                latch.countDown();
            }
            //	4.检测到参数错误
            if(line.startsWith("error")) {
            	 System.err.println("检测到模型进程异常终止: " + line);
                 // 设置加载失败状态
                 loadSuccess.set(false);
                 
                 // 从已加载进程列表中移除
                 this.loadedProcesses.remove(modelId);
                 this.modelPorts.remove(modelId);
                 
                 // 通过WebSocket广播模型停止事件
                 //LlamaServer.sendModelStopEvent(modelId, false, "模型启动失败: " + line);
                 
                 // 唤醒等待的线程
                 latch.countDown();
            }
        });
		
		// 启动进程
		boolean started = process.start();
		if (!started) {
			System.err.println("启动模型 " + modelId + " 失败");
			// 发送WebSocket事件通知启动失败
			LlamaServer.sendModelLoadEvent(modelId, false, "启动模型进程失败");
			return;
		}else {
			// 设置模型的状态为启动中
			synchronized (this.loadingModels) {
				this.loadingModels.add(targetModel.getModelId());
			}
		}
		
		// 等待进程加载完成，超时时间10分钟
		try {
			boolean timeout = !latch.await(10, TimeUnit.MINUTES);
			
			if (timeout) {
				System.err.println("加载模型 " + modelId + " 超时");
				// 停止进程
				process.stop();
				// 发送WebSocket事件通知加载超时
				LlamaServer.sendModelLoadEvent(modelId, false, "模型加载超时");
				return;
			}
			
			if (loadSuccess.get()) {
				// 保存进程信息
				this.loadedProcesses.put(modelId, process);
				this.modelPorts.put(modelId, port);
				System.out.println("成功启动模型 " + modelId + "，端口: " + port + "，PID: " + process.getPid());
				// 发送WebSocket事件通知加载成功
				LlamaServer.sendModelLoadEvent(modelId, true, "模型加载成功，端口: " + port);
			} else {
				System.err.println("加载模型 " + modelId + " 失败");
				// 停止进程
				process.stop();
				// 发送WebSocket事件通知加载失败
				LlamaServer.sendModelLoadEvent(modelId, false, "模型加载失败");
			}
		} catch (InterruptedException e) {
			System.err.println("等待模型加载时被中断: " + e.getMessage());
			Thread.currentThread().interrupt();
			// 停止进程
			process.stop();
			// 发送WebSocket事件通知加载被中断
			LlamaServer.sendModelLoadEvent(modelId, false, "模型加载被中断");
		}finally {
			synchronized (this.loadingModels) {
				this.loadingModels.remove(targetModel.getModelId());
			}
		}
	}
	
	/**
		* 停止所有模型进程并退出Java进程
		*/
	public synchronized void shutdownAll() {
		System.out.println("开始停止所有模型进程...");
		
		// 获取所有已加载的进程
		Map<String, LlamaCppProcess> processes = new HashMap<>(this.loadedProcesses);
		
		// 停止所有模型进程
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
		
		// 清空进程列表和端口映射
		this.loadedProcesses.clear();
		this.modelPorts.clear();
		
		// 关闭线程池
		this.executorService.shutdown();
		
		//System.out.println("所有模型进程已停止，即将退出Java进程");
		
		// 退出Java进程
		//System.exit(0);
	}
}
