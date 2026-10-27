package kr.workbench;

import java.util.List;
import java.util.Map;

/** Shared immutable contract for one scoped inspection and its retained original packets. */
final class ScopeInspection {
  enum State { READY, RUNNING, PAUSED, STOPPED, COMPLETED, FAILED, CLOSED }
  record Profile(String id, String label, Map<String, String> headers,
                 CookieSessionProbe.Config identity) {
    Profile {
      var clean = new java.util.TreeMap<String,String>();
      for (var entry : headers.entrySet()) {
        String key = entry.getKey().strip().toLowerCase(java.util.Locale.ROOT), value = entry.getValue();
        if (key.isEmpty() || key.chars().anyMatch(c -> c <= 32 || c >= 127 || c == ':')
            || value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)
          throw new IllegalArgumentException("계정 인증 헤더 형식을 확인하세요.");
        String previous = clean.putIfAbsent(key,value);
        if (previous != null && !previous.equals(value)) throw new IllegalArgumentException("중복 인증 헤더 값이 다릅니다.");
      }
      headers = Map.copyOf(clean);
    }
    @Override public String toString() { return label; }
  }
  record Options(String url, int budget, int depth, int delayMillis, int timeoutMillis,
                 int maxBodyBytes, int maxRedirects, boolean browser, boolean compareAccounts,
                 List<Profile> profiles) {
    Options {
      profiles = List.copyOf(profiles);
      if (profiles.isEmpty() || profiles.size() > 4 || budget < 1 || budget > 500
          || depth < 0 || depth > 3 || delayMillis < 0 || delayMillis > 10000
          || timeoutMillis < 1000 || timeoutMillis > 30000 || maxBodyBytes < 1024
          || maxBodyBytes > 5 * 1024 * 1024 || maxRedirects < 0 || maxRedirects > 5)
        throw new IllegalArgumentException("통합 점검 설정 범위를 확인하세요.");
    }
    @Override public String toString() { return "ScopeInspection.Options[url="+ActivityTracker.safeUrl(url)+", budget="+budget+", profiles="+profiles.stream().map(Profile::label).toList()+", browser="+browser+"]"; }
  }
  record Finding(String category, String title, String url, String method, String account,
                 String confidence, String severity, String state, String detail,
                 String originalId, int line, int offset, String fingerprint) {}
  record Candidate(String url, String method, String source, String label, boolean executable) {}
  record Page(String url, String method, String account, int status, String classification,
              boolean complete, String note, String digest, DiskTrafficStore.PacketRef original,
              String profileRevision) {
    Page(String url,String method,String account,int status,String classification,boolean complete,String note,String digest,DiskTrafficStore.PacketRef original) {
      this(url,method,account,status,classification,complete,note,digest,original,"r1");
    }
  }
  record Snapshot(String id, State state, String message, int sent, int completed, int pending,
                  int excluded, int failed, int partial, List<Finding> findings, List<Page> pages,
                  List<Candidate> candidates, long startedAt, long finishedAt) {
    Snapshot {
      findings = List.copyOf(findings); pages = List.copyOf(pages); candidates = List.copyOf(candidates);
    }
  }
  private ScopeInspection() {}
}
