CRITICAL — Fix before merge

1. AWS credentials hardcoded in application.yaml (lines 33–34)                                                                                                         
   The fallback values for ${AWS_ACCESS_KEY:...} and ${AWS_SECRET_KEY:...} are real credentials committed to the repo. Rotate those keys immediately and remove the       
   hardcoded defaults — use ${AWS_ACCESS_KEY} with no fallback.

2. LocalDateTime serializes incorrectly with GenericJackson2JsonRedisSerializer (RedisConfig.java:19, UrlCacheEntry.java)                                              
   The default ObjectMapper has no JavaTimeModule, so LocalDateTime serializes as an integer array and fails to deserialize on cache-hit — breaking every redirect for    
   URLs with a non-null expiredAt. Fix in RedisConfig:                                                                                                                    
   ObjectMapper mapper = new ObjectMapper();
   mapper.registerModule(new JavaTimeModule());                                                                                                                           
   mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);                                                                                                        
   new GenericJackson2JsonRedisSerializer(mapper);

3. Expired URLs not cached as NULL sentinel (UrlServiceImpl.java:152-155)                                                                                              
   When a DB-fetched URL is expired, nothing is written to Redis — so every subsequent request re-hits the DB. Cache the NULL sentinel with a 5-minute TTL before throwing
   UrlExpiredException.

  ---                                                                                                                                                                    
MAJOR — Should fix

4 & 5. Cache eviction fires before DB transaction commits (update() line 207, delete() line 223)
redisTemplate.delete() executes immediately, before the @Transactional method commits. If the process crashes after eviction but before commit, the DB still has the   
old value, which gets re-cached on the next miss. Use @TransactionalEventListener(phase = AFTER_COMMIT) for eviction, or at minimum document the inconsistency window.

6. Missing testcontainers core artifact in pom.xml                                                                                                                     
   Only junit-jupiter is declared; testcontainers core is an implicit transitive. Add it explicitly and consider importing the Testcontainers BOM.

7. redirect() missing @Transactional(readOnly = true)                                                                                                                  
   getById() and getAll() have it; redirect() (which also queries the DB on cache miss) does not — skipping Hibernate's read-only optimizations.

8. @SpringBootTest in RedisIntegrationTest starts a full web context unnecessarily                                                                                     
   Add webEnvironment = SpringBootTest.WebEnvironment.NONE.

  ---             
MINOR — Nice to fix

┌─────┬────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│  #  │                                                     Issue                                                      │                                               
├─────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ 9   │ NULL_SENTINEL = "NULL" is a fragile stringly-typed sentinel mixed with object values in the same Redis channel │
├─────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ 10  │ UrlCacheEntry implements Serializable is unused — Jackson doesn't use Java serialization                       │                                               
├─────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤                                               
│ 11  │ DEFAULT_TTL_HOURS is misleadingly named; it's an upper bound — rename to MAX_TTL_HOURS                         │                                               
├─────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤                                               
│ 12  │ LocalDateTime.now() called twice in redirect() — capture once before expiry check and pass to computeTtl       │
├─────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤                                               
│ 13  │ flushDb() via raw connection in tearDown() is deprecated in Spring Data Redis 3.x                              │
├─────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤                                               
│ 14  │ redirect() lacks Javadoc explaining the cache-aside contract                                                   │
├─────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤                                               
│ 15  │ docker-compose.yml has no Redis health check                                                                   │
└─────┴────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
                  
---                                                                                                                                                                    
Verdict: Fix before merge. Issues 2 and 1 are the highest priority — the LocalDateTime deserialization bug will crash the redirect hot path in production on any URL
with an expiry date, and the AWS credentials need immediate rotation.                                       