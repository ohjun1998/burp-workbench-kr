package kr.workbench;

/** Release identity shared by the runtime, standalone build, tests and packager. */
public final class BuildInfo {
  public static final String VERSION = "0.53.3";
  public static final String DISPLAY_NAME = "Burp Workbench Next KR · 웹 보안 점검 (" + VERSION + ")";
  // Evidence format evolves independently from the extension release.
  public static final String EVIDENCE_SCHEMA_VERSION = "2";

  /** Next keeps saved settings and disk history separate from the stable extension. */
  public static final String STORAGE_DIRECTORY = ".burp-workbench-next";
  public static String storageKey(String key) { return "next." + key; }

  private BuildInfo() {}
}

