package org.mark.llamacpp.download;

import java.time.LocalDateTime;

/**
 * 下载任务数据传输对象，用于JSON序列化
 * 不包含Path对象，避免Java 17+模块系统问题
 */
public class DownloadTaskDTO {
    private final String taskId;
    private final String url;
    private final String targetPath;
    private final String fileName;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    private BasicDownloader.DownloadState state;
    private long totalBytes;
    private long downloadedBytes;
    private int partsTotal;
    private int partsCompleted;
    private String errorMessage;
    
    public DownloadTaskDTO() {
        this.taskId = null;
        this.url = null;
        this.targetPath = null;
        this.fileName = null;
        this.createdAt = null;
    }
    
    public DownloadTaskDTO(DownloadTask task) {
        this.taskId = task.getTaskId();
        this.url = task.getUrl();
        this.targetPath = task.getTargetPath().toString();
        this.fileName = task.getFileName();
        this.createdAt = task.getCreatedAt();
        this.updatedAt = task.getUpdatedAt();
        this.state = task.getState();
        this.totalBytes = task.getTotalBytes();
        this.downloadedBytes = task.getDownloadedBytes();
        this.partsTotal = task.getPartsTotal();
        this.partsCompleted = task.getPartsCompleted();
        this.errorMessage = task.getErrorMessage();
    }
    
    public String getTaskId() {
        return taskId;
    }
    
    public String getUrl() {
        return url;
    }
    
    public String getTargetPath() {
        return targetPath;
    }
    
    public String getFileName() {
        return fileName;
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
    
    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
    
    public BasicDownloader.DownloadState getState() {
        return state;
    }
    
    public void setState(BasicDownloader.DownloadState state) {
        this.state = state;
    }
    
    public long getTotalBytes() {
        return totalBytes;
    }
    
    public void setTotalBytes(long totalBytes) {
        this.totalBytes = totalBytes;
    }
    
    public long getDownloadedBytes() {
        return downloadedBytes;
    }
    
    public void setDownloadedBytes(long downloadedBytes) {
        this.downloadedBytes = downloadedBytes;
    }
    
    public int getPartsTotal() {
        return partsTotal;
    }
    
    public void setPartsTotal(int partsTotal) {
        this.partsTotal = partsTotal;
    }
    
    public int getPartsCompleted() {
        return partsCompleted;
    }
    
    public void setPartsCompleted(int partsCompleted) {
        this.partsCompleted = partsCompleted;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
    
    public double getProgressRatio() {
        if (totalBytes <= 0) {
            return 0.0;
        }
        double ratio = (double) downloadedBytes / (double) totalBytes;
        if (ratio < 0.0) return 0.0;
        if (ratio > 1.0) return 1.0;
        return ratio;
    }
    
    /**
     * 转换为DownloadTask对象
     */
    public DownloadTask toDownloadTask() {
        if (taskId == null || url == null || targetPath == null || fileName == null || createdAt == null) {
            throw new IllegalStateException("DTO缺少必要字段，无法转换为DownloadTask");
        }
        
        DownloadTask task = new DownloadTask(url, java.nio.file.Paths.get(targetPath), fileName);
        
        // 使用反射设置私有字段，因为DownloadTask没有提供setter方法
        try {
            java.lang.reflect.Field taskIdField = DownloadTask.class.getDeclaredField("taskId");
            taskIdField.setAccessible(true);
            taskIdField.set(task, taskId);
            
            java.lang.reflect.Field createdAtField = DownloadTask.class.getDeclaredField("createdAt");
            createdAtField.setAccessible(true);
            createdAtField.set(task, createdAt);
            
            java.lang.reflect.Field updatedAtField = DownloadTask.class.getDeclaredField("updatedAt");
            updatedAtField.setAccessible(true);
            updatedAtField.set(task, updatedAt);
            
            // 设置其他字段
            task.setState(state);
            task.setTotalBytes(totalBytes);
            task.setDownloadedBytes(downloadedBytes);
            task.setPartsTotal(partsTotal);
            task.setPartsCompleted(partsCompleted);
            task.setErrorMessage(errorMessage);
            
        } catch (Exception e) {
            throw new RuntimeException("无法将DTO转换为DownloadTask", e);
        }
        
        return task;
    }
}