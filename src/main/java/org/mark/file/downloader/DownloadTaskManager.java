package org.mark.file.downloader;

import java.io.Closeable;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class DownloadTaskManager implements Closeable {

	private final Path cacheFile;
	private final ExecutorService workerPool;
	private final Map<String, DownloadTaskInfo> taskStore = new ConcurrentHashMap<>();
	private final Map<String, RuntimeTaskContext> runtimeStore = new ConcurrentHashMap<>();
	private final Map<String, DownloadProgressListener> listeners = new ConcurrentHashMap<>();
	private final Object fileLock = new Object();

	public DownloadTaskManager(Path cacheFile, int maxConcurrentTasks) throws IOException {
		Objects.requireNonNull(cacheFile, "cacheFile");
		if (maxConcurrentTasks < 1) {
			throw new IllegalArgumentException("maxConcurrentTasks must be >= 1");
		}
		this.cacheFile = cacheFile;
		this.workerPool = Executors.newFixedThreadPool(maxConcurrentTasks);
		loadFromCache();
		addProgressListener(new DownloadWebSocketListener());
	}

	public static DownloadTaskManager createDefault(int maxConcurrentTasks) throws IOException {
		Path cacheDir = Path.of(System.getProperty("user.dir"), "cache");
		return new DownloadTaskManager(cacheDir.resolve("tasks.cache"), maxConcurrentTasks);
	}

	public DownloadTaskInfo createTask(String sourceUrl, Path targetFile, int threadCount) throws IOException {
		Objects.requireNonNull(sourceUrl, "sourceUrl");
		Objects.requireNonNull(targetFile, "targetFile");
		if (threadCount < 1) {
			throw new IllegalArgumentException("threadCount must be >= 1");
		}
		long now = System.currentTimeMillis();
		String taskId = UUID.randomUUID().toString();
		DownloadTaskInfo task = new DownloadTaskInfo(taskId, sourceUrl, targetFile.toString(), threadCount, DownloadTaskStatus.PENDING,
				now, now, null, -1L, 0L, threadCount, 0, 0D, null);
		this.taskStore.put(taskId, task);
		persistToCache();
		notifyStateChanged(task.copy(), null, DownloadTaskStatus.PENDING);
		return task.copy();
	}

	public DownloadTaskInfo startTask(String taskId) throws IOException {
		DownloadTaskInfo task = requireTask(taskId);
		DownloadTaskStatus oldStatus;
		synchronized (task) {
			if (task.getStatus() == DownloadTaskStatus.RUNNING) {
				return task.copy();
			}
			if (task.getStatus() == DownloadTaskStatus.COMPLETED) {
				return task.copy();
			}
			oldStatus = task.getStatus();
			task.setStatus(DownloadTaskStatus.RUNNING);
			task.setErrorMessage(null);
			task.setPartsTotal(task.getThreadCount());
			task.setUpdatedAt(System.currentTimeMillis());
			persistToCache();
		}
		notifyStateChanged(task.copy(), oldStatus, DownloadTaskStatus.RUNNING);
		if (oldStatus == DownloadTaskStatus.PAUSED) {
			notifyTaskResumed(task.copy());
		}

		SimpleHttpDownloader downloader = new SimpleHttpDownloader(task.getThreadCount());
		downloader.setProgressListener((downloadedBytes, totalBytes, partsCompleted, partsTotal) -> {
			DownloadTaskInfo snapshot;
			DownloadTaskProgress progress;
			synchronized (task) {
				task.setDownloadedBytes(downloadedBytes);
				task.setTotalBytes(totalBytes);
				task.setPartsCompleted(partsCompleted);
				task.setPartsTotal(partsTotal);
				task.setProgressRatio(totalBytes > 0 ? (double) downloadedBytes / (double) totalBytes : 0D);
				task.setUpdatedAt(System.currentTimeMillis());
				snapshot = task.copy();
				progress = new DownloadTaskProgress(task.getDownloadedBytes(), task.getTotalBytes(), task.getPartsCompleted(),
						task.getPartsTotal(), task.getProgressRatio());
			}
			notifyProgressUpdated(snapshot, progress);
		});
		Path targetPath = Path.of(task.getTargetPath());
		Future<?> future = this.workerPool.submit(() -> {
			try {
				SimpleHttpDownloader.DownloadResult result = downloader.download(task.getSourceUrl(), targetPath);
				DownloadTaskInfo snapshot;
				synchronized (task) {
					task.setStatus(DownloadTaskStatus.COMPLETED);
					task.setFinalUrl(result.finalUrl());
					task.setTotalBytes(result.contentLength());
					task.setDownloadedBytes(result.contentLength());
					task.setPartsCompleted(result.parts());
					task.setPartsTotal(result.parts());
					task.setProgressRatio(1D);
					task.setErrorMessage(null);
					task.setUpdatedAt(System.currentTimeMillis());
					snapshot = task.copy();
				}
				notifyStateChanged(snapshot, DownloadTaskStatus.RUNNING, DownloadTaskStatus.COMPLETED);
				notifyTaskCompleted(snapshot);
			} catch (IOException e) {
				DownloadTaskInfo snapshot;
				DownloadTaskStatus newStatus;
				synchronized (task) {
					if (downloader.isStopRequested() || isPauseException(e)) {
						task.setStatus(DownloadTaskStatus.PAUSED);
						task.setErrorMessage("任务已暂停");
						newStatus = DownloadTaskStatus.PAUSED;
					} else {
						task.setStatus(DownloadTaskStatus.FAILED);
						task.setErrorMessage(e.getMessage());
						newStatus = DownloadTaskStatus.FAILED;
					}
					task.setUpdatedAt(System.currentTimeMillis());
					snapshot = task.copy();
				}
				notifyStateChanged(snapshot, DownloadTaskStatus.RUNNING, newStatus);
				if (newStatus == DownloadTaskStatus.PAUSED) {
					notifyTaskPaused(snapshot);
				} else {
					notifyTaskFailed(snapshot, e.getMessage());
				}
			} finally {
				this.runtimeStore.remove(task.getTaskId());
				try {
					persistToCache();
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		});
		this.runtimeStore.put(taskId, new RuntimeTaskContext(downloader, future));
		return task.copy();
	}

	public DownloadTaskInfo pauseTask(String taskId) throws IOException {
		DownloadTaskInfo task = requireTask(taskId);
		RuntimeTaskContext context = this.runtimeStore.get(taskId);
		if (context != null) {
			context.downloader.requestStop();
			context.future.cancel(true);
		}
		synchronized (task) {
			if (task.getStatus() == DownloadTaskStatus.PENDING || task.getStatus() == DownloadTaskStatus.RUNNING) {
				DownloadTaskStatus oldStatus = task.getStatus();
				task.setStatus(DownloadTaskStatus.PAUSED);
				task.setErrorMessage("任务已暂停");
				task.setUpdatedAt(System.currentTimeMillis());
				persistToCache();
				DownloadTaskInfo snapshot = task.copy();
				notifyStateChanged(snapshot, oldStatus, DownloadTaskStatus.PAUSED);
				notifyTaskPaused(snapshot);
			}
			return task.copy();
		}
	}

	public boolean deleteTask(String taskId, boolean deleteLocalFile) throws IOException {
		DownloadTaskInfo task = this.taskStore.get(taskId);
		if (task == null) {
			return false;
		}
		pauseTask(taskId);
		this.runtimeStore.remove(taskId);
		DownloadTaskInfo removed = this.taskStore.remove(taskId);
		persistToCache();
		if (removed != null) {
			notifyTaskFailed(removed.copy(), "任务已删除");
		}
		if (deleteLocalFile && removed != null) {
			deleteTaskFiles(removed);
		}
		return true;
	}

	public Optional<DownloadTaskInfo> getTask(String taskId) {
		DownloadTaskInfo task = this.taskStore.get(taskId);
		if (task == null) {
			return Optional.empty();
		}
		return Optional.of(task.copy());
	}

	public List<DownloadTaskInfo> listTasks() {
		List<DownloadTaskInfo> result = new ArrayList<>();
		for (DownloadTaskInfo task : this.taskStore.values()) {
			result.add(task.copy());
		}
		result.sort(Comparator.comparingLong(DownloadTaskInfo::getCreatedAt));
		return result;
	}

	public String addProgressListener(DownloadProgressListener listener) {
		Objects.requireNonNull(listener, "listener");
		String listenerId = UUID.randomUUID().toString();
		this.listeners.put(listenerId, listener);
		return listenerId;
	}

	public boolean removeProgressListener(String listenerId) {
		return this.listeners.remove(listenerId) != null;
	}

	@Override
	public void close() {
		for (RuntimeTaskContext context : this.runtimeStore.values()) {
			context.downloader.requestStop();
			context.future.cancel(true);
		}
		this.runtimeStore.clear();
		this.workerPool.shutdownNow();
	}

	private DownloadTaskInfo requireTask(String taskId) {
		Objects.requireNonNull(taskId, "taskId");
		DownloadTaskInfo task = this.taskStore.get(taskId);
		if (task == null) {
			throw new IllegalArgumentException("任务不存在: " + taskId);
		}
		return task;
	}

	private void deleteTaskFiles(DownloadTaskInfo task) throws IOException {
		Path target = Path.of(task.getTargetPath());
		Path temp = target.resolveSibling(target.getFileName() + ".downloading");
		Path metadata = target.resolveSibling(target.getFileName() + ".downloading.meta");
		Files.deleteIfExists(metadata);
		Files.deleteIfExists(temp);
		Files.deleteIfExists(target);
	}

	private boolean isPauseException(IOException e) {
		if (e.getMessage() == null) {
			return false;
		}
		return e.getMessage().contains("暂停") || e.getMessage().contains("中断");
	}

	private void notifyStateChanged(DownloadTaskInfo task, DownloadTaskStatus oldState, DownloadTaskStatus newState) {
		for (DownloadProgressListener listener : this.listeners.values()) {
			try {
				listener.onStateChanged(task, oldState, newState);
			} catch (Exception ignored) {
			}
		}
	}

	private void notifyProgressUpdated(DownloadTaskInfo task, DownloadTaskProgress progress) {
		for (DownloadProgressListener listener : this.listeners.values()) {
			try {
				listener.onProgressUpdated(task, progress);
			} catch (Exception ignored) {
			}
		}
	}

	private void notifyTaskCompleted(DownloadTaskInfo task) {
		for (DownloadProgressListener listener : this.listeners.values()) {
			try {
				listener.onTaskCompleted(task);
			} catch (Exception ignored) {
			}
		}
	}

	private void notifyTaskFailed(DownloadTaskInfo task, String error) {
		for (DownloadProgressListener listener : this.listeners.values()) {
			try {
				listener.onTaskFailed(task, error);
			} catch (Exception ignored) {
			}
		}
	}

	private void notifyTaskPaused(DownloadTaskInfo task) {
		for (DownloadProgressListener listener : this.listeners.values()) {
			try {
				listener.onTaskPaused(task);
			} catch (Exception ignored) {
			}
		}
	}

	private void notifyTaskResumed(DownloadTaskInfo task) {
		for (DownloadProgressListener listener : this.listeners.values()) {
			try {
				listener.onTaskResumed(task);
			} catch (Exception ignored) {
			}
		}
	}

	private void loadFromCache() throws IOException {
		Path parent = this.cacheFile.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		if (!Files.exists(this.cacheFile) || Files.size(this.cacheFile) == 0) {
			return;
		}
		synchronized (this.fileLock) {
			try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(this.cacheFile))) {
				TaskCache cache = (TaskCache) input.readObject();
				this.taskStore.clear();
				for (DownloadTaskInfo task : cache.tasks) {
					if (task.getStatus() == DownloadTaskStatus.RUNNING) {
						task.setStatus(DownloadTaskStatus.PAUSED);
						task.setErrorMessage("程序重启后任务重置为暂停");
						task.setUpdatedAt(System.currentTimeMillis());
					}
					this.taskStore.put(task.getTaskId(), task);
				}
			} catch (ClassNotFoundException e) {
				throw new IOException("读取任务缓存失败", e);
			}
		}
	}

	private void persistToCache() throws IOException {
		synchronized (this.fileLock) {
			TaskCache cache = new TaskCache(new ArrayList<>(this.taskStore.values()));
			try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(this.cacheFile))) {
				output.writeObject(cache);
			}
		}
	}

	private record RuntimeTaskContext(SimpleHttpDownloader downloader, Future<?> future) {
	}

	private static class TaskCache implements Serializable {
		@Serial
		private static final long serialVersionUID = 1L;
		private final List<DownloadTaskInfo> tasks;

		private TaskCache(List<DownloadTaskInfo> tasks) {
			this.tasks = tasks;
		}
	}
}
