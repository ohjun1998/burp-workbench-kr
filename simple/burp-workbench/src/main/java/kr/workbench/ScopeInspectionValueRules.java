package kr.workbench;

import java.util.*;
import java.util.regex.Pattern;

/** Shared response-value semantics. Format matches never validate credentials or access. */
final class ScopeInspectionValueRules {
  private static final Pattern CONNECTION_CONTEXT=Pattern.compile("(?i)(?:jdbc:(?:oracle|postgresql|mysql|mariadb|sqlserver|sqlite|h2):|(?:mongodb(?:\\+srv)?|postgres(?:ql)?|mysql|redis(?:s)?)://)[^\\s\"'<>]{1,4096}");
  private ScopeInspectionValueRules() {}
  static String normalized(String name) {
    return Objects.requireNonNullElse(name, "").replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
  }
  static String category(String name, String value) {
    return category(name,value,"");
  }
  static String category(String name, String value, String locator) {
    String key = normalized(name), text = Objects.requireNonNullElse(value, "");
    if (key.startsWith("icon") || key.startsWith("css") || key.startsWith("classname")
        || Set.of("domtokenlist", "passwordstrength", "credentials", "credentialsmode", "lasttoken", "bearerformat",
            "authorizationcode", "oauth2redirecturl", "welcomebannerstatuscookiekey").contains(key)) return null;
    if (key.matches(".*(?:password|passwd|passphrase|clientsecret|privatekey|signingsecret|secret).*")
        || Set.of("pwd", "pin").contains(key)) return "SENSITIVE";
    if (key.matches(".*(?:token|jwt|sessionid|sessionkey|sessioncookie|cookie|authorization).*")) return "TOKEN";
    if (key.matches(".*(?:apikey|accesskey|publickey|publishablekey|sitekey|measurementid|clientid).*")) return "KEY";
    if (key.matches(".*(?:database|databaseurl|connectionstring|dbhost|dbport|dbuser|dburl|jdbc|mongodb|redisurl)$")) return "DATABASE";
    if (personal(name,locator)) return "SENSITIVE";
    if (key.matches(".*(?:hostname|internalhost|internalip|internalpath|cwd|homedir)$")) return "INTERNAL";
    if (key.matches(".*(?:runtime|version|environment|platform|framework|server|was|nodeenv|osinfo)$")) return "ENVIRONMENT";
    if (key.matches(".*(?:apiurl|endpoint|baseurl)$") && uriLike(text)) return "API";
    if (key.matches(".*(?:url|uri|path|route|href|src)$") && uriLike(text)) return apiLike(text) ? "API" : "PATH";
    if (key.matches(".*(?:stacktrace|exception|error|debug|role|permission|username|userid|accountid|accountnumber|balance|featureflag|config)$") || key.equals("id")) return "OTHER";
    return null;
  }
  static boolean personal(String name) {
    return personal(name,"");
  }
  static boolean personal(String name,String locator) {
    String key = normalized(name);
    if (key.startsWith("icon") || key.startsWith("css") || key.startsWith("classname")) return false;
    if (key.matches(".*(?:email|phone|phonenumber|mobile|mobilenumber|residentnumber|creditcard|cardnumber|fullname|address|street|dateofbirth|birthdate|dob|socialsecuritynumber|accountnumber|iban)$")) return true;
    boolean geography=key.matches(".*(?:city|state|country|zipcode|postalcode)$");
    if(geography&&(key.matches(".*(?:address|shipping|billing).*")
        ||Objects.requireNonNullElse(locator,"").toLowerCase(Locale.ROOT).matches(".*(?:[/.:_-])(?:address|shipping|billing)(?:[/.:_-]).*")))return true;
    String words = Objects.requireNonNullElse(name, "").replaceAll("([a-z0-9])([A-Z])", "$1_$2").replaceAll("([A-Z])([A-Z][a-z])", "$1_$2");
    return Arrays.stream(words.split("[^A-Za-z0-9]+")).anyMatch(word -> word.equalsIgnoreCase("ssn"));
  }
  static boolean emptyOrMetadata(String name, String value, String type) {
    String text = Objects.requireNonNullElse(value, "").strip();
    if (text.isEmpty() || type.equals("NULL") || text.equals("null") || text.equals("undefined")) return true;
    String key = normalized(name);
    if (Set.of("domtokenlist", "passwordstrength", "credentials", "bearerformat").contains(key)) return true;
    return Set.of("SENSITIVE", "TOKEN", "KEY").contains(Objects.requireNonNullElse(category(name, value), ""))
        && (type.equals("BOOLEAN") || Set.of("string", "number", "boolean", "text", "password", "same-origin", "include", "omit", "Bearer", "Bearer ", "Basic", "Basic ", "JWT", "apiKey").contains(value));
  }
  static boolean codeFragment(String value) {
    String text = Objects.requireNonNullElse(value, "").strip();
    return text.matches("(?s)^[),;].*") || text.contains("createElement(") || text.contains("encodeURIComponent(")
        || text.matches("(?s).*(?:function\\s*\\(|=>).*");
  }
  static String classification(String category, String name, String value, String type) {
    return classification(category,name,value,type,"");
  }
  static String classification(String category, String name, String value, String type,String locator) {
    if (type.equals("RAW_TEMPLATE_INTERPOLATED")) return "DYNAMIC_UNRESOLVED";
    if (SecretCandidates.example(name, value) || value.startsWith("${") || value.startsWith("{{")
        || value.startsWith("process.env.") || value.startsWith("import.meta.env.")
        || value.matches("(?is).*(?:NOT_A_REAL_SECRET|LAB_ONLY_DEMO|<MERCHANT_API_KEY>).*")) return "EXAMPLE_OR_PLACEHOLDER";
    if(value.matches("(?s)(?:gh[pousr]_|github_pat_|xox[baprs]-|sk_live_|-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----).*"))return "SECRET_CANDIDATE";
    if (SecretCandidates.publicIdentifier(name) || value.startsWith("pk_live_") || value.startsWith("pk_test_")) return "PUBLIC_IDENTIFIER";
    if (personal(name,locator)) return "PERSONAL_DATA";
    if (Set.of("KEY", "TOKEN").contains(category) || category.equals("SENSITIVE")) return "SECRET_CANDIDATE";
    return "OBSERVED_VALUE";
  }
  static boolean uriLike(String value) {
    return value != null && value.length() <= 8192 && (value.matches("(?is)^(?:https?|wss?)://.*")
        || value.startsWith("/") || value.startsWith("./") || value.startsWith("../") || value.startsWith("#/"));
  }
  static boolean apiLike(String value) {
    return value.toLowerCase(Locale.ROOT).matches(".*(?:/(?:api|rest|graphql)(?:[/?.#]|$)|openapi|swagger|api-docs).*");
  }
  /** Email-shaped text inside a DB connection or URI authority is not an independent email address. */
  static boolean uriUserinfoEmail(String source,int start,int end) {
    if(source==null||start<0||end<start||end>source.length())return false;
    String matched=source.substring(start,end);int mailAt=matched.indexOf('@');if(mailAt<0)return false;
    // Only the bounded prefix and the actual match are inspected. Common JSON slash
    // escapes are normalized locally; source offsets and exported literals are untouched.
    String prefix=source.substring(Math.max(0,start-8192),start);
    if(prefix.indexOf('\\')>=0)prefix=prefix.replace("\\/","/").replace("\\u002f","/").replace("\\u002F","/").replace("\\u003a",":").replace("\\u003A",":");
    String context=prefix+matched;var connection=CONNECTION_CONTEXT.matcher(context);
    while(connection.find())if(connection.start()<=prefix.length()&&connection.end()>=context.length())return true;
    int marker=prefix.lastIndexOf("://"),authority=-1;
    if(marker>=0) {
      int scheme=marker-1;while(scheme>=0&&(Character.isLetterOrDigit(prefix.charAt(scheme))||"+.-".indexOf(prefix.charAt(scheme))>=0))scheme--;
      String name=prefix.substring(scheme+1,marker);
      if(name.matches("[A-Za-z][A-Za-z0-9+.-]{0,31}"))authority=marker+3;
    }
    int relative=prefix.lastIndexOf("//");
    // A colon distinguishes protocol-relative user:password from an ordinary
    // comment such as //contact@example.test. Prefer a newer relative reference
    // over an unrelated earlier absolute URI in the same response.
    if(relative>=0&&relative+2>authority&&prefix.substring(relative+2).indexOf(':')>=0)authority=relative+2;
    if(authority<0)return false;
    String authorityPrefix=prefix.substring(authority)+matched.substring(0,mailAt);
    return authorityPrefix.chars().noneMatch(c->Character.isWhitespace(c)||"/\\?#\"'<>".indexOf(c)>=0);
  }
}
