package org.mark.llamacpp.download;

import org.mark.llamacpp.server.websocket.WebSocketManager;

/**
 * 下载进度WebSocket监听器，用于将下载状态变化通过WebSocket广播给客户端
 */
public class DownloadWebSocketListener implements DownloadProgressListener {
    
    private final WebSocketManager webSocketManager;
    
    public DownloadWebSocketListener() {
        this.webSocketManager = WebSocketManager.getInstance();
    }
    
    @Override
    public void onStateChanged(DownloadTask task, BasicDownloader.DownloadState oldState, BasicDownloader.DownloadState newState) {
        // 广播状态变化事件
        webSocketManager.sendDownloadStatusEvent(
            task.getTaskId(),
            newState.toString(),
            task.getDownloadedBytes(),
            task.getTotalBytes(),
            task.getPartsCompleted(),
            task.getPartsTotal(),
            task.getFileName(),
            task.getErrorMessage()
        );
    }
    
    @Override
    public void onProgressUpdated(DownloadTask task, BasicDownloader.DownloadProgress progress) {
        // 只广播正在下载的任务的进度
        if (task.getState() == BasicDownloader.DownloadState.DOWNLOADING) {
            webSocketManager.sendDownloadProgressEvent(
                task.getTaskId(),
                progress.getDownloadedBytes(),
                progress.getTotalBytes(),
                progress.getPartsCompleted(),
                progress.getPartsTotal(),
                task.getProgressRatio()
            );
        }
    }
    
    @Override
    public void onTaskCompleted(DownloadTask task) {
        // 广播任务完成事件
        webSocketManager.sendDownloadStatusEvent(
            task.getTaskId(),
            BasicDownloader.DownloadState.COMPLETED.toString(),
            task.getDownloadedBytes(),
            task.getTotalBytes(),
            task.getPartsCompleted(),
            task.getPartsTotal(),
            task.getFileName(),
            task.getErrorMessage()
        );
    }
    
    @Override
    public void onTaskFailed(DownloadTask task, String error) {
        // 广播任务失败事件
        webSocketManager.sendDownloadStatusEvent(
            task.getTaskId(),
            BasicDownloader.DownloadState.FAILED.toString(),
            task.getDownloadedBytes(),
            task.getTotalBytes(),
            task.getPartsCompleted(),
            task.getPartsTotal(),
            task.getFileName(),
            error
        );
    }
    
    @Override
    public void onTaskPaused(DownloadTask task) {
        // 广播任务暂停事件
        webSocketManager.sendDownloadStatusEvent(
            task.getTaskId(),
            BasicDownloader.DownloadState.IDLE.toString(),
            task.getDownloadedBytes(),
            task.getTotalBytes(),
            task.getPartsCompleted(),
            task.getPartsTotal(),
            task.getFileName(),
            task.getErrorMessage()
        );
    }
    
    @Override
    public void onTaskResumed(DownloadTask task) {
        // 广播任务恢复事件
        webSocketManager.sendDownloadStatusEvent(
            task.getTaskId(),
            BasicDownloader.DownloadState.DOWNLOADING.toString(),
            task.getDownloadedBytes(),
            task.getTotalBytes(),
            task.getPartsCompleted(),
            task.getPartsTotal(),
            task.getFileName(),
            task.getErrorMessage()
        );
    }
}