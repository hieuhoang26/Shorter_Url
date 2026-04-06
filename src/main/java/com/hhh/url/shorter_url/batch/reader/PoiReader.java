package com.hhh.url.shorter_url.batch.reader;

import com.hhh.url.shorter_url.batch.mapper.PoiRowMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.util.Iterator;

/**
 * Generic, reusable Spring Batch {@link ItemStreamReader} that iterates rows in an Excel
 * workbook (sheet 0) and delegates each row to a {@link PoiRowMapper}.
 *
 * <p>Subclasses must implement {@link #getResource()} to supply the workbook bytes and
 * {@link #getRowMapper()} to supply the row-to-object mapping strategy.
 *
 * @param <T> the type produced per Excel row
 */
@Slf4j
public abstract class PoiReader<T> implements ItemStreamReader<T> {
    private static final String CTX_ROW_INDEX = "poi.reader.row.index";

    private static final int DEFAULT_MAX_EMPTY_ROWS = 5;
    private Workbook workbook;
    private Iterator<Row> rowIterator;
    private int currentRowNumber = 0;

    private int emptyRowCount = 0;

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        Resource resource = getResource();
        try {
            workbook = WorkbookFactory.create(resource.getInputStream());
        } catch (IOException e) {
            throw new ItemStreamException("Failed to open Excel workbook", e);
        }
        Sheet sheet = workbook.getSheetAt(0);
        rowIterator = sheet.rowIterator();
        // skip header row
        int headerCount = getHeaderRowCount();
        for (int i = 0; i < headerCount && rowIterator.hasNext(); i++) {
            rowIterator.next();
        }

        // restart support
        if (executionContext.containsKey(CTX_ROW_INDEX)) {

            int restartIndex = executionContext.getInt(CTX_ROW_INDEX);

            log.info("Restarting Excel read from row {}", restartIndex);

            for (int i = headerCount; i < restartIndex && rowIterator.hasNext(); i++) {
                rowIterator.next();
            }

            currentRowNumber = restartIndex;
        }


    }

    /**
     * Returns the next mapped item, skipping rows for which the mapper returns {@code null}.
     * Returns {@code null} when all rows are exhausted, signalling end-of-input to Spring Batch.
     */
    @Override
    public T read() {
        while (rowIterator != null && rowIterator.hasNext()) {
            Row row = rowIterator.next();
            currentRowNumber++;

            if (isRowEmpty(row)) {

                emptyRowCount++;

                log.debug("Skipping blank row {}", currentRowNumber);

                if (emptyRowCount >= getMaxEmptyRows()) {

                    log.info(
                            "Stopping read after {} consecutive empty rows at row {}",
                            emptyRowCount,
                            currentRowNumber
                    );

                    return null;
                }

                continue;
            }

            emptyRowCount = 0;

            try {

                T item = getRowMapper().mapRow(row, currentRowNumber);

                if (item != null) {

                    log.debug("Mapped row {}", currentRowNumber);

                    return item;
                }

            } catch (Exception ex) {

                log.error(
                        "Error mapping row {}: {}",
                        currentRowNumber,
                        ex.getMessage(),
                        ex
                );

                if (!shouldSkipOnError()) {
                    throw ex;
                }
            }
        }
        return null;
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        // no restart state needed — file is re-read from scratch on retry
        executionContext.putInt(CTX_ROW_INDEX, currentRowNumber);
    }

    @Override
    public void close() throws ItemStreamException {
        if (workbook != null) {
            try {
                workbook.close();
            } catch (IOException e) {
                throw new ItemStreamException("Failed to close Excel workbook", e);
            }
        }
    }

    /**
     * Provides the Excel file as a Spring {@link Resource}.
     * Called once during {@link #open(ExecutionContext)}.
     */
    protected abstract Resource getResource();

    /**
     * Provides the row mapper used to convert each POI {@link Row} into a {@code <T>} instance.
     */
    protected abstract PoiRowMapper<T> getRowMapper();

    protected boolean isRowEmpty(Row row) {

        if (row == null) {
            return true;
        }

        for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {

            Cell cell = row.getCell(c);

            if (cell == null) {
                continue;
            }

            if (cell.getCellType() != CellType.BLANK) {

                if (cell.getCellType() == CellType.STRING &&
                        cell.getStringCellValue().trim().isEmpty()) {

                    continue;
                }

                return false;
            }
        }

        return true;
    }

    protected int getSheetIndex() {
        return 0;
    }

    protected int getMaxEmptyRows() {
        return DEFAULT_MAX_EMPTY_ROWS;
    }

    protected boolean shouldSkipOnError() {
        return true;
    }

    protected int getHeaderRowCount() {
        return 1;
    }

}
