package kr.workbench;

import burp.api.montoya.MontoyaApi;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Project-local, credential-free setup library. A restore never activates comparison settings. */
final class AuthSetupBundleStore {
  record Loaded(List<AuthSetupBundles.Bundle> bundles, boolean recovered) {
    Loaded { bundles = List.copyOf(bundles); }
  }
  final Path directory, primary, backup;

  AuthSetupBundleStore(Path directory) {
    this.directory = directory.toAbsolutePath().normalize();
    primary = this.directory.resolve("bundles-v1.json");
    backup = this.directory.resolve("bundles-v1.backup.json");
  }

  static AuthSetupBundleStore project(MontoyaApi api) {
    try {
      if (api == null || api.persistence() == null || api.persistence().extensionData() == null) return null;
      var data = api.persistence().extensionData();
      String key = BuildInfo.storageKey("auth-setup-library-project-v1");
      String id = data.getString(key);
      if (id == null) {
        id = UUID.randomUUID().toString();
        data.setString(key, id);
        // A host that cannot retain a project identity must not share a fallback library.
        if (!id.equals(data.getString(key))) return null;
      }
      if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) return null;
      return new AuthSetupBundleStore(Path.of(System.getProperty("user.home"), BuildInfo.STORAGE_DIRECTORY,
          "auth-setups", id));
    } catch (RuntimeException error) { return null; }
  }

  Loaded load() throws IOException {
    if (!Files.exists(primary) && !Files.exists(backup)) return new Loaded(List.of(), false);
    Exception primaryFailure = null;
    if (Files.exists(primary)) {
      try { return new Loaded(read(primary), false); }
      catch (IOException | IllegalArgumentException error) {
        if (unsupported(error)) throw new IOException("더 새로운 형식의 설정 목록입니다. 현재 파일을 보존하고 지원 버전에서 여세요.", error);
        primaryFailure = error;
      }
    }
    try {
      if (Files.exists(backup)) return new Loaded(read(backup), true);
    } catch (IOException | IllegalArgumentException error) {
      if (primaryFailure != null) error.addSuppressed(primaryFailure);
      throw new IOException("설정 목록과 복구본을 읽을 수 없습니다. 파일을 보존하고 JSON 백업을 확인하세요.", error);
    }
    throw new IOException("설정 목록을 읽을 수 없으며 복구본이 없습니다. 파일을 보존하고 JSON 백업을 확인하세요.", primaryFailure);
  }

  /** Optimistic snapshot check + a filesystem lock prevents silent concurrent library overwrite. */
  void commit(List<AuthSetupBundles.Bundle> expected, List<AuthSetupBundles.Bundle> next) throws IOException {
    AuthSetupBundles.encode(next);
    Files.createDirectories(directory);
    try { Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------")); }
    catch (UnsupportedOperationException ignored) { }
    Path lockPath = directory.resolve("library.lock");
    try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      try { Files.setPosixFilePermissions(lockPath, PosixFilePermissions.fromString("rw-------")); }
      catch (UnsupportedOperationException ignored) { }
      try (FileLock lock = channel.tryLock()) {
        if (lock == null) throw new IOException("다른 창에서 설정 목록을 저장 중입니다. 저장 목록을 다시 읽고 시도하세요.");
        Loaded current = load();
        if (!Objects.equals(expected, current.bundles()))
          throw new IOException("다른 창에서 설정 목록이 변경되었습니다. 저장 목록을 다시 읽고 검토하세요.");
        // Never replace the last valid backup with a damaged primary.
        if (!current.recovered() && Files.exists(primary)) AuthSetupBundles.write(backup, current.bundles());
        AuthSetupBundles.write(primary, next);
        // Directory sync is supported on Unix; some Windows providers do not permit opening directories.
        try (FileChannel parent = FileChannel.open(directory, StandardOpenOption.READ)) { parent.force(true); }
        catch (IOException | UnsupportedOperationException ignored) { }
      }
    } catch (OverlappingFileLockException error) {
      throw new IOException("다른 창에서 설정 목록을 저장 중입니다. 저장 목록을 다시 읽고 시도하세요.", error);
    }
  }

  private static List<AuthSetupBundles.Bundle> read(Path path) throws IOException {
    List<AuthSetupBundles.Bundle> values = AuthSetupBundles.read(path);
    if (values.stream().anyMatch(bundle -> bundle.revision() < 1))
      throw new IllegalArgumentException("저장 목록에 revision 없는 초안이 있습니다.");
    return values;
  }

  private static boolean unsupported(Exception error) {
    return error instanceof IllegalArgumentException
        && Objects.toString(error.getMessage(), "").contains("지원하는 설정 묶음 schema");
  }
}
