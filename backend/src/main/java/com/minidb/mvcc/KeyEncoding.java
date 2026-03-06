package com.minidb.mvcc;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Encodes keys as <user_key>:<timestamp> where timestamp is big-endian descending.
 * Descending order ensures prefix seek returns newest version first.
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
