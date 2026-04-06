package com.hhh.url.shorter_url.batch.mapper;

import com.hhh.url.shorter_url.batch.dto.UrlRowDTO;
import com.hhh.url.shorter_url.batch.reader.PoiReader;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Maps an Excel row to a {@link UrlRowDTO}.
 *
 * <p>Column layout:
 * <ul>
 *   <li>Column 0 — {@code original_url} (required; row skipped if blank)</li>
 *   <li>Column 1 — {@code custom_alias} (optional)</li>
 *   <li>Column 2 — {@code expired_at} (optional; accepts {@code yyyy-MM-dd HH:mm:ss} or ISO-8601)</li>
 *   <li>Column 3 — {@code description} (optional)</li>
 *   <li>Column 4 — {@code tags} (optional; comma-separated)</li>
 * </ul>
 *
 * <p>Returns {@code null} for rows with a blank or missing URL, signalling the
 * parent {@link PoiReader} to skip that row.
 */
public class UrlRowMapper implements PoiRowMapper<UrlRowDTO> {

    private static final DateTimeFormatter DATETIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public UrlRowDTO mapRow(Row row, int rowNumber) {
        String originalUrl = getStringCellValue(row, 0);
        if (originalUrl == null || originalUrl.isBlank()) {
            return null;
        }
        return UrlRowDTO.builder()
                .rowNumber(rowNumber)
                .originalUrl(originalUrl.trim())
                .customAlias(trimOrNull(getStringCellValue(row, 1)))
                .expiredAt(parseDateTime(getStringCellValue(row, 2)))
                .description(trimOrNull(getStringCellValue(row, 3)))
                .tags(trimOrNull(getStringCellValue(row, 4)))
                .build();
    }

    private String getStringCellValue(Row row, int colIndex) {
        Cell cell = row.getCell(colIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) {
            return null;
        }
        CellType cellType = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType()
                : cell.getCellType();

        return switch (cellType) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
    }

    /**
     * Parses a date-time string accepting {@code yyyy-MM-dd HH:mm:ss} and ISO-8601 formats.
     * Returns {@code null} for blank or unparseable input (writer falls back to now + 5 days).
     */
    private LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim(), DATETIME_FORMATTER);
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(value.trim());
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    private String trimOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
