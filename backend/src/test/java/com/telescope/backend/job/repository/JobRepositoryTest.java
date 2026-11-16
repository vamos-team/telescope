package com.telescope.backend.job.repository;

import com.telescope.backend.TestcontainersConfig;
import com.telescope.backend.global.config.JpaAuditingConfig;
import com.telescope.backend.job.entity.Job;
import com.telescope.backend.job.entity.JobStatus;
import com.telescope.backend.job.entity.JobType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V3 마이그레이션이 적용된 MySQL에서 {@code ddl-auto: validate}를 통과하는지,
 * enum(VARCHAR)과 시각이 그대로 저장·조회되는지 확인한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfig.class, JpaAuditingConfig.class})
class JobRepositoryTest {

    @Autowired
    JobRepository jobRepository;

    @Autowired
    EntityManager em;

    @Test
    void 저장한_작업을_다시_읽으면_상태와_시각이_유지된다() {
        Job job = Job.create(JobType.ANALYSIS);
        job.assignExternalJobId("ext-1");
        job.start(LocalDateTime.of(2026, 11, 16, 9, 0));
        Long id = jobRepository.saveAndFlush(job).getId();
        em.clear();

        Job found = jobRepository.findById(id).orElseThrow();

        assertThat(found.getJobType()).isEqualTo(JobType.ANALYSIS);
        assertThat(found.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(found.getExternalJobId()).isEqualTo("ext-1");
        assertThat(found.getStartedAt()).isEqualTo(LocalDateTime.of(2026, 11, 16, 9, 0));
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void 잠금_조회는_작업을_반환하고_없는_ID는_비어_있다() {
        Long id = jobRepository.saveAndFlush(Job.create(JobType.PREDICTION)).getId();

        assertThat(jobRepository.findByIdForUpdate(id)).isPresent();
        assertThat(jobRepository.findByIdForUpdate(999_999L)).isEmpty();
    }
}
