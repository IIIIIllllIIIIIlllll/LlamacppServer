package org.mark.llamacpp.gguf;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GGUFBundle {
	private File primaryFile;
	private List<File> splitFiles = new ArrayList<>();
	private File mmprojFile;

	public GGUFBundle(File inputFile) {
		if (!inputFile.exists()) {
			throw new IllegalArgumentException("File not found: " + inputFile.getAbsolutePath());
		}
		resolveFiles(inputFile);
	}

	private void resolveFiles(File inputFile) {
		String fileName = inputFile.getName();
		File directory = inputFile.getParentFile();
		if (directory == null) {
			directory = new File(".");
		}

		// Pattern for splits: "name-00001-of-00005.gguf"
		// Also supports simple "name.gguf"
		Pattern splitPattern = Pattern.compile("^(.*)-(\\d{5})-of-(\\d{5})\\.gguf$");
		Matcher matcher = splitPattern.matcher(fileName);

		if (matcher.matches()) {
			String baseName = matcher.group(1);
			// int currentPart = Integer.parseInt(matcher.group(2));
			int totalParts = Integer.parseInt(matcher.group(3));

			this.primaryFile = new File(directory, String.format("%s-00001-of-%05d.gguf", baseName, totalParts));

			// Find all parts
			for (int i = 1; i <= totalParts; i++) {
				File part = new File(directory, String.format("%s-%05d-of-%05d.gguf", baseName, i, totalParts));
				if (part.exists()) {
					splitFiles.add(part);
				} else {
					System.err.println("Warning: Missing split file: " + part.getName());
				}
			}

			// Look for mmproj
			findMmproj(directory, baseName);
		} else {
			// Not a split pattern, assume single file
			this.primaryFile = inputFile;
			this.splitFiles.add(inputFile);

			// Try to find base name for mmproj search
			// If file is "qwen2-vl-7b-instruct-q4_k_m.gguf", base name might be
			// "qwen2-vl-7b-instruct-q4_k_m"
			String baseName = fileName.endsWith(".gguf") ? fileName.substring(0, fileName.length() - 5) : fileName;
			findMmproj(directory, baseName);
		}
	}

	private void findMmproj(File directory, String baseName) {
		// Heuristic 1: mmproj-modelname.gguf
		File candidate1 = new File(directory, "mmproj-" + baseName + ".gguf");
		if (candidate1.exists()) {
			this.mmprojFile = candidate1;
			return;
		}

		// Heuristic 2: modelname-mmproj.gguf
		File candidate2 = new File(directory, baseName + "-mmproj.gguf");
		if (candidate2.exists()) {
			this.mmprojFile = candidate2;
			return;
		}

		// Heuristic 3: loose search in directory
		// Look for any file containing "mmproj" and sharing a significant prefix
		File[] files = directory.listFiles((dir, name) -> name.contains("mmproj") && name.endsWith(".gguf"));
		if (files != null && files.length > 0) {
			// Pick the one that seems most related (e.g. longest common substring or just
			// the first one)
			// For now, let's pick the first one and warn if multiple
			this.mmprojFile = files[0];
			if (files.length > 1) {
				System.out.println("Warning: Multiple mmproj files found. Using: " + files[0].getName());
			}
		}
	}

	public File getPrimaryFile() {
		return primaryFile;
	}

	public List<File> getSplitFiles() {
		return splitFiles;
	}

	public File getMmprojFile() {
		return mmprojFile;
	}

	public long getTotalFileSize() {
		long size = 0;
		for (File f : splitFiles) {
			size += f.length();
		}
		if (mmprojFile != null) {
			size += mmprojFile.length();
		}
		return size;
	}
}
