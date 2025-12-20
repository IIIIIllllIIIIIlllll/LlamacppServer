package org.mark.llamacpp.download;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * 下载任务数据结构
 */
public class DownloadTask {
    private final String taskId;
    private final String url;
    private final Path targetPath;
    private final String fileName;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    private BasicDownloader.DownloadState state;
    private long totalBytes;
    private long downloadedBytes;
    private int partsTotal;
    private int partsCompleted;
    private String errorMessage;
    private transient BasicDownloader downloader;
    private transient Thread downloadThread;
    private volatile boolean paused;
    
    public DownloadTask(String url, Path targetPath, String fileName) {
        this.taskId = UUID.randomUUID().toString();
        this.url = Objects.requireNonNull(url, "url cannot be null");
        this.targetPath = Objects.requireNonNull(targetPath, "targetPath cannot be null");
        this.fileName = fileName != null ? fileName : extractFileNameFromUrl(url);
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.state = BasicDownloader.DownloadState.IDLE;
        this.totalBytes = 0;
        this.downloadedBytes = 0;
        this.partsTotal = 0;
        this.partsCompleted = 0;
        this.errorMessage = null;
        this.paused = false;
    }
    
    public String getTaskId() {
        return taskId;
    }
    
    public String getUrl() {
        return url;
    }
    
    public Path getTargetPath() {
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
    
    public BasicDownloader.DownloadState getState() {
        return state;
    }
    
    public void setState(BasicDownloader.DownloadState state) {
        this.state = state;
        this.updatedAt = LocalDateTime.now();
    }
    
    public long getTotalBytes() {
        return totalBytes;
    }
    
    public void setTotalBytes(long totalBytes) {
        this.totalBytes = totalBytes;
        this.updatedAt = LocalDateTime.now();
    }
    
    public long getDownloadedBytes() {
        return downloadedBytes;
    }
    
    public void setDownloadedBytes(long downloadedBytes) {
        this.downloadedBytes = downloadedBytes;
        this.updatedAt = LocalDateTime.now();
    }
    
    public int getPartsTotal() {
        return partsTotal;
    }
    
    public void setPartsTotal(int partsTotal) {
        this.partsTotal = partsTotal;
        this.updatedAt = LocalDateTime.now();
    }
    
    public int getPartsCompleted() {
        return partsCompleted;
    }
    
    public void setPartsCompleted(int partsCompleted) {
        this.partsCompleted = partsCompleted;
        this.updatedAt = LocalDateTime.now();
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
        this.updatedAt = LocalDateTime.now();
    }
    
    public BasicDownloader getDownloader() {
        return downloader;
    }
    
    public void setDownloader(BasicDownloader downloader) {
        this.downloader = downloader;
    }
    
    public Thread getDownloadThread() {
        return downloadThread;
    }
    
    public void setDownloadThread(Thread downloadThread) {
        this.downloadThread = downloadThread;
    }
    
    public boolean isPaused() {
        return paused;
    }
    
    public void setPaused(boolean paused) {
        this.paused = paused;
        this.updatedAt = LocalDateTime.now();
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
    
    public Path getFullTargetPath() {
        return targetPath.resolve(fileName);
    }
    
    private static String extractFileNameFromUrl(String url) {
        try {
            String path = new java.net.URI(url).getPath();
            if (path == null || path.isBlank() || "/".equals(path)) {
                return "download.bin";
            }
            String name = path.substring(path.lastIndexOf('/') + 1);
            return name.isBlank() ? "download.bin" : name;
        } catch (Exception e) {
            return "download.bin";
        }
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DownloadTask that = (DownloadTask) o;
        return Objects.equals(taskId, that.taskId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(taskId);
    }
    
    @Override
    public String toString() {
        return "DownloadTask{" +
                "taskId='" + taskId + '\'' +
                ", url='" + url + '\'' +
                ", fileName='" + fileName + '\'' +
                ", state=" + state +
                ", progress=" + String.format("%.2f%%", getProgressRatio() * 100) +
                '}';
    }
}