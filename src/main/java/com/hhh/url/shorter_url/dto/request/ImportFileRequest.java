package com.hhh.url.shorter_url.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Data;
import org.hibernate.validator.constraints.URL;

import java.util.List;

@Data
@Builder
public class ImportFileRequest {
    private String objectUrl;
    private String fileName;
}
