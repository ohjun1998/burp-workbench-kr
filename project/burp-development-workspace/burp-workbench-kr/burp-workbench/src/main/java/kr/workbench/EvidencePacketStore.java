package kr.workbench;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Evidence owns its raw packet copies independently of traffic selection/history. Only offsets,
 * lengths and digests stay in memory. Complete API-provided bytes live in a private temporary file
 * until evidence is cleared or the panel closes.
 */
final class EvidencePacketStore implements AutoCloseable {
  static final int BLOCK_BYTES = 64 * 1024;
  static final int PREVIEW_BYTES = 128 * 1024;

  record Part(long offset, int length, String sha256) {}

  static final class Ref {
    private final EvidencePacketStore store;
    private final Part request, response;
    private final boolean hasResponse;
    private final String packetId;

    Ref(EvidencePacketStore store, Part request, Part response, boolean hasResponse) {
      this.store = store;
      this.request = request;
      this.response = response;
      this.hasResponse = hasResponse;
      packetId = EvidencePacketStore.packetId(request.sha256, response.sha256, hasResponse);
    }

    String requestPreview() {
      return store.preview(request);
    }

    String responsePreview() {
      return store.preview(response);
    }

    Part request() {
      return request;
    }

    Part response() {
      return response;
    }

    boolean hasResponse() {
      return hasResponse;
    }

    String packetId() {
      return packetId;
    }

    Lease retain() {
      return store.retain();
    }

    void copyRequestTo(OutputStream out) throws IOException {
      store.copy(request, out);
    }

    void copyResponseTo(OutputStream out) throws IOException {
      store.copy(response, out);
    }
  }

  final class Lease implements AutoCloseable {
    private boolean released;

    @Override
    public void close() {
      synchronized (EvidencePacketStore.this) {
        if (released) return;
        released = true;
        readers--;
        if (closed && readers == 0) dispose();
      }
    }
  }

  private Path file;
  private RandomAccessFile data;
  private boolean closed;
  private int readers;

  /** File creation is deferred until the first manually selected evidence item. */
  private void open() throws IOException {
    if (closed) throw new IOException("증거 원본 저장소가 닫혔습니다.");
    if (data != null) return;
    Path created;
    try {
      created =
          Files.createTempFile(
              "burp-workbench-evidence-",
              ".bin",
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    } catch (UnsupportedOperationException unsupported) {
      created = Files.createTempFile("burp-workbench-evidence-", ".bin");
    }
    try {
      data = new RandomAccessFile(created.toFile(), "rw");
      file = created;
      file.toFile().deleteOnExit();
    } catch (IOException failure) {
      Files.deleteIfExists(created);
      throw failure;
    }
  }

  synchronized Ref append(HttpRequest request, HttpResponse response) {
    Objects.requireNonNull(request, "request");
    long start = -1;
    try {
      open();
      start = data.length();
      data.seek(start);
      Part q = write(request.toByteArray());
      Part r =
          response == null
              ? new Part(data.getFilePointer(), 0, hash(new byte[0]))
              : write(response.toByteArray());
      return new Ref(this, q, r, response != null);
    } catch (IOException | RuntimeException error) {
      if (start >= 0)
        try {
          data.setLength(start);
        } catch (IOException rollback) {
          error.addSuppressed(rollback);
        }
      throw new IllegalArgumentException("증거 원본을 임시 저장하지 못했습니다.", error);
    }
  }

  private Part write(ByteArray bytes) throws IOException {
    int length = bytes.length();
    long offset = data.getFilePointer();
    MessageDigest digest = digest();
    for (int start = 0; start < length; ) {
      int end = (int) Math.min((long) length, (long) start + BLOCK_BYTES);
      byte[] block = bytes.subArray(start, end).getBytes();
      if (block.length != end - start) throw new IOException("잘못된 HTTP 바이트 범위");
      data.write(block);
      digest.update(block);
      start = end;
    }
    return new Part(offset, length, HexFormat.of().formatHex(digest.digest()));
  }

  synchronized Lease retain() {
    if (closed || data == null) throw new IllegalArgumentException("증거 원본 저장소가 닫혔습니다.");
    readers++;
    return new Lease();
  }

  private void readable(Part part) throws IOException {
    if (data == null || closed && readers == 0) throw new IOException("증거 원본 저장소가 닫혔습니다.");
    if (part.offset < 0
        || part.length < 0
        || part.offset > data.length()
        || part.length > data.length() - part.offset)
      throw new IOException("증거 원본 바이트 범위가 유실되었습니다.");
  }

  private synchronized String preview(Part part) {
    try {
      readable(part);
      byte[] bytes = new byte[Math.min(part.length, PREVIEW_BYTES)];
      data.seek(part.offset);
      data.readFully(bytes);
      return new String(bytes, StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new IllegalArgumentException("증거 원본 미리보기를 읽지 못했습니다.", error);
    }
  }

  private synchronized void copy(Part part, OutputStream out) throws IOException {
    readable(part);
    data.seek(part.offset);
    byte[] block = new byte[BLOCK_BYTES];
    int remaining = part.length;
    while (remaining > 0) {
      int length = Math.min(remaining, block.length);
      data.readFully(block, 0, length);
      out.write(block, 0, length);
      remaining -= length;
    }
  }

  synchronized Path file() {
    return file;
  }

  synchronized long diskBytes() {
    try {
      return data == null ? 0 : data.length();
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
  }

  @Override
  public synchronized void close() {
    closed = true;
    if (readers == 0) dispose();
  }

  private void dispose() {
    IOException failure = null;
    if (data != null) {
      try {
        data.close();
      } catch (IOException error) {
        failure = error;
      }
      data = null;
    }
    if (file != null)
      try {
        Files.deleteIfExists(file);
      } catch (IOException error) {
        if (failure == null) failure = error;
        else failure.addSuppressed(error);
      }
    if (failure != null) throw new IllegalStateException("증거 원본 임시 파일을 정리하지 못했습니다.", failure);
  }

  static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  static String hash(byte[] bytes) {
    return HexFormat.of().formatHex(digest().digest(bytes));
  }

  /** Content identity shared across evidence stores; absent and empty responses differ. */
  static String packetId(String requestHash, String responseHash, boolean hasResponse) {
    return "http-sha256:"
        + hash(
            ("workbench-http-pair-v1\n"
                    + requestHash
                    + "\n"
                    + (hasResponse ? "response:" + responseHash : "no-response"))
                .getBytes(StandardCharsets.US_ASCII));
  }
}
