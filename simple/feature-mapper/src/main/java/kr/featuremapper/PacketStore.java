package kr.featuremapper;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

/** Raw traffic lives in a private append-only file, never in table rows. */
final class PacketStore implements AutoCloseable {
  record Ref(long offset, int requestBytes, int responseBytes) {}

  private final Path path;
  private final RandomAccessFile file;
  private boolean closed;
  private int snapshots;

  PacketStore() throws IOException {
    Path candidate;
    try {
      candidate =
          Files.createTempFile(
              "feature-mapper-",
              ".packets",
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    } catch (UnsupportedOperationException e) {
      candidate = Files.createTempFile("feature-mapper-", ".packets");
    }
    path = candidate;
    try {
      file = new RandomAccessFile(path.toFile(), "rw");
    } catch (IOException e) {
      Files.deleteIfExists(path);
      throw e;
    }
    path.toFile().deleteOnExit();
  }

  synchronized Ref append(byte[] request, byte[] response) throws IOException {
    ensureOpen();
    long start = file.length();
    file.seek(start);
    try {
      file.write(request);
      file.write(response);
      return new Ref(start, request.length, response.length);
    } catch (IOException e) {
      try {
        file.setLength(start);
      } catch (IOException rollback) {
        e.addSuppressed(rollback);
      }
      throw e;
    }
  }

  synchronized byte[][] read(Ref ref) throws IOException {
    return read(ref, Integer.MAX_VALUE);
  }

  synchronized byte[][] read(Ref ref, int limit) throws IOException {
    validate(ref);
    if (limit < 0) throw new IllegalArgumentException("negative preview limit");
    file.seek(ref.offset());
    byte[] request = new byte[Math.min(ref.requestBytes(), limit)];
    byte[] response = new byte[Math.min(ref.responseBytes(), limit)];
    file.readFully(request);
    file.seek(ref.offset() + ref.requestBytes());
    file.readFully(response);
    return new byte[][] {request, response};
  }

  synchronized void validate(Ref ref) throws IOException {
    ensureOpen();
    long size = (long) ref.requestBytes() + ref.responseBytes();
    if (ref.offset() < 0
        || ref.requestBytes() < 0
        || ref.responseBytes() < 0
        || ref.offset() > file.length()
        || size > file.length() - ref.offset()) {
      throw new IOException("패킷 저장 위치가 잘못됐습니다.");
    }
  }

  synchronized long bytes() throws IOException {
    ensureOpen();
    return file.length();
  }

  /**
   * Pins a fixed immutable prefix and a separate file descriptor; append never waits for ZIP IO.
   */
  synchronized Snapshot snapshot() throws IOException {
    ensureOpen();
    InputStream input = Files.newInputStream(path);
    try {
      Snapshot snapshot = new Snapshot(input, file.length());
      snapshots++;
      return snapshot;
    } catch (IOException | RuntimeException e) {
      input.close();
      throw e;
    }
  }

  final class Snapshot implements AutoCloseable {
    private final InputStream input;
    private final long size;
    private boolean released;

    private Snapshot(InputStream input, long size) {
      this.input = input;
      this.size = size;
    }

    long bytes() {
      return size;
    }

    /** Single-consumer snapshot; close is deferred while copying. */
    synchronized void copyTo(OutputStream output) throws IOException {
      if (released) throw new IOException("저장 스냅샷이 닫혔습니다.");
      byte[] buffer = new byte[65536];
      long remaining = size;
      while (remaining > 0) {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("프로젝트 저장 취소");
        int n = input.read(buffer, 0, (int) Math.min(remaining, buffer.length));
        if (n < 0) throw new EOFException("저장 스냅샷 원본이 부족합니다.");
        output.write(buffer, 0, n);
        remaining -= n;
      }
    }

    public synchronized void close() throws IOException {
      if (released) return;
      released = true;
      try {
        input.close();
      } finally {
        synchronized (PacketStore.this) {
          snapshots--;
          if (closed && snapshots == 0) Files.deleteIfExists(path);
        }
      }
    }
  }

  synchronized void restore(InputStream input) throws IOException {
    ensureOpen();
    if (snapshots != 0) throw new IOException("저장 중인 패킷 파일은 교체할 수 없습니다.");
    file.setLength(0);
    file.seek(0);
    byte[] buffer = new byte[65536];
    for (int n; (n = input.read(buffer)) >= 0; ) file.write(buffer, 0, n);
  }

  Path path() {
    return path;
  }

  private void ensureOpen() throws IOException {
    if (closed) throw new IOException("패킷 저장소가 닫혔습니다.");
  }

  public synchronized void close() throws IOException {
    if (closed) return;
    closed = true;
    try {
      file.close();
    } finally {
      // Windows also keeps leased file descriptors open until the save releases them.
      if (snapshots == 0) Files.deleteIfExists(path);
    }
  }
}
