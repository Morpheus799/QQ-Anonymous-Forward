package dev.anonforward;

/** Metadata fields only. Media elements, download keys and file identifiers are not rebuilt. */
final class MessageMetadataSanitizer {
    private MessageMetadataSanitizer() {}

    static void generalFlags(ProtoMessage flags) {
        // Bubble, ranks, pendant and the opaque VAS reserve. Keep longTextFlag/resId and routing flags.
        for (int field : new int[]{1, 10, 11, 12, 13, 15, 16, 17, 19}) flags.remove(field);
        if (flags.first(3) != null) flags.setVarint(3, AnonState.PLACEHOLDER_UIN);
    }

    static void extraInfo(ProtoMessage extra, String alias) {
        if (extra.first(1) != null) extra.setString(1, alias);
        if (extra.first(2) != null) extra.setString(2, alias);
        // Level, group mask, signature/tail style, unique title and notification text/sound.
        for (int field : new int[]{3, 5, 6, 7, 8, 11}) extra.remove(field);
        if (extra.first(9) != null) extra.setVarint(9, AnonState.PLACEHOLDER_UIN);
    }

    static byte[] localGeneralFlags(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return bytes;
        try {
            ProtoMessage flags = ProtoMessage.parse(bytes);
            generalFlags(flags);
            return flags.toByteArray();
        } catch (IllegalArgumentException unsupportedEncoding) {
            // This is a UI metadata bag. Do not return an undecodable identity/style blob.
            return new byte[0];
        }
    }
}
