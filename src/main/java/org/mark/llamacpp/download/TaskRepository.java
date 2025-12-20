package org.mark.llamacpp.download;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务仓库，负责任务的持久化存储和恢复
 */
public class TaskRepository {
    private static final String REPOSITORY_DIR = "downloads";
    private static final String TASKS_FILE = "tasks.json";
    private static final Path REPOSITORY_PATH = Paths.get(REPOSITORY_DIR);
    private static final Path TASKS_FILE_PATH = REPOSITORY_PATH.resolve(TASKS_FILE);
    
    private final Map<String, DownloadTask> tasks = new ConcurrentHashMap<>();
    private final Gson gson;
    
    public TaskRepository() {
        // 创建Gson实例，支持LocalDateTime的序列化/反序列化
        // 不再需要Path适配器，因为使用DTO进行序列化
        this.gson = new GsonBuilder()
                .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeAdapter())
                .setPrettyPrinting()
                .create();
        
        // 确保仓库目录存在
        try {
            if (!Files.exists(REPOSITORY_PATH)) {
                Files.createDirectories(REPOSITORY_PATH);
            }
        } catch (IOException e) {
            System.err.println("无法创建下载仓库目录: " + e.getMessage());
        }
        
        // 加载已保存的任务
        loadTasks();
    }
    
    /**
     * 添加或更新任务
     */
    public void saveTask(DownloadTask task) {
        tasks.put(task.getTaskId(), task);
        persistTasks();
    }
    
    /**
     * 获取任务
     */
    public DownloadTask getTask(String taskId) {
        return tasks.get(taskId);
    }
    
    /**
     * 获取所有任务
     */
    public List<DownloadTask> getAllTasks() {
        return new ArrayList<>(tasks.values());
    }
    
    /**
     * 删除任务
     */
    public void deleteTask(String taskId) {
        tasks.remove(taskId);
        persistTasks();
    }
    
    /**
     * 将任务持久化到文件
     */
    private void persistTasks() {
        try (BufferedWriter writer = Files.newBufferedWriter(
                TASKS_FILE_PATH,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            
            // 将DownloadTask转换为DTO再序列化
            List<DownloadTaskDTO> dtos = tasks.values().stream()
                    .map(DownloadTaskDTO::new)
                    .collect(java.util.stream.Collectors.toList());
            
            String json = gson.toJson(dtos);
            writer.write(json);
        } catch (IOException e) {
            System.err.println("保存任务失败: " + e.getMessage());
        }
    }
    
    /**
     * 从文件加载任务
     */
    private void loadTasks() {
        if (!Files.exists(TASKS_FILE_PATH)) {
            return;
        }
        
        try (BufferedReader reader = Files.newBufferedReader(TASKS_FILE_PATH)) {
            // 先尝试加载DTO格式
            try {
                DownloadTaskDTO[] loadedDTOs = gson.fromJson(reader, DownloadTaskDTO[].class);
                if (loadedDTOs != null) {
                    for (DownloadTaskDTO dto : loadedDTOs) {
                        DownloadTask task = dto.toDownloadTask();
                        
                        // 恢复任务状态，重置transient字段
                        task.setDownloader(null);
                        task.setDownloadThread(null);
                        task.setPaused(false);
                        
                        // 如果任务正在下载或暂停中，重置为准备状态
                        if (task.getState() == BasicDownloader.DownloadState.DOWNLOADING ||
                            task.getState() == BasicDownloader.DownloadState.PREPARING) {
                            task.setState(BasicDownloader.DownloadState.IDLE);
                        }
                        
                        tasks.put(task.getTaskId(), task);
                    }
                }
            } catch (Exception e) {
                // 如果DTO格式失败，尝试加载旧格式（向后兼容）
                System.err.println("尝试加载旧格式任务文件: " + e.getMessage());
                reader.reset();
                
                try {
                    DownloadTask[] loadedTasks = gson.fromJson(reader, DownloadTask[].class);
                    if (loadedTasks != null) {
                        for (DownloadTask task : loadedTasks) {
                            // 恢复任务状态，重置transient字段
                            task.setDownloader(null);
                            task.setDownloadThread(null);
                            task.setPaused(false);
                            
                            // 如果任务正在下载或暂停中，重置为准备状态
                            if (task.getState() == BasicDownloader.DownloadState.DOWNLOADING ||
                                task.getState() == BasicDownloader.DownloadState.PREPARING) {
                                task.setState(BasicDownloader.DownloadState.IDLE);
                            }
                            
                            tasks.put(task.getTaskId(), task);
                        }
                    }
                } catch (Exception ex) {
                    System.err.println("加载任务文件失败: " + ex.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("加载任务失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取仓库路径
     */
    public static Path getRepositoryPath() {
        return REPOSITORY_PATH;
    }
    
    /**
     * LocalDateTime的JSON适配器
     */
    private static class LocalDateTimeAdapter implements JsonSerializer<LocalDateTime>, JsonDeserializer<LocalDateTime> {
        private static final DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
        
        @Override
        public JsonElement serialize(LocalDateTime src, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(src.format(formatter));
        }
        
        @Override
        public LocalDateTime deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
                throws JsonParseException {
            return LocalDateTime.parse(json.getAsString(), formatter);
        }
    }
    
}