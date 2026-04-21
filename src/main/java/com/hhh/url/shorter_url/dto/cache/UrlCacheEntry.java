package com.hhh.url.shorter_url.dto.cache;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Minimal Redis cache value for the redirect hot path.
 * WARNING: Do not rename or relocate this class without flushing Redis (FLUSHDB),
 * as GenericJackson2JsonRedisSerializer embeds the fully-qualified class name in stored JSON.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UrlCacheEntry implements Serializable {

    private String originalUrl;
    /** null means no expiry */
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    private LocalDateTime expiredAt;
}
