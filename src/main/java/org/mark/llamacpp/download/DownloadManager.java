package org.mark.llamacpp.download;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 下载管理器，用于管理下载任务，支持任务状态持久化和恢复
 * 限制同时运行的下载任务为4个，使用线程池执行任务
 */
public class DownloadManager {
    
    private static final DownloadManager INSTANCE = new DownloadManager();
    private static final int MAX_CONCURRENT_DOWNLOADS = 4;
    
    private final TaskRepository repository;
    private final Map<String, DownloadProgressListener> listeners = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final ExecutorService downloadExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_DOWNLOADS);
    private final AtomicInteger activeDownloads = new AtomicInteger(0);
    private final Map<String, DownloadTask> pendingTasks = new ConcurrentHashMap<>();
    
    public static DownloadManager getInstance() {
        return INSTANCE;
    }
    
    private DownloadManager() {
        this.repository = new TaskRepository();
        this.init();
    }
    
	/**
	 * 初始化
	 */
	private void init() {
		// 启动进度监控定时任务
		this.scheduler.scheduleAtFixedRate(this::updateAllTasksProgress, 1, 1, TimeUnit.SECONDS);

		// 恢复未完成的任务
		this.resumePendingTasks();
	}
    
    /**
     * 创建下载任务
     * @param url 下载URL
     * @param path 保存路径
     * @return 任务ID
     */
    public String createTask(String url, String path) {
        return createTask(url, path, null);
    }
    
    /**
     * 创建下载任务
     * @param url 下载URL
     * @param path 保存路径
     * @param fileName 文件名（可选）
     * @return 任务ID
     */
	public String createTask(String url, String path, String fileName) {
		Objects.requireNonNull(url, "URL不能为空");
		Objects.requireNonNull(path, "路径不能为空");

		Path targetPath = Paths.get(path);
		DownloadTask task = new DownloadTask(url, targetPath, fileName);

		// 保存任务到仓库
		this.repository.saveTask(task);

		// 通知监听器
		notifyTaskCreated(task);

		// 如果当前活跃下载任务数未达到上限，则立即开始下载
		if (this.activeDownloads.get() < MAX_CONCURRENT_DOWNLOADS) {
			startDownload(task);
		} else {
			// 否则将任务加入等待队列
			this.pendingTasks.put(task.getTaskId(), task);
			task.setState(BasicDownloader.DownloadState.IDLE);
			this.repository.saveTask(task);
			System.out.println("任务 " + task.getTaskId() + " 已加入等待队列，当前活跃下载数: " + activeDownloads.get());
		}

		return task.getTaskId();
	}
    
    /**
     * 暂停指定任务
     * @param taskId 任务ID
     * @return 是否成功暂停
     */
    public boolean pause(String taskId) {
        DownloadTask task = repository.getTask(taskId);
        if (task == null) {
            return false;
        }
        
        if (task.getState() == BasicDownloader.DownloadState.DOWNLOADING) {
            task.setPaused(true);
            
            // 中断下载线程
            Thread downloadThread = task.getDownloadThread();
            if (downloadThread != null && downloadThread.isAlive()) {
                downloadThread.interrupt();
            }
            
            task.setState(BasicDownloader.DownloadState.IDLE);
            this.repository.saveTask(task);
            
            // 减少活跃下载计数
            this.activeDownloads.decrementAndGet();
            
            // 通知监听器
            notifyTaskPaused(task);
            
            // 尝试启动等待队列中的任务
            processPendingTasks();
            
            return true;
        }
        
        return false;
    }
    
	/**
	 * 恢复指定任务
	 * 
	 * @param taskId 任务ID
	 * @return 是否成功恢复
	 */
	public boolean resume(String taskId) {
		DownloadTask task = this.repository.getTask(taskId);
		if (task == null) {
			return false;
		}

		if (task.getState() == BasicDownloader.DownloadState.IDLE
				|| task.getState() == BasicDownloader.DownloadState.FAILED) {

			task.setPaused(false);

			// 如果当前活跃下载任务数未达到上限，则立即开始下载
			if (this.activeDownloads.get() < MAX_CONCURRENT_DOWNLOADS) {
				startDownload(task);
			} else {
				// 否则将任务加入等待队列
				this.pendingTasks.put(task.getTaskId(), task);
				System.out.println("任务 " + task.getTaskId() + " 已加入等待队列，当前活跃下载数: " + this.activeDownloads.get());
			}

			// 通知监听器
			notifyTaskResumed(task);
			return true;
		}

		return false;
	}
    
	/**
	 * 删除指定任务
	 * 
	 * @param taskId 任务ID
	 * @return 是否成功删除
	 */
	public boolean delete(String taskId) {
		DownloadTask task = repository.getTask(taskId);
		if (task == null) {
			return false;
		}

		// 如果任务正在下载，先暂停
		if (task.getState() == BasicDownloader.DownloadState.DOWNLOADING) {
			pause(taskId);
		}

		// 从等待队列中移除
		this.pendingTasks.remove(taskId);

		// 从仓库中删除
		this.repository.deleteTask(taskId);

		// 通知监听器
		this.notifyTaskDeleted(task);

		return true;
	}
    
    /**
     * 获取任务信息
     * @param taskId 任务ID
     * @return 任务信息，如果不存在则返回null
     */
    public DownloadTask getTask(String taskId) {
        return this.repository.getTask(taskId);
    }
    
    /**
     * 获取所有任务
     * @return 任务列表
     */
    public List<DownloadTask> getAllTasks() {
        return this.repository.getAllTasks();
    }
    
    /**
     * 添加进度监听器
     * @param listener 监听器
     * @return 监听器ID
     */
    public String addProgressListener(DownloadProgressListener listener) {
        String listenerId = UUID.randomUUID().toString();
        this.listeners.put(listenerId, listener);
        return listenerId;
    }
    
    /**
     * 移除进度监听器
     * @param listenerId 监听器ID
     * @return 是否成功移除
     */
    public boolean removeProgressListener(String listenerId) {
        return this.listeners.remove(listenerId) != null;
    }
    
	/**
	 * 开始下载任务
	 */
	private void startDownload(DownloadTask task) {
		// 增加活跃下载计数
		this.activeDownloads.incrementAndGet();

		// 使用线程池执行下载任务
		this.downloadExecutor.submit(() -> {
			try {
				task.setState(BasicDownloader.DownloadState.PREPARING);
				notifyStateChanged(task, BasicDownloader.DownloadState.IDLE, BasicDownloader.DownloadState.PREPARING);

				// 创建下载器
				BasicDownloader downloader = new BasicDownloader(task.getUrl(), task.getFullTargetPath());
				task.setDownloader(downloader);

				// 获取文件信息
				downloader.requestHead();
				BasicDownloader.DownloadProgress headProgress = downloader.getProgress();

				task.setTotalBytes(headProgress.getTotalBytes());
				task.setState(BasicDownloader.DownloadState.DOWNLOADING);
				this.repository.saveTask(task);
				notifyStateChanged(task, BasicDownloader.DownloadState.PREPARING,
						BasicDownloader.DownloadState.DOWNLOADING);

				// 开始下载
				downloader.download();

				// 下载完成
				task.setState(BasicDownloader.DownloadState.COMPLETED);
				this.repository.saveTask(task);
				notifyTaskCompleted(task);

			} catch (Exception e) {
				// 检查是否是暂停导致的异常
				if (task.isPaused()) {
					task.setState(BasicDownloader.DownloadState.IDLE);
					this.repository.saveTask(task);
				} else {
					task.setState(BasicDownloader.DownloadState.FAILED);
					task.setErrorMessage(e.getMessage());
					this.repository.saveTask(task);
					notifyTaskFailed(task, e.getMessage());
				}
			} finally {
				// 减少活跃下载计数
				this.activeDownloads.decrementAndGet();
				task.setDownloadThread(null);

				// 尝试启动等待队列中的任务
				processPendingTasks();
			}
		});
	}
    
	/**
	 * 处理等待队列中的任务
	 */
	private void processPendingTasks() {
		while (this.activeDownloads.get() < MAX_CONCURRENT_DOWNLOADS && !this.pendingTasks.isEmpty()) {
			// 获取等待队列中的第一个任务
			String taskId = this.pendingTasks.keySet().iterator().next();
			DownloadTask task = this.pendingTasks.remove(taskId);

			if (task != null && task.getState() == BasicDownloader.DownloadState.IDLE) {
				startDownload(task);
				System.out.println("从等待队列启动任务: " + task.getTaskId() + ", 当前活跃下载数: " + this.activeDownloads.get());
			}
		}
	}
    
	/**
	 * 更新所有任务的进度
	 */
	private void updateAllTasksProgress() {
		for (DownloadTask task : repository.getAllTasks()) {
			if (task.getState() == BasicDownloader.DownloadState.DOWNLOADING && task.getDownloader() != null) {
				BasicDownloader.DownloadProgress progress = task.getDownloader().getProgress();

				// 更新任务进度信息
				task.setDownloadedBytes(progress.getDownloadedBytes());
				task.setPartsTotal(progress.getPartsTotal());
				task.setPartsCompleted(progress.getPartsCompleted());

				// 通知进度更新
				notifyProgressUpdated(task, progress);
			}
		}
	}
    
	/**
	 * 恢复未完成的任务
	 */
	private void resumePendingTasks() {
		List<DownloadTask> unfinishedTasks = new ArrayList<>();
		for (DownloadTask task : repository.getAllTasks()) {
			if (task.getState() == BasicDownloader.DownloadState.IDLE
					|| task.getState() == BasicDownloader.DownloadState.FAILED) {
				unfinishedTasks.add(task);
			}
		}

		// 按照创建时间排序，先创建的任务优先恢复
		unfinishedTasks.sort((t1, t2) -> t1.getCreatedAt().compareTo(t2.getCreatedAt()));

		// 启动前MAX_CONCURRENT_DOWNLOADS个任务
		int startedCount = 0;
		for (DownloadTask task : unfinishedTasks) {
			if (startedCount >= MAX_CONCURRENT_DOWNLOADS) {
				pendingTasks.put(task.getTaskId(), task);
				System.out.println("任务 " + task.getTaskId() + " 已加入等待队列");
			} else {
				startDownload(task);
				startedCount++;
				System.out.println("恢复任务: " + task.getTaskId());
			}
		}
	}
    
    // 通知方法
    
    private void notifyTaskCreated(DownloadTask task) {
        for (DownloadProgressListener listener : listeners.values()) {
            try {
                listener.onStateChanged(task, null, task.getState());
            } catch (Exception e) {
                System.err.println("通知监听器失败: " + e.getMessage());
            }
        }
    }
    
    private void notifyStateChanged(DownloadTask task, BasicDownloader.DownloadState oldState, BasicDownloader.DownloadState newState) {
        for (DownloadProgressListener listener : listeners.values()) {
            try {
                listener.onStateChanged(task, oldState, newState);
            } catch (Exception e) {
                System.err.println("通知监听器失败: " + e.getMessage());
            }
        }
    }
    
    private void notifyProgressUpdated(DownloadTask task, BasicDownloader.DownloadProgress progress) {
        for (DownloadProgressListener listener : listeners.values()) {
            try {
                listener.onProgressUpdated(task, progress);
            } catch (Exception e) {
                System.err.println("通知监听器失败: " + e.getMessage());
            }
        }
    }
    
    private void notifyTaskCompleted(DownloadTask task) {
        for (DownloadProgressListener listener : listeners.values()) {
            try {
                listener.onTaskCompleted(task);
            } catch (Exception e) {
                System.err.println("通知监听器失败: " + e.getMessage());
            }
        }
    }
    
    private void notifyTaskFailed(DownloadTask task, String error) {
        for (DownloadProgressListener listener : listeners.values()) {
            try {
                listener.onTaskFailed(task, error);
            } catch (Exception e) {
                System.err.println("通知监听器失败: " + e.getMessage());
            }
        }
    }
    
    private void notifyTaskPaused(DownloadTask task) {
        for (DownloadProgressListener listener : listeners.values()) {
            try {
                listener.onTaskPaused(task);
            } catch (Exception e) {
                System.err.println("通知监听器失败: " + e.getMessage());
            }
        }
    }
    
    private void notifyTaskResumed(DownloadTask task) {
        for (DownloadProgressListener listener : listeners.values()) {
            try {
                listener.onTaskResumed(task);
            } catch (Exception e) {
                System.err.println("通知监听器失败: " + e.getMessage());
            }
        }
    }
    
    private void notifyTaskDeleted(DownloadTask task) {
        for (DownloadProgressListener listener : listeners.values()) {
            try {
                listener.onTaskFailed(task, "任务已删除");
            } catch (Exception e) {
                System.err.println("通知监听器失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 获取当前活跃下载任务数
     */
    public int getActiveDownloadsCount() {
        return activeDownloads.get();
    }
    
    /**
     * 获取等待队列中的任务数
     */
    public int getPendingTasksCount() {
        return pendingTasks.size();
    }
    
    /**
     * 获取最大并发下载数
     */
    public int getMaxConcurrentDownloads() {
        return MAX_CONCURRENT_DOWNLOADS;
    }
    
    /**
     * 关闭下载管理器，释放资源
     */
    public void shutdown() {
        // 暂停所有正在下载的任务
        for (DownloadTask task : repository.getAllTasks()) {
            if (task.getState() == BasicDownloader.DownloadState.DOWNLOADING) {
                pause(task.getTaskId());
            }
        }
        
        // 关闭下载线程池
        downloadExecutor.shutdown();
        try {
            if (!downloadExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                downloadExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            downloadExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // 关闭调度器
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
