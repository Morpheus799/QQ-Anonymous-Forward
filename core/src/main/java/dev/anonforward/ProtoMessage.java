package dev.anonforward;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

final class ProtoMessage {
    static final class Field {
        final int number;
        final int wireType;
        long varint;
        byte[] bytes;

        Field(int number, int wireType) {
            this.number = number;
            this.wireType = wireType;
        }
    }

    private final List<Field> fields = new ArrayList<>();

    static ProtoMessage parse(byte[] data) {
        ProtoMessage result = new ProtoMessage();
        Cursor cursor = new Cursor();
        while (cursor.offset < data.length) {
            long key = readVarint(data, cursor);
            int number = (int) (key >>> 3);
            int wireType = (int) (key & 7);
            if (number <= 0) throw new IllegalArgumentException("Invalid protobuf field number");
            Field field = new Field(number, wireType);
            switch (wireType) {
                case 0 -> field.varint = readVarint(data, cursor);
                case 1 -> field.bytes = readFixed(data, cursor, 8);
                case 2 -> {
                    long length = readVarint(data, cursor);
                    if (length < 0 || length > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid protobuf length");
                    field.bytes = readFixed(data, cursor, (int) length);
                }
                case 5 -> field.bytes = readFixed(data, cursor, 4);
                default -> throw new IllegalArgumentException("Unsupported protobuf wire type " + wireType);
            }
            result.fields.add(field);
        }
        return result;
    }

    List<Field> all(int number) {
        List<Field> result = new ArrayList<>();
        for (Field field : fields) if (field.number == number) result.add(field);
        return result;
    }

    List<Field> fields() {
        return new ArrayList<>(fields);
    }

    Field first(int number) {
        for (Field field : fields) if (field.number == number) return field;
        return null;
    }

    byte[] bytes(int number) {
        Field field = first(number);
        return field != null && field.wireType == 2 ? field.bytes : null;
    }

    String string(int number) {
        byte[] value = bytes(number);
        return value == null ? null : new String(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    long varint(int number, long fallback) {
        Field field = first(number);
        return field != null && field.wireType == 0 ? field.varint : fallback;
    }

    void setVarint(int number, long value) {
        Field field = first(number);
        if (field != null && field.wireType == 0) {
            field.varint = value;
            fields.removeIf(other -> other.number == number && other != field);
            return;
        }
        remove(number);
        Field replacement = new Field(number, 0);
        replacement.varint = value;
        fields.add(replacement);
    }

    void setBytes(int number, byte[] value) {
        Field field = first(number);
        if (field != null && field.wireType == 2) {
            field.bytes = value;
            fields.removeIf(other -> other.number == number && other != field);
            return;
        }
        remove(number);
        Field replacement = new Field(number, 2);
        replacement.bytes = value;
        fields.add(replacement);
    }

    void setString(int number, String value) {
        setBytes(number, value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    void replaceBytes(Field field, byte[] value) {
        if (field.wireType != 2) throw new IllegalArgumentException("Field is not length-delimited");
        field.bytes = value;
    }

    void remove(int number) {
        for (Iterator<Field> iterator = fields.iterator(); iterator.hasNext(); ) {
            if (iterator.next().number == number) iterator.remove();
        }
    }

    void removeField(Field field) { fields.remove(field); }

    byte[] toByteArray() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Field field : fields) {
            writeVarint(out, ((long) field.number << 3) | field.wireType);
            switch (field.wireType) {
                case 0 -> writeVarint(out, field.varint);
                case 1, 5 -> out.write(field.bytes, 0, field.bytes.length);
                case 2 -> {
                    writeVarint(out, field.bytes.length);
                    out.write(field.bytes, 0, field.bytes.length);
                }
                default -> throw new IllegalStateException("Unsupported wire type " + field.wireType);
            }
        }
        return out.toByteArray();
    }

    private static byte[] readFixed(byte[] data, Cursor cursor, int length) {
        if (cursor.offset + length > data.length) throw new IllegalArgumentException("Truncated protobuf field");
        byte[] value = java.util.Arrays.copyOfRange(data, cursor.offset, cursor.offset + length);
        cursor.offset += length;
        return value;
    }

    private static long readVarint(byte[] data, Cursor cursor) {
        long value = 0;
        int shift = 0;
        while (cursor.offset < data.length && shift < 64) {
            int item = data[cursor.offset++] & 0xff;
            value |= (long) (item & 0x7f) << shift;
            if ((item & 0x80) == 0) return value;
            shift += 7;
        }
        throw new IllegalArgumentException("Truncated protobuf varint");
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7fL) != 0) {
            out.write(((int) value & 0x7f) | 0x80);
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static final class Cursor {
        int offset;
    }
}
