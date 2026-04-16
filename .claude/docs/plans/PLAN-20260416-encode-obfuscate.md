# Plan: Short URL Code Generator with XOR Obfuscation

**Date**: 2026-04-16  
**Status**: DRAFT  
**Author**: Gemini CLI  

---

## 1. Requirement Summary
Implement a short URL code generator that obfuscates a unique numeric ID (long) using XOR with a configurable secret key before encoding it into a Base62 string. The implementation must be thread-safe, handle edge cases (negative IDs, ID=0), use no external libraries, and be efficient for high throughput. Additionally, it should support optional padding (minimum length of 6 characters) and a configurable Base62 charset.

## 2. Scope
### In Scope
- Add application properties for the obfuscation secret key, Base62 charset, and minimum short code length.
- Refactor `Base62Code` utility to accept a configurable charset and implement standard Base62 encoding/decoding logic.
- Implement XOR obfuscation and de-obfuscation logic in `Base62Service`.
- Implement padding/minimum length logic for the generated short code in `Base62Service`.
- Create unit tests for `Base62Service` and `Base62Code` to verify encoding, decoding, padding, edge cases, and thread safety.

### Out of Scope
- Changes to how the initial numeric ID is generated (e.g., Snowflake, DB sequence). This plan assumes the ID is provided and is positive.
- Changes to database schema or data models.

## 3. Technical Design

### Components to Create
| Component | Type | Location | Responsibility |
|-----------|------|----------|----------------|
| `Base62Properties` | Configuration Properties | `com.hhh.url.shorter_url.config` | Holds configurations for the shortener (secret key, charset, min length). |
| `Base62ServiceTest` | Test Class | `src/test/java/com/hhh/url/shorter_url/service` | Unit tests for encoding, decoding, and obfuscation. |
| `Base62EncoderTest` | Test Class | `src/test/java/com/hhh/url/shorter_url/util` | Unit tests for base Base62 conversion logic. |

### Components to Modify
| Component | Location | Change Description |
|-----------|----------|--------------------|
| `Base62Service` | `com.hhh.url.shorter_url.service` | Inject `Base62Properties`. Implement `generateShortCode` to perform XOR obfuscation and padding. Implement `resolveShortCode` to perform de-obfuscation and removing padding. |
| `Base62Code` | `com.hhh.url.shorter_url.util` | Refactor into an instantiable class `Base62Encoder` that takes the charset as a constructor parameter. Remove static methods. Implement thread-safe Base62 encoding/decoding. |
| `application.yaml` | `src/main/resources` | Add `app.shortener.secret-key`, `app.shortener.charset`, `app.shortener.min-length` properties. |

### Key Decisions
- **Decision**: Refactor `Base62Code` into an instantiable `Base62Encoder`.  
  **Reason**: Enables thread-safe configuration via `application.yaml` and easier testing. `Base62Service` will instantiate `Base62Encoder` using injected properties.  
  **Alternatives considered**: Keeping `Base62Code` static, which is less flexible and harder to test with different configurations.

- **Decision**: XOR operation `obfuscatedId = (id ^ secretKey) & Long.MAX_VALUE`.  
  **Reason**: Fast, reversible obfuscation. Using `& Long.MAX_VALUE` ensures the resulting long is positive, which is required for standard Base62 encoding. Since input IDs are assumed to be strictly positive database sequences, this bitmask guarantees positive numbers without losing data (as long as IDs are less than `Long.MAX_VALUE`).  
  **Alternatives considered**: Hashing (not reversible), block ciphers like AES (too slow, overkill).

- **Decision**: Padding implementation by prepending the zero-value character of the charset.  
  **Reason**: Simple and easily reversible. If the short code length is less than `minLength`, prepend the character at index 0 of the charset until the length is met. When decoding, these leading zero-value characters will simply contribute `0` to the value, acting as a no-op, meaning we don't even need explicit padding removal logic during decode.  
  **Alternatives considered**: Base62 encoding a larger number by adding a massive offset, which is more complex.

## 4. Implementation Steps

- [ ] Step 1: Create `Base62Properties` class annotated with `@ConfigurationProperties(prefix = "app.shortener")` to hold `secretKey`, `charset`, and `minLength`.
- [ ] Step 2: Update `application.yaml` with default values under `app.shortener`: a random `secret-key` (e.g., 238947239487L), standard Base62 `charset`, and `min-length: 6`.
- [ ] Step 3: Rename and refactor `Base62Code.java` to `Base62Encoder.java` (instantiable). Add a constructor that takes a `String charset`. Implement standard Base62 encoding/decoding logic.
- [ ] Step 4: Update `Base62Service` to inject `Base62Properties`. Initialize `Base62Encoder` in a `@PostConstruct` or constructor.
- [ ] Step 5: In `Base62Service.generateShortCode(long originalId)`, throw `IllegalArgumentException` if `originalId < 0`. Calculate `long obfuscated = (originalId ^ properties.getSecretKey()) & Long.MAX_VALUE;`. Encode `obfuscated`. Pad the resulting string with the charset's first character if length < `properties.getMinLength()`.
- [ ] Step 6: In `Base62Service.resolveShortCode(String shortCode)`, decode the string to `obfuscatedId` (leading pad characters are naturally ignored by Base62 decode logic). De-obfuscate using `long originalId = (obfuscatedId ^ properties.getSecretKey()) & Long.MAX_VALUE;`. Return `originalId`.
- [ ] Step 7: Fix any compilation errors in `UrlServiceImpl` and `UrlBatchItemWriter` caused by renaming `Base62Code` if they used it directly (they shouldn't, they use the service).
- [ ] Step 8: Write unit tests `Base62EncoderTest` for base encoding/decoding and edge cases.
- [ ] Step 9: Write unit tests `Base62ServiceTest` for XOR obfuscation, padding, negative ID handling, and thread safety (using an `ExecutorService` and `CountDownLatch` or similar).

## 5. Testing Strategy
- **Unit tests**: `Base62ServiceTest` and `Base62EncoderTest` will verify bidirectional conversion (`decode(encode(id)) == id`), padding correctness, and XOR obfuscation correctness.
- **Edge cases**: Test with `id = 0`, `id = Long.MAX_VALUE`, `id = 1`. Test negative IDs (should throw `IllegalArgumentException`). Test codes with invalid characters.
- **Thread safety**: Use a multi-threaded test in `Base62ServiceTest` to ensure concurrent calls to `generateShortCode` and `resolveShortCode` execute correctly.

## 6. Risks & Open Questions
- **Risk**: The XOR operation and `& Long.MAX_VALUE` mask limits the ID space to 63 bits (positive longs).
  **Mitigation**: This is acceptable since database auto-incrementing IDs or standard Snowflakes are typically positive 63-bit integers.
- **Open question**: Is the Base62 charset length strictly 62?
  **Mitigation**: The implementation of `Base62Encoder` should adapt to the length of the provided charset string (e.g., Base58 is also possible).

## 7. Estimated Complexity
[ ] Small (< 2h) / [x] Medium (2–8h) / [ ] Large (> 8h)