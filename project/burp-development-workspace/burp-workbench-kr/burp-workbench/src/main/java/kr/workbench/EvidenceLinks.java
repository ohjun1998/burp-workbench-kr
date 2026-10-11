package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import java.util.List;

/** Memory-only source links. Never substitute a displayed/truncated packet for its original. */
final class EvidenceLinks {
    static final int PACKET_LIMIT = 128 * 1024;
    static final int ITEM_LIMIT = 32;

    private EvidenceLinks() {}

    static boolean bounded(HttpRequest q, HttpResponse s) {
        if (q == null || s == null) return false;
        try {
            return q.toByteArray() != null && s.toByteArray() != null
                    && (long) q.toByteArray().length() + s.toByteArray().length() <= PACKET_LIMIT;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** A null result means no complete source is retained; the UI must disclose that limit. */
    static HttpRequestResponse retain(HttpRequest q, HttpResponse s) {
        if (!bounded(q, s)) return null;
        try {
            return HttpRequestResponse.httpRequestResponse(q, s);
        } catch (RuntimeException ex) {
            // Optional source retention must not prevent the independent analysis result from appearing.
            return null;
        }
    }

    /** Validate the whole batch before handing it to a consumer, including current scope. */
    static List<HttpRequestResponse> checked(MontoyaApi api, List<HttpRequestResponse> sources) {
        if (sources == null || sources.isEmpty() || sources.size() > ITEM_LIMIT)
            throw new IllegalArgumentException("원본 패킷은 1~32개가 필요합니다.");
        for (var rr : sources) {
            if (rr == null || !bounded(rr.request(), rr.response()))
                throw new IllegalArgumentException("원본 요청/응답 없음 또는 패킷 합계 128 KiB 초과 · 원본을 다시 선택하세요.");
            if (!WorkbenchScope.contains(api, rr.request()))
                throw new IllegalArgumentException("현재 SCOPE 밖 원본 포함 · 증거 전달을 중단했습니다.");
        }
        return List.copyOf(sources);
    }
}
