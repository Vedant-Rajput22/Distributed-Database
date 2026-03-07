package com.minidb.mvcc;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Encodes MVCC keys for the Speculative MVCC Consensus system.
 *
 * <h3>Key Format:</h3>
 * <pre>
 *   &lt;user_key_bytes&gt; ':' &lt;8-byte inverted timestamp&gt;
 * </pre>
 *
 * <h3>Value Format (with speculation support):</h3>
 * <pre>
 *   &lt;1-byte VersionState&gt; &lt;original value bytes&gt;
 * </pre>
 *
 * <p>The state byte is prepended to every value. This adds only 1 byte of overhead
 * per version (~0.01% for typical values) while enabling O(1) state transitions
 * during commit promotion and rollback — just overwrite byte 0.</p>
 *
 * <p>Timestamp is inverted (MAX_VALUE - ts) so newer versions sort first in
 * RocksDB's lexicographic ordering. This means a prefix scan returns the
 * newest version first without requiring a reverse iterator.</p>
 */
public final class KeyEncoding {

    private static final byte SEPARATOR = ':';
    private static final long TIMESTAMP_INVERSION = Long.MAX_VALUE;

    private KeyEncoding() {}

    /**
     * Encode a user key and timestamp into an MVCC key.
     * Timestamp is inverted (MAX_VALUE - ts) so newer versions sort first.
     */
    public static byte[] encode(String userKey, long timestamp) {
        byte[] keyBytes = userKey.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(keyBytes.length + 1 + 8);
        buffer.put(keyBytes);
        buffer.put(SEPARATOR);
        buffer.putLong(TIMESTAMP_INVERSION - timestamp); // descending order
        return buffer.array();
    }

    /**
     * Encode just the prefix for a user key (for prefix seeking).
     */
    public static byte[] encodePrefix(String userKey) {
        byte[] keyBytes = userKey.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(keyBytes.length + 1);
        buffer.put(keyBytes);
        buffer.put(SEPARATOR);
        return buffer.array();
    }

    /**
     * Encode the upper bound prefix for scanning past a user key.
     */
    public static byte[] encodePrefixUpperBound(String userKey) {
        byte[] keyBytes = userKey.getBytes(StandardCharsets.UTF_8);
        // Increment the last byte to get an exclusive upper bound
        byte[] upper = new byte[keyBytes.length + 1];
        System.arraycopy(keyBytes, 0, upper, 0, keyBytes.length);
        upper[keyBytes.length] = SEPARATOR + 1;
        return upper;
    }

    /**
     * Decode the user key from an MVCC-encoded key.
     */
    public static String decodeUserKey(byte[] mvccKey) {
        int sepIndex = findSeparator(mvccKey);
        if (sepIndex < 0) {
            return new String(mvccKey, StandardCharsets.UTF_8);
        }
        return new String(mvccKey, 0, sepIndex, StandardCharsets.UTF_8);
    }

    /**
     * Decode the timestamp from an MVCC-encoded key.
     */
    public static long decodeTimestamp(byte[] mvccKey) {
        int sepIndex = findSeparator(mvccKey);
        if (sepIndex < 0 || mvccKey.length < sepIndex + 9) {
            throw new IllegalArgumentException("Invalid MVCC key encoding");
        }
        ByteBuffer buf = ByteBuffer.wrap(mvccKey, sepIndex + 1, 8);
        long inverted = buf.getLong();
        return TIMESTAMP_INVERSION - inverted;
    }

    /**
     * Check if an MVCC key belongs to a given user key.
     */
    public static boolean belongsToKey(byte[] mvccKey, String userKey) {
        byte[] prefix = encodePrefix(userKey);
        if (mvccKey.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (mvccKey[i] != prefix[i]) return false;
        }
        return true;
    }

    // ================================================================
    // Speculative MVCC: Value encoding with state byte
    // ================================================================

    /**
     * Encode a value with its version state byte prepended.
     *
     * <p>Format: [1 byte state] [N bytes original value]</p>
     *
     * <p>This encoding adds only 1 byte of overhead per version.
     * State transitions (SPECULATIVE → COMMITTED or ROLLED_BACK) require
     * rewriting only this single byte — an O(1) operation.</p>
     */
    public static byte[] encodeValue(byte[] value, VersionState state) {
        if (value == null) {
            return new byte[]{state.getCode()};
        }
        byte[] encoded = new byte[1 + value.length];
        encoded[0] = state.getCode();
        System.arraycopy(value, 0, encoded, 1, value.length);
        return encoded;
    }

    /**
     * Decode the original value from a state-prefixed encoded value.
     * Returns the raw user value without the state byte.
     */
    public static byte[] decodeValue(byte[] encodedValue) {
        if (encodedValue == null || encodedValue.length == 0) {
            return null;
        }
        // Check if this is a state-encoded value (has valid state byte)
        byte firstByte = encodedValue[0];
        if (firstByte == VersionState.SPECULATIVE.getCode() ||
            firstByte == VersionState.COMMITTED.getCode() ||
            firstByte == VersionState.ROLLED_BACK.getCode()) {
            if (encodedValue.length == 1) return new byte[0];
            byte[] value = new byte[encodedValue.length - 1];
            System.arraycopy(encodedValue, 1, value, 0, value.length);
            return value;
        }
        // Legacy value without state byte — treat as-is (committed)
        return encodedValue;
    }

    /**
     * Extract the version state from a state-prefixed encoded value.
     * Returns COMMITTED for legacy values without a state byte.
     */
    public static VersionState decodeVersionState(byte[] encodedValue) {
        if (encodedValue == null || encodedValue.length == 0) {
            return VersionState.COMMITTED;
        }
        byte firstByte = encodedValue[0];
        if (firstByte == VersionState.SPECULATIVE.getCode() ||
            firstByte == VersionState.COMMITTED.getCode() ||
            firstByte == VersionState.ROLLED_BACK.getCode()) {
            return VersionState.fromCode(firstByte);
        }
        // Legacy value — no state byte means committed
        return VersionState.COMMITTED;
    }

    /**
     * Create a new encoded value with a different state byte.
     * Used for O(1) commit promotion and rollback.
     */
    public static byte[] changeState(byte[] encodedValue, VersionState newState) {
        if (encodedValue == null || encodedValue.length == 0) {
            return new byte[]{newState.getCode()};
        }
        byte[] result = encodedValue.clone();
        byte firstByte = result[0];
        if (firstByte == VersionState.SPECULATIVE.getCode() ||
            firstByte == VersionState.COMMITTED.getCode() ||
            firstByte == VersionState.ROLLED_BACK.getCode()) {
            result[0] = newState.getCode();
        } else {
            // Legacy value — prepend state byte
            result = new byte[1 + encodedValue.length];
            result[0] = newState.getCode();
            System.arraycopy(encodedValue, 0, result, 1, encodedValue.length);
        }
        return result;
    }

    private static int findSeparator(byte[] key) {
        // Find the last SEPARATOR (to handle user keys that might contain the separator char)
        for (int i = key.length - 9; i >= 0; i--) {
            if (key[i] == SEPARATOR) {
                return i;
            }
        }
        return -1;
    }
}
