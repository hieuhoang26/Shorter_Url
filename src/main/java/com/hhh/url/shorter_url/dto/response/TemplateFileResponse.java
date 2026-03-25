package com.hhh.url.shorter_url.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TemplateFileResponse {
    private String preSignUrl;
    private String fileName;
    private String expireAt;
    private String description;
}
