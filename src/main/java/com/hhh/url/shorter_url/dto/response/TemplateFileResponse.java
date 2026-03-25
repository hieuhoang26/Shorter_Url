package com.hhh.url.shorter_url.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
public class TemplateFileResponse {
    private String preSignUrl;
    private String fileName;
    private Long expireAt;
    private String description;
}
