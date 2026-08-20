package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpreadsheetCsvTest {
    @Test
    void parsesUtf8BomCrLfQuotedCommasEscapedQuotesAndTrailingEmptyCells() throws Exception {
        byte[] bytes = ("\ufeffid,name,note\r\n"
                        + "alpha,\"Lasher, (D)\",\"Wolf \"\"P\"\"\"\r\n"
                        + "beta,,\r\n")
                .getBytes(StandardCharsets.UTF_8);

        SpreadsheetCsv.Table table = SpreadsheetCsv.parse(bytes);

        assertEquals(List.of(
                        List.of("id", "name", "note"),
                        List.of("alpha", "Lasher, (D)", "Wolf \"P\""),
                        List.of("beta", "", "")),
                table.rows());
    }

    @Test
    void permitsNewlinesInsideQuotedCells() throws Exception {
        SpreadsheetCsv.Table table = SpreadsheetCsv.parse(
                "id,note\nalpha,\"line one\nline two\"\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("line one\nline two", table.rows().get(1).get(1));
    }

    @Test
    void rejectsMalformedQuotesAndInvalidUtf8() {
        assertThrows(IOException.class, () -> SpreadsheetCsv.parse(
                "id,name\nalpha,\"unterminated\n".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> SpreadsheetCsv.parse(new byte[] {(byte) 0xc3, 0x28}));
    }

    @Test
    void enforcesRowsColumnsCellCharactersAggregateCellsAndBytesIndependently() {
        assertThrows(IOException.class, () -> SpreadsheetCsv.parse(
                "a\nb\n".getBytes(StandardCharsets.UTF_8),
                new SpreadsheetCsv.Limits(100, 1, 10, 10, 100)));
        assertThrows(IOException.class, () -> SpreadsheetCsv.parse(
                "a,b,c\n".getBytes(StandardCharsets.UTF_8),
                new SpreadsheetCsv.Limits(100, 10, 2, 10, 100)));
        assertThrows(IOException.class, () -> SpreadsheetCsv.parse(
                "abcd\n".getBytes(StandardCharsets.UTF_8),
                new SpreadsheetCsv.Limits(100, 10, 10, 3, 100)));
        assertThrows(IOException.class, () -> SpreadsheetCsv.parse(
                ",,,,,\n".getBytes(StandardCharsets.UTF_8),
                new SpreadsheetCsv.Limits(100, 10, 10, 10, 5)));
        assertThrows(IOException.class, () -> SpreadsheetCsv.parse(
                "abcd\n".getBytes(StandardCharsets.UTF_8),
                new SpreadsheetCsv.Limits(3, 10, 10, 10, 100)));
    }
}
