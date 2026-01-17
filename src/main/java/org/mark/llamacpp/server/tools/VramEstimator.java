package org.mark.llamacpp.server.tools;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VramEstimator {

	public enum KvCacheType {
		F32("f32", 4.0),
		F16("f16", 2.0),
		BF16("bf16", 2.0),
		Q8_0("q8_0", 36.0 / 32.0),
		Q4_0("q4_0", 18.0 / 32.0),
		Q4_1("q4_1", 20.0 / 32.0),
		IQ4_NL("iq4_nl", 18.0 / 32.0),
		Q5_0("q5_0", 22.0 / 32.0),
		Q5_1("q5_1", 24.0 / 32.0);

		private final String id;
		private final double bytesPerElement;

		KvCacheType(String id, double bytesPerElement) {
			this.id = id;
			this.bytesPerElement = bytesPerElement;
		}

		public String id() {
			return id;
		}

		public double bytesPerElement() {
			return bytesPerElement;
		}

		public static KvCacheType from(String id) {
			if (id == null) {
				return F16;
			}
			String s = id.trim().toLowerCase(Locale.ROOT);
			for (KvCacheType t : values()) {
				if (t.id.equals(s)) {
					return t;
				}
			}
			throw new IllegalArgumentException("不支持的KV类型: " + id);
		}
	}

	public record Estimate(long modelWeightsBytes, long kvCacheBytes, long runtimeOverheadBytes, long totalBytes,
			String architecture, long contextLength, KvCacheType kvCacheTypeK, KvCacheType kvCacheTypeV,
			boolean flashAttention, long nLayer, long kvLayerCount, String kvLayerHeuristic, Set<Integer> kvLayers) {
		public double totalMiB() {
			return totalBytes / (1024.0 * 1024.0);
		}

		public double weightsMiB() {
			return modelWeightsBytes / (1024.0 * 1024.0);
		}

		public double kvMiB() {
			return kvCacheBytes / (1024.0 * 1024.0);
		}

		public double overheadMiB() {
			return runtimeOverheadBytes / (1024.0 * 1024.0);
		}
	}

	private record ModelParams(String architecture, long nLayer, long nEmbd, long nHeadKv, long headDimK, long headDimV,
			long slidingWindow, int[] headCountKvByLayer) {
	}

	private record ResolvedBundle(File primaryFile, List<File> parts) {
	}

	private record KvLayerScanResult(long kvLayerCount, String heuristic, Set<Integer> layers) {
	}

	private static final Pattern SPLIT_OF_PATTERN = Pattern.compile("^(.*)-(\\d{5})-of-(\\d{5})\\.gguf$",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern SPLIT_DOT_PATTERN = Pattern.compile("^(.*)\\.gguf\\.(\\d+)$", Pattern.CASE_INSENSITIVE);

	private static final Pattern[] LAYER_INDEX_PATTERNS = {
			Pattern.compile("(?:^|\\.)(?:blk|block|blocks|layer|layers)\\.(\\d+)\\.", Pattern.CASE_INSENSITIVE),
			Pattern.compile("(?:^|\\.)(?:model\\.)?layers\\.(\\d+)\\.", Pattern.CASE_INSENSITIVE),
			Pattern.compile("(?:^|\\.)transformer\\.h\\.(\\d+)\\.", Pattern.CASE_INSENSITIVE),
			Pattern.compile("(?:^|\\.)decoder\\.layers\\.(\\d+)\\.", Pattern.CASE_INSENSITIVE),
			Pattern.compile("(?:^|\\.)h\\.(\\d+)\\.", Pattern.CASE_INSENSITIVE),
	};
	private static final Pattern ANY_DOT_NUMBER_DOT_PATTERN = Pattern.compile("\\.(\\d+)\\.");

	private static final Pattern ATTN_OUTPUT_PATTERN = Pattern.compile(
			"(?i)(?:^|[._/])(?:attn_output|o_proj|out_proj|attn_o|wo)(?:\\.(?:weight|bias))?(?:$|[._/])");
	private static final Pattern KV_PROJ_PATTERN = Pattern.compile(
			"(?i)(?:^|[._/])(?:attn_k|attn_v|k_proj|v_proj|wk|wv|key|value)(?:\\.(?:weight|bias))?(?:$|[._/])");
	private static final Pattern QKV_PROJ_PATTERN = Pattern.compile(
			"(?i)(?:^|[._/])(?:attn_qkv|qkv|wqkv|qkv_proj|in_proj|query_key_value|c_attn)(?:\\.(?:weight|bias))?(?:$|[._/])");

	private VramEstimator() {
	}

	public static void main(String[] argv) throws Exception {
		Args args = Args.parse(argv);
		Estimate est = estimate(args.modelPath, args.ctx, args.kType, args.vType, args.flashAttention);
		System.out.println("architecture=" + est.architecture);
		System.out.println("n_layer=" + est.nLayer);
		System.out.println("kv_layer_count=" + est.kvLayerCount);
		System.out.println("kv_layer_heuristic=" + est.kvLayerHeuristic);
		System.out.println("kv_layers=" + est.kvLayers);
		System.out.println("weights_mib=" + est.weightsMiB());
		System.out.println("kv_mib=" + est.kvMiB());
		System.out.println("overhead_mib=" + est.overheadMiB());
		System.out.println("total_mib=" + est.totalMiB());
	}

	private static final class Args {
		private final File modelPath;
		private final int ctx;
		private final KvCacheType kType;
		private final KvCacheType vType;
		private final boolean flashAttention;

		private Args(File modelPath, int ctx, KvCacheType kType, KvCacheType vType, boolean flashAttention) {
			this.modelPath = modelPath;
			this.ctx = ctx;
			this.kType = kType;
			this.vType = vType;
			this.flashAttention = flashAttention;
		}

		static Args parse(String[] argv) {
			if (argv == null || argv.length == 0) {
				throw new IllegalArgumentException(
						"用法: <gguf|dir> [--ctx N] [--cache-type-k TYPE] [--cache-type-v TYPE] [--flash-attn on|off]");
			}
			File modelPath = new File(argv[0]);
			int ctx = 2048;
			KvCacheType kType = KvCacheType.F16;
			KvCacheType vType = KvCacheType.F16;
			boolean flash = false;
			int i = 1;
			while (i < argv.length) {
				String a = argv[i];
				if (a == null || a.isBlank()) {
					i++;
					continue;
				}
				switch (a) {
				case "--ctx" -> {
					ctx = Integer.parseInt(argv[i + 1]);
					i += 2;
				}
				case "--cache-type-k" -> {
					kType = KvCacheType.from(argv[i + 1]);
					i += 2;
				}
				case "--cache-type-v" -> {
					vType = KvCacheType.from(argv[i + 1]);
					i += 2;
				}
				case "--flash-attn" -> {
					String v = argv[i + 1];
					flash = v != null && (v.equalsIgnoreCase("on") || v.equalsIgnoreCase("true") || v.equals("1"));
					i += 2;
				}
				default -> throw new IllegalArgumentException("未知参数: " + a);
				}
			}
			if (ctx <= 0) {
				throw new IllegalArgumentException("ctx 必须大于0");
			}
			return new Args(modelPath, ctx, kType, vType, flash);
		}
	}

	public static Estimate estimate(File modelPath, int contextLength, KvCacheType kvCacheTypeK, KvCacheType kvCacheTypeV,
			boolean flashAttention) throws IOException {
		Objects.requireNonNull(modelPath, "modelPath");
		if (contextLength <= 0) {
			throw new IllegalArgumentException("contextLength 必须大于0");
		}

		ResolvedBundle bundle = resolveBundle(modelPath);
		if (bundle.primaryFile == null || bundle.parts.isEmpty()) {
			throw new IllegalArgumentException("未找到可用的GGUF文件: " + modelPath.getAbsolutePath());
		}

		Map<String, Object> meta = readGgufMetadata(bundle.primaryFile);
		ModelParams params = extractModelParams(meta);

		long weightsBytes = 0;
		for (File part : bundle.parts) {
			weightsBytes = safeAdd(weightsBytes, estimateTensorDataBytes(part));
		}

		KvLayerScanResult kvScan = resolveKvLayers(bundle.parts, params);
		long kvCacheBytes = estimateKvCacheBytes(params, contextLength, kvCacheTypeK, kvCacheTypeV, kvScan);
		long runtimeOverhead = estimateRuntimeOverheadBytes(params, contextLength, kvCacheBytes, flashAttention);
		long total = safeAdd(safeAdd(weightsBytes, kvCacheBytes), runtimeOverhead);

		return new Estimate(weightsBytes, kvCacheBytes, runtimeOverhead, total, params.architecture, contextLength, kvCacheTypeK,
				kvCacheTypeV, flashAttention, params.nLayer, kvScan.kvLayerCount, kvScan.heuristic, kvScan.layers);
	}

	private static KvLayerScanResult resolveKvLayers(List<File> ggufParts, ModelParams params) throws IOException {
		if (params.headCountKvByLayer != null && params.headCountKvByLayer.length > 0) {
			Set<Integer> out = new LinkedHashSet<>();
			for (int i = 0; i < params.headCountKvByLayer.length; i++) {
				if (params.headCountKvByLayer[i] > 0) {
					out.add(i);
				}
			}
			if (!out.isEmpty()) {
				return new KvLayerScanResult(out.size(), "head_count_kv", Set.copyOf(out));
			}
		}
		return scanKvLayers(ggufParts, params.nLayer);
	}

	private static KvLayerScanResult scanKvLayers(List<File> ggufParts, long expectedLayerCount) throws IOException {
		if (expectedLayerCount <= 0) {
			return new KvLayerScanResult(0, "none", Set.of());
		}
		boolean[] outputHit = new boolean[(int) Math.min(expectedLayerCount, Integer.MAX_VALUE)];
		boolean[] kvHit = new boolean[outputHit.length];
		boolean[] qkvHit = new boolean[outputHit.length];

		for (File part : ggufParts) {
			scanPartForLayers(part, outputHit, kvHit, qkvHit);
		}

		Set<Integer> out = new LinkedHashSet<>();
		for (int i = 0; i < outputHit.length; i++) {
			if (outputHit[i]) {
				out.add(i);
			}
		}
		if (!out.isEmpty()) {
			return new KvLayerScanResult(out.size(), "attn_output", Set.copyOf(out));
		}

		out.clear();
		for (int i = 0; i < kvHit.length; i++) {
			if (kvHit[i]) {
				out.add(i);
			}
		}
		if (!out.isEmpty()) {
			return new KvLayerScanResult(out.size(), "kv_proj", Set.copyOf(out));
		}

		out.clear();
		for (int i = 0; i < qkvHit.length; i++) {
			if (qkvHit[i]) {
				out.add(i);
			}
		}
		if (!out.isEmpty()) {
			return new KvLayerScanResult(out.size(), "qkv_proj", Set.copyOf(out));
		}

		return new KvLayerScanResult(0, "none", Set.of());
	}

	private static void scanPartForLayers(File ggufFile, boolean[] outputHit, boolean[] kvHit, boolean[] qkvHit)
			throws IOException {
		try (RandomAccessFile raf = new RandomAccessFile(ggufFile, "r"); FileChannel ch = raf.getChannel()) {
			long fileSize = ch.size();
			LeReader r = new LeReader(ch, Math.min(32L * 1024 * 1024, fileSize));

			byte[] magic = r.readBytes(4);
			String m = new String(magic, StandardCharsets.US_ASCII);
			if (!"GGUF".equals(m)) {
				return;
			}

			r.readI32();
			long tensorCount = r.readU64();
			long kvCount = r.readU64();

			for (long i = 0; i < kvCount; i++) {
				String key = r.readGgufString();
				int type = r.readI32();
				if ("tokenizer.ggml.tokens".equals(key) && type == 9) {
					int elemType = r.readI32();
					long len = r.readU64();
					for (long j = 0; j < len; j++) {
						r.skipGgufValue(elemType);
					}
				} else {
					r.skipGgufValue(type);
				}
			}

			for (long i = 0; i < tensorCount; i++) {
				String name = r.readGgufString();
				int nDims = r.readI32();
				for (int d = 0; d < nDims; d++) {
					r.readU64();
				}
				r.readI32();
				r.readU64();

				int idx = extractLayerIndex(name);
				if (idx < 0 || idx >= outputHit.length) {
					idx = extractFallbackLayerIndex(name, outputHit.length);
				}
				if (idx < 0 || idx >= outputHit.length) {
					continue;
				}

				if (!outputHit[idx] && ATTN_OUTPUT_PATTERN.matcher(name).find()) {
					outputHit[idx] = true;
				}
				if (!kvHit[idx] && KV_PROJ_PATTERN.matcher(name).find()) {
					kvHit[idx] = true;
				}
				if (!qkvHit[idx] && QKV_PROJ_PATTERN.matcher(name).find()) {
					qkvHit[idx] = true;
				}
			}
		} catch (EOFException eof) {
		}
	}

	private static int extractLayerIndex(String tensorName) {
		if (tensorName == null || tensorName.isBlank()) {
			return -1;
		}
		for (Pattern p : LAYER_INDEX_PATTERNS) {
			Matcher m = p.matcher(tensorName);
			if (!m.find()) {
				continue;
			}
			try {
				return Integer.parseInt(m.group(1));
			} catch (Exception e) {
				return -1;
			}
		}
		return -1;
	}

	private static int extractFallbackLayerIndex(String tensorName, int maxExclusive) {
		if (tensorName == null || tensorName.isBlank() || maxExclusive <= 0) {
			return -1;
		}
		Matcher m = ANY_DOT_NUMBER_DOT_PATTERN.matcher(tensorName);
		while (m.find()) {
			try {
				int idx = Integer.parseInt(m.group(1));
				if (idx >= 0 && idx < maxExclusive) {
					return idx;
				}
			} catch (Exception e) {
				return -1;
			}
		}
		return -1;
	}

	private static long estimateKvCacheBytes(ModelParams params, long contextLength, KvCacheType kvTypeK, KvCacheType kvTypeV,
			KvLayerScanResult kvScan) {
		long layers = (kvScan != null && kvScan.kvLayerCount > 0) ? kvScan.kvLayerCount : params.nLayer;
		if (layers <= 0 || params.nHeadKv <= 0 || params.headDimK <= 0 || params.headDimV <= 0) {
			return 0;
		}

		double bytesPerHeadCell = (params.headDimK * kvTypeK.bytesPerElement() + params.headDimV * kvTypeV.bytesPerElement());
		long sumHeads = 0;
		if (kvScan != null && kvScan.layers != null && !kvScan.layers.isEmpty()) {
			for (int idx : kvScan.layers) {
				int h = (params.headCountKvByLayer != null && idx >= 0 && idx < params.headCountKvByLayer.length)
						? params.headCountKvByLayer[idx]
						: (int) params.nHeadKv;
				if (h <= 0) {
					h = (int) params.nHeadKv;
				}
				sumHeads = safeAdd(sumHeads, h);
			}
		}
		double bytesPerCell = (sumHeads > 0 ? sumHeads : params.nHeadKv * (double) layers) * bytesPerHeadCell;

		double bytes;
		if (params.slidingWindow > 0 && contextLength > params.slidingWindow && isGemmaSlidingWindow(params.architecture)) {
			long globalLayers = estimateGemmaGlobalLayerCount(params.nLayer);
			if (params.nLayer > 0 && layers != params.nLayer) {
				double scaled = globalLayers * (layers / (double) params.nLayer);
				globalLayers = (long) Math.rint(scaled);
			}
			if (globalLayers < 0) {
				globalLayers = 0;
			}
			if (globalLayers > layers) {
				globalLayers = layers;
			}
			long swaLayers = layers - globalLayers;
			long swaCells = estimateGemmaSwaCells(params.slidingWindow, contextLength);
			double perLayerBytes = bytesPerCell / Math.max(1.0, layers);
			bytes = globalLayers * (double) contextLength * perLayerBytes + swaLayers * (double) swaCells * perLayerBytes;
		} else {
			bytes = (double) contextLength * bytesPerCell;
		}

		if (bytes <= 0) {
			return 0;
		}
		if (bytes >= Long.MAX_VALUE) {
			return Long.MAX_VALUE;
		}
		return (long) bytes;
	}

	private static long estimateRuntimeOverheadBytes(ModelParams params, long contextLength, long kvCacheBytes,
			boolean flashAttention) {
		long base = 256L * 1024 * 1024;
		long byCtx = 0;
		if (params.nEmbd > 0 && contextLength > 0) {
			double bytesPerElem = flashAttention ? 8.0 : 4.0;
			double b = contextLength * (double) params.nEmbd * bytesPerElem;
			if (b > 0 && b < Long.MAX_VALUE) {
				byCtx = (long) b;
			}
		}
		long byKv = kvCacheBytes > 0 ? kvCacheBytes / 4 : 0;
		long extra = flashAttention ? 64L * 1024 * 1024 : 0;
		return safeAdd(base, safeAdd(Math.max(byCtx, byKv), extra));
	}

	private static boolean isGemmaSlidingWindow(String arch) {
		if (arch == null || arch.isBlank()) {
			return false;
		}
		String s = arch.trim().toLowerCase(Locale.ROOT);
		return s.startsWith("gemma");
	}

	private static long estimateGemmaGlobalLayerCount(long nLayer) {
		if (nLayer <= 0) {
			return 0;
		}
		return (nLayer + 5) / 6;
	}

	private static long estimateGemmaSwaCells(long slidingWindow, long contextLength) {
		if (slidingWindow <= 0) {
			return contextLength;
		}
		long cells = safeAdd(slidingWindow, safeAdd(slidingWindow, slidingWindow));
		return Math.min(contextLength, cells);
	}

	private static ResolvedBundle resolveBundle(File input) {
		if (!input.exists()) {
			throw new IllegalArgumentException("文件不存在: " + input.getAbsolutePath());
		}

		File seed = input;
		if (input.isDirectory()) {
			File[] ggufs = input.listFiles((dir, name) -> name != null && name.toLowerCase(Locale.ROOT).endsWith(".gguf"));
			if (ggufs == null || ggufs.length == 0) {
				return new ResolvedBundle(null, List.of());
			}
			seed = pickSeedFile(ggufs);
		}

		Matcher ofMatcher = SPLIT_OF_PATTERN.matcher(seed.getName());
		if (ofMatcher.matches()) {
			String base = ofMatcher.group(1);
			int total = Integer.parseInt(ofMatcher.group(3));
			File dir = seed.getParentFile() == null ? new File(".") : seed.getParentFile();
			List<File> parts = new ArrayList<>(total);
			for (int i = 1; i <= total; i++) {
				File f = new File(dir, String.format("%s-%05d-of-%05d.gguf", base, i, total));
				if (f.exists()) {
					parts.add(f);
				}
			}
			File primary = new File(dir, String.format("%s-00001-of-%05d.gguf", base, total));
			if (!primary.exists() && !parts.isEmpty()) {
				primary = parts.getFirst();
			}
			return new ResolvedBundle(primary, parts);
		}

		Matcher dotMatcher = SPLIT_DOT_PATTERN.matcher(seed.getName());
		if (dotMatcher.matches()) {
			String base = dotMatcher.group(1);
			File dir = seed.getParentFile() == null ? new File(".") : seed.getParentFile();
			List<File> parts = new ArrayList<>();
			int idx = 1;
			while (true) {
				File f = new File(dir, base + ".gguf." + idx);
				if (!f.exists()) {
					break;
				}
				parts.add(f);
				idx++;
			}
			File primary = new File(dir, base + ".gguf.1");
			if (!primary.exists() && !parts.isEmpty()) {
				primary = parts.getFirst();
			}
			return new ResolvedBundle(primary, parts);
		}

		return new ResolvedBundle(seed, List.of(seed));
	}

	private static File pickSeedFile(File[] ggufs) {
		for (File f : ggufs) {
			String n = f.getName().toLowerCase(Locale.ROOT);
			if (n.matches(".*-00001-of-\\d{5}\\.gguf$")) {
				return f;
			}
		}
		for (File f : ggufs) {
			String n = f.getName().toLowerCase(Locale.ROOT);
			if (!n.contains("mmproj")) {
				return f;
			}
		}
		return ggufs[0];
	}

	private static Map<String, Object> readGgufMetadata(File ggufFile) throws IOException {
		try (RandomAccessFile raf = new RandomAccessFile(ggufFile, "r"); FileChannel ch = raf.getChannel()) {
			LeReader r = new LeReader(ch, Math.min(8L * 1024 * 1024, ch.size()));
			byte[] magic = r.readBytes(4);
			String m = new String(magic, StandardCharsets.US_ASCII);
			if (!"GGUF".equals(m)) {
				throw new IllegalArgumentException("不是有效GGUF文件: " + ggufFile.getAbsolutePath());
			}
			r.readI32();
			long tensorCount = r.readU64();
			long kvCount = r.readU64();

			Map<String, Object> out = new HashMap<>();
			out.put("__tensor_count", tensorCount);
			out.put("__kv_count", kvCount);

			for (long i = 0; i < kvCount; i++) {
				String key = r.readGgufString();
				int type = r.readI32();
				if ("tokenizer.ggml.tokens".equals(key) && type == 9) {
					int elemType = r.readI32();
					long len = r.readU64();
					for (long j = 0; j < len; j++) {
						r.skipGgufValue(elemType);
					}
					out.put(key + ".size", len);
				} else {
					Object val = r.readGgufValue(type);
					out.put(key, val);
				}
			}

			return out;
		}
	}

	private static ModelParams extractModelParams(Map<String, Object> meta) {
		String arch = asString(meta.get("general.architecture"));
		if (arch == null || arch.isBlank()) {
			arch = findBySuffix(meta, ".architecture");
		}
		if (arch == null || arch.isBlank()) {
			arch = "unknown";
		}

		long nEmbd = firstLong(meta, arch + ".embedding_length", findKeyBySuffix(meta, ".embedding_length"));
		long nLayer = firstLong(meta, arch + ".block_count", findKeyBySuffix(meta, ".block_count"));
		long nHead = firstLong(meta, arch + ".attention.head_count", findKeyBySuffix(meta, ".attention.head_count"));
		String headKvKey = arch + ".attention.head_count_kv";
		String headKvFallbackKey = findKeyBySuffix(meta, ".attention.head_count_kv");
		Object headKvObj = meta.get(headKvKey);
		if (headKvObj == null && headKvFallbackKey != null && !headKvFallbackKey.isBlank()) {
			headKvObj = meta.get(headKvFallbackKey);
		}
		int[] headCountKvByLayer = asIntArray(headKvObj);
		if (headCountKvByLayer != null && nLayer > 0 && headCountKvByLayer.length != nLayer) {
			headCountKvByLayer = Arrays.copyOf(headCountKvByLayer, (int) Math.min(nLayer, Integer.MAX_VALUE));
		}
		long nHeadKv = 0;
		if (headCountKvByLayer != null && headCountKvByLayer.length > 0) {
			for (int v : headCountKvByLayer) {
				if (v > nHeadKv) {
					nHeadKv = v;
				}
			}
		} else {
			nHeadKv = firstLong(meta, headKvKey, headKvFallbackKey);
		}
		if (nHeadKv == 0) {
			nHeadKv = nHead;
		}

		long keyLength = firstLong(meta, arch + ".attention.key_length", findKeyBySuffix(meta, ".attention.key_length"));
		long valueLength = firstLong(meta, arch + ".attention.value_length", findKeyBySuffix(meta, ".attention.value_length"));
		long slidingWindow = firstLong(meta, arch + ".attention.sliding_window", findKeyBySuffix(meta, ".attention.sliding_window"));

		long headDimK = 0;
		if (keyLength > 0) {
			headDimK = keyLength;
		} else if (nEmbd > 0 && nHead > 0) {
			headDimK = nEmbd / nHead;
		}
		long headDimV = 0;
		if (valueLength > 0) {
			headDimV = valueLength;
		} else {
			headDimV = headDimK;
		}

		return new ModelParams(arch, nLayer, nEmbd, nHeadKv, headDimK, headDimV, slidingWindow, headCountKvByLayer);
	}

	private static long estimateTensorDataBytes(File ggufFile) throws IOException {
		try (RandomAccessFile raf = new RandomAccessFile(ggufFile, "r"); FileChannel ch = raf.getChannel()) {
			long fileSize = ch.size();
			LeReader r = new LeReader(ch, Math.min(32L * 1024 * 1024, fileSize));

			byte[] magic = r.readBytes(4);
			String m = new String(magic, StandardCharsets.US_ASCII);
			if (!"GGUF".equals(m)) {
				return ggufFile.length();
			}

			r.readI32();
			long tensorCount = r.readU64();
			long kvCount = r.readU64();

			long alignment = 32;

			for (long i = 0; i < kvCount; i++) {
				String key = r.readGgufString();
				int type = r.readI32();
				if ("general.alignment".equals(key)) {
					Object val = r.readGgufValue(type);
					if (val instanceof Number) {
						long a = ((Number) val).longValue();
						if (a > 0) {
							alignment = a;
						}
					}
				} else if ("tokenizer.ggml.tokens".equals(key) && type == 9) {
					int elemType = r.readI32();
					long len = r.readU64();
					for (long j = 0; j < len; j++) {
						r.skipGgufValue(elemType);
					}
				} else {
					r.skipGgufValue(type);
				}
			}

			long[] offsets = new long[(int) Math.min(tensorCount, Integer.MAX_VALUE)];
			int count = 0;
			for (long i = 0; i < tensorCount && count < offsets.length; i++) {
				r.readGgufString();
				int nDims = r.readI32();
				for (int d = 0; d < nDims; d++) {
					r.readU64();
				}
				r.readI32();
				long off = r.readU64();
				offsets[count++] = off;
			}

			long pos = r.position();
			long dataStart = alignUp(pos, alignment);
			long dataLen = fileSize - dataStart;
			if (dataLen <= 0 || count == 0) {
				return ggufFile.length();
			}

			Arrays.sort(offsets, 0, count);
			long sum = 0;
			for (int i = 0; i < count; i++) {
				long cur = offsets[i];
				long next = (i + 1 < count) ? offsets[i + 1] : dataLen;
				long size = next - cur;
				if (size > 0) {
					sum = safeAdd(sum, size);
				}
			}
			return sum;
		} catch (EOFException eof) {
			return ggufFile.length();
		}
	}

	private static long alignUp(long value, long alignment) {
		if (alignment <= 0) {
			return value;
		}
		long r = value % alignment;
		if (r == 0) {
			return value;
		}
		return value + (alignment - r);
	}

	private static long safeAdd(long a, long b) {
		long r = a + b;
		if (((a ^ r) & (b ^ r)) < 0) {
			return Long.MAX_VALUE;
		}
		return r;
	}

	private static String asString(Object v) {
		if (v == null) {
			return null;
		}
		if (v instanceof String s) {
			return s;
		}
		return String.valueOf(v);
	}

	private static long firstLong(Map<String, Object> meta, String preferredKey, String fallbackKey) {
		Long v = asLong(meta.get(preferredKey));
		if (v != null) {
			return v;
		}
		if (fallbackKey != null && !fallbackKey.isBlank()) {
			v = asLong(meta.get(fallbackKey));
			if (v != null) {
				return v;
			}
		}
		return 0;
	}

	private static Long asLong(Object v) {
		if (v instanceof Number n) {
			return n.longValue();
		}
		if (v instanceof String s) {
			try {
				return Long.parseLong(s.trim());
			} catch (Exception e) {
				return null;
			}
		}
		return null;
	}

	private static int[] asIntArray(Object v) {
		if (!(v instanceof List<?> list) || list.isEmpty()) {
			return null;
		}
		int[] out = new int[list.size()];
		for (int i = 0; i < list.size(); i++) {
			Object e = list.get(i);
			if (e instanceof Number n) {
				out[i] = n.intValue();
			} else if (e instanceof String s) {
				try {
					out[i] = Integer.parseInt(s.trim());
				} catch (Exception ex) {
					out[i] = 0;
				}
			} else {
				out[i] = 0;
			}
		}
		return out;
	}

	private static String findBySuffix(Map<String, Object> meta, String suffix) {
		if (suffix == null || suffix.isBlank()) {
			return null;
		}
		for (Map.Entry<String, Object> e : meta.entrySet()) {
			String k = e.getKey();
			if (k != null && k.endsWith(suffix)) {
				return asString(e.getValue());
			}
		}
		return null;
	}

	private static String findKeyBySuffix(Map<String, Object> meta, String suffix) {
		if (suffix == null || suffix.isBlank()) {
			return null;
		}
		for (String k : meta.keySet()) {
			if (k != null && k.endsWith(suffix)) {
				return k;
			}
		}
		return null;
	}

	private static final class LeReader {
		private final FileChannel ch;
		private ByteBuffer buf;
		private long pos;

		LeReader(FileChannel ch, long bufferSize) {
			this.ch = ch;
			int cap = (int) Math.min(Integer.MAX_VALUE, Math.max(8192, bufferSize));
			this.buf = ByteBuffer.allocate(cap);
			this.buf.order(ByteOrder.LITTLE_ENDIAN);
			this.buf.limit(0);
			this.pos = 0;
		}

		long position() {
			return pos;
		}

		private void ensure(int n) throws IOException {
			if (n <= buf.remaining()) {
				return;
			}
			if (buf.hasRemaining()) {
				ByteBuffer nb = ByteBuffer.allocate(buf.capacity());
				nb.order(ByteOrder.LITTLE_ENDIAN);
				nb.put(buf);
				nb.flip();
				buf = nb;
			} else {
				buf.clear();
				buf.limit(0);
			}
			while (buf.remaining() < n) {
				buf.compact();
				int read = ch.read(buf);
				buf.flip();
				if (read < 0) {
					throw new EOFException();
				}
			}
		}

		byte[] readBytes(int n) throws IOException {
			ensure(n);
			byte[] b = new byte[n];
			buf.get(b);
			pos += n;
			return b;
		}

		int readI32() throws IOException {
			ensure(4);
			pos += 4;
			return buf.getInt();
		}

		long readU64() throws IOException {
			ensure(8);
			pos += 8;
			return buf.getLong();
		}

		float readF32() throws IOException {
			ensure(4);
			pos += 4;
			return buf.getFloat();
		}

		double readF64() throws IOException {
			ensure(8);
			pos += 8;
			return buf.getDouble();
		}

		boolean readBool() throws IOException {
			ensure(1);
			pos += 1;
			return buf.get() != 0;
		}

		String readGgufString() throws IOException {
			long len = readU64();
			if (len <= 0) {
				return "";
			}
			if (len > Integer.MAX_VALUE) {
				throw new IllegalArgumentException("GGUF string 太长: " + len);
			}
			byte[] b = readBytes((int) len);
			return new String(b, StandardCharsets.UTF_8);
		}

		Object readGgufValue(int type) throws IOException {
			return switch (type) {
			case 0 -> readU8();
			case 1 -> readI8();
			case 2 -> readU16();
			case 3 -> readI16();
			case 4 -> readU32();
			case 5 -> readI32();
			case 6 -> readF32();
			case 7 -> readBool();
			case 8 -> readGgufString();
			case 9 -> readArray();
			case 10 -> readU64();
			case 11 -> readI64();
			case 12 -> readF64();
			default -> throw new IllegalArgumentException("未知GGUF value type: " + type);
			};
		}

		void skipGgufValue(int type) throws IOException {
			switch (type) {
			case 0 -> skip(1);
			case 1 -> skip(1);
			case 2 -> skip(2);
			case 3 -> skip(2);
			case 4 -> skip(4);
			case 5 -> skip(4);
			case 6 -> skip(4);
			case 7 -> skip(1);
			case 8 -> skipGgufString();
			case 9 -> skipArray();
			case 10 -> skip(8);
			case 11 -> skip(8);
			case 12 -> skip(8);
			default -> throw new IllegalArgumentException("未知GGUF value type: " + type);
			}
		}

		private int readU8() throws IOException {
			ensure(1);
			pos += 1;
			return Byte.toUnsignedInt(buf.get());
		}

		private int readI8() throws IOException {
			ensure(1);
			pos += 1;
			return buf.get();
		}

		private int readU16() throws IOException {
			ensure(2);
			pos += 2;
			return Short.toUnsignedInt(buf.getShort());
		}

		private int readI16() throws IOException {
			ensure(2);
			pos += 2;
			return buf.getShort();
		}

		private long readU32() throws IOException {
			ensure(4);
			pos += 4;
			return Integer.toUnsignedLong(buf.getInt());
		}

		private long readI64() throws IOException {
			ensure(8);
			pos += 8;
			return buf.getLong();
		}

		private Object readArray() throws IOException {
			int elemType = readI32();
			long len = readU64();
			List<Object> out = new ArrayList<>();
			for (long i = 0; i < len; i++) {
				out.add(readGgufValue(elemType));
			}
			return out;
		}

		private void skip(int n) throws IOException {
			if (n <= 0) {
				return;
			}
			ensure(n);
			buf.position(buf.position() + n);
			pos += n;
		}

		private void skipGgufString() throws IOException {
			long len = readU64();
			if (len <= 0) {
				return;
			}
			if (len > Integer.MAX_VALUE) {
				throw new IllegalArgumentException("GGUF string 太长: " + len);
			}
			skip((int) len);
		}

		private void skipArray() throws IOException {
			int elemType = readI32();
			long len = readU64();
			for (long i = 0; i < len; i++) {
				skipGgufValue(elemType);
			}
		}
	}
}

