package kr.workbench;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Export exact display projections from the immutable1.4.22 JAR; no engine or HTTP is started. */
public final class ExportPresentation {
  private static final String EXPECTED_JAR_SHA =
      "d5ac13a818d0fd641c72cd093cd86c21c3fea7793e01e3cc5b8da25900f0ff7c";

  private ExportPresentation() {}

  private static String decode(String encoded) {
    return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
  }

  private static Constructor<?> constructor(Class<?> type, Class<?>... arguments) throws Exception {
    Constructor<?> constructor = type.getDeclaredConstructor(arguments);
    constructor.setAccessible(true);
    return constructor;
  }

  private static Object data(Object value) throws Exception {
    if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean)
      return value;
    if (value instanceof Collection<?> values) {
      List<Object> result = new ArrayList<>();
      for (Object entry : values) result.add(data(entry));
      return result;
    }
    if (value.getClass().isRecord()) {
      Map<String,Object> result = new LinkedHashMap<>();
      for (RecordComponent component : value.getClass().getRecordComponents()) {
        Method accessor = component.getAccessor();accessor.setAccessible(true);
        result.put(component.getName(), data(accessor.invoke(value)));
      }
      return result;
    }
    throw new IllegalArgumentException("Unexpected display projection type: " + value.getClass().getName());
  }

  /** Input UTF-8 TSV: U/base64URL or F/base64ID/origin/entity/name/state/count/key/renamed. */
  public static void main(String[] args) throws Exception {
    if (args.length != 2) throw new IllegalArgumentException("Expected input.tsv output.json");
    Path loadedJar = Path.of(ResultLabels.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    if (!Files.isRegularFile(loadedJar)) throw new IllegalStateException("Load ResultLabels from the official JAR");
    String jarSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(loadedJar)));
    if (!EXPECTED_JAR_SHA.equals(jarSha)) throw new IllegalStateException("The immutable1.4.22 JAR differs");

    Class<?> summaryType = Class.forName("kr.featuremapper.Mapper$FeatureSummary");
    Class<?> sourceType = Class.forName("kr.featuremapper.FeaturePresentation$Source");
    Constructor<?> summary = constructor(summaryType, String.class, String.class, String.class,
        String.class, String.class, int.class);
    Constructor<?> source = constructor(sourceType, String.class, boolean.class);
    List<Object> summaries = new ArrayList<>();Map<String,Object> sources = new LinkedHashMap<>();
    Map<String,Object> labels = new LinkedHashMap<>();
    int lineNumber = 0;
    for (String line : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
      lineNumber++;
      if (line.isBlank() || line.startsWith("#")) continue;
      String[] fields = line.split("\t", -1);
      if (fields.length == 2 && fields[0].equals("U")) {
        String url = decode(fields[1]);String group = ResultLabels.groupKey(url);
        Map<String,Object> label = new LinkedHashMap<>();
        label.put("label",ResultLabels.shortName(url));label.put("groupKey",group);
        label.put("groupLabel",ResultLabels.groupLabel(group));labels.put(url,label);
      } else if (fields.length == 9 && fields[0].equals("F")) {
        String id = decode(fields[1]);
        summaries.add(summary.newInstance(id, decode(fields[2]), decode(fields[3]),
            decode(fields[4]), decode(fields[5]), Integer.parseInt(fields[6])));
        if (!fields[8].equals("true") && !fields[8].equals("false"))
          throw new IllegalArgumentException("Invalid renamed flag at line " + lineNumber);
        sources.put(id,source.newInstance(decode(fields[7]),Boolean.parseBoolean(fields[8])));
      } else throw new IllegalArgumentException("Invalid display projection TSV at line " + lineNumber);
    }
    Class<?> presentation = Class.forName("kr.featuremapper.FeaturePresentation");
    Method groups = presentation.getDeclaredMethod("groups",List.class,Map.class);groups.setAccessible(true);
    Map<String,Object> result = new LinkedHashMap<>();
    result.put("version",BuildInfo.VERSION);result.put("sourceJarSha256",jarSha);
    result.put("labels",labels);result.put("mapperGroups",data(groups.invoke(null,summaries,sources)));
    String json = MetadataExport.json(result).replace("<","\\u003c").replace(">","\\u003e").replace("&","\\u0026");
    Files.writeString(Path.of(args[1]),json+"\n",StandardCharsets.UTF_8);
    System.out.println("Exported " + labels.size() + " URL captions and " + summaries.size() + " feature projections from " + jarSha);
  }
}
