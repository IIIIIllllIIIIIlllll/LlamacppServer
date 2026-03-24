package org.mark.llamacpp.server.io;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class BoundedQueueInputStream extends InputStream {

	private static final byte[] EOF = new byte[0];

	private final ArrayBlockingQueue<byte[]> queue;
	private final AtomicBoolean closed = new AtomicBoolean(false);
	private volatile IOException failure;

	private byte[] currentChunk;
	private int currentIndex;

	public BoundedQueueInputStream(int capacity) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("capacity must be > 0");
		}
		this.queue = new ArrayBlockingQueue<>(capacity);
	}

	public void offer(byte[] chunk) throws IOException {
		if (chunk == null || chunk.length == 0) {
			return;
		}
		if (closed.get()) {
			throw new IOException("stream closed");
		}
		byte[] copy = Arrays.copyOf(chunk, chunk.length);
		try {
			while (!closed.get()) {
				if (queue.offer(copy, 100, TimeUnit.MILLISECONDS)) {
					return;
				}
				if (failure != null) {
					throw failure;
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted while enqueuing request body", e);
		}
		throw new IOException("stream closed");
	}

	public void complete() {
		if (closed.compareAndSet(false, true)) {
			queue.offer(EOF);
		}
	}

	public void fail(IOException exception) {
		this.failure = exception;
		closed.set(true);
		queue.offer(EOF);
	}

	@Override
	public int read() throws IOException {
		byte[] chunk = ensureChunk();
		if (chunk == null) {
			return -1;
		}
		return chunk[currentIndex++] & 0xff;
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		if (b == null) {
			throw new NullPointerException("buffer");
		}
		if (off < 0 || len < 0 || len > b.length - off) {
			throw new IndexOutOfBoundsException();
		}
		if (len == 0) {
			return 0;
		}

		byte[] chunk = ensureChunk();
		if (chunk == null) {
			return -1;
		}
		int remaining = chunk.length - currentIndex;
		int count = Math.min(len, remaining);
		System.arraycopy(chunk, currentIndex, b, off, count);
		currentIndex += count;
		return count;
	}

	@Override
	public void close() {
		complete();
	}

	private byte[] ensureChunk() throws IOException {
		while (currentChunk == null || currentIndex >= currentChunk.length) {
			if (failure != null) {
				throw failure;
			}
			byte[] next;
			try {
				next = queue.take();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("interrupted while reading request body", e);
			}
			if (next == EOF) {
				currentChunk = null;
				currentIndex = 0;
				if (failure != null) {
					throw failure;
				}
				return null;
			}
			currentChunk = next;
			currentIndex = 0;
		}
		return currentChunk;
	}
}
