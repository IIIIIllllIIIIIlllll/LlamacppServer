package org.mark.file.downloader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class SimpleHttpDownloader {

	private static final int DEFAULT_TIMEOUT_MS = 30_000;
	private static final int DEFAULT_MAX_REDIRECTS = 10;
	private static final int DEFAULT_BUFFER_SIZE = 64 * 1024;
	private static final long DEFAULT_PROGRESS_EMIT_INTERVAL_MS = 1_000L;
	private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) JavaDownloader/1.0";

	private final int threadCount;
	private final int timeoutMs;
	private final int maxRedirects;
	private final int bufferSize;
	private final String userAgent;
	private volatile boolean stopRequested;
	private volatile ProgressListener progressListener;

	public SimpleHttpDownloader(int threadCount) {
		this(threadCount, DEFAULT_TIMEOUT_MS, DEFAULT_MAX_REDIRECTS, DEFAULT_BUFFER_SIZE, DEFAULT_USER_AGENT);
	}

	public SimpleHttpDownloader(int threadCount, int timeoutMs, int maxRedirects, int bufferSize, String userAgent) {
		if (threadCount < 1) {
			throw new IllegalArgumentException("threadCount must be >= 1");
		}
		if (timeoutMs < 1) {
			throw new IllegalArgumentException("timeoutMs must be >= 1");
		}
		if (maxRedirects < 0) {
			throw new IllegalArgumentException("maxRedirects must be >= 0");
		}
		if (bufferSize < 1) {
			throw new IllegalArgumentException("bufferSize must be >= 1");
		}
		this.threadCount = threadCount;
		this.timeoutMs = timeoutMs;
		this.maxRedirects = maxRedirects;
		this.bufferSize = bufferSize;
		this.userAgent = Objects.requireNonNull(userAgent, "userAgent");
	}

	public DownloadResult download(String sourceUrl, Path targetFile) throws IOException {
		Objects.requireNonNull(sourceUrl, "sourceUrl");
		Objects.requireNonNull(targetFile, "targetFile");
		this.stopRequested = false;

		ProbeResult probe = probe(sourceUrl);
		if (probe.contentLength <= 0) {
			throw new IOException("无法获取文件大小: " + sourceUrl);
		}

		int actualThreads = Math.min(this.threadCount, (int) Math.min(Integer.MAX_VALUE, probe.contentLength));
		if (!probe.rangeSupported && actualThreads > 1) {
			throw new IOException("目标服务器不支持Range分段下载，无法执行多线程下载");
		}

		Path parent = targetFile.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}

		List<Range> ranges = splitRanges(probe.contentLength, Math.max(1, actualThreads));
		List<RangeState> rangeStates = createRangeStates(ranges);
		Path tempFile = buildTempFile(targetFile);
		Path metadataFile = buildMetadataFile(targetFile);
		ResumeMetadata metadata = loadMetadata(metadataFile);
		boolean resumed = applyResumeState(metadata, probe, rangeStates, tempFile);
		if (!resumed) {
			initFreshTempFile(tempFile, metadataFile, probe.contentLength);
		}
		boolean restartedAsSingle = false;
		while (true) {
			MetadataStore metadataStore = new MetadataStore(metadataFile, probe.finalUrl, probe.contentLength, probe.etag,
					probe.lastModified, rangeStates);
			metadataStore.persistQuietly();
			DownloadTracker tracker = new DownloadTracker(probe.contentLength, rangeStates, metadataStore::persistQuietly);
			fireProgress(tracker.snapshot());
			try {
				if (rangeStates.size() == 1) {
					downloadRange(probe.finalUrl, tempFile, rangeStates.get(0), tracker);
				} else {
					ExecutorService executor = Executors.newFixedThreadPool(rangeStates.size());
					try {
						List<Future<Void>> futures = new ArrayList<>();
						for (RangeState rangeState : rangeStates) {
							futures.add(executor.submit(new RangeTask(probe.finalUrl, tempFile, rangeState, tracker)));
						}
						for (Future<Void> future : futures) {
							future.get();
						}
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						ensureNotStopped();
						throw new IOException("下载被中断", e);
					} catch (ExecutionException e) {
						ensureNotStopped();
						Throwable cause = e.getCause();
						if (cause instanceof IOException ioException) {
							throw ioException;
						}
						throw new IOException("分段下载失败: " + (cause == null ? e.getMessage() : cause.getMessage()),
								cause == null ? e : cause);
					} finally {
						executor.shutdownNow();
					}
				}

				ensureNotStopped();
				tracker.forceEmit();
				metadataStore.persist();
				Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
				Files.deleteIfExists(metadataFile);
				return new DownloadResult(probe.finalUrl, targetFile, probe.contentLength, rangeStates.size());
			} catch (RetryAsSingleDownloadException e) {
				metadataStore.persistQuietly();
				if (restartedAsSingle) {
					throw e;
				}
				restartedAsSingle = true;
				ranges = splitRanges(probe.contentLength, 1);
				rangeStates = createRangeStates(ranges);
				initFreshTempFile(tempFile, metadataFile, probe.contentLength);
			} catch (IOException e) {
				metadataStore.persistQuietly();
				throw e;
			}
		}
	}

	public ProbeResult probe(String sourceUrl) throws IOException {
		String finalUrl = resolveRedirects(sourceUrl);
		ProbeResult headProbe = probeByHead(finalUrl);
		if (headProbe.contentLength > 0) {
			return headProbe;
		}
		ProbeResult rangeProbe = probeByRange(finalUrl);
		if (rangeProbe.contentLength > 0) {
			return rangeProbe;
		}
		throw new IOException("探测文件大小失败: " + sourceUrl);
	}

	private ProbeResult probeByHead(String finalUrl) throws IOException {
		HttpURLConnection connection = openConnection(finalUrl, "HEAD");
		try {
			int code = connection.getResponseCode();
			if (isRedirectCode(code)) {
				String redirected = resolveLocation(finalUrl, connection.getHeaderField("Location"));
				return probeByHead(resolveRedirects(redirected));
			}
			if (code < 200 || code >= 400) {
				return new ProbeResult(finalUrl, -1, false, null, null);
			}
			long contentLength = parseContentLength(connection.getHeaderField("Content-Length"));
			boolean rangeSupported = isRangeSupported(connection.getHeaderField("Accept-Ranges"));
			String etag = normalizeHeaderValue(connection.getHeaderField("ETag"));
			String lastModified = normalizeHeaderValue(connection.getHeaderField("Last-Modified"));
			return new ProbeResult(finalUrl, contentLength, rangeSupported, etag, lastModified);
		} finally {
			connection.disconnect();
		}
	}

	private ProbeResult probeByRange(String finalUrl) throws IOException {
		HttpURLConnection connection = openConnection(finalUrl, "GET");
		connection.setRequestProperty("Range", "bytes=0-0");
		try {
			int code = connection.getResponseCode();
			if (isRedirectCode(code)) {
				String redirected = resolveLocation(finalUrl, connection.getHeaderField("Location"));
				return probeByRange(resolveRedirects(redirected));
			}
			if (code == HttpURLConnection.HTTP_PARTIAL) {
				long size = parseContentRangeSize(connection.getHeaderField("Content-Range"));
				String etag = normalizeHeaderValue(connection.getHeaderField("ETag"));
				String lastModified = normalizeHeaderValue(connection.getHeaderField("Last-Modified"));
				return new ProbeResult(finalUrl, size, size > 0, etag, lastModified);
			}
			if (code == HttpURLConnection.HTTP_OK) {
				long size = parseContentLength(connection.getHeaderField("Content-Length"));
				String etag = normalizeHeaderValue(connection.getHeaderField("ETag"));
				String lastModified = normalizeHeaderValue(connection.getHeaderField("Last-Modified"));
				return new ProbeResult(finalUrl, size, false, etag, lastModified);
			}
			return new ProbeResult(finalUrl, -1, false, null, null);
		} finally {
			connection.disconnect();
		}
	}

	public String resolveRedirects(String sourceUrl) throws IOException {
		String current = sourceUrl;
		for (int i = 0; i <= this.maxRedirects; i++) {
			HttpURLConnection connection = openConnection(current, "HEAD");
			try {
				int code = connection.getResponseCode();
				if (!isRedirectCode(code)) {
					return current;
				}
				String location = connection.getHeaderField("Location");
				current = resolveLocation(current, location);
			} finally {
				connection.disconnect();
			}
		}
		throw new IOException("重定向次数超过限制: " + sourceUrl);
	}

	private HttpURLConnection openConnection(String url, String method) throws IOException {
		URL target = URI.create(url).toURL();
		HttpURLConnection connection = (HttpURLConnection) target.openConnection();
		connection.setRequestMethod(method);
		connection.setInstanceFollowRedirects(false);
		connection.setConnectTimeout(this.timeoutMs);
		connection.setReadTimeout(this.timeoutMs);
		connection.setRequestProperty("User-Agent", this.userAgent);
		connection.setRequestProperty("Accept-Encoding", "identity");
		connection.setRequestProperty("Connection", "keep-alive");
		return connection;
	}

	private void downloadRange(String finalUrl, Path tempFile, RangeState rangeState, DownloadTracker tracker) throws IOException {
		long current = rangeState.currentOffset();
		if (current > rangeState.end()) {
			return;
		}
		while (current <= rangeState.end()) {
			ensureNotStopped();
			HttpURLConnection connection = openConnection(finalUrl, "GET");
			connection.setRequestProperty("Range", "bytes=" + current + "-" + rangeState.end());
			try {
				int code = connection.getResponseCode();
				if (code == HttpURLConnection.HTTP_MOVED_TEMP || code == HttpURLConnection.HTTP_MOVED_PERM
						|| code == HttpURLConnection.HTTP_SEE_OTHER || code == 307 || code == 308) {
					String redirected = resolveLocation(finalUrl, connection.getHeaderField("Location"));
					finalUrl = resolveRedirects(redirected);
					continue;
				}
				if (code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK) {
					throw new IOException("HTTP请求失败, code=" + code + ", range=" + current + "-" + rangeState.end());
				}
				if (code == HttpURLConnection.HTTP_OK && current > rangeState.start()) {
					throw new RetryAsSingleDownloadException("服务端忽略Range续传，切换为单线程全量下载");
				}
				try (InputStream inputStream = connection.getInputStream();
						RandomAccessFile raf = new RandomAccessFile(tempFile.toFile(), "rw")) {
					raf.seek(current);
					byte[] buffer = new byte[this.bufferSize];
					int read;
					while ((read = inputStream.read(buffer)) != -1) {
						ensureNotStopped();
						long remaining = rangeState.end() - current + 1;
						if (remaining <= 0) {
							break;
						}
						int writable = (int) Math.min(remaining, read);
						raf.write(buffer, 0, writable);
						current += writable;
						rangeState.onBytes(writable);
						tracker.onBytes(writable);
						if (current > rangeState.end()) {
							break;
						}
					}
				}
				if (current > rangeState.end()) {
					rangeState.markCompleted();
					tracker.onPartCompleted();
					return;
				}
			} finally {
				connection.disconnect();
			}
		}
	}

	public void requestStop() {
		this.stopRequested = true;
	}

	public boolean isStopRequested() {
		return this.stopRequested;
	}

	public void setProgressListener(ProgressListener progressListener) {
		this.progressListener = progressListener;
	}

	private void ensureNotStopped() throws IOException {
		if (this.stopRequested || Thread.currentThread().isInterrupted()) {
			throw new IOException("下载已暂停");
		}
	}

	private void fireProgress(ProgressSnapshot snapshot) {
		ProgressListener listener = this.progressListener;
		if (listener != null) {
			listener.onProgress(snapshot.downloadedBytes(), snapshot.totalBytes(), snapshot.partsCompleted(), snapshot.partsTotal());
		}
	}

	private List<Range> splitRanges(long totalSize, int count) {
		long chunk = totalSize / count;
		long remainder = totalSize % count;
		long cursor = 0;
		List<Range> ranges = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			long size = chunk + (i < remainder ? 1 : 0);
			long start = cursor;
			long end = cursor + size - 1;
			ranges.add(new Range(start, end));
			cursor = end + 1;
		}
		return ranges;
	}

	private static Path buildTempFile(Path targetFile) {
		return targetFile.resolveSibling(targetFile.getFileName().toString() + ".downloading");
	}

	private static Path buildMetadataFile(Path targetFile) {
		return targetFile.resolveSibling(targetFile.getFileName().toString() + ".downloading.meta");
	}

	private static List<RangeState> createRangeStates(List<Range> ranges) {
		List<RangeState> result = new ArrayList<>(ranges.size());
		for (Range range : ranges) {
			result.add(new RangeState(range.start, range.end, 0));
		}
		return result;
	}

	private static void initFreshTempFile(Path tempFile, Path metadataFile, long size) throws IOException {
		Files.deleteIfExists(metadataFile);
		Files.deleteIfExists(tempFile);
		try (RandomAccessFile raf = new RandomAccessFile(tempFile.toFile(), "rw")) {
			raf.setLength(size);
		}
	}

	private static ResumeMetadata loadMetadata(Path metadataFile) {
		if (!Files.exists(metadataFile)) {
			return null;
		}
		Properties properties = new Properties();
		try (InputStream inputStream = Files.newInputStream(metadataFile)) {
			properties.load(inputStream);
		} catch (IOException e) {
			return null;
		}
		long contentLength = parseLong(properties.getProperty("contentLength"), -1);
		int partCount = (int) parseLong(properties.getProperty("partCount"), -1);
		if (contentLength <= 0 || partCount <= 0) {
			return null;
		}
		List<RangeSnapshot> ranges = new ArrayList<>(partCount);
		for (int i = 0; i < partCount; i++) {
			long start = parseLong(properties.getProperty("part." + i + ".start"), -1);
			long end = parseLong(properties.getProperty("part." + i + ".end"), -1);
			long downloaded = parseLong(properties.getProperty("part." + i + ".downloaded"), -1);
			if (start < 0 || end < start || downloaded < 0) {
				return null;
			}
			ranges.add(new RangeSnapshot(start, end, downloaded));
		}
		String finalUrl = normalizeHeaderValue(properties.getProperty("finalUrl"));
		String etag = normalizeHeaderValue(properties.getProperty("etag"));
		String lastModified = normalizeHeaderValue(properties.getProperty("lastModified"));
		return new ResumeMetadata(finalUrl, contentLength, etag, lastModified, ranges);
	}

	private static boolean applyResumeState(ResumeMetadata metadata, ProbeResult probe, List<RangeState> rangeStates, Path tempFile) {
		if (metadata == null || !Files.exists(tempFile)) {
			return false;
		}
		if (metadata.contentLength() != probe.contentLength() || metadata.ranges().size() != rangeStates.size()) {
			return false;
		}
		if (!isSameIdentity(metadata.etag(), probe.etag())) {
			return false;
		}
		if (!isSameIdentity(metadata.lastModified(), probe.lastModified())) {
			return false;
		}
		for (int i = 0; i < rangeStates.size(); i++) {
			RangeState rangeState = rangeStates.get(i);
			RangeSnapshot snapshot = metadata.ranges().get(i);
			if (rangeState.start() != snapshot.start() || rangeState.end() != snapshot.end()) {
				return false;
			}
		}
		try {
			if (Files.size(tempFile) != probe.contentLength()) {
				try (RandomAccessFile raf = new RandomAccessFile(tempFile.toFile(), "rw")) {
					raf.setLength(probe.contentLength());
				}
			}
		} catch (IOException e) {
			return false;
		}
		for (int i = 0; i < rangeStates.size(); i++) {
			RangeState rangeState = rangeStates.get(i);
			RangeSnapshot snapshot = metadata.ranges().get(i);
			rangeState.setDownloaded(snapshot.downloaded());
		}
		return true;
	}

	private static boolean isRedirectCode(int code) {
		return code == HttpURLConnection.HTTP_MOVED_PERM
				|| code == HttpURLConnection.HTTP_MOVED_TEMP
				|| code == HttpURLConnection.HTTP_SEE_OTHER
				|| code == 307
				|| code == 308;
	}

	private static boolean isRangeSupported(String acceptRanges) {
		return acceptRanges != null && acceptRanges.toLowerCase(Locale.ROOT).contains("bytes");
	}

	private static long parseContentLength(String contentLength) {
		if (contentLength == null || contentLength.isBlank()) {
			return -1;
		}
		try {
			return Long.parseLong(contentLength.trim());
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static long parseContentRangeSize(String contentRange) {
		if (contentRange == null || contentRange.isBlank()) {
			return -1;
		}
		int slash = contentRange.lastIndexOf('/');
		if (slash < 0 || slash + 1 >= contentRange.length()) {
			return -1;
		}
		String sizePart = contentRange.substring(slash + 1).trim();
		if ("*".equals(sizePart)) {
			return -1;
		}
		try {
			return Long.parseLong(sizePart);
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static long parseLong(String value, long defaultValue) {
		if (value == null || value.isBlank()) {
			return defaultValue;
		}
		try {
			return Long.parseLong(value.trim());
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}

	private static String normalizeHeaderValue(String value) {
		if (value == null) {
			return null;
		}
		String normalized = value.trim();
		return normalized.isEmpty() ? null : normalized;
	}

	private static boolean isSameIdentity(String previous, String current) {
		if (previous == null || current == null) {
			return true;
		}
		return previous.equals(current);
	}

	private static String resolveLocation(String currentUrl, String location) throws IOException {
		if (location == null || location.isBlank()) {
			throw new IOException("重定向响应缺少Location头: " + currentUrl);
		}
		try {
			URI currentUri = new URI(currentUrl);
			return currentUri.resolve(location).toString();
		} catch (URISyntaxException e) {
			throw new IOException("解析重定向地址失败: " + location, e);
		}
	}

	private static class Range {
		private final long start;
		private final long end;

		private Range(long start, long end) {
			this.start = start;
			this.end = end;
		}
	}

	private class RangeTask implements Callable<Void> {
		private final String finalUrl;
		private final Path tempFile;
		private final RangeState rangeState;
		private final DownloadTracker tracker;

		private RangeTask(String finalUrl, Path tempFile, RangeState rangeState, DownloadTracker tracker) {
			this.finalUrl = finalUrl;
			this.tempFile = tempFile;
			this.rangeState = rangeState;
			this.tracker = tracker;
		}

		@Override
		public Void call() throws Exception {
			downloadRange(this.finalUrl, this.tempFile, this.rangeState, this.tracker);
			return null;
		}
	}

	private class DownloadTracker {
		private final long totalBytes;
		private final int partsTotal;
		private final AtomicLong downloadedBytes;
		private final AtomicInteger partsCompleted;
		private final AtomicLong lastEmitMs = new AtomicLong(0L);
		private final Runnable checkpointAction;

		private DownloadTracker(long totalBytes, List<RangeState> rangeStates, Runnable checkpointAction) {
			this.totalBytes = totalBytes;
			this.partsTotal = rangeStates.size();
			this.checkpointAction = checkpointAction;
			long downloaded = 0L;
			int completed = 0;
			for (RangeState state : rangeStates) {
				downloaded += state.downloaded();
				if (state.isCompleted()) {
					completed++;
				}
			}
			this.downloadedBytes = new AtomicLong(downloaded);
			this.partsCompleted = new AtomicInteger(completed);
			this.lastEmitMs.set(System.currentTimeMillis());
		}

		private void onBytes(int delta) {
			this.downloadedBytes.addAndGet(delta);
			emitIfNeeded(false);
		}

		private void onPartCompleted() {
			int next = this.partsCompleted.incrementAndGet();
			if (next > this.partsTotal) {
				this.partsCompleted.set(this.partsTotal);
			}
			emitIfNeeded(true);
		}

		private void forceEmit() {
			emitIfNeeded(true);
		}

		private void emitIfNeeded(boolean force) {
			long now = System.currentTimeMillis();
			if (!force) {
				while (true) {
					long prev = this.lastEmitMs.get();
					if (now - prev < DEFAULT_PROGRESS_EMIT_INTERVAL_MS) {
						return;
					}
					if (this.lastEmitMs.compareAndSet(prev, now)) {
						break;
					}
				}
			} else {
				this.lastEmitMs.set(now);
			}
			fireProgress(snapshot());
			this.checkpointAction.run();
		}

		private ProgressSnapshot snapshot() {
			return new ProgressSnapshot(this.downloadedBytes.get(), this.totalBytes, this.partsCompleted.get(), this.partsTotal);
		}
	}

	@FunctionalInterface
	public interface ProgressListener {
		void onProgress(long downloadedBytes, long totalBytes, int partsCompleted, int partsTotal);
	}

	private record ProgressSnapshot(long downloadedBytes, long totalBytes, int partsCompleted, int partsTotal) {}

	private static class RangeState {
		private final long start;
		private final long end;
		private final AtomicLong downloaded;

		private RangeState(long start, long end, long downloaded) {
			this.start = start;
			this.end = end;
			this.downloaded = new AtomicLong(Math.max(0, downloaded));
		}

		private long start() {
			return this.start;
		}

		private long end() {
			return this.end;
		}

		private long size() {
			return this.end - this.start + 1;
		}

		private long downloaded() {
			long value = this.downloaded.get();
			return Math.min(value, size());
		}

		private void setDownloaded(long value) {
			long bounded = Math.max(0, Math.min(value, size()));
			this.downloaded.set(bounded);
		}

		private long currentOffset() {
			return this.start + downloaded();
		}

		private boolean isCompleted() {
			return downloaded() >= size();
		}

		private void onBytes(long delta) {
			if (delta <= 0) {
				return;
			}
			this.downloaded.updateAndGet(v -> {
				long next = v + delta;
				long max = size();
				return next > max ? max : next;
			});
		}

		private void markCompleted() {
			this.downloaded.set(size());
		}
	}

	private record RangeSnapshot(long start, long end, long downloaded) {}

	private record ResumeMetadata(String finalUrl, long contentLength, String etag, String lastModified,
			List<RangeSnapshot> ranges) {}

	private static class MetadataStore {
		private final Path metadataFile;
		private final String finalUrl;
		private final long contentLength;
		private final String etag;
		private final String lastModified;
		private final List<RangeState> rangeStates;

		private MetadataStore(Path metadataFile, String finalUrl, long contentLength, String etag, String lastModified,
				List<RangeState> rangeStates) {
			this.metadataFile = metadataFile;
			this.finalUrl = finalUrl;
			this.contentLength = contentLength;
			this.etag = etag;
			this.lastModified = lastModified;
			this.rangeStates = rangeStates;
		}

		private void persist() throws IOException {
			Properties properties = new Properties();
			properties.setProperty("finalUrl", this.finalUrl == null ? "" : this.finalUrl);
			properties.setProperty("contentLength", String.valueOf(this.contentLength));
			properties.setProperty("etag", this.etag == null ? "" : this.etag);
			properties.setProperty("lastModified", this.lastModified == null ? "" : this.lastModified);
			properties.setProperty("partCount", String.valueOf(this.rangeStates.size()));
			for (int i = 0; i < this.rangeStates.size(); i++) {
				RangeState state = this.rangeStates.get(i);
				properties.setProperty("part." + i + ".start", String.valueOf(state.start()));
				properties.setProperty("part." + i + ".end", String.valueOf(state.end()));
				properties.setProperty("part." + i + ".downloaded", String.valueOf(state.downloaded()));
			}
			try (OutputStream outputStream = Files.newOutputStream(this.metadataFile)) {
				properties.store(outputStream, null);
			}
		}

		private void persistQuietly() {
			try {
				persist();
			} catch (IOException ignored) {
			}
		}
	}

	private static class RetryAsSingleDownloadException extends IOException {
		private static final long serialVersionUID = 1L;

		private RetryAsSingleDownloadException(String message) {
			super(message);
		}
	}

	public record ProbeResult(String finalUrl, long contentLength, boolean rangeSupported, String etag, String lastModified) {}

	public record DownloadResult(String finalUrl, Path targetFile, long contentLength, int parts) {}

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			throw new IllegalArgumentException("用法: java ...SimpleHttpDownloader <url> <output-path> [threads]");
		}

		String url = args[0];
		Path target = Path.of(args[1]);
		int threads = args.length >= 3 ? Integer.parseInt(args[2]) : Math.max(2, Runtime.getRuntime().availableProcessors());

		SimpleHttpDownloader downloader = new SimpleHttpDownloader(threads);
		ProbeResult probe = downloader.probe(url);
		System.out.println("finalUrl=" + probe.finalUrl());
		System.out.println("contentLength=" + probe.contentLength());
		System.out.println("rangeSupported=" + probe.rangeSupported());

		DownloadResult result = downloader.download(url, target);
		System.out.println("downloaded=" + result.targetFile());
		System.out.println("size=" + result.contentLength());
		System.out.println("parts=" + result.parts());
	}
}
