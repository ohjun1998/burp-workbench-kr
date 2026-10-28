package kr.workbench;

import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit reservations for retained input and temporary restoration/decoding work. Not JVM heap. */
final class ResponseAnalysisBudget {
  static final long QUEUED_LIMIT = 16L * 1024 * 1024;
  static final long WORKING_LIMIT = 224L * 1024 * 1024;
  static final int WIRE_LIMIT = 5 * 1024 * 1024;
  static final int BODY_LIMIT = 2 * 1024 * 1024;
  private final long queuedLimit, workingLimit;
  private long queued, working, peakQueued, peakWorking, rejected;

  record Metrics(long queuedBytes, long queuedLimit, long workingBytes, long workingLimit,
      long peakQueuedBytes, long peakWorkingBytes, long rejected) {}

  ResponseAnalysisBudget() { this(QUEUED_LIMIT, WORKING_LIMIT); }
  ResponseAnalysisBudget(long queuedLimit, long workingLimit) {
    if (queuedLimit < 1 || workingLimit < 1) throw new IllegalArgumentException("positive budgets required");
    this.queuedLimit = queuedLimit;
    this.workingLimit = workingLimit;
  }

  synchronized Reservation queued(long bytes) { return reserve(bytes, false); }
  synchronized Reservation working(long bytes) { return reserve(bytes, true); }

  private Reservation reserve(long bytes, boolean temporary) {
    if (bytes < 0) throw new IllegalArgumentException("negative reservation");
    long used = temporary ? working : queued, limit = temporary ? workingLimit : queuedLimit;
    if (bytes > limit - used) { rejected++; return null; }
    if (temporary) peakWorking = Math.max(peakWorking, working += bytes);
    else peakQueued = Math.max(peakQueued, queued += bytes);
    return new Reservation(bytes, temporary);
  }

  synchronized Metrics metrics() {
    return new Metrics(queued, queuedLimit, working, workingLimit, peakQueued, peakWorking, rejected);
  }

  String diagnostics() {
    Metrics value = metrics();
    return "응답 입력 보유 예약 " + RuntimeMetrics.memory(value.queuedBytes()) + " / "
        + RuntimeMetrics.memory(value.queuedLimit()) + " · 복원·디코딩 작업 예약 "
        + RuntimeMetrics.memory(value.workingBytes()) + " / " + RuntimeMetrics.memory(value.workingLimit())
        + " · 예약 제외 " + value.rejected()
        + " (바이트·문자 복사량 추정, 파서·증거 캐시·JVM 전체 heap 상한 아님)";
  }

  final class Reservation implements AutoCloseable {
    private final long bytes;
    private final boolean temporary;
    private final AtomicBoolean released = new AtomicBoolean();
    private Reservation(long bytes, boolean temporary) { this.bytes = bytes; this.temporary = temporary; }
    @Override public void close() {
      if (released.compareAndSet(false, true)) synchronized (ResponseAnalysisBudget.this) {
        if (temporary) working -= bytes;
        else queued -= bytes;
      }
    }
  }
}
