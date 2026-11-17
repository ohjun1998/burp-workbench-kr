package kr.workbench;

/** Release identity shared by the runtime, standalone build, tests and packager. */
public final class BuildInfo {
  public static final String VERSION = "1.0.0";
  public static final String PRODUCT_NAME = "Workbench Strix";
  public static final String IMPLEMENTATION_TITLE = "Burp Workbench Strix KR";
  public static final String ARTIFACT_ID = "burp-workbench-strix";
  public static final String DISPLAY_NAME = IMPLEMENTATION_TITLE + " · Strix 진단 연동 (" + VERSION + ")";
  // Evidence format evolves independently from the extension release.
  public static final String EVIDENCE_SCHEMA_VERSION = "2";

  /** Keep the former Simple-Strix storage for upgrades; this rename does not migrate user data. */
  public static final String STORAGE_DIRECTORY = ".burp-workbench-simple";
  public static String storageKey(String key) { return "simple." + key; }

  private BuildInfo() {}
}

