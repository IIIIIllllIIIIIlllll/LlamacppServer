package org.mark.llamacpp.download;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 	基本下载器的实现。本来想自己改的，做了一半背疼。画了个工作流程让AI自己做了。
 */
public class BasicDownloader {
	
	public enum DownloadState {
		IDLE,
		PREPARING,
		DOWNLOADING,
		MERGING,
		VERIFYING,
		COMPLETED,
		FAILED
	}
	
	public static final class DownloadProgress {
		private final DownloadState state;
		private final URI sourceUri;
		private final URI finalUri;
		private final Path targetFile;
		private final long totalBytes;
		private final long downloadedBytes;
		private final int partsTotal;
		private final int partsCompleted;
		private final long startedAtNanos;
		private final long finishedAtNanos;
		private final String errorMessage;
		
		private DownloadProgress(
				DownloadState state,
				URI sourceUri,
				URI finalUri,
				Path targetFile,
				long totalBytes,
				long downloadedBytes,
				int partsTotal,
				int partsCompleted,
				long startedAtNanos,
				long finishedAtNanos,
				String errorMessage) {
			this.state = state;
			this.sourceUri = sourceUri;
			this.finalUri = finalUri;
			this.targetFile = targetFile;
			this.totalBytes = totalBytes;
			this.downloadedBytes = downloadedBytes;
			this.partsTotal = partsTotal;
			this.partsCompleted = partsCompleted;
			this.startedAtNanos = startedAtNanos;
			this.finishedAtNanos = finishedAtNanos;
			this.errorMessage = errorMessage;
		}
		
		public DownloadState getState() {
			return state;
		}
		
		public URI getSourceUri() {
			return sourceUri;
		}
		
		public URI getFinalUri() {
			return finalUri;
		}
		
		public Path getTargetFile() {
			return targetFile;
		}
		
		public long getTotalBytes() {
			return totalBytes;
		}
		
		public long getDownloadedBytes() {
			return downloadedBytes;
		}
		
		public int getPartsTotal() {
			return partsTotal;
		}
		
		public int getPartsCompleted() {
			return partsCompleted;
		}
		
		public String getErrorMessage() {
			return errorMessage;
		}
		
		public double getProgressRatio() {
			if (totalBytes <= 0) {
				return 0.0;
			}
			double r = (double) downloadedBytes / (double) totalBytes;
			if (r < 0.0) {
				return 0.0;
			}
			if (r > 1.0) {
				return 1.0;
			}
			return r;
		}
		
		public long getElapsedMillis() {
			long start = startedAtNanos;
			if (start <= 0) {
				return 0;
			}
			long end = finishedAtNanos > 0 ? finishedAtNanos : System.nanoTime();
			long diff = end - start;
			if (diff < 0) {
				return 0;
			}
			return diff / 1_000_000L;
		}
		
		public long getSpeedBytesPerSecond() {
			long elapsedMillis = getElapsedMillis();
			if (elapsedMillis <= 0) {
				return 0;
			}
			return (downloadedBytes * 1000L) / elapsedMillis;
		}
	}
	
	/**
	 * 	输入的原始地址
	 */
	private URI sourceUri;
	
	/**
	 * 	经过跳转后的最终地址
	 */
	private URI finalUri;
	
	/**
	 * 	目标文件。
	 */
	private Path targetFile;
	
	/**
	 * 	最大重定向次数
	 */
	private int maxRedirects = 5;
	private int parallelism = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
	private long minPartSizeBytes = 8L * 1024 * 1024;
	private int maxRetries = 5;
	private Duration requestTimeout = Duration.ofSeconds(60);
	private String userAgent = "llama-server BasicDownloader";
	
	private long contentLength = -1;
	private String etag;
	boolean rangeSupported;
	
	private final HttpClient httpClient;
	
	private final AtomicLong downloadedBytes = new AtomicLong(0);
	private final AtomicInteger partsTotal = new AtomicInteger(0);
	private final AtomicInteger partsCompleted = new AtomicInteger(0);
	private volatile DownloadState state = DownloadState.IDLE;
	private volatile long startedAtNanos;
	private volatile long finishedAtNanos;
	private volatile String errorMessage;
	private final AtomicBoolean stopRequested = new AtomicBoolean(false);
	private final Set<AutoCloseable> activeResources = ConcurrentHashMap.newKeySet();
	private volatile ExecutorService activePool;
	
	
	
	public BasicDownloader(String uri) {
		this(URI.create(uri), Path.of(guessFileName(URI.create(uri))));
	}
	
	public BasicDownloader(String uri, Path targetFile) {
		this(URI.create(uri), targetFile);
	}
	
	public BasicDownloader(URI uri, Path targetFile) {
		this.sourceUri = Objects.requireNonNull(uri, "uri");
		this.targetFile = Objects.requireNonNull(targetFile, "targetFile");
		this.httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
	}
	
	/**
	 * 	
	 * @param parallelism
	 */
	public void setParallelism(int parallelism) {
		if (parallelism < 1) {
			throw new IllegalArgumentException("parallelism must be >= 1");
		}
		this.parallelism = parallelism;
	}
	
	
	/**
	 * 	
	 * @param minPartSizeBytes
	 */
	public void setMinPartSizeBytes(long minPartSizeBytes) {
		if (minPartSizeBytes < 1) {
			throw new IllegalArgumentException("minPartSizeBytes must be >= 1");
		}
		this.minPartSizeBytes = minPartSizeBytes;
	}
	
	/**
	 * 	最大重试次数
	 * @param maxRetries
	 */
	public void setMaxRetries(int maxRetries) {
		if (maxRetries < 0) {
			throw new IllegalArgumentException("maxRetries must be >= 0");
		}
		this.maxRetries = maxRetries;
	}
	
	/**
	 * 	请求超时时间
	 * @param requestTimeout
	 */
	public void setRequestTimeout(Duration requestTimeout) {
		this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
	}
	
	/**
	 * 	用户标识
	 * @param userAgent
	 */
	public void setUserAgent(String userAgent) {
		this.userAgent = Objects.requireNonNull(userAgent, "userAgent");
	}
	
	public URI getSourceUri() {
		return this.sourceUri;
	}
	
	public URI getFinalUri() {
		return this.finalUri;
	}
	
	public void setFinalUri(URI finalUri) {
		this.finalUri = finalUri;
	}
	
	public long getContentLength() {
		return this.contentLength;
	}
	
	public void setContentLenght(long contentLength) {
		this.contentLength = contentLength;
	}
	
	public String getEtag() {
		return this.etag;
	}
	
	public void setEtag(String etag) {
		this.etag = etag;
	}
	
	public boolean isRangeSupported() {
		return this.rangeSupported;
	}
	
	public DownloadState getState() {
		return this.state;
	}
	
	public long getDownloadedBytes() {
		return this.downloadedBytes.get();
	}
	
	public DownloadProgress getProgress() {
		return new DownloadProgress(
				this.state,
				this.sourceUri,
				this.finalUri,
				this.targetFile,
				this.contentLength,
				this.downloadedBytes.get(),
				this.partsTotal.get(),
				this.partsCompleted.get(),
				this.startedAtNanos,
				this.finishedAtNanos,
				this.errorMessage);
	}
	
	public Path getTargetFile() {
		return this.targetFile;
	}
	
	public void setTargetFile(Path targetFile) {
		this.targetFile = Objects.requireNonNull(targetFile, "targetFile");
	}
	
	public void requestHead() throws IOException, URISyntaxException, InterruptedException {
		this.resetStop();
		this.resetProgress();
		this.startedAtNanos = System.nanoTime();
		this.finishedAtNanos = 0;
		this.errorMessage = null;
		
		try {
			this.prepare();
			this.state = DownloadState.IDLE;
		} catch (InterruptedException e) {
			this.state = DownloadState.IDLE;
			this.errorMessage = e.getMessage();
			Thread.currentThread().interrupt();
			throw e;
		} catch (IOException | URISyntaxException e) {
			this.state = DownloadState.FAILED;
			this.errorMessage = e.getMessage();
			throw e;
		} catch (RuntimeException e) {
			this.state = DownloadState.FAILED;
			this.errorMessage = e.getMessage();
			throw e;
		} finally {
			this.finishedAtNanos = System.nanoTime();
		}
	}
	
	
	
	/**
	 * 	开始下载操作。
	 * @throws IOException
	 * @throws URISyntaxException
	 * @throws InterruptedException
	 */
	public void download() throws IOException, URISyntaxException, InterruptedException {
		this.resetStop();
		this.resetProgress();
		this.startedAtNanos = System.nanoTime();
		this.finishedAtNanos = 0;
		this.errorMessage = null;
		
		try {
			this.prepare();
			
			if (this.contentLength <= 0) {
				throw new IOException("无法获取文件大小");
			}
			
			ensureParentDirectory(this.targetFile);
			
			this.state = DownloadState.DOWNLOADING;
			if (this.rangeSupported && this.parallelism > 1) {
				this.downloadMultipart();
			} else {
				this.downloadSingle();
			}
			
			this.state = DownloadState.VERIFYING;
			this.verifyIntegrity();
			
			this.state = DownloadState.COMPLETED;
		} catch (InterruptedException e) {
			this.state = DownloadState.IDLE;
			this.errorMessage = e.getMessage();
			this.finishedAtNanos = System.nanoTime();
			Thread.currentThread().interrupt();
			throw e;
		} catch (IOException | URISyntaxException e) {
			this.state = DownloadState.FAILED;
			this.errorMessage = e.getMessage();
			this.finishedAtNanos = System.nanoTime();
			throw e;
		} catch (RuntimeException e) {
			this.state = DownloadState.FAILED;
			this.errorMessage = e.getMessage();
			this.finishedAtNanos = System.nanoTime();
			throw e;
		} finally {
			if (this.finishedAtNanos == 0) {
				this.finishedAtNanos = System.nanoTime();
			}
		}
	}
	
	/**
	 * 	断点续传下载操作。
	 * @param existingDownloadedBytes 已下载的字节数
	 * @throws IOException
	 * @throws URISyntaxException
	 * @throws InterruptedException
	 */
	public void resume(long existingDownloadedBytes) throws IOException, URISyntaxException, InterruptedException {
		this.resetStop();
		this.downloadedBytes.set(existingDownloadedBytes);
		this.startedAtNanos = System.nanoTime();
		this.finishedAtNanos = 0;
		this.errorMessage = null;
		
		try {
			// 如果finalUri还未设置，需要重新解析
			if (this.finalUri == null) {
				this.prepare();
			} else {
				// 验证文件是否仍然有效
				this.validateRemoteFile();
			}
			
			if (this.contentLength <= 0) {
				throw new IOException("无法获取文件大小");
			}
			
			ensureParentDirectory(this.targetFile);
			
			this.state = DownloadState.DOWNLOADING;
			if (this.rangeSupported && this.parallelism > 1) {
				this.resumeMultipart();
			} else {
				this.resumeSingle();
			}
			
			this.state = DownloadState.VERIFYING;
			this.verifyIntegrity();
			
			this.state = DownloadState.COMPLETED;
		} catch (InterruptedException e) {
			this.state = DownloadState.IDLE;
			this.errorMessage = e.getMessage();
			this.finishedAtNanos = System.nanoTime();
			Thread.currentThread().interrupt();
			throw e;
		} catch (IOException | URISyntaxException e) {
			this.state = DownloadState.FAILED;
			this.errorMessage = e.getMessage();
			this.finishedAtNanos = System.nanoTime();
			throw e;
		} catch (RuntimeException e) {
			this.state = DownloadState.FAILED;
			this.errorMessage = e.getMessage();
			this.finishedAtNanos = System.nanoTime();
			throw e;
		} finally {
			if (this.finishedAtNanos == 0) {
				this.finishedAtNanos = System.nanoTime();
			}
		}
	}
	
	/**
	 * 	前期准备
	 * @throws IOException
	 * @throws URISyntaxException
	 * @throws InterruptedException
	 */
	private void prepare() throws IOException, URISyntaxException, InterruptedException {
		this.checkStop();
		this.state = DownloadState.PREPARING;
		this.finalUri = this.resolveFinalUri(this.sourceUri);
		
		HttpResponse<Void> headResponse = this.sendHeadOrFallback(this.finalUri);
		
		if (headResponse == null) {
			throw new IOException("无法获取文件头信息");
		}
		
		this.contentLength = parseContentLength(headResponse.headers().map());
		if (this.contentLength <= 0) {
			throw new IOException("无法获取文件大小");
		}
		
		this.etag = firstHeaderValue(headResponse.headers().map(), "etag");
		
		HttpResponse<Void> rangeProbe = this.sendRangeProbe(this.finalUri);
		this.rangeSupported = rangeProbe != null && rangeProbe.statusCode() == 206;
	}
	
	/**
	 * 	重置进度
	 */
	private void resetProgress() {
		this.downloadedBytes.set(0);
		this.partsTotal.set(0);
		this.partsCompleted.set(0);
		this.state = DownloadState.IDLE;
	}
	
	/**
	 * 	验证远程文件是否仍然有效
	 * @throws IOException
	 * @throws InterruptedException
	 */
	private void validateRemoteFile() throws IOException, InterruptedException {
		this.checkStop();
		HttpResponse<Void> headResponse = this.sendHeadOrFallback(this.finalUri);
		if (headResponse == null) {
			throw new IOException("无法验证远程文件");
		}
		
		// 检查ETag是否匹配
		String currentEtag = firstHeaderValue(headResponse.headers().map(), "etag");
		if (this.etag != null && currentEtag != null && !normalizeEtag(this.etag).equals(normalizeEtag(currentEtag))) {
			throw new IOException("远程文件已更改，无法断点续传");
		}
		
		// 检查内容长度是否匹配
		long remoteContentLength = parseContentLength(headResponse.headers().map());
		if (this.contentLength > 0 && remoteContentLength > 0 && this.contentLength != remoteContentLength) {
			throw new IOException("远程文件大小已更改，无法断点续传");
		}
	}
	
	/**
	 * 	单线程断点续传
	 * @throws IOException
	 * @throws InterruptedException
	 */
	private void resumeSingle() throws IOException, InterruptedException {
		this.partsTotal.set(1);
		this.partsCompleted.set(0);
		
		// 检查已存在的文件大小
		long existingFileSize = 0;
		if (Files.exists(this.targetFile)) {
			existingFileSize = Files.size(this.targetFile);
		}
		
		// 如果已下载的文件大小超过总大小，则重新下载
		if (existingFileSize >= this.contentLength) {
			Files.deleteIfExists(this.targetFile);
			existingFileSize = 0;
		}
		
		// 如果没有已下载的内容，则使用普通下载
		if (existingFileSize == 0) {
			downloadSingle();
			return;
		}
		
		this.downloadedBytes.set(existingFileSize);
		
		// 使用Range请求继续下载
		HttpRequest get = HttpRequest.newBuilder()
				.uri(this.finalUri)
				.timeout(this.requestTimeout)
				.header("User-Agent", this.userAgent)
				.header("Range", "bytes=" + existingFileSize + "-")
				.GET()
				.build();
		
		HttpResponse<InputStream> response = this.httpClient.send(get, BodyHandlers.ofInputStream());
		if (response.statusCode() != 206) {
			// 如果服务器不支持范围请求，则重新下载
			Files.deleteIfExists(this.targetFile);
			downloadSingle();
			return;
		}
		
		try (InputStream in = new BufferedInputStream(response.body());
				OutputStream out = new BufferedOutputStream(new FileOutputStream(this.targetFile.toString(), true))) {
			this.activeResources.add(in);
			this.activeResources.add(out);
			byte[] buffer = new byte[1024 * 256];
			int read;
			try {
				while ((read = in.read(buffer)) != -1) {
					this.checkStop();
					out.write(buffer, 0, read);
					this.downloadedBytes.addAndGet(read);
				}
			} catch (IOException e) {
				if (this.stopRequested.get() || Thread.currentThread().isInterrupted()) {
					throw new InterruptedException("下载已暂停");
				}
				throw e;
			} finally {
				this.activeResources.remove(in);
				this.activeResources.remove(out);
			}
		}
		
		long size = Files.size(this.targetFile);
		if (this.contentLength > 0 && size != this.contentLength) {
			throw new IOException("下载文件大小不匹配，期望: " + this.contentLength + " 实际: " + size);
		}
		
		this.partsCompleted.set(1);
	}
	
	/**
	 * 	多线程断点续传
	 * @throws IOException
	 * @throws InterruptedException
	 */
	private void resumeMultipart() throws IOException, InterruptedException {
		this.downloadedBytes.set(0);
		List<Part> parts = splitParts(this.contentLength, this.parallelism, this.minPartSizeBytes);
		this.partsTotal.set(parts.size());
		this.partsCompleted.set(0);
		
		// 检查已存在的分片文件
		List<Path> partFiles = new ArrayList<>();
		List<Part> remainingParts = new ArrayList<>();
		int completedParts = 0;
		
		for (int i = 0; i < parts.size(); i++) {
			Path partFile = this.targetFile.resolveSibling(this.targetFile.getFileName().toString() + ".part" + i);
			partFiles.add(partFile);
			Part part = parts.get(i);
			if (Files.exists(partFile)) {
				long partSize = Files.size(partFile);
				if (partSize == part.length()) {
					// 分片已完成
					completedParts++;
					this.downloadedBytes.addAndGet(partSize);
					this.partsCompleted.incrementAndGet();
				} else if (partSize > 0 && partSize < part.length()) {
					this.downloadedBytes.addAndGet(partSize);
					remainingParts.add(part);
				} else {
					// 分片不完整，需要重新下载
					Files.deleteIfExists(partFile);
					remainingParts.add(part);
				}
			} else {
				// 分片不存在，需要下载
				remainingParts.add(part);
			}
		}
		
		// 如果所有分片都已完成，直接合并
		if (completedParts == parts.size()) {
			this.state = DownloadState.MERGING;
			this.mergeParts(parts, partFiles, this.targetFile);
			
			for (Path p : partFiles) {
				Files.deleteIfExists(p);
			}
			
			long size = Files.size(this.targetFile);
			if (size != this.contentLength) {
				throw new IOException("下载文件大小不匹配，期望: " + this.contentLength + " 实际: " + size);
			}
			return;
		}
		
		// 确保目标文件存在并预分配空间
		if (!Files.exists(this.targetFile)) {
			this.preAllocateTargetFile(this.targetFile, this.contentLength);
		}
		
		// 下载剩余的分片
		ExecutorService pool = Executors.newFixedThreadPool(Math.min(this.parallelism, remainingParts.size()), Thread.ofVirtual().factory());
		this.activePool = pool;
		try {
			List<Future<Void>> futures = new ArrayList<>();
			for (int i = 0; i < parts.size(); i++) {
				Part part = parts.get(i);
				Path partFile = partFiles.get(i);
				
				// 跳过已完成的分片
				if (Files.exists(partFile) && Files.size(partFile) == part.length()) {
					continue;
				}
				
				futures.add(pool.submit(new PartDownloadTask(this.httpClient, this.finalUri, this.userAgent, this.requestTimeout, part, partFile, this.maxRetries, this.downloadedBytes, this.partsCompleted, this.stopRequested, this.activeResources)));
			}
			
			for (Future<Void> f : futures) {
				try {
					f.get();
				} catch (ExecutionException e) {
					Throwable cause = e.getCause();
					if (cause instanceof InterruptedException ie) {
						throw ie;
					}
					if (cause instanceof IOException io) {
						throw io;
					}
					if (cause instanceof RuntimeException re) {
						throw re;
					}
					throw new IOException(cause);
				}
			}
		} finally {
			pool.shutdownNow();
			this.activePool = null;
		}
		
		this.state = DownloadState.MERGING;
		this.mergeParts(parts, partFiles, this.targetFile);
		
		for (Path p : partFiles) {
			Files.deleteIfExists(p);
		}
		
		long size = Files.size(this.targetFile);
		if (size != this.contentLength) {
			throw new IOException("下载文件大小不匹配，期望: " + this.contentLength + " 实际: " + size);
		}
	}
	
	/**
	 * 	找到最终下载的地址
	 * @param initialUri
	 * @return
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws URISyntaxException
	 */
	private URI resolveFinalUri(URI initialUri) throws IOException, InterruptedException, URISyntaxException {
		URI current = initialUri;
		for (int i = 0; i < this.maxRedirects; i++) {
			this.checkStop();
			HttpRequest head = HttpRequest.newBuilder()
					.uri(current)
					.timeout(this.requestTimeout)
					.header("User-Agent", this.userAgent)
					.method("HEAD", HttpRequest.BodyPublishers.noBody())
					.build();
			
			HttpResponse<Void> response = this.httpClient.send(head, BodyHandlers.discarding());
			int code = response.statusCode();
			
			if (code == 405 || code == 501) {
				this.checkStop();
				HttpRequest get = HttpRequest.newBuilder()
						.uri(current)
						.timeout(this.requestTimeout)
						.header("User-Agent", this.userAgent)
						.header("Range", "bytes=0-0")
						.GET()
						.build();
				HttpResponse<InputStream> getResponse = this.httpClient.send(get, BodyHandlers.ofInputStream());
				try (InputStream ignored = getResponse.body()) {
					code = getResponse.statusCode();
					if (code >= 300 && code <= 399) {
						String location = firstHeaderValue(getResponse.headers().map(), "location");
						if (location == null || location.isBlank()) {
							throw new IOException("重定向响应缺少Location头");
						}
						current = current.resolve(location);
						continue;
					}
				}
				return current;
			}
			
			if (code >= 300 && code <= 399) {
				String location = firstHeaderValue(response.headers().map(), "location");
				if (location == null || location.isBlank()) {
					throw new IOException("重定向响应缺少Location头");
				}
				URI next = current.resolve(location);
				current = next;
				continue;
			}
			
			return current;
		}
		throw new IOException("重定向次数超过限制: " + this.maxRedirects);
	}
	
	/**
	 * 	发送请求头
	 * @param uri
	 * @return
	 * @throws IOException
	 * @throws InterruptedException
	 */
	private HttpResponse<Void> sendHeadOrFallback(URI uri) throws IOException, InterruptedException {
		this.checkStop();
		HttpRequest head = HttpRequest.newBuilder()
				.uri(uri)
				.timeout(this.requestTimeout)
				.header("User-Agent", this.userAgent)
				.header("Range", "bytes=0-0")
				.method("HEAD", HttpRequest.BodyPublishers.noBody())
				.build();
		
		HttpResponse<Void> headResponse = this.httpClient.send(head, BodyHandlers.discarding());
		if (headResponse.statusCode() >= 200 && headResponse.statusCode() <= 299) {
			return headResponse;
		}
//		HttpRequest rangeGet = HttpRequest.newBuilder()
//				.uri(uri)
//				.timeout(this.requestTimeout)
//				.header("User-Agent", this.userAgent)
//				.header("Range", "bytes=0-0")
//				.method("GET", HttpRequest.BodyPublishers.noBody())
//				.build();
//		HttpResponse<Void> rangeResponse = this.httpClient.send(rangeGet, BodyHandlers.discarding());
//		if (rangeResponse.statusCode() == 206 || rangeResponse.statusCode() == 200) {
//			return rangeResponse;
//		}
		return null;
	}
	
	/**
	 * 	发送文件范围的测试请求
	 * @param uri
	 * @return
	 * @throws IOException
	 * @throws InterruptedException
	 */
	private HttpResponse<Void> sendRangeProbe(URI uri) throws IOException, InterruptedException {
		this.checkStop();
		HttpRequest rangeTest = HttpRequest.newBuilder()
				.uri(uri)
				.timeout(this.requestTimeout)
				.header("User-Agent", this.userAgent)
				.header("Range", "bytes=0-0")
				.method("GET", HttpRequest.BodyPublishers.noBody())
				.build();
		return this.httpClient.send(rangeTest, BodyHandlers.discarding());
	}
	
	
	/**
	 * 	不支持端点续传，只能单独下载。
	 * @throws IOException
	 * @throws InterruptedException
	 */
	private void downloadSingle() throws IOException, InterruptedException {
		this.partsTotal.set(1);
		this.partsCompleted.set(0);
		this.checkStop();
		
		HttpRequest get = HttpRequest.newBuilder()
				.uri(this.finalUri)
				.timeout(this.requestTimeout)
				.header("User-Agent", this.userAgent)
				.GET()
				.build();
		
		HttpResponse<InputStream> response = this.httpClient.send(get, BodyHandlers.ofInputStream());
		if (response.statusCode() != 200 && response.statusCode() != 206) {
			throw new IOException("下载失败，HTTP状态码: " + response.statusCode());
		}
		
		try (InputStream in = new BufferedInputStream(response.body());
				OutputStream out = new BufferedOutputStream(new FileOutputStream(this.targetFile.toString(), false))) {
			this.activeResources.add(in);
			this.activeResources.add(out);
			byte[] buffer = new byte[1024 * 256];
			int read;
			try {
				while ((read = in.read(buffer)) != -1) {
					this.checkStop();
					out.write(buffer, 0, read);
					this.downloadedBytes.addAndGet(read);
				}
			} catch (IOException e) {
				if (this.stopRequested.get() || Thread.currentThread().isInterrupted()) {
					throw new InterruptedException("下载已暂停");
				}
				throw e;
			} finally {
				this.activeResources.remove(in);
				this.activeResources.remove(out);
			}
		}
		
		long size = Files.size(this.targetFile);
		if (this.contentLength > 0 && size != this.contentLength) {
			throw new IOException("下载文件大小不匹配，期望: " + this.contentLength + " 实际: " + size);
		}
		
		this.partsCompleted.set(1);
	}
	
	/**
	 * 	支持断点续传，多线程下载
	 * @throws IOException
	 * @throws InterruptedException
	 */
	private void downloadMultipart() throws IOException, InterruptedException {
		List<Part> parts = splitParts(this.contentLength, this.parallelism, this.minPartSizeBytes);
		this.partsTotal.set(parts.size());
		this.partsCompleted.set(0);
		this.preAllocateTargetFile(this.targetFile, this.contentLength);
		
		List<Path> partFiles = new ArrayList<>();
		for (int i = 0; i < parts.size(); i++) {
			partFiles.add(this.targetFile.resolveSibling(this.targetFile.getFileName().toString() + ".part" + i));
		}
		
		ExecutorService pool = Executors.newFixedThreadPool(Math.min(this.parallelism, parts.size()), Thread.ofVirtual().factory());
		this.activePool = pool;
		try {
			List<Future<Void>> futures = new ArrayList<>();
			for (int i = 0; i < parts.size(); i++) {
				Part part = parts.get(i);
				Path partFile = partFiles.get(i);
				futures.add(pool.submit(new PartDownloadTask(this.httpClient, this.finalUri, this.userAgent, this.requestTimeout, part, partFile, this.maxRetries, this.downloadedBytes, this.partsCompleted, this.stopRequested, this.activeResources)));
			}
			
			for (Future<Void> f : futures) {
				try {
					f.get();
				} catch (ExecutionException e) {
					Throwable cause = e.getCause();
					if (cause instanceof InterruptedException ie) {
						throw ie;
					}
					if (cause instanceof IOException io) {
						throw io;
					}
					if (cause instanceof RuntimeException re) {
						throw re;
					}
					throw new IOException(cause);
				}
			}
		} finally {
			pool.shutdownNow();
			this.activePool = null;
		}
		
		this.state = DownloadState.MERGING;
		this.mergeParts(parts, partFiles, this.targetFile);
		
		for (Path p : partFiles) {
			Files.deleteIfExists(p);
		}
		
		long size = Files.size(this.targetFile);
		if (size != this.contentLength) {
			throw new IOException("下载文件大小不匹配，期望: " + this.contentLength + " 实际: " + size);
		}
	}
	
	/**
	 * 	准备分配本地缓存文件
	 * @param target
	 * @param size
	 * @throws IOException
	 */
	private void preAllocateTargetFile(Path target, long size) throws IOException {
		try (RandomAccessFile raf = new RandomAccessFile(target.toString(), "rw")) {
			raf.setLength(size);
		}
	}
	
	/**
	 * 	将下载后的文件合并。
	 * @param parts
	 * @param partFiles
	 * @param target
	 * @throws IOException
	 */
	private void mergeParts(List<Part> parts, List<Path> partFiles, Path target) throws IOException {
		List<PartWithFile> ordered = new ArrayList<>();
		for (int i = 0; i < parts.size(); i++) {
			ordered.add(new PartWithFile(parts.get(i), partFiles.get(i)));
		}
		ordered.sort(Comparator.comparingLong(p -> p.part.startInclusive));
		
		try (RandomAccessFile raf = new RandomAccessFile(target.toString(), "rw")) {
			for (PartWithFile pwf : ordered) {
				long expected = pwf.part.length();
				long actual = Files.size(pwf.file);
				if (actual != expected) {
					throw new IOException("分片大小不匹配: " + pwf.file.getFileName() + " 期望: " + expected + " 实际: " + actual);
				}
				
				raf.seek(pwf.part.startInclusive);
				try (InputStream in = new BufferedInputStream(Files.newInputStream(pwf.file))) {
					byte[] buffer = new byte[1024 * 256];
					int read;
					while ((read = in.read(buffer)) != -1) {
						raf.write(buffer, 0, read);
					}
				}
			}
		}
	}
	
	/**
	 * 	校验
	 * @throws IOException
	 * @throws InterruptedException
	 */
	private void verifyIntegrity() throws IOException, InterruptedException {
		this.checkStop();
		if (this.etag == null || this.etag.isBlank()) {
			return;
		}
		
		HttpResponse<Void> head = this.sendHeadOrFallback(this.finalUri);
		if (head == null) {
			return;
		}
		
		String latest = firstHeaderValue(head.headers().map(), "etag");
		if (latest == null || latest.isBlank()) {
			return;
		}
		
		if (!Objects.equals(normalizeEtag(this.etag), normalizeEtag(latest))) {
			throw new IOException("ETag校验失败");
		}
	}
	
	private static String normalizeEtag(String etag) {
		if (etag == null) {
			return null;
		}
		String t = etag.trim();
		if (t.startsWith("W/")) {
			t = t.substring(2).trim();
		}
		if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) {
			t = t.substring(1, t.length() - 1);
		}
		return t;
	}
	
	private static long parseContentLength(Map<String, List<String>> headers) {
		String value = firstHeaderValue(headers, "content-length");
		String contentRange = firstHeaderValue(headers, "content-range");
		long v1 = -1;
		long v2 = -1;
		if (value != null) {
			try {
				v1 = Long.parseLong(value.trim());
			} catch (NumberFormatException ignored) {
			}
		}
		if (contentRange != null) {
			v2 = parseTotalFromContentRange(contentRange);
		}
		return v1 > v2 ? v1 : v2;
	}
	
	private static Long parseTotalFromContentRange(String contentRange) {
		String v = contentRange.trim();
		int slash = v.lastIndexOf('/');
		if (slash < 0 || slash == v.length() - 1) {
			return null;
		}
		String totalPart = v.substring(slash + 1).trim();
		if ("*".equals(totalPart)) {
			return null;
		}
		try {
			return Long.parseLong(totalPart);
		} catch (NumberFormatException e) {
			return null;
		}
	}
	
	private static String firstHeaderValue(Map<String, List<String>> headers, String headerNameLowercase) {
		for (Map.Entry<String, List<String>> e : headers.entrySet()) {
			if (e.getKey() == null) {
				continue;
			}
			if (e.getKey().equalsIgnoreCase(headerNameLowercase)) {
				List<String> values = e.getValue();
				if (values == null || values.isEmpty()) {
					return null;
				}
				return values.get(0);
			}
		}
		return null;
	}
	
	private static void ensureParentDirectory(Path targetFile) throws IOException {
		Path parent = targetFile.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
	}
	
	private static String guessFileName(URI uri) {
		String path = uri.getPath();
		if (path == null || path.isBlank() || "/".equals(path)) {
			return "download.bin";
		}
		String name = path.substring(path.lastIndexOf('/') + 1);
		if (name == null || name.isBlank()) {
			return "download.bin";
		}
		return name;
	}
	
	private static List<Part> splitParts(long size, int parallelism, long minPartSizeBytes) {
		if (size <= 0) {
			throw new IllegalArgumentException("size must be > 0");
		}
		
		long suggestedParts = (size + minPartSizeBytes - 1) / minPartSizeBytes;
		int parts = (int) Math.max(1, Math.min(parallelism, Math.min(suggestedParts, 64)));
		
		List<Part> result = new ArrayList<>(parts);
		long start = 0;
		for (int i = 0; i < parts; i++) {
			long remaining = size - start;
			long partSize = remaining / (parts - i);
			if (partSize <= 0) {
				partSize = remaining;
			}
			long end = start + partSize - 1;
			if (i == parts - 1) {
				end = size - 1;
			}
			result.add(new Part(start, end));
			start = end + 1;
		}
		return result;
	}
	
	private static final class Part {
		private final long startInclusive;
		private final long endInclusive;
		
		private Part(long startInclusive, long endInclusive) {
			if (startInclusive < 0 || endInclusive < startInclusive) {
				throw new IllegalArgumentException("invalid range: " + startInclusive + "-" + endInclusive);
			}
			this.startInclusive = startInclusive;
			this.endInclusive = endInclusive;
		}
		
		private long length() {
			return this.endInclusive - this.startInclusive + 1;
		}
	}
	
	private static final class PartWithFile {
		private final Part part;
		private final Path file;
		
		private PartWithFile(Part part, Path file) {
			this.part = part;
			this.file = file;
		}
	}
	
	private static final class PartDownloadTask implements Callable<Void> {
		private final HttpClient httpClient;
		private final URI uri;
		private final String userAgent;
		private final Duration timeout;
		private final Part part;
		private final Path partFile;
		private final int maxRetries;
		private final AtomicLong downloadedBytes;
		private final AtomicInteger partsCompleted;
		private final AtomicBoolean stopRequested;
		private final Set<AutoCloseable> activeResources;
		private final long expectedBytes;
		private long existingBytesAtAttemptStart;
		
		private PartDownloadTask(
				HttpClient httpClient,
				URI uri,
				String userAgent,
				Duration timeout,
				Part part,
				Path partFile,
				int maxRetries,
				AtomicLong downloadedBytes,
				AtomicInteger partsCompleted,
				AtomicBoolean stopRequested,
				Set<AutoCloseable> activeResources) {
			this.httpClient = httpClient;
			this.uri = uri;
			this.userAgent = userAgent;
			this.timeout = timeout;
			this.part = part;
			this.partFile = partFile;
			this.maxRetries = maxRetries;
			this.downloadedBytes = downloadedBytes;
			this.partsCompleted = partsCompleted;
			this.stopRequested = stopRequested;
			this.activeResources = activeResources;
			this.expectedBytes = part.length();
		}
		
		@Override
		public Void call() throws Exception {
			ensureParentDirectory(this.partFile);
			
			long backoffMillis = 200;
			int attempt = 0;
			while (true) {
				this.checkStop();
				attempt++;
				long bytesThisAttempt = 0;
				try {
					bytesThisAttempt = this.downloadOnce();
					this.partsCompleted.incrementAndGet();
					return null;
				} catch (InterruptedException e) {
					throw e;
				} catch (IOException e) {
					if (this.stopRequested.get() || Thread.currentThread().isInterrupted()) {
						throw new InterruptedException("下载已暂停");
					}
					long rollbackBytes = bytesThisAttempt;
					if (e instanceof PartDownloadException pde) {
						rollbackBytes = pde.bytesWritten;
					}
					if (rollbackBytes > 0) {
						this.downloadedBytes.addAndGet(-rollbackBytes);
					}
					if (this.existingBytesAtAttemptStart > 0) {
						this.downloadedBytes.addAndGet(-this.existingBytesAtAttemptStart);
					}
					Files.deleteIfExists(this.partFile);
					this.existingBytesAtAttemptStart = 0;
					if (attempt > this.maxRetries) {
						throw e;
					}
					Thread.sleep(backoffMillis);
					backoffMillis = Math.min(backoffMillis * 2, 5_000);
				}
			}
		}
		
		private long downloadOnce() throws IOException, InterruptedException {
			this.checkStop();
			
			long existing = 0;
			if (Files.exists(this.partFile)) {
				existing = Files.size(this.partFile);
				if (existing >= this.expectedBytes) {
					return 0;
				}
			}
			this.existingBytesAtAttemptStart = existing;
			
			long startInclusive = this.part.startInclusive + existing;
			if (startInclusive > this.part.endInclusive) {
				return 0;
			}
			
			HttpRequest request = HttpRequest.newBuilder()
					.uri(this.uri)
					.timeout(this.timeout)
					.header("User-Agent", this.userAgent)
					.header("Range", "bytes=" + startInclusive + "-" + this.part.endInclusive)
					.GET()
					.build();
			
			HttpResponse<InputStream> response = this.httpClient.send(request, BodyHandlers.ofInputStream());
			if (response.statusCode() != 206) {
				throw new IOException("分片下载失败，HTTP状态码: " + response.statusCode());
			}
			
			long bytesWritten = 0;
			try (InputStream in = new BufferedInputStream(response.body());
					OutputStream out = new BufferedOutputStream(new FileOutputStream(this.partFile.toString(), existing > 0))) {
				this.activeResources.add(in);
				this.activeResources.add(out);
				byte[] buffer = new byte[1024 * 256];
				int read;
				try {
					while ((read = in.read(buffer)) != -1) {
						this.checkStop();
						out.write(buffer, 0, read);
						bytesWritten += read;
						this.downloadedBytes.addAndGet(read);
					}
				} catch (IOException e) {
					if (this.stopRequested.get() || Thread.currentThread().isInterrupted()) {
						throw new InterruptedException("下载已暂停");
					}
					throw e;
				} finally {
					this.activeResources.remove(in);
					this.activeResources.remove(out);
				}
			} catch (IOException e) {
				throw new PartDownloadException(e, bytesWritten);
			}
			
			long actual = Files.size(this.partFile);
			if (actual != this.expectedBytes) {
				throw new IOException("分片大小不匹配，期望: " + this.expectedBytes + " 实际: " + actual);
			}
			
			long expectedWritten = this.expectedBytes - existing;
			if (bytesWritten != expectedWritten) {
				throw new IOException("分片下载字节数不匹配，期望: " + expectedWritten + " 实际: " + bytesWritten);
			}
			
			return bytesWritten;
		}
		
		private void checkStop() throws InterruptedException {
			if (this.stopRequested.get() || Thread.currentThread().isInterrupted()) {
				throw new InterruptedException("下载已暂停");
			}
		}
	}
	
	private static final class PartDownloadException extends IOException {
		private static final long serialVersionUID = 1L;
		private final long bytesWritten;
		
		private PartDownloadException(IOException cause, long bytesWritten) {
			super(cause.getMessage(), cause);
			this.bytesWritten = bytesWritten;
		}
	}
	
	
	public void requestStop() {
		if (this.stopRequested.compareAndSet(false, true)) {
			ExecutorService pool = this.activePool;
			if (pool != null) {
				pool.shutdownNow();
			}
			for (AutoCloseable c : this.activeResources) {
				try {
					c.close();
				} catch (Exception ignored) {
				}
			}
		}
	}
	
	private void resetStop() {
		this.stopRequested.set(false);
		this.activeResources.clear();
		this.activePool = null;
	}
	
	private void checkStop() throws InterruptedException {
		if (this.stopRequested.get() || Thread.currentThread().isInterrupted()) {
			throw new InterruptedException("下载已暂停");
		}
	}
	
}
