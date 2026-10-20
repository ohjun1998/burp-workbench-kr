package kr.workbench;

import java.util.*;

/** Carries only bounded, top-level immutable string declarations across statement cuts. */
final class JsBatchContext {
  private final Map<String, String> declarations = new LinkedHashMap<>();
  private int size;
  String prelude() {return String.join("\n", declarations.values()) + (declarations.isEmpty() ? "" : "\n");}

  void capture(String unit) {
    var cursor = new JsSyntax.Cursor(unit);
    int depth = 0;
    JsSyntax.Token token;
    while ((token = cursor.next()) != null) {
      if (Thread.currentThread().isInterrupted()) return;
      if (token.quoted()) continue;
      if (token.text().equals("const") && depth == 0) {
        var name = cursor.next(); var eq = cursor.next(); var value = cursor.next(); var end = cursor.next();
        if (name != null && eq != null && value != null && end != null && !name.quoted()
            && name.text().matches("[A-Za-z_$][A-Za-z0-9_$]*") && eq.text().equals("=")
            && value.quoted() && !value.text().startsWith("`") && value.text().length() <= 8192 && end.text().equals(";")) {
          String declaration = "const " + name.text() + "=" + value.text() + ";";
          if (!declarations.containsKey(name.text()) && declarations.size() < 128 && size + declaration.length() < 65536) {
            declarations.put(name.text(), declaration);size += declaration.length();
          }
        } else {
          // Restart at the next token; skipped punctuation must still affect top-level depth.
          for (var part : new JsSyntax.Token[]{name, eq, value, end}) if (part != null && !part.quoted()) {
            if (Set.of("{", "(", "[").contains(part.text())) depth++;
            if (Set.of("}", ")", "]").contains(part.text())) depth--;
          }
        }
      } else if (Set.of("{", "(", "[").contains(token.text())) depth++;
      else if (Set.of("}", ")", "]").contains(token.text())) depth--;
    }
  }
}
