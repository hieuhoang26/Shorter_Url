package com.hhh.url.shorter_url.batch.reader;

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
public abstract class PoiReader<T> implements ItemStreamReader<T> {

    private Workbook workbook;
    private Iterator<Row> rowIterator;
    private int currentRowNumber = 0;

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
        if (rowIterator.hasNext()) {
            rowIterator.next();
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
            T result = getRowMapper().mapRow(row, currentRowNumber);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        // no restart state needed — file is re-read from scratch on retry
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
}
