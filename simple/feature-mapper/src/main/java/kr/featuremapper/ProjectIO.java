package kr.featuremapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

final class ProjectIO {
  static void save(Mapper mapper, Path target) throws IOException {
    try (Mapper.SaveSnapshot snapshot = mapper.snapshot()) {
      save(snapshot, target);
    }
  }

  static void save(Mapper.SaveSnapshot snapshot, Path target) throws IOException {
    Path absolute = target.toAbsolutePath(),
        tmp = Files.createTempFile(absolute.getParent(), ".fmkr-save-", ".tmp");
    try {
      try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tmp))) {
        zip.putNextEntry(new ZipEntry("project.json"));
        zip.write(JsonWrite.text(snapshot.metadata()).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        zip.putNextEntry(new ZipEntry("packets.bin"));
        snapshot.raw().copyTo(zip);
        zip.closeEntry();
      }
      try {
        Files.move(
            tmp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  @SuppressWarnings("unchecked")
  static Mapper load(Path path) throws IOException {
    Mapper candidate = new Mapper();
    try (ZipFile zip = new ZipFile(path.toFile())) {
      Set<String> names = new HashSet<>();
      var entries = zip.entries();
      while (entries.hasMoreElements()) {
        String name = entries.nextElement().getName();
        if (!Set.of("project.json", "packets.bin").contains(name) || !names.add(name))
          throw new IOException("프로젝트에 알 수 없는/중복 항목이 있습니다.");
      }
      if (names.size() != 2) throw new IOException("프로젝트 구성 파일이 없습니다.");
      try (InputStream raw = zip.getInputStream(zip.getEntry("packets.bin"))) {
        candidate.store().restore(raw);
      }
      // Bounds apply to import metadata parsing, not packet sizes or retained row count.
      byte[] json;
      try (InputStream in = zip.getInputStream(zip.getEntry("project.json"))) {
        json = in.readNBytes(128 * 1024 * 1024 + 1);
      }
      if (json.length > 128 * 1024 * 1024) throw new IOException("프로젝트 메타데이터가 너무 큽니다.");
      candidate.restoreMetadata(
          (Map<String, Object>)
              Json.parse(new String(json, StandardCharsets.UTF_8), 128 * 1024 * 1024, 2_000_000));
      return candidate;
    } catch (Exception e) {
      candidate.close();
      if (e instanceof IOException io) throw io;
      throw new IOException("프로젝트를 읽지 못했습니다.", e);
    }
  }
}
