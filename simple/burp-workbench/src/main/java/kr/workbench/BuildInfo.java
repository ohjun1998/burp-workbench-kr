package kr.workbench;

/** Release identity shared by the runtime, standalone build, tests and packager. */
public final class BuildInfo {
  public static final String VERSION = "1.3.1";
  public static final String DISPLAY_NAME = "Burp Workbench Simple KR · 웹 보안 점검 (" + VERSION + ")";
  // Evidence format evolves independently from the extension release.
  public static final String EVIDENCE_SCHEMA_VERSION = "2";

  /** Simple keeps settings and disk history separate from stable and Next extensions. */
  public static final String STORAGE_DIRECTORY = ".burp-workbench-simple";
  public static String storageKey(String key) { return "simple." + key; }

  private BuildInfo() {}
}

