package org.mark.llamacpp.download;

/**
 * 下载进度监听器接口
 */
public interface DownloadProgressListener {
    
    /**
     * 当任务状态发生变化时调用
     * @param task 下载任务
     * @param oldState 旧状态
     * @param newState 新状态
     */
    void onStateChanged(DownloadTask task, BasicDownloader.DownloadState oldState, BasicDownloader.DownloadState newState);
    
    /**
     * 当下载进度更新时调用
     * @param task 下载任务
     * @param progress 进度信息
     */
    void onProgressUpdated(DownloadTask task, BasicDownloader.DownloadProgress progress);
    
    /**
     * 当任务完成时调用
     * @param task 下载任务
     */
    void onTaskCompleted(DownloadTask task);
    
    /**
     * 当任务失败时调用
     * @param task 下载任务
     * @param error 错误信息
     */
    void onTaskFailed(DownloadTask task, String error);
    
    /**
     * 当任务被暂停时调用
     * @param task 下载任务
     */
    void onTaskPaused(DownloadTask task);
    
    /**
     * 当任务被恢复时调用
     * @param task 下载任务
     */
    void onTaskResumed(DownloadTask task);
}