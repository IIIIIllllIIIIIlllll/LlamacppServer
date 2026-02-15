package org.mark.llamacpp.gguf;

import java.io.BufferedInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * GGUF元数据读取器 (简化版)
 * 只保留 fileName, filePath, general.architecture, context_length
 */
public class GGUFMetaData {

	/**
	 * 	文件名
	 */
    private final String fileName;
    
    /**
     * 	文件路径
     */
    private final String filePath;
    
    /**
     * 	架构
     */
    private final String architecture;
    
    /**
     * 	上下文长度
     */
    private final Integer contextLength;
    
    private final Integer fileType;

    private final String baseName;

    private final String name;

    private final String sizeLabel;

    private GGUFMetaData(String fileName, String filePath, String architecture, Integer contextLength, Integer fileType, String baseName, String name, String sizeLabel) {
        this.fileName = fileName;
        this.filePath = filePath;
        this.architecture = architecture;
        this.contextLength = contextLength;
        this.fileType = fileType;
        this.baseName = baseName;
        this.name = name;
        this.sizeLabel = sizeLabel;
    }

    public static GGUFMetaData readFile(File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            return null;
        }

        String architecture = null;
        Integer contextLength = null;
        Integer fileType = null;
        String baseName = null;
        String name = null;
        String sizeLabel = null;

        // 使用 BufferedInputStream 提高读取性能
        try (FileInputStream fis = new FileInputStream(file);
             BufferedInputStream bis = new BufferedInputStream(fis, 128 * 1024); // 128KB buffer
             DataInputStream dis = new DataInputStream(bis)) {
            
            byte[] magicBytes = new byte[4];
            dis.readFully(magicBytes);
            String magic = new String(magicBytes, StandardCharsets.US_ASCII);
            if (!"GGUF".equals(magic)) {
                return null;
            }

            // Version
            readUInt32(dis);
            // TensorCount
            readUInt64(dis);
            // KVCount
            long kvCount = readUInt64(dis);

            for (long i = 0; i < kvCount; i++) {
                long keyLength = readUInt64(dis);
                String key = readString(dis, (int) keyLength);
                int valueTypeId = readUInt32(dis);
                GgufType valueType = GgufType.fromId(valueTypeId);

                boolean handled = false;
                if ("general.architecture".equals(key)) {
                    Object value = readValue(dis, valueType);
                    architecture = value instanceof String ? (String) value : null;
                    handled = true;
                } else if ("general.basename".equals(key)) {
                    Object value = readValue(dis, valueType);
                    baseName = value instanceof String ? (String) value : null;
                    handled = true;
                } else if ("general.name".equals(key)) {
                    Object value = readValue(dis, valueType);
                    name = value instanceof String ? (String) value : null;
                    handled = true;
                } else if ("general.size_label".equals(key)) {
                    Object value = readValue(dis, valueType);
                    sizeLabel = value instanceof String ? (String) value : null;
                    handled = true;
                } else if ("general.file_type".equals(key)) {
                    Object value = readValue(dis, valueType);
                    if (value instanceof Number) {
                        fileType = ((Number) value).intValue();
                    }
                    handled = true;
                } else if (key.endsWith(".context_length")) {
                    // 简单的策略：读取任何以 .context_length 结尾的键
                    Object value = readValue(dis, valueType);
                    if (contextLength == null) {
                        contextLength = value instanceof Integer ? (Integer) value :
                                        (value instanceof Long ? ((Long) value).intValue() : null);
                    }
                    handled = true;
                }

                if (!handled) {
                    skipValue(dis, valueType);
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }

        return new GGUFMetaData(file.getName(), file.getAbsolutePath(), architecture, contextLength, fileType, baseName, name, sizeLabel);
    }

    public String getFileName() {
        return fileName;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getArchitecture() {
        return architecture;
    }

    public Integer getContextLength() {
        return contextLength;
    }
    
    public Integer getFileType() {
    	return fileType;
    }

    public String getBaseName() {
        return baseName;
    }

    public String getName() {
        return name;
    }

    public String getSizeLabel() {
        return sizeLabel;
    }
    
    public String getQuantizationType() {
    	if (fileType == null) return null;
    	return fileTypeToQuantizationName(fileType.intValue());
    }

    /**
     * 兼容旧代码的 Getter
     */
    public String getStringValue(String key) {
        if ("general.architecture".equals(key)) {
            return architecture;
        }
        if ("general.basename".equals(key)) {
            return baseName;
        }
        if ("general.name".equals(key)) {
            return name;
        }
        if ("general.size_label".equals(key)) {
            return sizeLabel;
        }
        return null;
    }

    /**
     * 兼容旧代码的 Getter
     */
    public Integer getIntValue(String key) {
        if (key != null && key.endsWith(".context_length")) {
            return contextLength;
        }
        if ("general.file_type".equals(key)) {
        	return fileType;
        }
        return null;
    }
    
    private static String fileTypeToQuantizationName(int fileType) {
    	return switch (fileType) {
    	case 0 -> "F32";
    	case 1 -> "F16";
    	case 2 -> "Q4_0";
    	case 3 -> "Q4_1";
    	case 7 -> "Q8_0";
    	case 8 -> "Q5_0";
    	case 9 -> "Q5_1";
    	case 10 -> "Q2_K";
    	case 11 -> "Q3_K_S";
    	case 12 -> "Q3_K_M";
    	case 13 -> "Q3_K_L";
    	case 14 -> "Q4_K_S";
    	case 15 -> "Q4_K_M";
    	case 16 -> "Q5_K_S";
    	case 17 -> "Q5_K_M";
    	case 18 -> "Q6_K";
    	case 76 -> "MXFP4";
    	case 38 -> "MXFP4";
    	case 32 -> "BF16";
        case 30 -> "IQ4_XS";
        case 25 -> "IQ4_NL";
    	default -> "UNKNOWN(" + fileType + ")";
    	};
    }

    // --- 内部辅助方法和枚举 ---

    private enum GgufType {
        UINT8(0), INT8(1), UINT16(2), INT16(3), UINT32(4), INT32(5),
        FLOAT32(6), BOOL(7), STRING(8), ARRAY(9), UINT64(10), INT64(11),
        FLOAT64(12), UNKNOWN(-1);
        private final int id;
        GgufType(int id) { this.id = id; }
        public static GgufType fromId(int id) {
            for (GgufType type : values()) { if (type.id == id) return type; }
            return UNKNOWN;
        }
    }

    private static Object readValue(DataInput in, GgufType type) throws IOException {
        switch (type) {
            case UINT8:  return in.readUnsignedByte();
            case INT8:   return in.readByte();
            case UINT16: return readUInt16(in);
            case INT16:  return in.readShort();
            case UINT32: return readUInt32(in);
            case INT32:  return in.readInt();
            case UINT64: return readUInt64(in);
            case INT64:  return in.readLong();
            case FLOAT32:return in.readFloat();
            case FLOAT64:return in.readDouble();
            case BOOL:   return in.readByte() != 0;
            case STRING:
                long strLen = readUInt64(in);
                return readString(in, (int) strLen);
            case ARRAY:
                GgufType elemType = GgufType.fromId(readUInt32(in));
                long elemCount = readUInt64(in);
                Object[] array = new Object[(int) elemCount];
                for (int i = 0; i < elemCount; i++) {
                    array[i] = readValue(in, elemType);
                }
                return array;
            default:
                // 对于不支持的类型，返回null
                return null;
        }
    }

    private static void skipValue(DataInput in, GgufType type) throws IOException {
        switch (type) {
            case UINT8:  skipBytes(in, 1); break;
            case INT8:   skipBytes(in, 1); break;
            case UINT16: skipBytes(in, 2); break;
            case INT16:  skipBytes(in, 2); break;
            case UINT32: skipBytes(in, 4); break;
            case INT32:  skipBytes(in, 4); break;
            case UINT64: skipBytes(in, 8); break;
            case INT64:  skipBytes(in, 8); break;
            case FLOAT32: skipBytes(in, 4); break;
            case FLOAT64: skipBytes(in, 8); break;
            case BOOL:   skipBytes(in, 1); break;
            case STRING:
                long strLen = readUInt64(in);
                skipBytes(in, strLen);
                break;
            case ARRAY:
                GgufType elemType = GgufType.fromId(readUInt32(in));
                long elemCount = readUInt64(in);
                for (int i = 0; i < elemCount; i++) {
                    skipValue(in, elemType);
                }
                break;
            default:
                break;
        }
    }

    private static void skipBytes(DataInput in, long n) throws IOException {
        if (n <= 0) return;
        
        if (in instanceof RandomAccessFile) {
           RandomAccessFile raf = (RandomAccessFile) in;
           while (n > 0) {
               int skipped = raf.skipBytes((int) Math.min(n, Integer.MAX_VALUE));
               if (skipped <= 0) break; 
               n -= skipped;
           }
       } else if (in instanceof DataInputStream) {
            long remaining = n;
            while (remaining > 0) {
                long skipped = ((DataInputStream) in).skip(remaining);
                if (skipped <= 0) {
                	// EOF or stream closed, but DataInputStream might not support skip correctly if wrapped?
                	// fallback to read
                	byte[] buffer = new byte[(int)Math.min(remaining, 4096)];
                	int read = ((DataInputStream) in).read(buffer);
                	if (read < 0) break;
                	skipped = read;
                }
                remaining -= skipped;
            }
       } else {
            // Fallback
            while (n > 0) {
                int skipped = in.skipBytes((int) Math.min(n, Integer.MAX_VALUE));
                if (skipped <= 0) break; 
                n -= skipped;
            }
       }
   }

    private static String readString(DataInput in, int length) throws IOException {
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int readUInt16(DataInput in) throws IOException {
        return Short.toUnsignedInt(Short.reverseBytes(in.readShort()));
    }

    private static int readUInt32(DataInput in) throws IOException {
        return Integer.reverseBytes(in.readInt());
    }

    private static long readUInt64(DataInput in) throws IOException {
        return Long.reverseBytes(in.readLong());
    }
}
