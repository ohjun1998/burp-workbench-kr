package kr.featuremapper;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

/** Standalone entry point. Workbench embeds the workspace and excludes this entry class. */
public final class FeatureMapperExtension implements BurpExtension {
  private final FeatureMapperWorkspace workspace = new FeatureMapperWorkspace();

  @Override public void initialize(MontoyaApi api) { workspace.initialize(api); }
}
