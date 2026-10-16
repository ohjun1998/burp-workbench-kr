package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.responses.HttpResponse;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipFile;

/** Large original packets remain byte exact on disk; previews and archive export stay bounded. */
public final class EvidenceStorageTest extends WorkbenchTest {
    private static final AtomicInteger largestRead = new AtomicInteger();

    private static ByteArray boundedBytes(byte[] bytes, int offset, int length) {
        return proxy(ByteArray.class, (p, m, a) -> switch (m.getName()) {
            case "length" -> length;
            case "getBytes" -> {
                checkChunk(length);
                yield Arrays.copyOfRange(bytes, offset, offset + length);
            }
            case "subArray" -> {
                int from = (int) a[0], to = (int) a[1];
                java.util.Objects.checkFromToIndex(from, to, length);
                yield boundedBytes(bytes, offset + from, to - from);
            }
            case "toString" -> throw new AssertionError("Large packet must never be decoded as a whole String");
            default -> def(m);
        });
    }

    private static void checkChunk(int length) {
        if (length > EvidencePacketStore.BLOCK_BYTES) throw new AssertionError("Unbounded source byte copy: " + length);
        largestRead.accumulateAndGet(length, Math::max);
    }

    private static HttpResponse rawResponse(byte[] bytes) {
        return proxy(HttpResponse.class, (p, m, a) -> switch (m.getName()) {
            case "toByteArray" -> boundedBytes(bytes, 0, bytes.length);
            case "statusCode" -> (short) 200;
            case "toString", "bodyToString" -> throw new AssertionError("Whole packet text must not be retained");
            default -> def(m);
        });
    }

    private static String hashResponse(EvidencePacketStore.Ref ref) throws Exception {
        var digest = EvidencePacketStore.digest();
        try (var out = new DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
            ref.copyResponseTo(out);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static void main(String[] args) throws Exception {
        BountyFeaturesTest.factory();
        var q = BountyFeaturesTest.rq("https://auth.test/evidence?token=QUERY_SECRET", "GET", "",
                List.of(header("Cookie", "SID=COOKIE_SECRET")));
        byte[] raw = new byte[4 * 1024 * 1024 + 37];
        for (int i = 0; i < raw.length; i++) raw[i] = (byte) (i * 31);
        byte[] header = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(header, 0, raw, 0, header.length);
        var response = rawResponse(raw);
        String expectedHash = Core.hash(raw);
        var store = new EvidencePacketStore();
        check(store.file() == null, "empty evidence creates no temporary file");
        var ref = store.append(q, response);
        check(largestRead.get() == EvidencePacketStore.BLOCK_BYTES, "large packet captured in at most 64 KiB chunks");
        check(ref.response().length() == raw.length && ref.response().sha256().equals(expectedHash), "complete binary source length and hash retained");
        check(hashResponse(ref).equals(expectedHash), "binary NUL and non-UTF8 original bytes round trip without truncation");
        check(ref.responsePreview().length() <= EvidencePacketStore.PREVIEW_BYTES, "preview decoding is bounded independently of original length");
        check(ref.responsePreview().contains("\ufffd") && hashResponse(ref).equals(expectedHash), "invalid UTF8 preview uses replacement characters without modifying original bytes");
        var item = new BountyEvidence.Item("large", "GET", q.url(), "manual", "", ref);
        check(item.diskBacked() && get(item, "requestText") == null && get(item, "responseText") == null,
                "evidence item holds disk references instead of full packet Strings");
        check(item.responseTruncated(), "bounded preview marks its truncation explicitly");
        try (var other = new EvidencePacketStore()) {
            var same = other.append(q, response);
            check(same.packetId().equals(ref.packetId()), "packet content identity is stable across evidence stores");
            var absent = other.append(q, null);
            var empty = other.append(q, rawResponse(new byte[0]));
            check(!absent.packetId().equals(empty.packetId()), "absent response and present empty response have different identities");
        }

        long before = store.diskBytes();
        HttpResponse broken = proxy(HttpResponse.class, (p, m, a) -> {
            if (m.getName().equals("toByteArray")) throw new IllegalArgumentException("simulated host failure");
            return def(m);
        });
        rejects(() -> store.append(q, broken), "failed pair is not published");
        check(store.diskBytes() == before && hashResponse(ref).equals(expectedHash), "partial pair rollback preserves existing original bytes");

        Path zip = Files.createTempFile("evidence-storage-", ".zip");
        try {
            BountyEvidence.save(zip, "title", "impact", "steps", List.of(item), Set.of(),
                    Set.of("SID=COOKIE_SECRET"), true, "{}", null);
            try (ZipFile saved = new ZipFile(zip.toFile())) {
                String request = new String(saved.getInputStream(saved.getEntry("requests/1.http")).readAllBytes(), StandardCharsets.UTF_8);
                String result = new String(saved.getInputStream(saved.getEntry("responses/1.http")).readAllBytes(), StandardCharsets.UTF_8);
                String json = new String(saved.getInputStream(saved.getEntry("evidence.json")).readAllBytes(), StandardCharsets.UTF_8);
                check(!request.contains("QUERY_SECRET") && !request.contains("COOKIE_SECRET"), "disk-backed export preserves credential redaction");
                check(result.contains("128 KiB") && result.length() < 1024, "large body export declares preview limit without copying binary content");
                check(json.contains(expectedHash) && json.contains("responseRawBytes\":" + raw.length), "archive records full original hash and byte length");
                check(json.contains("sourceTextDigestRepresentation") && json.contains("responseExportTextSha256") && json.contains("rawProvenance"), "raw-byte provenance and preview/export text digests remain distinct");
                check(json.contains(item.packetId()) && json.contains(item.evidenceId()), "archive includes stable packet identity and evidence identity");
                check(json.contains("schemaVersion") && json.contains("producerVersion"), "schema and producer versions are separate");
                check(json.contains("\"version\":\"" + BuildInfo.EVIDENCE_SCHEMA_VERSION + "\"") && json.contains("sourceTextEncoding"), "legacy schema alias and UTF8 replacement semantics are explicit");
                check(Collections.list(saved.entries()).stream().noneMatch(e -> e.getName().endsWith(".bin")), "portable evidence ZIP never exports unredacted raw packet storage");
            }
            check(BountyEvidence.fileHash(zip).equals(Core.hash(Files.readAllBytes(zip))), "streamed final ZIP digest matches direct digest");

            // Hold the exporter after it pins the store, then unload concurrently.
            CountDownLatch reading = new CountDownLatch(1), proceed = new CountDownLatch(1);
            AtomicBoolean once = new AtomicBoolean();
            var gate = new AbstractCollection<String>() {
                @Override public int size() { return 0; }
                @Override public Iterator<String> iterator() {
                    if (once.compareAndSet(false, true)) {
                        reading.countDown();
                        try { if (!proceed.await(10, TimeUnit.SECONDS)) throw new AssertionError("export gate timeout"); }
                        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
                    }
                    return Collections.emptyIterator();
                }
            };
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread exporter = new Thread(() -> {
                try { BountyEvidence.save(zip, "title", "impact", "steps", List.of(item), Set.of(), gate, false, "{}", null); }
                catch (Throwable error) { failure.set(error); }
            });
            exporter.start();
            try {
                check(reading.await(10, TimeUnit.SECONDS), "export acquired source lease before reading");
                store.close();
                check(Files.exists(store.file()), "unload defers deleting raw bytes while export holds a lease");
            } finally { proceed.countDown(); exporter.join(10000); }
            check(!exporter.isAlive() && failure.get() == null, "export survives concurrent store close with original integrity");
            check(!Files.exists(store.file()), "last export lease removes retired raw storage");
            rejects(item::request, "retired evidence cannot silently read a replacement store");
        } finally { store.close(); Files.deleteIfExists(zip); }

        MontoyaApi api = proxy(MontoyaApi.class, (p, m, a) -> def(m));
        edt(() -> {
            var auth = new AuthComparePanel(api, List::of);
            var activity = new ActivityPanel(api);
            var panel = new BountySupportPanel(api, List::of, auth, activity);
            try {
                for (int i = 0; i < 32; i++) panel.remember(q, response, "large " + i, "manual", "");
                check(panel.evidence.size() == 32 && panel.evidence.stream().allMatch(BountyEvidence.Item::diskBacked), "32 large evidence items retain only disk references");
                check(panel.evidenceStore.diskBytes() > 128L * 1024 * 1024, "complete originals beyond 128 MiB retained without a capture byte cap");
                rejects(() -> panel.remember(q, response, "extra", "manual", ""), "existing 32-item manual evidence limit remains explicit");
                Path retained = panel.evidenceStore.file();
                panel.clearEvidence();
                check(panel.evidence.isEmpty() && !Files.exists(retained), "clear removes evidence-owned originals and resets item list");
                check(panel.evidenceStore.file() == null, "cleared evidence store stays lazy");
                panel.remember(q, response, "after clear", "manual", "");
                Path second = panel.evidenceStore.file();
                panel.close();
                check(!Files.exists(second), "panel unload removes newly captured evidence originals");
            } finally { panel.close(); auth.close(); activity.close(); }
        });
        System.out.println("EvidenceStorageTest: " + checks + " checks passed");
    }
}
