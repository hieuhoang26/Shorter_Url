package com.hhh.url.shorter_url.batch.reader;

import com.hhh.url.shorter_url.batch.dto.UrlRowDTO;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;

/**
 * Maps an Excel row to a {@link UrlRowDTO}.
 *
 * <p>Column layout:
 * <ul>
 *   <li>Column 0 — {@code original_url} (required)</li>
 * </ul>
 *
 * <p>Returns {@code null} for rows with a blank or missing URL, signalling the
 * parent {@link PoiReader} to skip that row.
 */
public class UrlRowMapper implements PoiRowMapper<UrlRowDTO> {

    @Override
    public UrlRowDTO mapRow(Row row, int rowNumber) {
        String originalUrl = getStringCellValue(row, 0);
        if (originalUrl == null || originalUrl.isBlank()) {
            return null;
        }
        return UrlRowDTO.builder()
                .rowNumber(rowNumber)
                .originalUrl(originalUrl.trim())
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
            default -> null;
        };
    }
}
