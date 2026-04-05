package com.hhh.url.shorter_url.batch.reader;

import org.apache.poi.ss.usermodel.Row;

/**
 * Generic contract for mapping a single Apache POI {@link Row} to a typed object.
 *
 * @param <T> the target type produced from each Excel row
 */
public interface PoiRowMapper<T> {

    /**
     * Maps an Excel row to a domain object.
     *
     * @param row       the POI row (never null)
     * @param rowNumber 1-based row counter, not counting the header
     * @return the mapped object, or {@code null} to signal that this row should be skipped
     */
    T mapRow(Row row, int rowNumber);
}
