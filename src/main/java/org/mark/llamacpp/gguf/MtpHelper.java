package org.mark.llamacpp.gguf;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.*;

import com.google.gson.*;

/**
 * MTP (Multi-Token Prediction) helper for GGUF models.
 * <p>
 * Provides three operations:
 * <ul>
 *   <li>{@link #detectMtpInfo(File)} — detect whether a GGUF has MTP layers</li>
 *   <li>{@link #extractDonor(File, File)} — extract MTP tensors into a lightweight donor file</li>
 *   <li>{@link #mergeDonor(File, File, File)} — transplant MTP tensors from a donor into a base model</li>
 * </ul>
 * <p>
 * All methods are static and architecture-agnostic. The architecture name is read
 * dynamically from {@code general.architecture} in the GGUF header.
 */
public final class MtpHelper {

    @FunctionalInterface
    private interface BufferParser<T> {
        T parse(ByteBuffer buffer) throws IOException;
    }

    private MtpHelper() {}

    // ── GGUF value type constants ──────────────────────────────────────────

    private static final int UINT8 = 0;
    private static final int INT8 = 1;
    private static final int UINT16 = 2;
    private static final int INT16 = 3;
    private static final int UINT32 = 4;
    private static final int INT32 = 5;
    private static final int FLOAT32 = 6;
    private static final int BOOL = 7;
    private static final int STRING = 8;
    private static final int ARRAY = 9;
    private static final int UINT64 = 10;
    private static final int INT64 = 11;
    private static final int FLOAT64 = 12;

    // ── Public records ─────────────────────────────────────────────────────

    /**
     * MTP layer detection result.
     */
    public record MtpInfo(
        boolean hasMtp,
        String architecture,
        int blockCount,
        int nextnPredictLayers,
        int trunkCount,
        List<String> mtpBlockPrefixes
    ) {
        public static MtpInfo none() {
            return new MtpInfo(false, null, 0, 0, 0, Collections.emptyList());
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Detect MTP layers in a GGUF file by reading KV metadata.
     * <p>
     * MTP exists when {@code {arch}.nextn_predict_layers > 0}. The MTP blocks
     * occupy indices {@code [block_count - nextn_predict_layers, block_count)}.
     *
     * @param file the GGUF file
     * @return MtpInfo with detection results, or {@link MtpInfo#none()} if no MTP
     */
    public static MtpInfo detectMtpInfo(File file) {
        Map<String, Object> meta = readMetadata(file);
        if (meta.isEmpty()) return MtpInfo.none();

        String arch = (String) meta.get("general.architecture");
        if (arch == null) return MtpInfo.none();

        Number blockCount = (Number) meta.get(arch + ".block_count");
        Number nextn = (Number) meta.get(arch + ".nextn_predict_layers");
        if (nextn == null || nextn.intValue() <= 0) return MtpInfo.none();
        if (blockCount == null) return MtpInfo.none();

        int bc = blockCount.intValue();
        int nn = nextn.intValue();
        int trunk = bc - nn;

        List<String> prefixes = new ArrayList<>(nn);
        for (int i = 0; i < nn; i++) {
            prefixes.add("blk." + (trunk + i) + ".");
        }
        return new MtpInfo(true, arch, bc, nn, trunk, prefixes);
    }

    /**
     * Extract MTP tensors from a full GGUF into a lightweight donor GGUF file.
     * <p>
     * The donor contains only MTP tensors plus essential KV metadata
     * (architecture, block_count, nextn_predict_layers, alignment, name, description).
     *
     * @param source the full GGUF containing MTP layers
     * @param output the output donor GGUF file
     * @throws IOException on file I/O error
     * @throws IllegalArgumentException if no MTP layers are found
     */
    public static void extractDonor(File source, File output) throws IOException {
        MtpInfo mtpInfo = detectMtpInfo(source);
        if (!mtpInfo.hasMtp()) {
            throw new IllegalArgumentException("No MTP layers found in " + source);
        }

        Map<String, Object> meta = readMetadata(source);
        int alignment = meta.containsKey("general.alignment")
            ? ((Number) meta.get("general.alignment")).intValue()
            : 32;
        String arch = mtpInfo.architecture();

        List<TensorInfo> allTensors = new ArrayList<>();
        List<Long> onDiskSizes = new ArrayList<>();
        parseGguf(
            source,
            source.length(),
            alignment,
            new LinkedHashMap<>(),
            new LinkedHashMap<>(),
            allTensors,
            onDiskSizes
        );

        List<String> mtpPrefixes = mtpInfo.mtpBlockPrefixes();
        List<TensorInfo> mtpTensors = new ArrayList<>();
        for (TensorInfo t : allTensors) {
            for (String prefix : mtpPrefixes) {
                if (t.name.startsWith(prefix)) {
                    mtpTensors.add(t);
                    break;
                }
            }
        }
        if (mtpTensors.isEmpty()) {
            throw new IllegalArgumentException(
                "No MTP tensors found (nextn_predict_layers=" + mtpInfo.nextnPredictLayers() + ")");
        }

        try (RandomAccessFile raf = new RandomAccessFile(source, "r");
             FileChannel channel = raf.getChannel();
             RandomAccessFile outRaf = new RandomAccessFile(output, "rw")) {

            outRaf.write("GGUF".getBytes(StandardCharsets.US_ASCII));
            writeULE32(outRaf, 3);
            writeULE64(outRaf, (long) mtpTensors.size());

            String donorKvJson = (String) meta.get("general.mtp_donor_kv_json");
            if (donorKvJson != null) {
                JsonObject donorKvData = JsonParser.parseString(donorKvJson).getAsJsonObject();
                writeULE64(outRaf, (long) donorKvData.size());
                for (Map.Entry<String, JsonElement> e : donorKvData.entrySet()) {
                    writeJsonBackedKV(outRaf, e.getKey(), e.getValue().getAsJsonObject());
                }
            } else {
                List<String> kvKeys = Arrays.asList(
                    "general.architecture", "general.name", "general.description",
                    "general.alignment", arch + ".block_count", arch + ".nextn_predict_layers"
                );
                kvKeys = kvKeys.stream().filter(k -> meta.containsKey(k)).collect(Collectors.toList());

                writeULE64(outRaf, (long) kvKeys.size());
                for (String key : kvKeys) {
                    Object value = meta.get(key);
                    if (value instanceof String s) {
                        writeStringKV(outRaf, key, s);
                    } else {
                        writeUint32KV(outRaf, key, ((Number) value).intValue());
                    }
                }
            }

            long currentDataOffset = 0;
            long[] dataOffsets = new long[mtpTensors.size()];
            for (int i = 0; i < mtpTensors.size(); i++) {
                dataOffsets[i] = currentDataOffset;
                currentDataOffset += mtpTensors.get(i).dataSize;
            }
            for (int i = 0; i < mtpTensors.size(); i++) {
                TensorInfo t = mtpTensors.get(i);
                byte[] nb = t.name.getBytes(StandardCharsets.UTF_8);
                writeULE64(outRaf, (long) nb.length);
                outRaf.write(nb);
                writeULE32(outRaf, t.shape.size());
                for (long dim : t.shape) writeULE64(outRaf, dim);
                writeULE32(outRaf, t.tensorType);
                writeULE64(outRaf, dataOffsets[i]);
            }

            long pos = outRaf.getFilePointer();
            long padding = (alignment - (pos % alignment)) % alignment;
            for (int i = 0; i < padding; i++) outRaf.write(0);

            ByteBuffer chunk = ByteBuffer.allocate(8192);
            for (TensorInfo t : mtpTensors) {
                channel.position(t.dataOffset);
                long remaining = t.dataSize;
                while (remaining > 0) {
                    int toRead = (int) Math.min(remaining, 8192);
                    chunk.clear();
                    chunk.limit(toRead);
                    int bytesRead = channel.read(chunk);
                    if (bytesRead == -1) throw new IOException("Unexpected EOF reading tensor " + t.name);
                    chunk.flip();
                    while (chunk.hasRemaining()) outRaf.getChannel().write(chunk);
                    remaining -= bytesRead;
                }
            }
        }
    }

    /**
     * Merge MTP tensors from a donor GGUF into a base model GGUF.
     * <p>
     * The base model's tensors and metadata are kept as-is. MTP tensors from the
     * donor (those with {@code blk.{base_block_count}.*} prefix) are appended.
     * The output {@code block_count} is overridden with the donor's value.
     * <p>
     * Per-row metadata for quantizations like IQ4_KS is preserved by copying
     * raw on-disk tensor data (including padding between tensors).
     *
     * @param base   the base GGUF (tensors + metadata kept as-is)
     * @param donor  the GGUF with extra MTP blocks to transplant
     * @param output the resulting merged GGUF
     * @throws IOException on file I/O error
     */
    public static void mergeDonor(File base, File donor, File output) throws IOException {
        long baseFileSize = base.length();
        long donorFileSize = donor.length();
        int alignment = 32;

        Map<String, Object> baseMeta = new LinkedHashMap<>();
        Map<String, Integer> baseKvTypes = new LinkedHashMap<>();
        List<TensorInfo> baseTensors = new ArrayList<>();
        List<Long> baseOnDiskSizes = new ArrayList<>();

        Map<String, Object> donorMeta = new LinkedHashMap<>();
        Map<String, Integer> donorKvTypes = new LinkedHashMap<>();
        List<TensorInfo> donorTensors = new ArrayList<>();
        List<Long> donorOnDiskSizes = new ArrayList<>();

        parseGguf(base, baseFileSize, alignment, baseMeta, baseKvTypes, baseTensors, baseOnDiskSizes);
        alignment = baseMeta.containsKey("general.alignment")
            ? ((Number) baseMeta.get("general.alignment")).intValue()
            : 32;
        parseGguf(donor, donorFileSize, alignment, donorMeta, donorKvTypes, donorTensors, donorOnDiskSizes);

        String arch = (String) baseMeta.get("general.architecture");
        if (arch == null) {
            throw new IllegalArgumentException("Base GGUF has no general.architecture key");
        }

        Number donorBlockCount = (Number) donorMeta.get(arch + ".block_count");
        Number donorNextn = (Number) donorMeta.get(arch + ".nextn_predict_layers");
        if (donorNextn == null || donorNextn.intValue() <= 0) {
            throw new IllegalArgumentException("Donor GGUF has no nextn_predict_layers key");
        }

        Number baseBlockCount = (Number) baseMeta.get(arch + ".block_count");
        if (baseBlockCount == null) {
            throw new IllegalArgumentException("Base GGUF has no block_count key");
        }

        Map<String, TensorInfo> donorTensorMap = new LinkedHashMap<>();
        for (int i = 0; i < donorTensors.size(); i++) {
            donorTensorMap.put(donorTensors.get(i).name, donorTensors.get(i));
        }

        List<TensorInfo> extraTensors = new ArrayList<>();
        List<Long> extraSizes = new ArrayList<>();
        String prefix = "blk." + baseBlockCount + ".";
        for (int i = 0; i < donorTensors.size(); i++) {
            TensorInfo t = donorTensors.get(i);
            if (t.name.startsWith(prefix)) {
                extraTensors.add(t);
                extraSizes.add(donorOnDiskSizes.get(i));
            }
        }
        if (extraTensors.isEmpty()) {
            throw new IllegalArgumentException(
                "No tensors found with prefix '" + prefix + "' in donor");
        }

        List<TensorInfo> allTensors = new ArrayList<>(baseTensors.size() + extraTensors.size());
        allTensors.addAll(baseTensors);
        allTensors.addAll(extraTensors);

        List<Long> allOnDiskSizes = new ArrayList<>(baseOnDiskSizes.size() + extraSizes.size());
        allOnDiskSizes.addAll(baseOnDiskSizes);
        allOnDiskSizes.addAll(extraSizes);

        JsonObject donorKvData = serializeDonorKVs(donorMeta, donorKvTypes);
        String donorKvJson = donorKvData.toString();

        try (RandomAccessFile baseFin = new RandomAccessFile(base, "r");
             FileChannel baseCh = baseFin.getChannel();
             RandomAccessFile donorFin = new RandomAccessFile(donor, "r");
             FileChannel donorCh = donorFin.getChannel();
             RandomAccessFile fout = new RandomAccessFile(output, "rw")) {

            fout.write("GGUF".getBytes(StandardCharsets.US_ASCII));
            writeULE32(fout, 3);
            writeULE64(fout, (long) allTensors.size());

            int kvCount = 0;
            for (String k : baseMeta.keySet()) {
                if (!k.startsWith("GGUF.") && !k.equals(arch + ".block_count")) {
                    kvCount++;
                }
            }
            kvCount++; // block_count override
            kvCount++; // nextn_predict_layers
            for (String k : donorMeta.keySet()) {
                if (!k.startsWith("GGUF.")
                    && !baseMeta.containsKey(k)
                    && !k.equals(arch + ".block_count")
                    && !k.equals(arch + ".nextn_predict_layers")) {
                    kvCount++;
                }
            }
            kvCount++; // general.mtp_donor_kv_json
            writeULE64(fout, (long) kvCount);

            Set<String> writtenKeys = new LinkedHashSet<>();

            for (String key : baseMeta.keySet()) {
                if (key.startsWith("GGUF.")) continue;
                if (key.equals(arch + ".block_count")) continue;
                int type = baseKvTypes.getOrDefault(key, 0);
                writeKV(fout, key, type, baseMeta.get(key));
                writtenKeys.add(key);
            }

            // Override block_count with donor value
            writeUint32KV(fout, arch + ".block_count", donorBlockCount.intValue());
            writtenKeys.add(arch + ".block_count");

            // Add nextn_predict_layers
            writeUint32KV(fout, arch + ".nextn_predict_layers", donorNextn.intValue());
            writtenKeys.add(arch + ".nextn_predict_layers");

            // Copy donor-only KVs
            for (String key : donorMeta.keySet()) {
                if (key.startsWith("GGUF.")) continue;
                if (writtenKeys.contains(key)) continue;
                if (key.equals(arch + ".nextn_predict_layers")) continue;
                int type = donorKvTypes.getOrDefault(key, 0);
                writeKV(fout, key, type, donorMeta.get(key));
                writtenKeys.add(key);
            }

            // Store serialized donor KVs
            writeStringKV(fout, "general.mtp_donor_kv_json", donorKvJson);

            // Calculate tensor data offsets
            long currentOffset = 0;
            long[] tensorOffsets = new long[allTensors.size()];
            for (int i = 0; i < allTensors.size(); i++) {
                tensorOffsets[i] = currentOffset;
                currentOffset += allOnDiskSizes.get(i);
            }

            // Write tensor info
            for (int i = 0; i < allTensors.size(); i++) {
                TensorInfo t = allTensors.get(i);
                byte[] nb = t.name.getBytes(StandardCharsets.UTF_8);
                writeULE64(fout, (long) nb.length);
                fout.write(nb);
                writeULE32(fout, t.shape.size());
                for (long dim : t.shape) writeULE64(fout, dim);
                writeULE32(fout, t.tensorType);
                writeULE64(fout, tensorOffsets[i]);
            }

            // Padding
            long pos = fout.getFilePointer();
            long padding = (alignment - (pos % alignment)) % alignment;
            for (int i = 0; i < padding; i++) fout.write(0);

            // Copy tensor data
            ByteBuffer chunk = ByteBuffer.allocate(65536);
            long baseTensorCount = baseTensors.size();
            for (int i = 0; i < allTensors.size(); i++) {
                FileChannel srcCh;
                long absOffset;
                long size = allOnDiskSizes.get(i);

                if (i < baseTensorCount) {
                    absOffset = baseTensors.get(i).dataOffset;
                    srcCh = baseCh;
                } else {
                    TensorInfo extraT = extraTensors.get(i - (int) baseTensorCount);
                    absOffset = extraT.dataOffset;
                    srcCh = donorCh;
                }

                long remaining = size;
                srcCh.position(absOffset);
                while (remaining > 0) {
                    int toRead = (int) Math.min(remaining, 65536);
                    chunk.clear();
                    chunk.limit(toRead);
                    int bytesRead = srcCh.read(chunk);
                    if (bytesRead == -1) throw new IOException("Unexpected EOF at tensor " + i);
                    chunk.flip();
                    while (chunk.hasRemaining()) fout.getChannel().write(chunk);
                    remaining -= bytesRead;
                }
            }
        }
    }

    // ── GGUF parsing ───────────────────────────────────────────────────────

    /**
     * Parse a GGUF file into metadata, tensor info, and on-disk sizes.
     */
    static void parseGguf(
        File file, long fileSize, int alignment,
        Map<String, Object> metaOut, Map<String, Integer> kvTypesOut,
        List<TensorInfo> tensorsOut, List<Long> onDiskSizesOut
    ) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r");
             FileChannel channel = raf.getChannel()) {
            ParsedGguf parsed = parseWithGrowingBuffer(channel, fileSize, buffer -> {
                skipMagic(buffer);
                buffer.getInt(); // version
                long tensorCount = readULE64(buffer);
                long kvCount = readULE64(buffer);

                LinkedHashMap<String, Object> meta = new LinkedHashMap<>();
                LinkedHashMap<String, Integer> kvTypes = new LinkedHashMap<>();
                for (long i = 0; i < kvCount; i++) {
                    String key = readString(buffer);
                    int type = buffer.getInt();
                    kvTypes.put(key, type);
                    Object value = readValue(buffer, type);
                    meta.put(key, value);
                }

                List<TensorInfo> tensors = new ArrayList<>((int) tensorCount);
                for (long i = 0; i < tensorCount; i++) {
                    String tname = readString(buffer);
                    int nDims = buffer.getInt();
                    List<Long> shape = new ArrayList<>(nDims);
                    for (int j = 0; j < nDims; j++) shape.add(readULE64(buffer));
                    int ttype = buffer.getInt();
                    long off = readULE64(buffer);
                    tensors.add(new TensorInfo(tname, shape, ttype, off, 0));
                }

                int effectiveAlignment = meta.containsKey("general.alignment")
                    ? ((Number) meta.get("general.alignment")).intValue()
                    : alignment;
                long posAfterTi = buffer.position();
                long padToAlign = (effectiveAlignment - (posAfterTi % effectiveAlignment)) % effectiveAlignment;
                long dataSectionStart = posAfterTi + padToAlign;

                List<TensorInfo> tensorsWithSizes = new ArrayList<>(tensors.size());
                for (int i = 0; i < tensors.size(); i++) {
                    TensorInfo t = tensors.get(i);
                    long absOff = dataSectionStart + t.dataOffset;
                    long sz = (i < tensors.size() - 1)
                        ? (dataSectionStart + tensors.get(i + 1).dataOffset) - absOff
                        : fileSize - absOff;
                    tensorsWithSizes.add(new TensorInfo(t.name, t.shape, t.tensorType, absOff, sz));
                }

                return new ParsedGguf(meta, kvTypes, tensorsWithSizes);
            });

            metaOut.putAll(parsed.meta());
            kvTypesOut.putAll(parsed.kvTypes());
            tensorsOut.addAll(parsed.tensors());
            for (TensorInfo t : parsed.tensors()) {
                onDiskSizesOut.add(t.dataSize);
            }
        }
    }

    /**
     * Read only KV metadata from a GGUF file (skips tensor data).
     */
    static Map<String, Object> readMetadata(File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            return Collections.emptyMap();
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "r");
             FileChannel channel = raf.getChannel()) {
            long size = channel.size();
            Map<String, Object> metadata = parseWithGrowingBuffer(channel, size, buffer -> {
                byte[] magic = new byte[4];
                buffer.get(magic);
                if (!"GGUF".equals(new String(magic, StandardCharsets.US_ASCII))) {
                    return Collections.emptyMap();
                }
                buffer.getInt(); // version
                readULE64(buffer); // tensor count (skip)
                long kvCount = readULE64(buffer);

                Map<String, Object> result = new HashMap<>();
                for (long i = 0; i < kvCount; i++) {
                    String key = readString(buffer);
                    int type = buffer.getInt();
                    if ("tokenizer.ggml.tokens".equals(key) && type == ARRAY) {
                        int elemType = buffer.getInt();
                        long len = readULE64(buffer);
                        for (long j = 0; j < len; j++) {
                            skipValue(buffer, elemType);
                        }
                        result.put(key + ".size", len);
                    } else {
                        Object value = readValue(buffer, type);
                        result.put(key, value);
                    }
                }
                return result;
            });
            metadata.put("file.name", file.getName());
            metadata.put("file.path", file.getAbsolutePath());
            return metadata;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    static <T> T parseWithGrowingBuffer(
        FileChannel channel,
        long fileSize,
        BufferParser<T> parser
    ) throws IOException {
        if (fileSize <= 0) {
            throw new EOFException("Empty GGUF file");
        }

        long maxReadableSize = Math.min(fileSize, (long) Integer.MAX_VALUE);
        long bufSize = Math.min(maxReadableSize, 8L * 1024 * 1024);
        if (bufSize <= 0) {
            bufSize = maxReadableSize;
        }

        while (true) {
            ByteBuffer buffer = ByteBuffer.allocate((int) bufSize);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            channel.position(0);
            while (buffer.hasRemaining()) {
                int n = channel.read(buffer);
                if (n == -1) {
                    break;
                }
            }
            buffer.flip();

            try {
                return parser.parse(buffer);
            } catch (BufferUnderflowException e) {
                if (bufSize >= maxReadableSize) {
                    throw new IOException("GGUF header exceeds readable prefix size: " + fileSize, e);
                }
                long nextSize = Math.min(maxReadableSize, Math.max(bufSize * 2, bufSize + 8L * 1024 * 1024));
                if (nextSize <= bufSize) {
                    throw new IOException("Failed to grow GGUF header buffer", e);
                }
                bufSize = nextSize;
            }
        }
    }

    // ── Serialize donor KVs to JSON for round-trip ─────────────────────────

    static JsonObject serializeDonorKVs(Map<String, Object> meta, Map<String, Integer> types) {
        JsonObject result = new JsonObject();
        for (Map.Entry<String, Object> entry : meta.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("GGUF.")) continue;
            if (key.equals("file.name") || key.equals("file.path")) continue;

            int type = types.getOrDefault(key, 0);
            JsonArray typeArr = new JsonArray();
            typeArr.add(type);

            Object val = entry.getValue();
            if (type == ARRAY) {
                @SuppressWarnings("unchecked")
                List<Object> arr = (List<Object>) val;
                if (arr != null && !arr.isEmpty()) {
                    int subType = -1;
                    for (Object elem : arr) {
                        if (elem instanceof Number || elem instanceof Boolean || elem instanceof String) {
                            subType = inferValueType(elem);
                            break;
                        }
                    }
                    if (subType >= 0) typeArr.add(subType);
                }
            }

            JsonObject kvObj = new JsonObject();
            kvObj.add("types", typeArr);
            kvObj.add("value", toJsonElement(val, type));
            result.add(key, kvObj);
        }
        return result;
    }

    static int inferValueType(Object val) {
        if (val instanceof Boolean) return BOOL;
        if (val instanceof String) return STRING;
        if (val instanceof Float) return FLOAT32;
        if (val instanceof Double) return FLOAT64;
        if (val instanceof Integer) return UINT32;
        if (val instanceof Long) return UINT64;
        return UINT32;
    }

    static JsonElement toJsonElement(Object val, int type) {
        if (val == null) return JsonNull.INSTANCE;
        if (type == ARRAY) {
            @SuppressWarnings("unchecked")
            List<Object> arr = (List<Object>) val;
            JsonArray jarr = new JsonArray();
            if (arr != null) {
                for (Object elem : arr) {
                    jarr.add(toJsonElement(elem, -1));
                }
            }
            return jarr;
        }
        if (val instanceof Boolean b) return new JsonPrimitive(b);
        if (val instanceof String s) return new JsonPrimitive(s);
        if (val instanceof Number n) {
            if (val instanceof Float) return new JsonPrimitive(n.floatValue());
            if (val instanceof Double) return new JsonPrimitive(n.doubleValue());
            if (val instanceof Integer) return new JsonPrimitive(n.intValue());
            return new JsonPrimitive(n.longValue());
        }
        return JsonNull.INSTANCE;
    }

    // ── ByteBuffer read helpers ────────────────────────────────────────────

    static void skipMagic(ByteBuffer buffer) {
        ensureRemaining(buffer, 4);
        buffer.position(buffer.position() + 4);
    }

    static long readULE64(ByteBuffer buffer) {
        return buffer.getLong();
    }

    static String readString(ByteBuffer buffer) {
        long len = readULE64(buffer);
        ensureRemaining(buffer, len);
        byte[] bytes = new byte[(int) len];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static void skipString(ByteBuffer buffer) {
        long len = readULE64(buffer);
        ensureRemaining(buffer, len);
        buffer.position(buffer.position() + (int) len);
    }

    static Object readValue(ByteBuffer buffer, int type) {
        switch (type) {
            case UINT8:  return buffer.get() & 0xFF;
            case INT8:   return buffer.get();
            case UINT16: return Short.toUnsignedInt(buffer.getShort());
            case INT16:  return buffer.getShort();
            case UINT32: return buffer.getInt() & 0xFFFFFFFFL;
            case INT32:  return buffer.getInt();
            case FLOAT32: return buffer.getFloat();
            case BOOL:   return buffer.get() != 0;
            case STRING: return readString(buffer);
            case ARRAY: {
                int subType = buffer.getInt();
                long len = readULE64(buffer);
                List<Object> list = new ArrayList<>((int) len);
                for (int i = 0; i < len; i++) {
                    list.add(readValue(buffer, subType));
                }
                return list;
            }
            case UINT64: return buffer.getLong();
            case INT64:  return buffer.getLong();
            case FLOAT64: return buffer.getDouble();
            default: throw new IllegalArgumentException("Unknown GGUF value type: " + type);
        }
    }

    static void skipValue(ByteBuffer buffer, int type) {
        switch (type) {
            case UINT8: case INT8: case BOOL:
                buffer.get(); break;
            case UINT16: case INT16:
                buffer.getShort(); break;
            case UINT32: case INT32: case FLOAT32:
                buffer.getInt(); break;
            case UINT64: case INT64: case FLOAT64:
                buffer.getLong(); break;
            case STRING: {
                long len = readULE64(buffer);
                ensureRemaining(buffer, len);
                buffer.position(buffer.position() + (int) len);
                break;
            }
            case ARRAY: {
                int subType = buffer.getInt();
                long len = readULE64(buffer);
                for (int i = 0; i < len; i++) {
                    skipValue(buffer, subType);
                }
                break;
            }
            default: break;
        }
    }

    static void ensureRemaining(ByteBuffer buffer, long needed) {
        if (needed < 0 || needed > Integer.MAX_VALUE || buffer.remaining() < (int) needed) {
            throw new BufferUnderflowException();
        }
    }

    // ── RandomAccessFile write helpers ─────────────────────────────────────

    static void writeULE32(RandomAccessFile raf, int v) throws IOException {
        raf.writeByte(v & 0xFF);
        raf.writeByte((v >> 8) & 0xFF);
        raf.writeByte((v >> 16) & 0xFF);
        raf.writeByte((v >> 24) & 0xFF);
    }

    static void writeULE64(RandomAccessFile raf, long v) throws IOException {
        raf.writeByte((int) (v & 0xFF));
        raf.writeByte((int) ((v >> 8) & 0xFF));
        raf.writeByte((int) ((v >> 16) & 0xFF));
        raf.writeByte((int) ((v >> 24) & 0xFF));
        raf.writeByte((int) ((v >> 32) & 0xFF));
        raf.writeByte((int) ((v >> 40) & 0xFF));
        raf.writeByte((int) ((v >> 48) & 0xFF));
        raf.writeByte((int) ((v >> 56) & 0xFF));
    }

    static void writeKV(RandomAccessFile raf, String key, int type, Object value) throws IOException {
        byte[] kb = key.getBytes(StandardCharsets.UTF_8);
        writeULE64(raf, (long) kb.length);
        raf.write(kb);
        writeULE32(raf, type);
        writeValue(raf, type, value);
    }

    static void writeValue(RandomAccessFile raf, int type, Object value) throws IOException {
        switch (type) {
            case UINT8: case INT8:
                raf.write(((Number) value).byteValue()); break;
            case UINT16: case INT16: {
                int v = ((Number) value).intValue();
                raf.writeByte(v & 0xFF);
                raf.writeByte((v >> 8) & 0xFF);
                break;
            }
            case UINT32: case INT32:
                writeULE32(raf, ((Number) value).intValue()); break;
            case FLOAT32:
                writeULE32(raf, Float.floatToIntBits(((Number) value).floatValue())); break;
            case BOOL:
                raf.write(value instanceof Boolean ? (((Boolean) value) ? 1 : 0) : ((Number) value).byteValue());
                break;
            case STRING: {
                String s = (String) value;
                byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                writeULE64(raf, (long) bytes.length);
                raf.write(bytes);
                break;
            }
            case ARRAY: {
                List<?> list = (List<?>) value;
                if (list != null && !list.isEmpty()) {
                    int subType = inferValueType(list.get(0));
                    writeULE32(raf, subType);
                    writeULE64(raf, (long) list.size());
                    for (Object elem : list) {
                        writeValue(raf, subType, elem);
                    }
                } else {
                    writeULE32(raf, UINT32);
                    writeULE64(raf, 0);
                }
                break;
            }
            case UINT64: case INT64:
                writeULE64(raf, ((Number) value).longValue()); break;
            case FLOAT64:
                writeULE64(raf, Double.doubleToLongBits(((Number) value).doubleValue())); break;
        }
    }

    static void writeStringKV(RandomAccessFile raf, String key, String value) throws IOException {
        byte[] kb = key.getBytes(StandardCharsets.UTF_8);
        writeULE64(raf, (long) kb.length);
        raf.write(kb);
        writeULE32(raf, STRING);
        byte[] vb = value.getBytes(StandardCharsets.UTF_8);
        writeULE64(raf, (long) vb.length);
        raf.write(vb);
    }

    static void writeUint32KV(RandomAccessFile raf, String key, int value) throws IOException {
        byte[] kb = key.getBytes(StandardCharsets.UTF_8);
        writeULE64(raf, (long) kb.length);
        raf.write(kb);
        writeULE32(raf, UINT32);
        writeULE32(raf, value);
    }

    // ── JSON-backed KV round-trip ──────────────────────────────────────────

    static void writeJsonBackedKV(
        RandomAccessFile raf,
        String key,
        JsonObject kv
    ) throws IOException {
        JsonArray types = kv.getAsJsonArray("types");
        int type = types.get(0).getAsInt();
        int subType = types.size() > 1 ? types.get(1).getAsInt() : -1;
        byte[] kb = key.getBytes(StandardCharsets.UTF_8);
        writeULE64(raf, (long) kb.length);
        raf.write(kb);
        writeULE32(raf, type);
        writeJsonValue(raf, type, kv.get("value"), subType);
    }

    static void writeJsonValue(
        RandomAccessFile raf,
        int type,
        JsonElement value,
        int subType
    ) throws IOException {
        switch (type) {
            case UINT8: case INT8:
                raf.write(jsonAsInt(value)); break;
            case UINT16: case INT16: {
                int v = jsonAsInt(value);
                raf.writeByte(v & 0xFF);
                raf.writeByte((v >> 8) & 0xFF);
                break;
            }
            case UINT32: case INT32:
                writeULE32(raf, jsonAsInt(value)); break;
            case FLOAT32:
                writeULE32(raf, Float.floatToIntBits(value.getAsFloat())); break;
            case BOOL:
                raf.write(jsonAsBoolean(value) ? 1 : 0); break;
            case STRING: {
                String s = value.getAsString();
                byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                writeULE64(raf, (long) bytes.length);
                raf.write(bytes);
                break;
            }
            case ARRAY:
                writeJsonArrayValue(raf, subType, value.getAsJsonArray()); break;
            case UINT64: case INT64:
                writeULE64(raf, jsonAsLong(value)); break;
            case FLOAT64:
                writeULE64(raf, Double.doubleToLongBits(value.getAsDouble())); break;
            default:
                throw new IllegalArgumentException("Unknown GGUF value type: " + type);
        }
    }

    static void writeJsonArrayValue(
        RandomAccessFile raf,
        int subType,
        JsonArray list
    ) throws IOException {
        writeULE32(raf, subType);
        writeULE64(raf, (long) list.size());
        for (JsonElement elem : list) {
            writeJsonValue(raf, subType, elem, -1);
        }
    }

    static int jsonAsInt(JsonElement value) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
            return value.getAsBoolean() ? 1 : 0;
        }
        return value.getAsInt();
    }

    static long jsonAsLong(JsonElement value) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
            return value.getAsBoolean() ? 1L : 0L;
        }
        return value.getAsLong();
    }

    static boolean jsonAsBoolean(JsonElement value) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
            return value.getAsBoolean();
        }
        return value.getAsInt() != 0;
    }

    // ── Internal tensor record ─────────────────────────────────────────────

    private static record TensorInfo(
        String name, List<Long> shape, int tensorType, long dataOffset, long dataSize
    ) {}

    private static record ParsedGguf(
        Map<String, Object> meta,
        Map<String, Integer> kvTypes,
        List<TensorInfo> tensors
    ) {}
}
