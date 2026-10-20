package kr.payloadrunner;

import java.io.StringReader;
import java.util.regex.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;

/** Preserves a captured XML envelope only for the built-in DTD probes and an explicit description field. */
final class XmlProbe {
  static String adapt(String probe, String baseline) {
    if (!probe.startsWith("<!DOCTYPE r [<!ENTITY pr ") || !probe.endsWith("<r>&pr;</r>")) return probe;
    if (baseline.length() > 262144 || !baseline.stripLeading().startsWith("<") || baseline.contains("<!DOCTYPE")) return probe;
    Matcher field = Pattern.compile("(?s)<((?:[A-Za-z_][\\w.-]*:)?description)(?:\\s[^<>]*)?>([^<]*)</\\1\\s*>").matcher(baseline);
    if (!field.find()) return probe;
    try {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
      var builder = factory.newDocumentBuilder();builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
      String root = builder.parse(new InputSource(new StringReader(baseline))).getDocumentElement().getTagName();
      int end = probe.indexOf("]>") + 2;
      if (end < 2) return probe;
      String declaration = probe.substring(0, end).replaceFirst("DOCTYPE r ", "DOCTYPE " + Matcher.quoteReplacement(root) + " ");
      String changed = baseline.substring(0, field.start(2)) + "&pr;" + baseline.substring(field.end(2));
      int insert = changed.startsWith("<?xml") ? changed.indexOf("?>") + 2 : 0;
      return changed.substring(0, insert) + declaration + changed.substring(insert);
    } catch (Exception ignored) {return probe;}
  }
}
