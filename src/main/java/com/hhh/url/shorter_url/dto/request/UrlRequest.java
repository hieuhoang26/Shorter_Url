package com.hhh.url.shorter_url.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.validator.constraints.URL;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UrlRequest {

    @NotBlank(message = "Original URL is required")
    @URL(message = "Invalid URL format")
    private String originalUrl;

    @Pattern(regexp = "^[a-zA-Z0-9-]{1,100}$", message = "Alias must be alphanumeric with hyphens, max 100 chars")
    private String customAlias;

    private String description;

    private String tags;
}
