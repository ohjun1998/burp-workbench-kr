package kr.workbench;
import java.util.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;

public final class SimpleExposureResearchTest extends WorkbenchTest {
  static String fixture(){return """
  {"totalResults":1,"vulnerabilities":[{"cve":{"id":"CVE-2099-10001","vulnStatus":"Analyzed",
  "descriptions":[{"lang":"en","value":"Synthetic fixture only. Optional module must be enabled."}],
  "metrics":{"cvssMetricV31":[{"source":"fixture.test","type":"Primary","cvssData":{"baseScore":9.8,"vectorString":"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"}}]},
  "configurations":[{"nodes":[{"operator":"OR","cpeMatch":[{"vulnerable":true,"criteria":"cpe:2.3:a:nginx:nginx:*:*:*:*:*:*:*:*","versionStartIncluding":"1.18.0","versionEndExcluding":"1.24.0"}]}]}],
  "references":[{"url":"https://example.test/security/advisory"},{"url":"javascript:alert(1)"}],"lastModified":"2099-01-01T00:00:00Z"}}]}
  """;}
  static ExposureResearch.Product p(String v){return new ExposureResearch.Product("nginx","nginx",v,"Server: nginx/"+v,"https://example.test/","fixture-store:42");}
  public static void main(String[] args)throws Exception {
    var candidates=ExposureResearch.parseNvd(fixture());var c=candidates.get(0);
    check(candidates.size()==1&&c.references().size()==1,"NVD parsing preserves valid identifiers and HTTPS references only");
    check(ExposureResearch.parseNvd(fixture().replace("Analyzed","Rejected")).isEmpty(),"rejected CVEs excluded");
    check(ExposureResearch.applicability(c,p("1.22.1"))==ExposureResearch.Truth.YES,"numeric observed version falls in affected interval");
    check(ExposureResearch.applicability(c,p("1.24.0"))==ExposureResearch.Truth.NO,"exclusive upper bound excluded");
    check(ExposureResearch.applicability(c,p("1.18.0"))==ExposureResearch.Truth.YES,"inclusive lower bound included");
    check(ExposureResearch.applicability(c,p("1.22.1-ubuntu1"))==ExposureResearch.Truth.UNKNOWN,"distribution and backport suffix is unknown");
    check(ExposureResearch.applicability(c,p(""))==ExposureResearch.Truth.UNKNOWN,"missing observed version unknown");
    var other=new ExposureResearch.Product("apache","http_server","2.4.1","","","");
    check(ExposureResearch.applicability(c,other)==ExposureResearch.Truth.UNKNOWN,"unmatched product never confirmed");
    var os=Map.of("criteria","cpe:2.3:o:linux:linux_kernel:*:*:*:*:*:*:*:*","vulnerable",false);
    var node=Map.of("operator","AND","nodes",c.configurations(),"cpeMatch",List.of(os));
    var platform=new ExposureResearch.Candidate(c.id(),c.description(),c.score(),c.vector(),c.metricSource(),List.of(node),c.references(),c.modified());
    check(ExposureResearch.applicability(platform,p("1.22.1"))==ExposureResearch.Truth.UNKNOWN,"AND OS requirements remain unknown");
    check(ExposureResearch.node(Map.of("negate",true,"nodes",c.configurations()),p("1.24.0"))==ExposureResearch.Truth.UNKNOWN,"negated configuration never becomes positive vulnerability evidence");
    check(ExposureResearch.remoteAnonymous(c)==ExposureResearch.Truth.YES,"CVSS vector establishes remote unauthenticated prerequisites");
    var products=ExposureResearch.observe("https://example.test/","Server: nginx/1.22.1\nSet-Cookie: secret=HIDDEN","store:8");
    check(products.size()==1&&products.get(0).version().equals("1.22.1")&&!products.get(0).evidence().contains("HIDDEN"),"passive signature keeps version and excludes unrelated secrets");
    check(products.get(0).originalId().equals("store:8"),"immutable original identity retained");
    check(ExposureResearch.within("https://api.example.test/a","example.test",true)&&!ExposureResearch.within("https://example.test.attacker.test","example.test",true),"domain boundary checked");
    check(!ExposureResearch.within("https://api.example.test","example.test",false),"exact-host setting enforced");
    check(ExposureResearch.query("example.test","API 문서",false).contains("-site:*.example.test"),"exact-host dork excludes subdomains");
    boolean rejected=false;try{ExposureResearch.url("https://u:p@example.test/");}catch(IllegalArgumentException e){rejected=true;}check(rejected,"userinfo rejected");
    check(WorkbenchUi.tone("미확인")==WorkbenchUi.Tone.NEUTRAL&&WorkbenchUi.tone("확인 필요")==WorkbenchUi.Tone.NEUTRAL,"unknown badges are never success-colored");
    boolean oversize=false;try{VulnerabilityClient.readBody(new java.io.ByteArrayInputStream(new byte[8*1024*1024+1]),()->false);}catch(java.io.IOException e){oversize=true;}check(oversize,"provider response capped at 8 MiB");
    boolean endpoint=false;try{VulnerabilityClient.fetch(java.net.URI.create("https://example.test/cve"));}catch(java.io.IOException e){endpoint=true;}check(endpoint,"unapproved intelligence endpoint rejected before transport");
    var calls=new ArrayList<String>();var client=new VulnerabilityClient(uri->{calls.add(uri.toString());return new VulnerabilityClient.Reply(200,uri.getHost().contains("nist")?fixture():uri.getHost().contains("cisa")?"{\"vulnerabilities\":[{\"cveID\":\"CVE-2099-10001\"}]}":"{\"data\":[{\"cve\":\"CVE-2099-10001\",\"epss\":\"0.23\"}]}","");});
    var result=client.lookup(p("1.22.1"),()->false);
    check(result.kev().contains(c.id())&&result.epss().get(c.id())==.23,"KEV and EPSS join actual provider identifiers");
    check(calls.size()==3&&calls.stream().noneMatch(u->u.contains("example.test")||u.contains("1.22.1")),"provider requests exclude target host and observed version");
    var failed=new VulnerabilityClient(uri->new VulnerabilityClient.Reply(uri.getHost().contains("nist")?200:403,fixture(),""));
    var partial=failed.lookup(p("1.22.1"),()->false);
    check(partial.candidates().size()==1&&partial.epss().isEmpty()&&partial.kevStatus().contains("미확인"),"secondary provider failure retains NVD and unknown enrichment");
    boolean cancelled=false;try{client.lookup(p("1"),()->true);}catch(CancellationException e){cancelled=true;}check(cancelled,"cancel precedes any provider request");
    AtomicReference<ExposureResearchPanel> panelRef=new AtomicReference<>();AtomicInteger requested=new AtomicInteger();CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
    var slow=new VulnerabilityClient(uri->{requested.incrementAndGet();started.countDown();release.await();return new VulnerabilityClient.Reply(200,fixture(),"");});
    edt(()->{
      var panel=new ExposureResearchPanel(null,u->u.startsWith("https://example.test/"),slow);panelRef.set(panel);panel.offer(request("https://example.test/", "GET", true, List.of()),response(200,"{}"),"store:1");check(panel.observations.isEmpty(),"responses without header metadata preserve capture and create no observation");panel.target.setText("example.test");panel.fillProduct(p("1.22.1"));
      check(panel.tabs.getTabCount()==2&&requested.get()==0,"two-tab construction and edits issue zero network requests");
      panel.importUrls("https://example.test/docs\nhttps://example.test/docs#x\nhttps://badexample.test/a\nhttps://api.example.test/openapi");
      check(panel.exposures.size()==2&&panel.exposureTable.getRowCount()==2,"imports deduplicate fragments and reject different domains");
      panel.exposureTable.setRowSelectionInterval(1,1);boolean blocked=false;try{panel.repeater();}catch(IllegalArgumentException e){blocked=true;}check(blocked,"repeater rechecks live SCOPE before preparing");
      panel.display(result,p("1.22.1"),false);check(panel.cveTable.getRowCount()==1,"candidate filters retain matching record");panel.cveTable.setRowSelectionInterval(0,0);
      check(panel.evidence.getText().contains("fixture-store:42")&&panel.fixedDetail.getText().contains("공지 확인"),"detail preserves original ID and avoids invented fixed version");
      panel.minimum.setValue(10.0);check(panel.cveTable.getRowCount()==0&&panel.evidence.getText().isBlank(),"filter clears stale selected evidence");panel.minimum.setValue(7.0);
      panel.cache.put(panel.cacheKey(p("1.22.1")),result);panel.restoreCache();check(panel.freshness.getText().startsWith("세션 캐시"),"cache retains source retrieval timestamp");panel.start();
    });
    check(started.await(3,TimeUnit.SECONDS),"explicit lookup starts background transport");
    edt(()->panelRef.get().target.setText("changed.example.test"));release.countDown();Thread.sleep(100);edt(()->{var panel=panelRef.get();check(panel.batch==null&&panel.cveTable.getRowCount()==0,"target change discards stale result");panel.close();check(panel.cache.isEmpty()&&panel.observations.isEmpty(),"unload clears session data");});
    AtomicReference<ExposureResearchPanel> observedPanel=new AtomicReference<>();
    edt(()->{var panel=new ExposureResearchPanel(null,u->true);panel.target.setText("example.test");observedPanel.set(panel);});
    var first=proxy(burp.api.montoya.http.message.responses.HttpResponse.class,(o,m,a)->m.getName().equals("headers")?List.of(header("Server","nginx/1.22.1")):def(m));
    var second=proxy(burp.api.montoya.http.message.responses.HttpResponse.class,(o,m,a)->m.getName().equals("headers")?List.of(header("Server","nginx/1.24.0")):def(m));
    observedPanel.get().offer(request("https://example.test/", "GET", true, List.of()),first,"store:101");edt(()->{});
    edt(()->{check(observedPanel.get().version.getText().equals("1.22.1"),"first passive observation fills product without fetching");observedPanel.get().version.setText("1.22.1-custom");});
    observedPanel.get().offer(request("https://example.test/", "GET", true, List.of()),second,"store:102");edt(()->{});
    edt(()->{var panel=observedPanel.get();check(panel.observed.getItemCount()==2&&panel.version.getText().equals("1.22.1-custom"),"new observed versions update choices without overwriting user corrections");panel.resetProject();check(panel.target.getText().isEmpty()&&panel.observed.getItemCount()==0&&panel.version.getText().isEmpty()&&panel.vendor.getText().isEmpty(),"project reset removes prior product choices");panel.close();});
    System.out.println("SimpleExposureResearchTest: "+checks+" checks passed");
  }
}
