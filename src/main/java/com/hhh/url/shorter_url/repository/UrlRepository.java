package com.hhh.url.shorter_url.repository;

import com.hhh.url.shorter_url.model.Url;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UrlRepository extends JpaRepository<Url, Long> {
    Optional<Url> findById(long id);
    Optional<Url> findByShortCode(String shortCode);
    // Case A: same originalUrl, no custom alias
    Optional<Url> findByOriginalUrlAndCustomAliasIsNull(String originalUrl);
    // Case B: same originalUrl + same custom alias
    Optional<Url> findByOriginalUrlAndCustomAlias(String originalUrl, String customAlias);
    // Batch Case A: bulk lookup for rows with no alias
    List<Url> findByOriginalUrlInAndCustomAliasIsNull(Collection<String> originalUrls);
    // Batch Case B/C: bulk lookup by alias
    List<Url> findByCustomAliasIn(Collection<String> aliases);
}
