package com.hhh.url.shorter_url.repository;

import com.hhh.url.shorter_url.model.batch.UrlFileBatches;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface UrlFileBatchRepository extends JpaRepository<UrlFileBatches, UUID> {
}
