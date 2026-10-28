package kr.workbench;

import java.util.*;

/** Literal evidence, unsupported syntax and source-map regressions; no JS execution or HTTP. */
public final class ScopeInspectionSecretEvidenceTest extends WorkbenchTest {
  public static void main(String[] args){
    String expressions="const password=credentials.currentPassword;const apiKey=resolveApiKey();const accessToken=SESSION_TOKEN;const streamKey=this.connection.streamKey;";
    check(JsAnalysis.analyze(expressions,"https://site.test/app.js","application/javascript").secrets().isEmpty(),"runtime variable/property/call expressions are not secret values in parsed JavaScript");
    String unsupported="@decorator class Example {}\n"+expressions+"const signingKey='N7vQ9mLp'+runtimeSuffix;";
    check(JsAnalysis.analyze(unsupported,"https://site.test/app.js","application/javascript").secrets().isEmpty(),"AST fallback does not promote bare expressions or partial string concatenations to secrets");
    check(JsAnalysis.analyze(unsupported+"\nconst signingKey='N7vQ9mLp4XzR2cBw'\n + runtimeSuffix;","https://site.test/app.js","application/javascript").secrets().isEmpty(),"a newline cannot turn a continued string expression into a complete credential literal");
    String real="N7vQ9mLp4XzR2cBw8YtH6aKs";
    String literal=unsupported+"\nconst databasePassword='"+real+"';";
    var literalResult=JsAnalysis.analyze(literal,"https://site.test/app.js","application/javascript");
    check(literalResult.secrets().size()==1&&literalResult.secrets().get(0).length()==real.length(),"a real quoted high-entropy literal survives the same unsupported-syntax fallback");
    check(literalResult.secrets().get(0).start()==literal.indexOf(real),"fallback literal retains its exact source value offset");
    check(!literalResult.toString().contains(real),"literal candidate DTOs remain masked");
    check(!JsAnalysis.analyze("const sessionToken='q7VnPk2x.A8tRz9Lm';","https://site.test/app.js").secrets().isEmpty(),"opaque literal credentials containing dots are not confused with property expressions");
    var plain=JsAnalysis.analyze("API_KEY="+real+"\n","https://site.test/.env","text/plain");
    check(plain.secrets().stream().anyMatch(s->s.length()==real.length()),"unquoted dotenv scalar values remain data literals");

    String github="ghp_"+"A1b2C3d4".repeat(5);
    String provider="const public_key='pk_live_PUBLIC_IDENTIFIER';\nconst githubToken='"+github+"';";
    var providerResult=JsAnalysis.analyze(provider,"https://site.test/app.js");
    var token=providerResult.secrets().stream().filter(s->s.rule().equals("GitHub token")).findFirst().orElseThrow();
    check(token.start()==provider.indexOf(github)&&token.sourceLength()==github.length(),"provider token evidence points at the exact literal bytes rather than the surrounding declaration");
    check(!token.confidence().contains("공개 식별자"),"an adjacent public key cannot downgrade a private provider token");
    String google="AIza"+"A".repeat(35),other="AIza"+"B".repeat(35);
    var firebase=JsAnalysis.analyze("const firebaseConfig={apiKey:'"+google+"',authDomain:'app.firebaseapp.com'};const other={apiKey:'"+other+"'};","https://site.test/app.js");
    check(firebase.secrets().stream().filter(s->s.confidence().contains("공개 식별자")).count()==1,"Firebase public configuration classification uses its own object rather than nearby text");

    String original="@decorator class Original {}\n"+expressions+"\nconst databasePassword='"+real+"';\nfetch('/rest/original');";
    String map=MetadataExport.json(Map.of("version",3,"sources",List.of("src/original.ts"),"sourcesContent",List.of(original),"mappings",""));
    var recovered=JsAnalysis.analyze(map,"https://site.test/app.js.map","application/json");
    check(recovered.secrets().size()==1&&recovered.secrets().get(0).confidence().contains("source map: src/original.ts"),"source-map fallback rejects expressions while preserving a real recovered literal");
    check(recovered.secrets().get(0).start()==-1-original.indexOf(real)&&recovered.secrets().get(0).sourceLength()==real.length(),"recovered secret evidence retains its original file-relative offset and span");

    String sample="sk_test_AbCdEfGh12345678XyZ9";
    var duplicated=ScopeInspectionAnalysis.analyze("https://site.test/config.json",200,"Content-Type: application/json",MetadataExport.json(Map.of("stripeApiKey",sample,"otherSecret",sample)),"anonymous","original-1");
    var secrets=duplicated.findings().stream().filter(f->f.category().equals("SECRET")).toList();
    check(secrets.size()==1&&secrets.get(0).state().equals("예제값 가능")&&secrets.get(0).title().equals("Stripe secret key"),"the same sample token has one consistent classification across provider and named-field detection");
    var privateKey=ScopeInspectionAnalysis.analyze("https://site.test/config.json",200,"Content-Type: application/json",MetadataExport.json(Map.of("public_key",github)),"anonymous","original-2");
    check(privateKey.findings().stream().anyMatch(f->f.category().equals("SECRET")&&f.title().equals("GitHub token")&&f.state().equals("유효성 미검증")),"a private provider token is retained even when stored under a misleading public field name");
    var latest=ScopeInspectionAnalysis.analyze("https://site.test/config.json",200,"Content-Type: application/json",MetadataExport.json(Map.of("latestToken",real)),"anonymous","original-3");
    check(latest.findings().stream().anyMatch(f->f.category().equals("SECRET")&&f.state().equals("유효성 미검증")),"the substring test in latestToken is not mistaken for a test fixture marker");
    var unsupportedScope=ScopeInspectionAnalysis.analyze("https://site.test/app.js",200,"Content-Type: application/javascript",unsupported,"anonymous","original-4");
    check(unsupportedScope.findings().stream().noneMatch(f->f.category().equals("SECRET")),"integrated analysis also rejects generic expression candidates from unsupported JavaScript");
    System.out.println("ScopeInspectionSecretEvidenceTest: "+checks+" checks passed (static literal/source-map evidence, no network)");
  }
}
