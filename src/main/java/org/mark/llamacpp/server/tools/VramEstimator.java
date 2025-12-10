package org.mark.llamacpp.server.tools;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mark.llamacpp.gguf.GGUFBundle;
import org.mark.llamacpp.server.struct.VramEstimation;

/**
 * A tool to estimate VRAM usage for GGUF models. Re-designed to parse GGUF
 * metadata directly.
 */
public class VramEstimator {

	public static void main(String[] args) {
		if (args.length < 1) {
			System.out.println(
					"Usage: VramEstimator <path-to-gguf-file> [context-length] [kv-cache-bit-depth] [batch-size] [ubatch-size]");
			System.out.println("Example: VramEstimator model.gguf 8192 16 2048 512");
			return;
		}

		String filePath = args[0];
		int contextLength = args.length > 1 ? Integer.parseInt(args[1]) : 4096;
		int kvCacheBits = args.length > 2 ? Integer.parseInt(args[2]) : 16; // Default f16
		int batchSize = args.length > 3 ? Integer.parseInt(args[3]) : 512; // Default 512
		int ubatchSize = args.length > 4 ? Integer.parseInt(args[4]) : batchSize; // Default to batchSize

		try {
			estimateVram(new File(filePath), contextLength, kvCacheBits, batchSize, ubatchSize);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private VramEstimator() {

	}

	public static VramEstimation estimateVram(File ggufFile, int contextLength) throws IOException {
		return estimateVram(ggufFile, contextLength, 16, 512, 512);
	}

	public static VramEstimation estimateVram(File ggufFile, int contextLength, int kvCacheBits) throws IOException {
		return estimateVram(ggufFile, contextLength, kvCacheBits, 512, 512);
	}

	public static VramEstimation estimateVram(File ggufFile, int contextLength, int kvCacheBits, int batchSize)
			throws IOException {
		return estimateVram(ggufFile, contextLength, kvCacheBits, batchSize, batchSize);
	}

	public static VramEstimation estimateVram(File ggufFile, int contextLength, int kvCacheBits, int batchSize,
			int ubatchSize) throws IOException {
		
		// Use GGUFBundle to resolve all file parts
		GGUFBundle bundle = new GGUFBundle(ggufFile);
		File primaryFile = bundle.getPrimaryFile();
		
		//System.out.println("Analyzing bundle based on: " + ggufFile.getName());
		//System.out.println("Primary metadata file: " + primaryFile.getName());
		//System.out.println("Total split files found: " + bundle.getSplitFiles().size());
		if (bundle.getMmprojFile() != null) {
			//System.out.println("Vision projector found: " + bundle.getMmprojFile().getName());
		}
		
		try (RandomAccessFile raf = new RandomAccessFile(primaryFile, "r"); FileChannel channel = raf.getChannel()) {

			// Map the beginning of the file to read header and metadata
			// We'll map a small chunk first, but metadata can be large.
			// Better to read incrementally or map a generous amount (e.g. 10MB).
			// GGUF headers are usually usually not huge, but let's be safe.
			long fileSize = channel.size();
			long mapSize = Math.min(fileSize, 20 * 1024 * 1024); // Start with 20MB
			ByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, mapSize);
			buffer.order(ByteOrder.LITTLE_ENDIAN);

			// 1. Read Magic
			byte[] magic = new byte[4];
			buffer.get(magic);
			String magicStr = new String(magic, StandardCharsets.US_ASCII);
			if (!"GGUF".equals(magicStr)) {
				throw new IllegalArgumentException("Not a valid GGUF file. Magic: " + magicStr);
			}

			// 2. Read Version
			buffer.getInt();
			//int version = buffer.getInt();
			//System.out.println("GGUF Version: " + version);

			// 3. Read Counts
			buffer.getLong();
			//long tensorCount = buffer.getLong(); // uint64
			long metadataKvCount = buffer.getLong(); // uint64

			//System.out.println("Tensors: " + tensorCount);
			//System.out.println("Metadata Pairs: " + metadataKvCount);

			// 4. Parse Metadata
			Map<String, Object> metadata = new HashMap<>();
			long vocabSize = 0;

			for (int i = 0; i < metadataKvCount; i++) {
				String key = readString(buffer);
				int type = buffer.getInt(); // gguf_metadata_value_type

				// Special handling for tokens array to avoid reading 32k+ strings
				if ("tokenizer.ggml.tokens".equals(key)) {
					// It's an array of strings. We just want the size.
					if (type != 9) { // 9 is ARRAY
						Object val = readValue(buffer, type);
						metadata.put(key, val);
					} else {
						// Read array header
						int elemType = buffer.getInt();
						long len = buffer.getLong();
						vocabSize = len;
						// Skip the content
						for (long j = 0; j < len; j++) {
							skipValue(buffer, elemType);
						}
						metadata.put(key + ".size", len);
					}
				} else {
					Object value = readValue(buffer, type);
					metadata.put(key, value);
				}
			}

			// 5. Calculate Model Weights Size (approximate from file size or sum tensors)
			// To sum tensors, we need to iterate them.
			// The cursor is now at the start of tensor info.
			// We need to know where the alignment is to calculate offsets correctly,
			// but for VRAM estimation, we just need the size of the tensors.

			// However, iterating tensor info in the mapped buffer might exceed the 20MB
			// limit if there are many tensors.
			// Let's rely on metadata for architecture info first.

			// Extract Model Params
			String architecture = (String) metadata.get("general.architecture");
			if (architecture == null) {
				throw new IllegalArgumentException("Architecture not found in metadata.");
			}
			// System.out.println("Architecture: " + architecture);

			long nEmbd = getLong(metadata, architecture + ".embedding_length");
			long nLayer = getLong(metadata, architecture + ".block_count");
			long nHead = getLong(metadata, architecture + ".attention.head_count");
			long nHeadKv = getLong(metadata, architecture + ".attention.head_count_kv", nHead); // Default to nHead if
																								// missing
			long nFf = getLong(metadata, architecture + ".feed_forward_length");
			if (nFf == 0) {
				// Fallback approximation for FFN size if not present (e.g. older GGUF or
				// specific arch)
				// Standard FFN is 4 * n_embd. SwiGLU is often different but 4x is a safe lower
				// bound estimate for buffer.
				// Let's use a slightly higher factor for SwiGLU safety if unknown:
				nFf = 4 * nEmbd;
			}

			// If vocabSize wasn't found in tokens, try to find a count key
			if (vocabSize == 0) {
				// Try common keys
				// vocabSize = getLong(metadata, "tokenizer.ggml.model.vocab_size", 32000);
				// (GGUF usually implies it from tokens array)
				vocabSize = 32000; // Fallback
			}

			// GGUF context length (training limit), used as default if not provided
			// long trainContext = getLong(metadata, architecture + ".context_length_train",
			// 4096L);

			// System.out.println("Embedding Length (n_embd): " + nEmbd);
			// System.out.println("Layers (n_layer): " + nLayer);
			// System.out.println("Head Count (n_head): " + nHead);
			// System.out.println("KV Head Count (n_head_kv): " + nHeadKv);
			// System.out.println("Training Context: " + trainContext);

			// 6. Calculate KV Cache Size
			// Formula: 2 * n_layers * n_ctx * (n_embd / n_head) * n_head_kv * sizeof(type)
			// sizeof(type) depends on cache bit depth.

			double bytesPerElement = kvCacheBits / 8.0;

			long kvCacheBytes = (long) (2L * nLayer * contextLength * (nEmbd / nHead) * nHeadKv * bytesPerElement);

			// 7. Calculate Model Weights Size
			// We can approximate this by taking the file size and subtracting the header
			// size.
			// The header size is roughly where we are now + tensor infos.
			// Since we didn't parse tensor infos, let's just use the file size.
			// Most of the GGUF file is weights.
			long fileSizeBytes = bundle.getTotalFileSize();

			// Context overhead (activation buffers)
			// This is harder to estimate exactly without full graph, but usually a few
			// hundred MBs depending on batch size.
			// Base overhead for CUDA/ROCm context + fragmentation
			long baseOverhead = 256 * 1024 * 1024L;

			// Compute Graph Overhead
			// Major contributors:
			// 1. Logits: ubatch_size * vocab_size * 4 bytes (float32)
			// 2. FFN Activations: ubatch_size * n_ff * 4 bytes (float32)
			// Note: We use ubatchSize instead of batchSize because llama.cpp splits processing 
			// into physical batches (ubatch) to save memory.
			long logitsSize = ubatchSize * vocabSize * 4L;
			long ffnSize = ubatchSize * nFf * 4L;
			long graphOverhead = Math.max(logitsSize, ffnSize);
			
			// Vision Overhead (if applicable)
			// If mmproj is present, we need extra context for the vision encoder.
			// Vision encoders (like CLIP/SigLIP) have their own overhead.
			// A crude estimate is ~200-500MB depending on image resolution.
			long visionOverhead = 0;
			if (bundle.getMmprojFile() != null) {
			    // Additional overhead for loading vision model and image processing buffers
			    visionOverhead = 256 * 1024 * 1024L; 
			}

			// Add some safety margin for other buffers (norms, embeddings, etc)
			long runtimeOverhead = baseOverhead + graphOverhead + visionOverhead;

			long totalVramBytes = fileSizeBytes + kvCacheBytes + runtimeOverhead;

			// System.out.println("------------------------------------------------");
			// System.out.println("Estimation for Context Length: " + contextLength);
			// System.out.printf("Model Weights: %.2f GB%n", fileSizeBytes / (1024.0 * 1024
			// * 1024));
			// System.out.printf("KV Cache (%d-bit): %.2f GB%n", kvCacheBits, kvCacheBytes /
			// (1024.0 * 1024 * 1024));
			// System.out.printf("Runtime Overhead (est): %.2f GB%n", runtimeOverhead /
			// (1024.0 * 1024 * 1024));
			// System.out.println("------------------------------------------------");
			// System.out.printf("Total VRAM Required: %.2f GB%n", totalVramBytes / (1024.0
			// * 1024 * 1024));

			VramEstimation result = new VramEstimation();

			result.setModelWeights(fileSizeBytes);
			result.setKvCache(kvCacheBytes);
			result.setRuntimeOverhead(runtimeOverhead);
			result.setTotalVramRequired(totalVramBytes);

			return result;
		}
	}

	// Helper methods for GGUF parsing

	private static String readString(ByteBuffer buffer) {
		long len = buffer.getLong();
		byte[] bytes = new byte[(int) len];
		buffer.get(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	private static Object readValue(ByteBuffer buffer, int type) {
		switch (type) {
		case 0: // UINT8
			return buffer.get() & 0xFF;
		case 1: // INT8
			return buffer.get();
		case 2: // UINT16
			return buffer.getShort() & 0xFFFF;
		case 3: // INT16
			return buffer.getShort();
		case 4: // UINT32
			return buffer.getInt() & 0xFFFFFFFFL;
		case 5: // INT32
			return buffer.getInt();
		case 6: // FLOAT32
			return buffer.getFloat();
		case 7: // BOOL
			return buffer.get() != 0;
		case 8: // STRING
			return readString(buffer);
		case 9: // ARRAY
			return readArray(buffer);
		case 10: // UINT64
			return buffer.getLong();
		case 11: // INT64
			return buffer.getLong();
		case 12: // FLOAT64
			return buffer.getDouble();
		default:
			throw new IllegalArgumentException("Unknown GGUF value type: " + type);
		}
	}

	private static void skipValue(ByteBuffer buffer, int type) {
		switch (type) {
		case 0: // UINT8
			buffer.position(buffer.position() + 1);
			break;
		case 1: // INT8
			buffer.position(buffer.position() + 1);
			break;
		case 2: // UINT16
			buffer.position(buffer.position() + 2);
			break;
		case 3: // INT16
			buffer.position(buffer.position() + 2);
			break;
		case 4: // UINT32
			buffer.position(buffer.position() + 4);
			break;
		case 5: // INT32
			buffer.position(buffer.position() + 4);
			break;
		case 6: // FLOAT32
			buffer.position(buffer.position() + 4);
			break;
		case 7: // BOOL
			buffer.position(buffer.position() + 1);
			break;
		case 8: // STRING
			long len = buffer.getLong();
			buffer.position(buffer.position() + (int) len);
			break;
		case 9: // ARRAY
			int elemType = buffer.getInt();
			long arrayLen = buffer.getLong();
			for (long i = 0; i < arrayLen; i++) {
				skipValue(buffer, elemType);
			}
			break;
		case 10: // UINT64
			buffer.position(buffer.position() + 8);
			break;
		case 11: // INT64
			buffer.position(buffer.position() + 8);
			break;
		case 12: // FLOAT64
			buffer.position(buffer.position() + 8);
			break;
		default:
			throw new IllegalArgumentException("Unknown GGUF value type: " + type);
		}
	}

	private static List<Object> readArray(ByteBuffer buffer) {
		int type = buffer.getInt();
		long len = buffer.getLong();
		List<Object> list = new ArrayList<>((int) len);
		for (int i = 0; i < len; i++) {
			list.add(readValue(buffer, type));
		}
		return list;
	}

	private static long getLong(Map<String, Object> metadata, String key) {
		Object val = metadata.get(key);
		if (val instanceof Number) {
			return ((Number) val).longValue();
		}
		return 0L;
	}

	private static long getLong(Map<String, Object> metadata, String key, long defaultValue) {
		Object val = metadata.get(key);
		if (val instanceof Number) {
			return ((Number) val).longValue();
		}
		return defaultValue;
	}
}
