package kr.workbench;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;

/** Automatic jobs retain a disk reference; manual jobs retain only a bounded immutable wire copy. */
sealed interface ResponseAnalysisInput {
  final class TooLarge extends IllegalArgumentException {
    TooLarge(String message) { super(message); }
  }
  String url();
  long wireBytes();
  HttpRequestResponse packet();

  record Prepared(ResponseAnalysisInput input, ResponseAnalysisBudget.Reservation reservation) {}
  record Data(String url, String method, short status, byte[] body, String encoding,
      String contentType, String headers, String requestBody, String location) {}

  static Data data(HttpRequest request, HttpResponse response) {
    String headers = response.toString().split("\\r?\\n\\r?\\n", 2)[0];
    return new Data(request.url(), request.method(), response.statusCode(), response.body().getBytes(),
        response.headerValue("Content-Encoding"), response.headerValue("Content-Type"), headers,
        request.body() != null && request.body().length() <= 65536 ? request.bodyToString() : "",
        response.headerValue("Location"));
  }

  record Original(DiskTrafficStore.PacketRef original) implements ResponseAnalysisInput {
    public String url() { return original.url(); }
    public long wireBytes() { return original.requestLength() + (long) original.responseLength(); }
    public HttpRequestResponse packet() { return original.packet(); }
  }

  final class Snapshot implements ResponseAnalysisInput {
    private final String url, host;
    private final int port;
    private final boolean secure;
    private final byte[] request, response;
    private final Data data;
    private Snapshot(String url, String host, int port, boolean secure, byte[] request, byte[] response, Data data) {
      this.url = url; this.host = host; this.port = port; this.secure = secure;
      this.request = request; this.response = response;
      this.data = data;
    }
    Data data() { return data; }
    public String url() { return url; }
    public long wireBytes() { return request.length + (long) response.length; }
    public HttpRequestResponse packet() {
      HttpService service = HttpService.httpService(host, port, secure);
      return HttpRequestResponse.httpRequestResponse(
          HttpRequest.httpRequest(service, ByteArray.byteArray(request)),
          HttpResponse.httpResponse(ByteArray.byteArray(response)));
    }
  }

  static Prepared original(DiskTrafficStore.PacketRef original, ResponseAnalysisBudget budget) {
    Original input = new Original(original);
    if (input.wireBytes() > ResponseAnalysisBudget.WIRE_LIMIT) return new Prepared(input, null);
    long metadata = 512L + 4L * original.url().length() + 2L * original.method().length();
    return new Prepared(input, budget.queued(metadata));
  }

  static Prepared snapshot(HttpRequest request, HttpResponse response, ResponseAnalysisBudget budget) {
    ByteArray q = request.toByteArray(), s = response.toByteArray();
    long bytes = q.length() + (long) s.length();
    if (bytes > ResponseAnalysisBudget.WIRE_LIMIT)
      throw new TooLarge("원본 복원 5 MiB 한도 초과");
    String url = request.url();
    HttpService service = request.httpService();
    String host = service.host();
    // Raw wire, extracted body/header text and bounded request body may coexist.
    ResponseAnalysisBudget.Reservation reservation = budget.queued(3L * bytes + 256 * 1024L + 2L * (url.length() + host.length()));
    if (reservation == null) return new Prepared(null, null);
    try {
      return new Prepared(new Snapshot(url, host, service.port(), service.secure(), q.getBytes(), s.getBytes(), data(request, response)), reservation);
    } catch (RuntimeException | Error failure) { reservation.close(); throw failure; }
  }
}
