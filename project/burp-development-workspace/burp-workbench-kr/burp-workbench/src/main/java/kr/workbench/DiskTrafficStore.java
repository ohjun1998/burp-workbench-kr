package kr.workbench;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;

/**
 * Raw packets for one extension load. The table keeps only PacketRefs in memory;
 * reading a selected packet restores its original bytes and HTTP destination.
 * This is temporary storage, not a persistent Burp project/history database.
 */
final class DiskTrafficStore implements AutoCloseable {
    static final class StorageException extends IllegalStateException {
        StorageException(String message, Throwable cause) { super(message, cause); }
        StorageException(String message) { super(message); }
    }

    /** No HttpRequest/HttpResponse or raw byte arrays are retained in this ref. */
    static final class PacketRef {
        private final DiskTrafficStore store;
        private final HttpService service;
        private final String method, url, responseStatus;
        private final long requestOffset, responseOffset;
        private final int requestLength, responseLength, responseBodyLength;
        private final boolean hasResponse;

        private PacketRef(DiskTrafficStore store, HttpService service,
                          String method, String url, String responseStatus,
                          int responseBodyLength, long requestOffset, int requestLength,
                          long responseOffset, int responseLength, boolean hasResponse) {
            this.store = store; this.service = service;
            this.method = method; this.url = url; this.responseStatus = responseStatus;
            this.responseBodyLength = responseBodyLength;
            this.requestOffset = requestOffset; this.requestLength = requestLength;
            this.responseOffset = responseOffset; this.responseLength = responseLength;
            this.hasResponse = hasResponse;
        }

        String method() { return method; }
        String url() { return url; }
        String responseStatus() { return responseStatus; }
        int responseBodyLength() { return responseBodyLength; }
        int requestLength() { return requestLength; }
        int responseLength() { return responseLength; }
        boolean hasResponse() { return hasResponse; }

        HttpRequest request() {
            return HttpRequest.httpRequest(service, ByteArray.byteArray(store.read(requestOffset, requestLength)));
        }

        HttpResponse response() {
            if (!hasResponse) { store.ensureOpen(); return null; }
            return HttpResponse.httpResponse(ByteArray.byteArray(store.read(responseOffset, responseLength)));
        }

        HttpRequestResponse packet() {
            byte[][] raw = store.readPair(this);
            HttpRequest request = HttpRequest.httpRequest(service, ByteArray.byteArray(raw[0]));
            HttpResponse response = hasResponse ? HttpResponse.httpResponse(ByteArray.byteArray(raw[1])) : null;
            return HttpRequestResponse.httpRequestResponse(request, response);
        }
    }

    private final Path file;
    private final RandomAccessFile data;
    private long count;
    private boolean closed;

    DiskTrafficStore() {
        Path created = null;
        try {
            // On POSIX systems the file is private from the moment it is created.
            try {
                created = Files.createTempFile("burp-workbench-traffic-", ".bin",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException unsupported) {
                created = Files.createTempFile("burp-workbench-traffic-", ".bin");
            }
            data = new RandomAccessFile(created.toFile(), "rw");
            file = created;
            // Also clean up after a normal JVM exit if Burp misses its unload hook.
            file.toFile().deleteOnExit();
        } catch (IOException error) {
            if (created != null) try { Files.deleteIfExists(created); } catch (IOException ignored) {}
            throw new StorageException("트래픽 임시 저장소를 만들 수 없습니다.", error);
        }
    }

    synchronized PacketRef append(HttpRequest request, HttpResponse response) {
        ensureOpen();
        Objects.requireNonNull(request, "request");
        // Gather metadata before a write so a malformed host packet cannot leave
        // a half-record visible. All raw arrays below are released on return.
        HttpService service = request.httpService();
        String method = request.method(), url = request.url();
        String responseStatus = response == null ? "없음" : Short.toString(response.statusCode());
        int responseBodyLength = response == null ? 0 : response.body().length();
        byte[] requestBytes = request.toByteArray().getBytes();
        byte[] responseBytes = response == null ? new byte[0] : response.toByteArray().getBytes();
        long start = -1;
        try {
            start = data.length();
            data.seek(start);
            data.write(requestBytes);
            data.write(responseBytes);
            PacketRef ref = new PacketRef(this, service, method, url, responseStatus,
                    responseBodyLength, start, requestBytes.length,
                    start + requestBytes.length, responseBytes.length, response != null);
            count++;
            return ref;
        } catch (IOException error) {
            if (start >= 0) try { data.setLength(start); } catch (IOException rollback) { error.addSuppressed(rollback); }
            throw new StorageException("트래픽 원본을 임시 저장하지 못했습니다.", error);
        }
    }

    private synchronized byte[] read(long offset, int length) {
        ensureOpen();
        try {
            long fileLength = data.length();
            if (offset < 0 || length < 0 || offset > fileLength || length > fileLength - offset)
                throw new IOException("Invalid or truncated packet range");
            byte[] raw = new byte[length];
            data.seek(offset);
            data.readFully(raw);
            return raw;
        } catch (IOException error) {
            throw new StorageException("트래픽 원본을 읽지 못했습니다.", error);
        }
    }

    private synchronized byte[][] readPair(PacketRef ref) {
        // One lock protects both reads against concurrent writes or unloading.
        return new byte[][] { read(ref.requestOffset, ref.requestLength),
                ref.hasResponse ? read(ref.responseOffset, ref.responseLength) : null };
    }

    private synchronized void ensureOpen() {
        if (closed) throw new StorageException("트래픽 임시 저장소가 닫혔습니다.");
    }

    synchronized long size() { return count; }
    synchronized long diskBytes() {
        ensureOpen();
        try { return data.length(); }
        catch (IOException error) { throw new StorageException("트래픽 저장 크기를 확인하지 못했습니다.", error); }
    }
    Path file() { return file; }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        IOException failure = null;
        try { data.close(); } catch (IOException error) { failure = error; }
        try { Files.deleteIfExists(file); }
        catch (IOException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        if (failure != null) throw new StorageException("트래픽 임시 저장소를 정리하지 못했습니다.", failure);
    }
}
