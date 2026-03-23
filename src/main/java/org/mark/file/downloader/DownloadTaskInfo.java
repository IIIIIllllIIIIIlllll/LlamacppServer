package org.mark.file.downloader;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

public class DownloadTaskInfo implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	private String taskId;
	private String sourceUrl;
	private String targetPath;
	private int threadCount;
	private DownloadTaskStatus status;
	private long createdAt;
	private long updatedAt;
	private String finalUrl;
	private long totalBytes;
	private long downloadedBytes;
	private int partsTotal;
	private int partsCompleted;
	private double progressRatio;
	private String errorMessage;

	public DownloadTaskInfo() {
	}

	public DownloadTaskInfo(String taskId, String sourceUrl, String targetPath, int threadCount, DownloadTaskStatus status,
			long createdAt, long updatedAt, String finalUrl, long totalBytes, long downloadedBytes, int partsTotal,
			int partsCompleted, double progressRatio, String errorMessage) {
		this.taskId = Objects.requireNonNull(taskId, "taskId");
		this.sourceUrl = Objects.requireNonNull(sourceUrl, "sourceUrl");
		this.targetPath = Objects.requireNonNull(targetPath, "targetPath");
		this.threadCount = threadCount;
		this.status = Objects.requireNonNull(status, "status");
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
		this.finalUrl = finalUrl;
		this.totalBytes = totalBytes;
		this.downloadedBytes = downloadedBytes;
		this.partsTotal = partsTotal;
		this.partsCompleted = partsCompleted;
		this.progressRatio = progressRatio;
		this.errorMessage = errorMessage;
	}

	public DownloadTaskInfo copy() {
		return new DownloadTaskInfo(this.taskId, this.sourceUrl, this.targetPath, this.threadCount, this.status,
				this.createdAt, this.updatedAt, this.finalUrl, this.totalBytes, this.downloadedBytes, this.partsTotal,
				this.partsCompleted, this.progressRatio, this.errorMessage);
	}

	public String getTaskId() {
		return taskId;
	}

	public void setTaskId(String taskId) {
		this.taskId = taskId;
	}

	public String getSourceUrl() {
		return sourceUrl;
	}

	public void setSourceUrl(String sourceUrl) {
		this.sourceUrl = sourceUrl;
	}

	public String getTargetPath() {
		return targetPath;
	}

	public void setTargetPath(String targetPath) {
		this.targetPath = targetPath;
	}

	public int getThreadCount() {
		return threadCount;
	}

	public void setThreadCount(int threadCount) {
		this.threadCount = threadCount;
	}

	public DownloadTaskStatus getStatus() {
		return status;
	}

	public void setStatus(DownloadTaskStatus status) {
		this.status = status;
	}

	public long getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(long createdAt) {
		this.createdAt = createdAt;
	}

	public long getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(long updatedAt) {
		this.updatedAt = updatedAt;
	}

	public String getFinalUrl() {
		return finalUrl;
	}

	public void setFinalUrl(String finalUrl) {
		this.finalUrl = finalUrl;
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

	public double getProgressRatio() {
		return progressRatio;
	}

	public void setProgressRatio(double progressRatio) {
		this.progressRatio = progressRatio;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
	}
}
