package com.hhh.url.shorter_url.service;

import com.hhh.url.shorter_url.config.Base62Properties;
import com.hhh.url.shorter_url.util.Base62Encoder;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

/**
 * Service that generates and resolves obfuscated Base62 short codes.
 *
 * <p>Short codes are produced by XOR-obfuscating the numeric ID with a configurable
 * secret key, encoding the result with {@link Base62Encoder}, and left-padding with
 * the charset's zero character to enforce a minimum length.
 *
 * <p>Decoding reverses the process: decode → XOR → original ID.
 */
@Service
public class Base62Service {

    private final Base62Properties properties;
    private Base62Encoder encoder;

    public Base62Service(Base62Properties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        this.encoder = new Base62Encoder(properties.getCharset());
    }

    /**
     * Generates an obfuscated, padded short code for the given numeric ID.
     *
     * @param originalId the non-negative database-assigned ID.
     * @return the short code string of at least {@code minLength} characters.
     * @throws IllegalArgumentException if originalId is negative.
     */
    public String generateShortCode(long originalId) {
        if (originalId < 0) {
            throw new IllegalArgumentException("originalId must be non-negative, got: " + originalId);
        }
        long obfuscated = (originalId ^ properties.getSecretKey()) & Long.MAX_VALUE;
        String encoded = encoder.encode(obfuscated);
        char padChar = properties.getCharset().charAt(0);
        while (encoded.length() < properties.getMinLength()) {
            encoded = padChar + encoded;
        }
        return encoded;
    }

    /**
     * Resolves a short code back to its original numeric ID.
     *
     * @param shortCode the encoded short code.
     * @return the original numeric ID.
     * @throws IllegalArgumentException if the short code contains invalid characters.
     */
    public long resolveShortCode(String shortCode) {
        long obfuscatedId = encoder.decode(shortCode);
        return (obfuscatedId ^ properties.getSecretKey()) & Long.MAX_VALUE;
    }
}
