package com.hhh.url.shorter_url.repository;

import com.hhh.url.shorter_url.model.Url;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UrlRepository extends JpaRepository<Url, Long> {
    Optional<Url> findById(long id);
    Optional<Url> findByShortCode(String shortCode);
}
