package dev.starsector.preflight.cli;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Strict, bounded CSV parsing for read-only spreadsheet provenance evidence. */
final class SpreadsheetCsv {
    private static final int STANDARD_MAX_CELLS = 2_000_000;
    static final Limits STANDARD_LIMITS =
            new Limits(32 * 1024 * 1024, 250_000, 1_024, 1_048_576, STANDARD_MAX_CELLS);

    private SpreadsheetCsv() {
    }

    static Table parse(byte[] bytes) throws IOException {
        return parse(bytes, STANDARD_LIMITS);
    }

    static Table parse(byte[] bytes, Limits limits) throws IOException {
        if (bytes == null) {
            throw new IOException("CSV bytes are missing");
        }
        if (bytes.length > limits.maxBytes()) {
            throw new IOException("CSV exceeds byte limit");
        }

        int offset = hasUtf8Bom(bytes) ? 3 : 0;
        String text = decodeUtf8(bytes, offset, bytes.length - offset);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        boolean quotedCellClosed = false;
        long cells = 0;

        for (int cursor = 0; cursor < text.length(); cursor++) {
            char value = text.charAt(cursor);
            if (inQuotes) {
                if (value == '"') {
                    if (cursor + 1 < text.length() && text.charAt(cursor + 1) == '"') {
                        append(cell, '"', limits);
                        cursor++;
                    } else {
                        inQuotes = false;
                        quotedCellClosed = true;
                    }
                } else {
                    append(cell, value, limits);
                }
                continue;
            }

            if (quotedCellClosed) {
                if (value == ',') {
                    cells = finishCell(row, cell, limits, cells);
                    quotedCellClosed = false;
                    continue;
                }
                if (value == '\r' || value == '\n') {
                    cells = finishCell(row, cell, limits, cells);
                    finishRow(rows, row, limits);
                    quotedCellClosed = false;
                    if (value == '\r' && cursor + 1 < text.length() && text.charAt(cursor + 1) == '\n') {
                        cursor++;
                    }
                    continue;
                }
                throw new IOException("Unexpected character after closing CSV quote");
            }

            if (value == '"') {
                if (!cell.isEmpty()) {
                    throw new IOException("CSV quote appeared inside an unquoted cell");
                }
                inQuotes = true;
            } else if (value == ',') {
                cells = finishCell(row, cell, limits, cells);
            } else if (value == '\r' || value == '\n') {
                cells = finishCell(row, cell, limits, cells);
                finishRow(rows, row, limits);
                if (value == '\r' && cursor + 1 < text.length() && text.charAt(cursor + 1) == '\n') {
                    cursor++;
                }
            } else {
                append(cell, value, limits);
            }
        }

        if (inQuotes) {
            throw new IOException("CSV ended inside a quoted cell");
        }
        if (!row.isEmpty() || !cell.isEmpty() || quotedCellClosed) {
            finishCell(row, cell, limits, cells);
            finishRow(rows, row, limits);
        }
        return new Table(rows);
    }

    private static void append(StringBuilder cell, char value, Limits limits) throws IOException {
        if (cell.length() >= limits.maxCellChars()) {
            throw new IOException("CSV cell exceeds character limit");
        }
        cell.append(value);
    }

    private static long finishCell(
            List<String> row,
            StringBuilder cell,
            Limits limits,
            long cells) throws IOException {
        if (row.size() >= limits.maxColumns()) {
            throw new IOException("CSV row exceeds column limit");
        }
        if (cells >= limits.maxCells()) {
            throw new IOException("CSV exceeds aggregate cell limit");
        }
        row.add(cell.toString());
        cell.setLength(0);
        return cells + 1;
    }

    private static void finishRow(List<List<String>> rows, List<String> row, Limits limits) throws IOException {
        if (rows.size() >= limits.maxRows()) {
            throw new IOException("CSV exceeds row limit");
        }
        rows.add(List.copyOf(row));
        row.clear();
    }

    private static String decodeUtf8(byte[] bytes, int offset, int length) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, length))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new IOException("CSV is not valid UTF-8", error);
        }
    }

    private static boolean hasUtf8Bom(byte[] bytes) {
        return bytes.length >= 3
                && (bytes[0] & 0xff) == 0xef
                && (bytes[1] & 0xff) == 0xbb
                && (bytes[2] & 0xff) == 0xbf;
    }

    record Limits(int maxBytes, int maxRows, int maxColumns, int maxCellChars, int maxCells) {
        Limits(int maxBytes, int maxRows, int maxColumns, int maxCellChars) {
            this(maxBytes, maxRows, maxColumns, maxCellChars, STANDARD_MAX_CELLS);
        }

        Limits {
            if (maxBytes < 1 || maxRows < 1 || maxColumns < 1 || maxCellChars < 1 || maxCells < 1) {
                throw new IllegalArgumentException("CSV limits must be positive");
            }
        }
    }

    record Table(List<List<String>> rows) {
        Table {
            rows = rows.stream().map(List::copyOf).toList();
        }
    }
}
