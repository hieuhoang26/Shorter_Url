You are a senior Java backend engineer.

Implement a short URL code generator using the following strategy:

* Input: a unique numeric ID (long)
* Step 1: obfuscate the ID using XOR with a secret key
* Step 2: encode the result into a Base62 string
* Output: short code string

Requirements:

    * encode: obfuscated = id ^ SECRET_KEY → Base62.encode(obfuscated)
    * decode: Base62.decode(code) ^ SECRET_KEY → original id

4. Constraints:

    * Thread-safe
    * No external libraries
    * Handle edge cases (id = 0, negative values not allowed)
    * Efficient for high throughput
Optional:

* Add padding or minimum length for short code (e.g. 6 chars)
* Make Base62 charset configurable

