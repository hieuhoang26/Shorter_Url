package com.hhh.url.shorter_url.batch.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * Represents a single parsed row from the uploaded XLSX file.
 */
@Getter
@Builder
public class UrlRowDTO {

    private final int rowNumber;
    private final String originalUrl;
}
