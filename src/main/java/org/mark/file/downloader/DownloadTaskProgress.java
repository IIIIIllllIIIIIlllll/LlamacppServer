package org.mark.file.downloader;

public record DownloadTaskProgress(
		long downloadedBytes,
		long totalBytes,
		int partsCompleted,
		int partsTotal,
		double progressRatio) {
}
