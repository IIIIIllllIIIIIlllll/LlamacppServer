package org.mark.file.downloader;

import java.nio.file.Path;

import org.mark.llamacpp.server.websocket.WebSocketManager;

public class DownloadWebSocketListener implements DownloadProgressListener {

	private final WebSocketManager webSocketManager;

	public DownloadWebSocketListener() {
		this.webSocketManager = WebSocketManager.getInstance();
	}

	@Override
	public void onStateChanged(DownloadTaskInfo task, DownloadTaskStatus oldState, DownloadTaskStatus newState) {
		this.webSocketManager.sendDownloadStatusEvent(
				task.getTaskId(),
				mapState(newState),
				task.getDownloadedBytes(),
				task.getTotalBytes(),
				task.getPartsCompleted(),
				task.getPartsTotal(),
				resolveFileName(task),
				task.getErrorMessage());
	}

	@Override
	public void onProgressUpdated(DownloadTaskInfo task, DownloadTaskProgress progress) {
		this.webSocketManager.sendDownloadProgressEvent(
				task.getTaskId(),
				progress.downloadedBytes(),
				progress.totalBytes(),
				progress.partsCompleted(),
				progress.partsTotal(),
				progress.progressRatio());
	}

	@Override
	public void onTaskCompleted(DownloadTaskInfo task) {
		this.webSocketManager.sendDownloadStatusEvent(
				task.getTaskId(),
				mapState(DownloadTaskStatus.COMPLETED),
				task.getDownloadedBytes(),
				task.getTotalBytes(),
				task.getPartsCompleted(),
				task.getPartsTotal(),
				resolveFileName(task),
				task.getErrorMessage());
	}

	@Override
	public void onTaskFailed(DownloadTaskInfo task, String error) {
		this.webSocketManager.sendDownloadStatusEvent(
				task.getTaskId(),
				mapState(DownloadTaskStatus.FAILED),
				task.getDownloadedBytes(),
				task.getTotalBytes(),
				task.getPartsCompleted(),
				task.getPartsTotal(),
				resolveFileName(task),
				error);
	}

	@Override
	public void onTaskPaused(DownloadTaskInfo task) {
		this.webSocketManager.sendDownloadStatusEvent(
				task.getTaskId(),
				mapState(DownloadTaskStatus.PAUSED),
				task.getDownloadedBytes(),
				task.getTotalBytes(),
				task.getPartsCompleted(),
				task.getPartsTotal(),
				resolveFileName(task),
				task.getErrorMessage());
	}

	@Override
	public void onTaskResumed(DownloadTaskInfo task) {
		this.webSocketManager.sendDownloadStatusEvent(
				task.getTaskId(),
				mapState(DownloadTaskStatus.RUNNING),
				task.getDownloadedBytes(),
				task.getTotalBytes(),
				task.getPartsCompleted(),
				task.getPartsTotal(),
				resolveFileName(task),
				task.getErrorMessage());
	}

	private String mapState(DownloadTaskStatus status) {
		return switch (status) {
		case RUNNING -> "DOWNLOADING";
		case PAUSED -> "PAUSED";
		case COMPLETED -> "COMPLETED";
		case FAILED -> "FAILED";
		case PENDING -> "IDLE";
		};
	}

	private String resolveFileName(DownloadTaskInfo task) {
		try {
			Path target = Path.of(task.getTargetPath());
			return target.getFileName() == null ? "" : target.getFileName().toString();
		} catch (Exception e) {
			return "";
		}
	}
}
