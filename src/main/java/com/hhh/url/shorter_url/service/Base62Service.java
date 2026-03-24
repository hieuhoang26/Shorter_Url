package com.hhh.url.shorter_url.service;

import com.hhh.url.shorter_url.util.Base62Code;
import org.springframework.stereotype.Service;

@Service
public class Base62Service {

    public String generateShortCode(long originalId) {
        return Base62Code.encode(originalId);
    }

    public long resolveShortCode(String shortCode) {
        return Base62Code.decode(shortCode);
    }
}
