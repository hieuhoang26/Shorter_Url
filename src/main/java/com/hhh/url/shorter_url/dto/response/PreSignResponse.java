package com.hhh.url.shorter_url.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PreSignResponse {
    private String preSignUrl;
    private String fileName;
    private String object;

    private Long expireAt;
}
