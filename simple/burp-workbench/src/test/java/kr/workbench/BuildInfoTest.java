package kr.workbench;

import java.nio.file.Path;
import java.util.jar.JarFile;

/** Checks the packaged runtime identity, not just matching source constants. */
public final class BuildInfoTest {
    public static void main(String[] args) throws Exception {
        Path artifact = Path.of(BuildInfo.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!artifact.toString().endsWith(".jar")) {
            throw new AssertionError("Run the release identity regression against the packaged JAR");
        }
        try (JarFile jar = new JarFile(artifact.toFile())) {
            String version = jar.getManifest().getMainAttributes().getValue("Implementation-Version");
            if (!BuildInfo.VERSION.equals(version)) throw new AssertionError("Runtime/manifest version mismatch");
            if (!artifact.getFileName().toString().equals("burp-workbench-simple-" + version + ".jar")) {
                throw new AssertionError("Artifact filename/manifest version mismatch");
            }
            if (!FeatureGuide.EXTENSION_NAME.equals(BuildInfo.DISPLAY_NAME)) throw new AssertionError("Display identity mismatch");
        }
        System.out.println("BuildInfoTest: 3 checks passed");
    }
}
