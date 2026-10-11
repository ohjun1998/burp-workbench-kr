package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.Http;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import javax.swing.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Source identity, scope changes and editing must not silently produce unrelated evidence. */
public final class EvidenceLinkTest extends WorkbenchTest {
    static HttpRequestResponse pair(HttpRequest q, HttpResponse s) {
        return proxy(HttpRequestResponse.class, (p,m,a) -> switch (m.getName()) {
            case "request" -> q; case "response" -> s; default -> def(m);
        });
    }

    public static void main(String[] args) throws Exception {
        AtomicInteger sends = new AtomicInteger();
        var http = proxy(Http.class, (p,m,a) -> { if(m.getName().equals("sendRequest")) sends.incrementAndGet(); return def(m); });
        MontoyaApi api = proxy(MontoyaApi.class, (p,m,a) -> m.getName().equals("http") ? http : def(m));
        ObjectFactoryLocator.FACTORY = proxy(MontoyaObjectFactory.class, (p,m,a) -> switch (m.getName()) {
            case "httpRequestResponse" -> pair((HttpRequest)a[0], (HttpResponse)a[1]);
            case "httpRequestFromUrl" -> request((String)a[0], "GET", true, List.of());
            case "requestOptions" -> proxy(RequestOptions.class,(pp,mm,aa)->pp);
            default -> def(m);
        });
        HttpRequest qa = request("https://example.com/a?token=local-secret", "GET", true, List.of(header("Authorization", "Bearer local-secret")));
        HttpRequest qb = request("https://example.com/b", "GET", true, List.of());
        HttpResponse sa = response(200, "{\"owner\":\"alice\"}"), sb = response(403, "{\"error\":\"denied\"}");
        HttpRequestResponse a = pair(qa,sa), b = pair(qb,sb);
        check(EvidenceLinks.retain(qa,sa).request() == qa, "retained source keeps actual original request identity");
        check(EvidenceLinks.retain(qa,null) == null, "missing response has no complete evidence source");
        check(EvidenceLinks.retain(qa,response(200,"x".repeat(EvidenceLinks.PACKET_LIMIT))) == null, "oversized response never becomes truncated evidence");
        check(EvidenceLinks.retain(request("https://example.com/a","POST",true,List.of(header("X-Large","x".repeat(EvidenceLinks.PACKET_LIMIT)))),sa) == null, "combined request and response retention is bounded");
        rejects(() -> EvidenceLinks.checked(api,List.of()), "empty source batch rejected");
        rejects(() -> EvidenceLinks.checked(api,Collections.nCopies(33,a)), "more than 32 sources rejected");
        rejects(() -> EvidenceLinks.checked(api,List.of(a,pair(request("https://outside.test/a","GET",false,List.of()),sa))), "mixed in/out-of-scope batch rejected atomically");
        List<HttpRequestResponse> mutable = new ArrayList<>(List.of(a,b));
        var snapshot = EvidenceLinks.checked(api,mutable); mutable.clear();
        check(snapshot.size()==2 && snapshot.get(1)==b, "validated source batch owns immutable ordered snapshot");

        AtomicReference<List<HttpRequestResponse>> captured = new AtomicReference<>();
        AtomicReference<String> note = new AtomicReference<>();
        AtomicReference<StructuredComparePanel> panelBox = new AtomicReference<>();
        edt(() -> {
            var panel = new StructuredComparePanel(api); panelBox.set(panel);
            panel.evidenceSink = (packets,text) -> { captured.set(packets); note.set(text); };
            panel.selectedTraffic = () -> List.of(a);
            panel.loadSelected(true);
            panel.selectedTraffic = () -> List.of(b);
            panel.loadSelected(false);
            check(panel.leftInput.getText().equals(sa.toString()) && panel.rightInput.getText().equals(sb.toString()), "comparison loads original left/right responses without formatting mutation");
            check(!panel.sourceStatus.getText().contains("local-secret"), "source summary excludes query values and authentication");
            panel.compare(); panel.captureEvidence();
            check(captured.get().get(0)==a && captured.get().get(1)==b && note.get().contains("왼쪽/오른쪽"), "comparison evidence preserves ordered request-response pairs");
            panel.formatted.doClick();
            check(panel.leftSource==a && panel.rightSource==b, "format toggle preserves source linkage");
            panel.rightInput.append(" ");
            check(panel.rightSource==null && panel.leftSource==a && !panel.captureEvidence.isEnabled(), "editing one side immediately disconnects only that original");
            rejects(panel::captureEvidence, "edited comparison cannot falsely claim original evidence");
            panel.rightInput.setText(sb.toString());
            rejects(panel::captureEvidence, "restoring text manually does not restore unverified original linkage");
            panel.loadSource(false,pair(qb,response(200,"x".repeat(70000)))); panel.captureEvidence();
            check(note.get().contains("비교 표시 제한") && note.get().contains("전체 응답 차이 수가 아님"), "bounded comparison note discloses incomplete display instead of claiming total differences");
            panel.loadSource(false,b);
            panel.selectedTraffic = () -> List.of(a,b);
            rejects(() -> panel.loadSelected(true), "ambiguous traffic selection rejected without replacing original");
            check(panel.leftSource==a, "failed import preserves previous original association");
        });
        WorkbenchScope scope = WorkbenchScope.install(api); scope.add("https://example.com/a",false);
        edt(() -> rejects(panelBox.get()::captureEvidence, "scope narrowed after loading blocks original batch export"));
        WorkbenchScope.release(api);
        edt(() -> {
            panelBox.get().clearSources();
            check(panelBox.get().leftSource==null && panelBox.get().rightSource==null && panelBox.get().leftInput.getText().isEmpty(), "unload clears retained comparison packets and pasted data");
            var panel = new PassiveScanPanel(api);
            panel.evidenceSink = (packets,text) -> { captured.set(packets); note.set(text); };
            panel.add(qa.url(),"fixture GET"); panel.add(qb.url(),"fixture GET");
            panel.packets.put(qa.url(),new PassiveScanPanel.Packet("display request A","display response A","decoded A",a));
            panel.packets.put(qb.url(),new PassiveScanPanel.Packet("display request B","display response B","decoded B",b));
            panel.table.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(1,SortOrder.DESCENDING)));
            panel.table.setRowSelectionInterval(0,0); panel.captureEvidence();
            check(captured.get().size()==1 && captured.get().get(0)==b, "sorted URL selection exports actual matching original instead of display preview");
            check(note.get().contains("인증값 미지정 GET") && !note.get().contains("local-secret"), "URL evidence note identifies GET observation and required session check with safe URL");
            panel.packets.put(qb.url(),new PassiveScanPanel.Packet("preview","preview","preview")); panel.showSelected();
            check(!panel.captureEvidence.isEnabled(), "preview-only packet does not advertise original evidence");
            rejects(panel::captureEvidence, "missing or evicted original cannot be exported as a synthetic GET");
            panel.close();
            var representatives=new PassiveScanPanel(api);
            for(int i=1;i<=5;i++){String url="https://example.com/items/"+i;representatives.add(url,"fixture GET");representatives.items.get(url).state="HTTP 200";}
            String denied="https://example.com/items/6",missing="https://example.com/items/7",post="https://example.com/items/8";
            representatives.add(denied,"fixture GET");representatives.items.get(denied).state="HTTP 403";
            representatives.add(missing,"fixture GET");representatives.items.get(missing).state="HTTP 404";
            representatives.add(post,"fixture POST");representatives.items.get(post).state="HTTP 200";
            representatives.representativeCount.setValue(5);representatives.representatives.setSelected(true);representatives.applyFilter();
            check(representatives.representativeUrls.size()==5 && representatives.representativeUrls.containsAll(List.of(denied,missing,post)),"representative cap keeps later denial/missing/method groups before repeated 200 URLs");
            representatives.representativeCount.setValue(1);representatives.applyFilter();
            check(representatives.representativeUrls.size()==4,"one representative per status and observed method preserves distinct groups");
            representatives.search.setText("/items/1");
            check(representatives.visible().equals(List.of("https://example.com/items/1")),"representative selection respects active search before grouping");
            representatives.close();
            check(panel.packets.isEmpty(), "closing URL target releases retained originals");
        });
        check(sends.get()==0, "comparison and evidence linkage emit no target requests");
        AtomicReference<HttpRequestResponse> sentPair=new AtomicReference<>();
        var liveHttp=proxy(Http.class,(p,m,x)->{if(!m.getName().equals("sendRequest"))return def(m);var actual=((HttpRequest)x[0]).withHeader("Cookie","session=injected-local-secret");var rr=pair(actual,sa);sentPair.set(rr);return rr;});
        MontoyaApi liveApi=proxy(MontoyaApi.class,(p,m,x)->m.getName().equals("http")?liveHttp:def(m));
        AtomicReference<PassiveScanPanel> collecting=new AtomicReference<>();
        edt(()->{var panel=new PassiveScanPanel(liveApi);collecting.set(panel);panel.target.setText("example.com");panel.add(qa.url(),"fixture GET");panel.evidenceSink=(packets,text)->{captured.set(packets);note.set(text);};panel.verify(List.of(qa.url()));});
        PassiveScanTest.idle(collecting.get());
        edt(()->{var panel=collecting.get();panel.table.setRowSelectionInterval(0,0);panel.captureEvidence();check(captured.get().get(0)==sentPair.get() && captured.get().get(0).response()==sa,"GET verification retains actual sent pair through worker and sorted UI evidence callback");check(captured.get().get(0).request().headers().stream().anyMatch(h->h.name().equals("Cookie")) && !note.get().contains("비로그인") && note.get().contains("실제 전송 인증/세션 확인"),"session-rule credentials preserve actual packet without claiming anonymous verification");panel.close();});
        System.out.println("EvidenceLinkTest: "+checks+" checks passed");
    }
}
