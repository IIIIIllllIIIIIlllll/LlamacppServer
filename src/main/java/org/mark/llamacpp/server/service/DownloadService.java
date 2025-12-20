package org.mark.llamacpp.server.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mark.llamacpp.download.DownloadManager;
import org.mark.llamacpp.download.DownloadTask;

/**
 * 下载服务类，处理下载相关的业务逻辑
 */
public class DownloadService {
    
    private final DownloadManager downloadManager;
    
    public DownloadService() {
        this.downloadManager = DownloadManager.getInstance();
    }
    
    /**
     * 创建下载任务
     * @param url 下载URL
     * @param path 保存路径
     * @param fileName 文件名（可选）
     * @return 创建结果
     */
    public Map<String, Object> createDownloadTask(String url, String path, String fileName) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            String taskId;
            if (fileName != null && !fileName.trim().isEmpty()) {
                taskId = downloadManager.createTask(url, path, fileName);
            } else {
                taskId = downloadManager.createTask(url, path);
            }
            
            result.put("success", true);
            result.put("taskId", taskId);
            result.put("message", "下载任务创建成功");
        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("error", "创建下载任务失败: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 暂停下载任务
     * @param taskId 任务ID
     * @return 操作结果
     */
    public Map<String, Object> pauseDownloadTask(String taskId) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            boolean success = downloadManager.pause(taskId);
            result.put("success", success);
            
            if (success) {
                result.put("message", "下载任务已暂停");
            } else {
                result.put("error", "无法暂停任务，任务可能不存在或当前状态不允许暂停");
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "暂停下载任务失败: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 恢复下载任务
     * @param taskId 任务ID
     * @return 操作结果
     */
    public Map<String, Object> resumeDownloadTask(String taskId) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            boolean success = downloadManager.resume(taskId);
            result.put("success", success);
            
            if (success) {
                result.put("message", "下载任务已恢复");
            } else {
                result.put("error", "无法恢复任务，任务可能不存在或当前状态不允许恢复");
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "恢复下载任务失败: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 删除下载任务
     * @param taskId 任务ID
     * @return 操作结果
     */
    public Map<String, Object> deleteDownloadTask(String taskId) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            boolean success = downloadManager.delete(taskId);
            result.put("success", success);
            
            if (success) {
                result.put("message", "下载任务已删除");
            } else {
                result.put("error", "无法删除任务，任务可能不存在");
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "删除下载任务失败: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 获取所有下载任务
     * @return 任务列表
     */
    public Map<String, Object> getAllDownloadTasks() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            List<DownloadTask> tasks = downloadManager.getAllTasks();
            List<Map<String, Object>> taskDataList = new ArrayList<>();
            
            for (DownloadTask task : tasks) {
                Map<String, Object> taskData = new HashMap<>();
                taskData.put("taskId", task.getTaskId());
                taskData.put("url", task.getUrl());
                taskData.put("targetPath", task.getTargetPath().toString());
                taskData.put("fileName", task.getFileName());
                taskData.put("state", task.getState().toString());
                taskData.put("totalBytes", task.getTotalBytes());
                taskData.put("downloadedBytes", task.getDownloadedBytes());
                taskData.put("partsTotal", task.getPartsTotal());
                taskData.put("partsCompleted", task.getPartsCompleted());
                taskData.put("progressRatio", task.getProgressRatio());
                taskData.put("createdAt", task.getCreatedAt().toString());
                taskData.put("updatedAt", task.getUpdatedAt().toString());
                
                if (task.getErrorMessage() != null) {
                    taskData.put("errorMessage", task.getErrorMessage());
                }
                
                taskDataList.add(taskData);
            }
            
            result.put("success", true);
            result.put("downloads", taskDataList);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "获取下载任务列表失败: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 获取下载统计信息
     * @return 统计信息
     */
    public Map<String, Object> getDownloadStats() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            List<DownloadTask> tasks = downloadManager.getAllTasks();
            
            long activeCount = tasks.stream()
                .filter(t -> t.getState() == org.mark.llamacpp.download.BasicDownloader.DownloadState.DOWNLOADING)
                .count();
                
            long pendingCount = tasks.stream()
                .filter(t -> t.getState() == org.mark.llamacpp.download.BasicDownloader.DownloadState.IDLE)
                .count();
                
            long completedCount = tasks.stream()
                .filter(t -> t.getState() == org.mark.llamacpp.download.BasicDownloader.DownloadState.COMPLETED)
                .count();
                
            long failedCount = tasks.stream()
                .filter(t -> t.getState() == org.mark.llamacpp.download.BasicDownloader.DownloadState.FAILED)
                .count();
                
            Map<String, Object> stats = new HashMap<>();
            stats.put("active", activeCount);
            stats.put("pending", pendingCount);
            stats.put("completed", completedCount);
            stats.put("failed", failedCount);
            stats.put("total", tasks.size());
            stats.put("maxConcurrent", downloadManager.getMaxConcurrentDownloads());
            
            result.put("success", true);
            result.put("stats", stats);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "获取下载统计信息失败: " + e.getMessage());
        }
        
        return result;
    }
}