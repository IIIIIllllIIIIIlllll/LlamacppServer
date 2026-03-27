package org.mark.llamacpp.server;

public final class BuildInfo {

	private static final String TAG = "{tag}";

	private static final String VERSION = "{version}";

	private static final String CREATED_TIME = "{createdTime}";

	private BuildInfo() {
	}

	public static String getTag() {
		return TAG;
	}

	public static String getVersion() {
		return VERSION;
	}

	public static String getCreatedTime() {
		return CREATED_TIME;
	}
}
