package kr.workbench;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Temporary, append-only original packets and a disk index, with a bounded metadata cache. */
final class DiskTrafficStore implements AutoCloseable {
  static final int CACHE_ROWS = 256;

  static final class StorageException extends IllegalStateException {
    StorageException(String message, Throwable cause) {
      super(message, cause);
    }

    StorageException(String message) {
      super(message);
    }
  }

  /** Immutable metadata only: the original packet is reconstructed on demand. */
  static final class PacketRef {
    private final DiskTrafficStore store;
    private final HttpService service;
    private final String method, url, responseStatus, time, source;
    private final long sequence, requestOffset, responseOffset, capturedAt;
    private final int requestLength, responseLength, responseBodyLength;
    private final boolean hasResponse;

    private PacketRef(
        DiskTrafficStore store,
        long sequence,
        HttpService service,
        String method,
        String url,
        String responseStatus,
        String time,
        String source,
        int responseBodyLength,
        long requestOffset,
        int requestLength,
        long responseOffset,
        int responseLength,
        boolean hasResponse, long capturedAt) {
      this.store = store;
      this.sequence = sequence;
      this.service = service;
      this.method = method;
      this.url = url;
      this.responseStatus = responseStatus;
      this.time = time;
      this.source = source;
      this.responseBodyLength = responseBodyLength;
      this.requestOffset = requestOffset;
      this.requestLength = requestLength;
      this.responseOffset = responseOffset;
      this.responseLength = responseLength;
      this.hasResponse = hasResponse;
      this.capturedAt = capturedAt;
    }

    String id() {
      return store.identity + ":" + sequence;
    }

    long sequence() {
      return sequence;
    }

    String method() {
      return method;
    }

    String url() {
      return url;
    }

    String responseStatus() {
      return responseStatus;
    }

    long capturedAt() { return capturedAt; }

    String time() {
      return time;
    }

    String source() {
      return source;
    }

    int responseBodyLength() {
      return responseBodyLength;
    }

    int requestLength() {
      return requestLength;
    }

    int responseLength() {
      return responseLength;
    }

    boolean hasResponse() {
      return hasResponse;
    }

    byte[] requestPrefix(int limit) { return store.read(requestOffset, Math.min(requestLength, limit)); }
    byte[] responsePrefix(int limit) { return hasResponse ? store.read(responseOffset, Math.min(responseLength, limit)) : new byte[0]; }

    HttpRequest request() {
      return HttpRequest.httpRequest(
          service, ByteArray.byteArray(store.read(requestOffset, requestLength)));
    }

    HttpResponse response() {
      if (!hasResponse) {
        store.ensureOpen();
        return null;
      }
      return HttpResponse.httpResponse(
          ByteArray.byteArray(store.read(responseOffset, responseLength)));
    }

    HttpRequestResponse packet() {
      byte[][] raw = store.readPair(this);
      HttpRequest request = HttpRequest.httpRequest(service, ByteArray.byteArray(raw[0]));
      HttpResponse response =
          hasResponse ? HttpResponse.httpResponse(ByteArray.byteArray(raw[1])) : null;
      return HttpRequestResponse.httpRequestResponse(request, response);
    }
  }

  private final String identity = UUID.randomUUID().toString();
  private final Path file, indexFile;
  private final RandomAccessFile data, index;
  private final FileChannel reader, indexReader;
  // Reads and appends may proceed concurrently; close alone excludes both.
  private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
  private final Map<Long, PacketRef> cache =
      new LinkedHashMap<>(CACHE_ROWS, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<Long, PacketRef> e) {
          return size() > CACHE_ROWS;
        }
      };
  private volatile long count;
  private volatile boolean closed;

  DiskTrafficStore() {
    Path created = null, createdIndex = null;
    RandomAccessFile opened = null, openedIndex = null;
    FileChannel openedReader = null, openedIndexReader = null;
    try {
      created = privateFile("burp-workbench-traffic-", ".bin");
      createdIndex = privateFile("burp-workbench-traffic-", ".idx");
      opened = new RandomAccessFile(created.toFile(), "rw");
      openedIndex = new RandomAccessFile(createdIndex.toFile(), "rw");
      // Separate handles are essential: a channel from RandomAccessFile shares its
      // file pointer with that writer. Windows positional IO may move that pointer
      // internally, outside the append monitor, while observers read older rows.
      openedReader = FileChannel.open(created, StandardOpenOption.READ);
      openedIndexReader = FileChannel.open(createdIndex, StandardOpenOption.READ);
      file = created;
      indexFile = createdIndex;
      data = opened;
      index = openedIndex;
      reader = openedReader;
      indexReader = openedIndexReader;
      file.toFile().deleteOnExit();
      indexFile.toFile().deleteOnExit();
    } catch (IOException error) {
      if (openedReader != null)
        try {
          openedReader.close();
        } catch (IOException cleanup) {
          error.addSuppressed(cleanup);
        }
      if (openedIndexReader != null)
        try {
          openedIndexReader.close();
        } catch (IOException cleanup) {
          error.addSuppressed(cleanup);
        }
      if (opened != null)
        try {
          opened.close();
        } catch (IOException ignored) {
        }
      if (openedIndex != null)
        try {
          openedIndex.close();
        } catch (IOException ignored) {
        }
      if (created != null)
        try {
          Files.deleteIfExists(created);
        } catch (IOException ignored) {
        }
      if (createdIndex != null)
        try {
          Files.deleteIfExists(createdIndex);
        } catch (IOException ignored) {
        }
      throw new StorageException("트래픽 임시 저장소를 만들 수 없습니다.", error);
    }
  }

  private static Path privateFile(String prefix, String suffix) throws IOException {
    try {
      return Files.createTempFile(
          prefix,
          suffix,
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    } catch (UnsupportedOperationException unsupported) {
      return Files.createTempFile(prefix, suffix);
    }
  }

  PacketRef append(HttpRequest request, HttpResponse response) {
    return append("", "", request, response);
  }

  synchronized PacketRef append(
      String time, String source, HttpRequest request, HttpResponse response) {
    return append(time, source, request, response, System.currentTimeMillis());
  }

  synchronized PacketRef append(String time, String source, HttpRequest request,
      HttpResponse response, long capturedAt) {
    lifecycle.readLock().lock();
    try {
      ensureOpen();
      Objects.requireNonNull(request, "request");
      HttpService service = request.httpService();
      String method = request.method(), url = request.url();
      String status = response == null ? "없음" : Short.toString(response.statusCode());
      int bodyLength = response == null ? 0 : response.body().length();
      byte[] requestBytes = request.toByteArray().getBytes();
      byte[] responseBytes = response == null ? new byte[0] : response.toByteArray().getBytes();
      long start = -1, indexStart = -1;
      try {
        start = data.length();
        indexStart = index.length();
        PacketRef ref =
            new PacketRef(
                this,
                count,
                service,
                method,
                url,
                status,
                time,
                source,
                bodyLength,
                start,
                requestBytes.length,
                start + requestBytes.length,
                responseBytes.length,
                response != null, capturedAt);
        byte[] metadata = encode(ref);
        data.seek(start);
        data.write(requestBytes);
        data.write(responseBytes);
        long metadataOffset = data.getFilePointer();
        data.write(metadata);
        index.seek(indexStart);
        index.writeLong(metadataOffset);
        index.writeInt(metadata.length);
        synchronized (cache) {
          cache.put(count, ref);
        }
        count++;
        return ref;
      } catch (IOException error) {
        if (start >= 0)
          try {
            data.setLength(start);
          } catch (IOException rollback) {
            error.addSuppressed(rollback);
          }
        if (indexStart >= 0)
          try {
            index.setLength(indexStart);
          } catch (IOException rollback) {
            error.addSuppressed(rollback);
          }
        throw new StorageException("트래픽 원본을 임시 저장하지 못했습니다.", error);
      }
    } finally {
      lifecycle.readLock().unlock();
    }
  }

  PacketRef ref(long sequence) {
    lifecycle.readLock().lock();
    try {
      ensureOpen();
      if (sequence < 0 || sequence >= count)
        throw new IndexOutOfBoundsException(Long.toString(sequence));
      synchronized (cache) {
        PacketRef ref = cache.get(sequence);
        if (ref != null) return ref;
      }
      try {
        ByteBuffer pointer = ByteBuffer.wrap(readFully(indexReader, sequence * 12, 12));
        long offset = pointer.getLong();
        int length = pointer.getInt();
        PacketRef ref = decode(sequence, readFully(reader, offset, length));
        synchronized (cache) {
          cache.put(sequence, ref);
        }
        return ref;
      } catch (IOException error) {
        throw new StorageException("트래픽 색인을 읽지 못했습니다.", error);
      }
    } finally {
      lifecycle.readLock().unlock();
    }
  }

  private static void string(DataOutputStream out, String value) throws IOException {
    byte[] bytes = Objects.toString(value, "").getBytes(StandardCharsets.UTF_8);
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  private static String string(DataInputStream in) throws IOException {
    int length = in.readInt();
    if (length < 0 || length > in.available()) throw new IOException("Invalid metadata string");
    return new String(in.readNBytes(length), StandardCharsets.UTF_8);
  }

  private byte[] encode(PacketRef ref) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      string(out, ref.service.host());
      out.writeInt(ref.service.port());
      out.writeBoolean(ref.service.secure());
      string(out, ref.method);
      string(out, ref.url);
      string(out, ref.responseStatus);
      string(out, ref.time);
      string(out, ref.source);
      out.writeInt(ref.responseBodyLength);
      out.writeLong(ref.requestOffset);
      out.writeInt(ref.requestLength);
      out.writeLong(ref.responseOffset);
      out.writeInt(ref.responseLength);
      out.writeBoolean(ref.hasResponse);
      out.writeLong(ref.capturedAt);
    }
    return bytes.toByteArray();
  }

  private PacketRef decode(long sequence, byte[] bytes) throws IOException {
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
      HttpService service = HttpService.httpService(string(in), in.readInt(), in.readBoolean());
      return new PacketRef(
          this,
          sequence,
          service,
          string(in),
          string(in),
          string(in),
          string(in),
          string(in),
          in.readInt(),
          in.readLong(),
          in.readInt(),
          in.readLong(),
          in.readInt(),
          in.readBoolean(), in.readLong());
    }
  }

  private static byte[] readFully(FileChannel channel, long offset, int length) throws IOException {
    long size = channel.size();
    if (offset < 0 || length < 0 || offset > size || length > size - offset)
      throw new IOException("Invalid or truncated packet range");
    ByteBuffer bytes = ByteBuffer.allocate(length);
    while (bytes.hasRemaining())
      if (channel.read(bytes, offset + bytes.position()) < 0) throw new EOFException();
    return bytes.array();
  }

  private byte[] read(long offset, int length) {
    lifecycle.readLock().lock();
    try {
      ensureOpen();
      return readFully(reader, offset, length);
    } catch (IOException error) {
      throw new StorageException("트래픽 원본을 읽지 못했습니다.", error);
    } finally {
      lifecycle.readLock().unlock();
    }
  }

  private byte[][] readPair(PacketRef ref) {
    lifecycle.readLock().lock();
    try {
      ensureOpen();
      return new byte[][] {
        read(ref.requestOffset, ref.requestLength),
        ref.hasResponse ? read(ref.responseOffset, ref.responseLength) : null
      };
    } finally {
      lifecycle.readLock().unlock();
    }
  }

  private void ensureOpen() {
    if (closed) throw new StorageException("트래픽 임시 저장소가 닫혔습니다.");
  }

  long size() {
    return count;
  }

  long diskBytes() {
    lifecycle.readLock().lock();
    try {
      ensureOpen();
      return reader.size() + indexReader.size();
    } catch (IOException error) {
      throw new StorageException("트래픽 저장 크기를 확인하지 못했습니다.", error);
    } finally {
      lifecycle.readLock().unlock();
    }
  }

  int cachedRows() {
    synchronized (cache) {
      return cache.size();
    }
  }

  Path file() {
    return file;
  }

  Path indexFile() {
    return indexFile;
  }

  @Override
  public void close() {
    lifecycle.writeLock().lock();
    try {
      if (closed) return;
      closed = true;
      synchronized (cache) {
        cache.clear();
      }
      IOException failure = null;
      for (Closeable handle : List.of(reader, indexReader, data, index))
        try {
          handle.close();
        } catch (IOException error) {
          if (failure == null) failure = error;
          else failure.addSuppressed(error);
        }
      for (Path path : List.of(file, indexFile))
        try {
          Files.deleteIfExists(path);
        } catch (IOException error) {
          if (failure == null) failure = error;
          else failure.addSuppressed(error);
        }
      if (failure != null) throw new StorageException("트래픽 임시 저장소를 정리하지 못했습니다.", failure);
    } finally {
      lifecycle.writeLock().unlock();
    }
  }
}
