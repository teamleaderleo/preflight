package dev.starsector.preflight.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Indexed single-file persistence for a profile's existing SPFT blobs. */
public final class PreparedTexturePackIO {
    public static final int FORMAT_VERSION = 3;
    private static final byte[] MAGIC = {'S', 'P', 'F', 'P'};
    private static final int CHECKSUM_BYTES = 32;
    private static final int FIXED_HEADER_BYTES = MAGIC.length + Integer.BYTES * 2
            + Long.BYTES + CHECKSUM_BYTES;
    private static final int MAX_INDEX_BYTES = 256 * 1024 * 1024;
    private static final int MAX_ENTRIES = 100_000;
    private static final int MAX_STRING_BYTES = 16 * 1024 * 1024;

    private PreparedTexturePackIO() {
    }

    /**
     * Packs are rebuildable acceleration data, so format changes replace the incompatible pack in
     * the established namespace instead of retaining a second multi-gigabyte copy. The SPFP header
     * version makes old/new bytes unambiguous; preparation rebuilds an incompatible pack before it
     * is used, and an already-open old pack keeps its own file handle through atomic replacement.
     */
    public static Path directory(Path cacheRoot) {
        return cacheRoot.resolve("packs");
    }

    public static Path path(Path cacheRoot, String profileFingerprint) {
        validateProfile(profileFingerprint);
        String fileStem = profileFingerprint.matches("[0-9a-fA-F]{64}")
                ? profileFingerprint.toLowerCase(java.util.Locale.ROOT)
                : Hashes.sha256(profileFingerprint.getBytes(StandardCharsets.UTF_8));
        return directory(cacheRoot).resolve(fileStem + ".spfp")
                .toAbsolutePath().normalize();
    }

    /** Exact final pack size for a prospective ordered set of blob paths and file lengths. */
    public static long estimatedFileBytes(
            String profileFingerprint, Map<String, Long> blobFileBytes) {
        validateProfile(profileFingerprint);
        if (blobFileBytes == null || blobFileBytes.isEmpty() || blobFileBytes.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Prepared texture pack entries are invalid");
        }
        long indexBytes = Integer.BYTES
                + profileFingerprint.getBytes(StandardCharsets.UTF_8).length
                + Integer.BYTES;
        long payloadBytes = 0;
        Set<String> normalized = new LinkedHashSet<>();
        for (Map.Entry<String, Long> entry : blobFileBytes.entrySet()) {
            String path = ResourceIndex.normalizeRelativePath(entry.getKey());
            if (!normalized.add(path)) {
                throw new IllegalArgumentException("Duplicate prepared texture pack path: " + path);
            }
            long length = entry.getValue() == null ? -1 : entry.getValue();
            if (length <= 0 || length > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Prepared texture blob length is invalid: " + path);
            }
            indexBytes = Math.addExact(indexBytes,
                    Integer.BYTES + path.getBytes(StandardCharsets.UTF_8).length
                            + Long.BYTES + Integer.BYTES + Integer.BYTES);
            payloadBytes = Math.addExact(payloadBytes, length);
        }
        if (indexBytes > MAX_INDEX_BYTES) {
            throw new IllegalArgumentException("Prepared texture pack index exceeds its safety limit");
        }
        return Math.addExact(FIXED_HEADER_BYTES, Math.addExact(indexBytes, payloadBytes));
    }

    public static PreparedTexturePack open(
            Path source, String expectedProfile, Collection<String> expectedBlobPaths)
            throws IOException {
        validateProfile(expectedProfile);
        Set<String> expected = normalizePaths(expectedBlobPaths);
        FileChannel channel = FileChannel.open(source, StandardOpenOption.READ);
        boolean success = false;
        try {
            long fileSize = channel.size();
            if (fileSize < FIXED_HEADER_BYTES) {
                throw new IOException("Prepared texture pack is too small: " + source);
            }
            ByteBuffer header = ByteBuffer.allocate(FIXED_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
            readFully(channel, 0, header, "Prepared texture pack ended inside its header");
            header.flip();
            byte[] magic = new byte[MAGIC.length];
            header.get(magic);
            if (!java.util.Arrays.equals(MAGIC, magic)) {
                throw new IOException("Prepared texture pack magic header is invalid");
            }
            int version = header.getInt();
            if (version != FORMAT_VERSION) {
                throw new IOException("Unsupported prepared texture pack version: " + version);
            }
            int indexLength = header.getInt();
            long payloadLength = header.getLong();
            byte[] expectedChecksum = new byte[CHECKSUM_BYTES];
            header.get(expectedChecksum);
            if (indexLength <= 0 || indexLength > MAX_INDEX_BYTES || payloadLength < 0
                    || FIXED_HEADER_BYTES + (long) indexLength > Long.MAX_VALUE - payloadLength
                    || FIXED_HEADER_BYTES + (long) indexLength + payloadLength != fileSize) {
                throw new IOException("Prepared texture pack lengths are invalid");
            }
            ByteBuffer indexBuffer = ByteBuffer.allocate(indexLength);
            readFully(channel, FIXED_HEADER_BYTES, indexBuffer,
                    "Prepared texture pack ended inside its index");
            byte[] indexBytes = indexBuffer.array();
            if (!MessageDigest.isEqual(expectedChecksum, Hashes.sha256Bytes(indexBytes))) {
                throw new IOException("Prepared texture pack index checksum mismatch");
            }
            Decoded decoded = decodeIndex(indexBytes, payloadLength);
            if (!expectedProfile.equals(decoded.profileFingerprint())) {
                throw new IOException("Prepared texture pack profile does not match its manifest");
            }
            if (!expected.equals(decoded.entries().keySet())) {
                throw new IOException("Prepared texture pack entries do not match its manifest");
            }
            PreparedTexturePack pack = new PreparedTexturePack(
                    source.toAbsolutePath().normalize(),
                    decoded.profileFingerprint(),
                    channel,
                    fileSize,
                    FIXED_HEADER_BYTES + (long) indexLength,
                    decoded.entries());
            success = true;
            return pack;
        } finally {
            if (!success) {
                channel.close();
            }
        }
    }

    public static void write(
            Path target,
            String profileFingerprint,
            Path cacheRoot,
            Collection<String> blobRelativePaths) throws IOException {
        validateProfile(profileFingerprint);
        Path realRoot = PathContainment.realDirectory(cacheRoot);
        Collection<String> normalized = normalizeOrderedPaths(blobRelativePaths);
        if (normalized.isEmpty()) {
            throw new IOException("Prepared texture pack cannot be empty");
        }
        List<Source> sources = new ArrayList<>(normalized.size());
        long payloadLength = 0;
        for (String relative : normalized) {
            Path blob = PathContainment.existingInsideRealRoot(realRoot, realRoot.resolve(relative));
            if (!Files.isRegularFile(blob)) {
                throw new IOException("Prepared texture blob is not a regular file: " + relative);
            }
            long length = Files.size(blob);
            if (length <= 0 || length > Integer.MAX_VALUE) {
                throw new IOException("Prepared texture blob length is invalid: " + relative);
            }
            sources.add(new Source(relative, blob, payloadLength, Math.toIntExact(length), 0));
            payloadLength = Math.addExact(payloadLength, length);
        }
        byte[] placeholderIndex = encodeIndex(profileFingerprint, sources);
        if (placeholderIndex.length > MAX_INDEX_BYTES) {
            throw new IOException("Prepared texture pack index exceeds its safety limit");
        }

        Path absolute = target.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        Path temporary = absolute.resolveSibling(
                absolute.getFileName() + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
        boolean moved = false;
        try {
            try (FileChannel output = FileChannel.open(temporary,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                // Reserve the fixed-size header/index region. CRC32C values are learned from the
                // exact SPFT bytes while those bytes are copied and cryptographically verified.
                output.position(FIXED_HEADER_BYTES + (long) placeholderIndex.length);
                ByteBuffer copyBuffer = ByteBuffer.allocate(PreparedTexturePackIntegrity.COPY_BUFFER_BYTES);
                List<Source> verified = new ArrayList<>(sources.size());
                for (Source source : sources) {
                    try (FileChannel input = FileChannel.open(source.path(), StandardOpenOption.READ)) {
                        int crc32c = PreparedTexturePackIntegrity.copyVerifiedSpft(
                                input,
                                source.length(),
                                output,
                                copyBuffer,
                                source.relativePath());
                        verified.add(new Source(
                                source.relativePath(),
                                source.path(),
                                source.offset(),
                                source.length(),
                                crc32c));
                    }
                }

                byte[] index = encodeIndex(profileFingerprint, verified);
                if (index.length != placeholderIndex.length) {
                    throw new IOException("Prepared texture pack index size changed during publication");
                }
                output.position(0);
                ByteBuffer header = ByteBuffer.allocate(FIXED_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
                header.put(MAGIC);
                header.putInt(FORMAT_VERSION);
                header.putInt(index.length);
                header.putLong(payloadLength);
                header.put(Hashes.sha256Bytes(index));
                header.flip();
                writeFully(output, header);
                writeFully(output, ByteBuffer.wrap(index));
                output.force(true);
            }
            AtomicPublish.replace(temporary, absolute);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static byte[] encodeIndex(String profile, List<Source> sources) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            writeString(output, profile);
            output.writeInt(sources.size());
            for (Source source : sources) {
                writeString(output, source.relativePath());
                output.writeLong(source.offset());
                output.writeInt(source.length());
                output.writeInt(source.crc32c());
            }
        }
        return bytes.toByteArray();
    }

    private static Decoded decodeIndex(byte[] bytes, long payloadLength) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            String profile = readString(input);
            validateProfile(profile);
            int count = input.readInt();
            if (count <= 0 || count > MAX_ENTRIES) {
                throw new IOException("Prepared texture pack entry count is invalid");
            }
            Map<String, PreparedTexturePack.Range> entries = new LinkedHashMap<>();
            long expectedOffset = 0;
            for (int index = 0; index < count; index++) {
                String path = readCanonicalRelativePath(input);
                long offset = input.readLong();
                int length = input.readInt();
                int crc32c = input.readInt();
                if (offset != expectedOffset || length <= 0
                        || offset > Long.MAX_VALUE - length || offset + length > payloadLength) {
                    throw new IOException("Prepared texture pack range is invalid for " + path);
                }
                if (entries.put(path, new PreparedTexturePack.Range(offset, length, crc32c)) != null) {
                    throw new IOException("Duplicate prepared texture pack path: " + path);
                }
                expectedOffset += length;
            }
            if (expectedOffset != payloadLength || input.available() != 0) {
                throw new IOException("Prepared texture pack index does not cover its payload exactly");
            }
            return new Decoded(profile,
                    java.util.Collections.unmodifiableMap(new LinkedHashMap<>(entries)));
        } catch (IllegalArgumentException | ArithmeticException error) {
            throw new IOException("Prepared texture pack index is invalid: " + error.getMessage(), error);
        }
    }

    private static Set<String> normalizePaths(Collection<String> paths) {
        if (paths == null || paths.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Prepared texture pack path collection is invalid");
        }
        TreeSet<String> normalized = new TreeSet<>();
        for (String path : paths) {
            normalized.add(ResourceIndex.normalizeRelativePath(path));
        }
        return java.util.Collections.unmodifiableSet(normalized);
    }

    private static Collection<String> normalizeOrderedPaths(Collection<String> paths) {
        if (paths == null || paths.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Prepared texture pack path collection is invalid");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String path : paths) {
            normalized.add(ResourceIndex.normalizeRelativePath(path));
        }
        return java.util.Collections.unmodifiableCollection(normalized);
    }

    private static void validateProfile(String profile) {
        if (profile == null || profile.isEmpty()
                || !StandardCharsets.UTF_8.newEncoder().canEncode(profile)
                || profile.getBytes(StandardCharsets.UTF_8).length > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("Prepared texture pack profile identity is invalid");
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes;
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
        } catch (CharacterCodingException error) {
            throw new IOException("Prepared texture pack string cannot be encoded as UTF-8", error);
        }
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("Prepared texture pack string exceeds its safety limit");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readCanonicalRelativePath(DataInputStream input) throws IOException {
        String value = readString(input);
        String canonical = ResourceIndex.normalizeRelativePath(value);
        if (!value.equals(canonical)) {
            throw new IOException("Prepared texture pack path is not canonical: " + value);
        }
        return value;
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("Prepared texture pack string length is invalid");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Prepared texture pack ended inside a string");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new IOException("Prepared texture pack string is not valid UTF-8", error);
        }
    }

    private static void readFully(
            FileChannel channel, long offset, ByteBuffer target, String eofMessage) throws IOException {
        long position = offset;
        while (target.hasRemaining()) {
            int read = channel.read(target, position);
            if (read < 0) throw new EOFException(eofMessage);
            if (read == 0) throw new IOException("Prepared texture pack read made no progress");
            position += read;
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer source) throws IOException {
        while (source.hasRemaining()) {
            if (channel.write(source) <= 0) {
                throw new IOException("Prepared texture pack write made no progress");
            }
        }
    }

    private record Source(String relativePath, Path path, long offset, int length, int crc32c) {
    }

    private record Decoded(
            String profileFingerprint, Map<String, PreparedTexturePack.Range> entries) {
    }
}
