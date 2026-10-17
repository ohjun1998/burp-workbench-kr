package kr.workbench;

/** Release identity shared by the runtime, standalone build, tests and packager. */
public final class BuildInfo {
  public static final String VERSION = "0.37.0";
  public static final String DISPLAY_NAME = "Burp Workbench KR · 웹 보안 점검 (" + VERSION + ")";
  // Evidence format evolves independently from the extension release.
  public static final String EVIDENCE_SCHEMA_VERSION = "2";

  private BuildInfo() {}
}

