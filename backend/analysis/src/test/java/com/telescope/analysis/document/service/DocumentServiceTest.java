package com.telescope.analysis.document.service;

import com.telescope.common.test.TestcontainersConfig;
import com.telescope.analysis.document.dto.DocumentResponse;
import com.telescope.analysis.document.dto.DocumentUploadCommand;
import com.telescope.analysis.document.dto.DocumentUploadResponse;
import com.telescope.analysis.document.dto.UploadRejection;
import com.telescope.analysis.document.entity.DocumentVersion;
import com.telescope.analysis.document.repository.DocumentPreparationResultRepository;
import com.telescope.analysis.document.repository.DocumentRepository;
import com.telescope.analysis.document.repository.DocumentVersionRepository;
import com.telescope.common.config.JpaAuditingConfig;
import com.telescope.common.exception.BusinessException;
import com.telescope.common.exception.ErrorCode;
import com.telescope.analysis.infra.python.AiCallFailedException;
import com.telescope.analysis.infra.python.AiJobStatus;
import com.telescope.analysis.infra.python.AnalysisRequest;
import com.telescope.analysis.infra.python.DocumentPreparationRequest;
import com.telescope.analysis.infra.python.JobStartResponse;
import com.telescope.analysis.infra.python.JobStatusResponse;
import com.telescope.analysis.infra.python.PredictionRequest;
import com.telescope.analysis.infra.python.PythonAiClient;
import com.telescope.analysis.infra.storage.FileStorage;
import com.telescope.analysis.job.entity.Job;
import com.telescope.analysis.job.entity.JobStatus;
import com.telescope.analysis.job.repository.JobRepository;
import com.telescope.analysis.job.service.JobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 업로드 순서(입력·권한 → 파일 저장 → 문서·버전·작업 → ai 시작)와 실패 처리, 결과 저장 후 조회까지.
 * 실제 PostgreSQL에 커밋되는 상태로 확인한다. 파일 저장소와 ai 서비스는 가짜로 대신한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfig.class, JpaAuditingConfig.class, DocumentService.class,
        DocumentPreparationResultHandler.class, JobService.class,
        DocumentServiceTest.TestBeans.class, DocumentServiceTest.FakeCanvasAccess.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DocumentServiceTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 26);

    @Autowired DocumentService documentService;
    @Autowired JobService jobService;
    @Autowired DocumentRepository documentRepository;
    @Autowired DocumentVersionRepository versionRepository;
    @Autowired DocumentPreparationResultRepository resultRepository;
    @Autowired JobRepository jobRepository;
    @Autowired FakeCanvasAccess canvasAccess;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeStorage storage;
    @Autowired FakeAi ai;

    Long owner;
    Long editor;
    Long viewer;
    Long stranger;
    Long canvasId;

    @BeforeEach
    void setUp() {
        storage.reset();
        ai.reset();
        owner = user();
        editor = user();
        viewer = user();
        stranger = user();
        canvasId = 1L;
        canvasAccess.clear();
        canvasAccess.grant(owner, "owner");
        canvasAccess.grant(editor, "edit");
        canvasAccess.grant(viewer, "view");
    }

    @Test
    void 편집_권한자가_올리면_원본_문서_버전_작업을_만들고_자료_준비를_시작한다() {
        DocumentUploadResponse response = upload(editor, "2026 예산서.pdf", LocalDate.of(2026, 6, 30));

        assertThat(response.documentIds()).hasSize(1);
        Long documentId = response.documentIds().getFirst();
        DocumentVersion version = versionRepository.findFirstByDocumentIdOrderByVersionNoDesc(documentId).orElseThrow();
        assertThat(version.getJobId()).isEqualTo(response.jobId());
        assertThat(version.getCanvasId()).isEqualTo(canvasId);
        assertThat(version.getUploadedBy()).isEqualTo(editor);
        assertThat(version.getPublishedDate()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(version.getSource()).isEqualTo(DocumentVersion.UPLOAD_SOURCE);
        assertThat(storage.files).containsKey(version.getFileStorageKey());
        assertThat(documentRepository.findById(documentId).orElseThrow().getTitle()).isEqualTo("2026 예산서");

        DocumentPreparationRequest sent = ai.started;
        assertThat(sent.documentId()).isEqualTo(documentId.toString());
        assertThat(sent.documentVersionId()).isEqualTo(version.getId().toString());
        assertThat(sent.fileStorageKey()).isEqualTo(version.getFileStorageKey());
        Job job = jobRepository.findById(response.jobId()).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(job.getExternalJobId()).isEqualTo("ext-1");
    }

    @Test
    void 발행일이_없으면_올린_날로_둔다() {
        DocumentUploadResponse response = upload(owner, "근거.xlsx", null);

        DocumentVersion version = versionRepository
                .findFirstByDocumentIdOrderByVersionNoDesc(response.documentIds().getFirst()).orElseThrow();
        assertThat(version.getPublishedDate()).isEqualTo(TODAY);
    }

    @Test
    void 보기_권한자는_올릴_수_없고_파일도_저장되지_않는다() {
        assertThatThrownBy(() -> upload(viewer, "a.pdf", null)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CANVAS_EDIT_DENIED));
        assertThat(storage.files).isEmpty();
    }

    @Test
    void 권한_없는_사람은_K001로_거절된다() {
        assertThatThrownBy(() -> upload(stranger, "a.pdf", null)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CANVAS_ACCESS_DENIED));
    }

    @Test
    void 허용하지_않는_형식은_저장하기_전에_D001_FORMAT_MISMATCH로_거절한다() {
        assertThatThrownBy(() -> upload(editor, "script.exe", null)).isInstanceOfSatisfying(BusinessException.class,
                e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DOCUMENT_UPLOAD_REJECTED);
                    assertThat(e.getData()).isEqualTo(UploadRejection.format());
                    assertThat(e.getMessage()).contains(".exe").contains("pdf");
                });
        assertThat(storage.files).isEmpty();
    }

    @Test
    void 빈_파일과_확장자_없는_파일도_FORMAT_MISMATCH다() {
        assertThatThrownBy(() -> documentService.upload(editor,
                new DocumentUploadCommand(canvasId, "SKT", null, "a.pdf", 0), new ByteArrayInputStream(new byte[0])))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getData()).isEqualTo(UploadRejection.format());
                    assertThat(e.getMessage()).contains("빈 파일");
                });
        assertThatThrownBy(() -> upload(editor, "README", null)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getData()).isEqualTo(UploadRejection.format()));
    }

    @Test
    void 대상_회사가_아니면_저장하기_전에_D001_COMPANY_MISMATCH로_거절한다() {
        assertThatThrownBy(() -> uploadAs(editor, "삼성전자")).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DOCUMENT_UPLOAD_REJECTED);
            assertThat(e.getData()).isEqualTo(UploadRejection.company());
            assertThat(e.getMessage()).contains("SKT").contains("SKB");
        });
        assertThat(storage.files).isEmpty();
    }

    @Test
    void 회사가_비어_있으면_C001이다() {
        assertThatThrownBy(() -> uploadAs(editor, " ")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    void 고른_회사는_목록의_표기로_저장한다() {
        Long documentId = uploadAs(editor, " lgu+ ").documentIds().getFirst();

        assertThat(documentRepository.findById(documentId).orElseThrow().getCompany()).isEqualTo("LGU+");
    }

    @Test
    void SKB도_대상_회사다() {
        assertThat(uploadAs(editor, "SKB").documentIds()).hasSize(1);
    }

    @Test
    void 문서_저장이_실패하면_작업까지_되돌리고_올린_파일을_지운다() {
        long documentsBefore = documentRepository.count();
        long jobsBefore = jobRepository.count();
        // 파일을 저장한 직후 document 테이블에 새 행을 막는 제약을 걸어, 그다음 DB 저장을 실패시킨다
        storage.failNextSaveAfterStore = true;

        assertThatThrownBy(() -> upload(editor, "a.pdf", null)).isInstanceOf(RuntimeException.class);

        assertThat(storage.files).isEmpty();
        assertThat(storage.deleted).hasSize(1);
        assertThat(documentRepository.count()).isEqualTo(documentsBefore);
        assertThat(jobRepository.count()).isEqualTo(jobsBefore);
        assertThat(ai.started).isNull();
    }

    @Test
    void ai_시작이_실패하면_문서는_남고_작업은_사유와_함께_FAILED가_된다() {
        ai.startError = new AiCallFailedException("ai 서비스가 아직 지원하지 않는 작업입니다.");

        DocumentUploadResponse response = upload(editor, "a.pdf", null);

        Job job = jobRepository.findById(response.jobId()).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getErrorMessage()).isEqualTo("ai 서비스가 아직 지원하지 않는 작업입니다.");
        assertThat(documentRepository.existsById(response.documentIds().getFirst())).isTrue();
    }

    @Test
    void 예상하지_못한_오류로_ai_시작이_실패하면_내부_사유를_감춘다() {
        ai.startError = new IllegalStateException("내부 경로 /secret");

        DocumentUploadResponse response = upload(editor, "a.pdf", null);

        assertThat(jobRepository.findById(response.jobId()).orElseThrow().getErrorMessage())
                .isEqualTo(DocumentService.START_FAILED_MESSAGE);
    }

    @Test
    void 작업이_끝나면_결과가_저장되고_문서_조회에_요약_수치_태그가_담긴다() {
        DocumentUploadResponse response = upload(editor, "a.pdf", null);
        Long documentId = response.documentIds().getFirst();

        DocumentResponse before = documentService.get(viewer, documentId);
        assertThat(before.summary()).isNull();
        assertThat(before.figures()).isEmpty();

        ai.status = new JobStatusResponse("ext-1", AiJobStatus.SUCCEEDED, Map.of(
                "summary", "설비투자 요약",
                "figures", List.of(Map.of("label", "CAPEX", "value", 1.2, "unit", "조원", "period", "2026")),
                "tags", List.of("capex")), null);
        assertThat(jobService.getJob(response.jobId()).status()).isEqualTo(JobStatus.SUCCEEDED);

        DocumentResponse after = documentService.get(viewer, documentId);
        assertThat(after.kind()).isEqualTo("internal");
        assertThat(after.uploaded()).isTrue();
        assertThat(after.publisher()).isEqualTo(DocumentVersion.UPLOAD_SOURCE);
        assertThat(after.summary()).isEqualTo("설비투자 요약");
        assertThat(after.figures()).hasSize(1);
        assertThat(after.tags()).containsExactly("capex");
        Long versionId = versionRepository.findByJobId(response.jobId()).orElseThrow().getId();
        assertThat(resultRepository.findByDocumentVersionId(versionId)).isPresent();
    }

    @Test
    void 결과_형식이_예상과_달라도_조회는_빈_값으로_돌려준다() {
        DocumentUploadResponse response = upload(editor, "a.pdf", null);
        ai.status = new JobStatusResponse("ext-1", AiJobStatus.SUCCEEDED, Map.of("summary", 3, "figures", "x"), null);
        jobService.getJob(response.jobId());

        DocumentResponse doc = documentService.get(owner, response.documentIds().getFirst());

        assertThat(doc.summary()).isNull();
        assertThat(doc.figures()).isEmpty();
        assertThat(doc.tags()).isEmpty();
    }

    @Test
    void 캔버스_권한이_없으면_업로드_문서를_볼_수_없다() {
        DocumentUploadResponse response = upload(editor, "a.pdf", null);

        assertThatThrownBy(() -> documentService.get(stranger, response.documentIds().getFirst()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CANVAS_ACCESS_DENIED));
    }

    @Test
    void 없는_문서는_404용_예외가_난다() {
        assertThatThrownBy(() -> documentService.get(owner, 999_999L)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private DocumentUploadResponse upload(Long userId, String filename, LocalDate publishedDate) {
        return documentService.upload(userId,
                new DocumentUploadCommand(canvasId, "SKT", publishedDate, filename, 3), bytes("abc"));
    }

    private DocumentUploadResponse uploadAs(Long userId, String company) {
        return documentService.upload(userId,
                new DocumentUploadCommand(canvasId, company, null, "a.pdf", 3), bytes("abc"));
    }

    private static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes());
    }

    private static final java.util.concurrent.atomic.AtomicLong USER_IDS = new java.util.concurrent.atomic.AtomicLong(1000);

    // 사용자는 다른 서비스(auth) 소관이라 analysis 테스트는 행을 만들지 않고 id만 쓴다.
    private Long user() {
        return USER_IDS.incrementAndGet();
    }

    /** 문서가 쓰는 캔버스 권한 확인의 테스트 구현. 운영은 canvas 서비스를 HTTP로 부른다(HttpCanvasAccessClient). */
    static class FakeCanvasAccess implements CanvasAccessClient {
        private final java.util.Map<Long, String> roles = new java.util.HashMap<>();
        void clear() { roles.clear(); }
        void grant(Long userId, String role) { roles.put(userId, role); }
        @Override public void requireView(Long canvasId, Long userId) {
            if (!roles.containsKey(userId)) throw new BusinessException(ErrorCode.CANVAS_ACCESS_DENIED);
        }
        @Override public void requireEdit(Long canvasId, Long userId) {
            requireView(canvasId, userId);
            String r = roles.get(userId);
            if (!"owner".equals(r) && !"edit".equals(r)) throw new BusinessException(ErrorCode.CANVAS_EDIT_DENIED);
        }
    }

    @TestConfiguration
    static class TestBeans {

        @Bean
        Clock clock() {
            return Clock.fixed(TODAY.atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }

        @Bean
        FakeStorage fakeStorage(JdbcTemplate jdbc) {
            return new FakeStorage(jdbc);
        }

        @Bean
        FakeAi fakeAi() {
            return new FakeAi();
        }
    }

    /**
     * 메모리 파일 저장소. {@code failNextSaveAfterStore}이면 저장 직후 job 테이블을 잠시 쓸 수 없게 해
     * 다음 DB 저장을 실패시킨다(저장 실패 시 파일을 지우는지 확인용).
     */
    static class FakeStorage implements FileStorage {

        final Map<String, byte[]> files = new ConcurrentHashMap<>();
        final List<String> deleted = new CopyOnWriteArrayList<>();
        volatile boolean failNextSaveAfterStore;
        private final JdbcTemplate jdbc;

        FakeStorage(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        void reset() {
            files.clear();
            deleted.clear();
            failNextSaveAfterStore = false;
            jdbc.execute("ALTER TABLE app.document DROP CONSTRAINT IF EXISTS test_block_insert");
        }

        @Override
        public String store(String originalFilename, InputStream content) {
            String key = UUID.randomUUID() + ".pdf";
            try {
                files.put(key, content.readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (failNextSaveAfterStore) {
                failNextSaveAfterStore = false;
                // 다음 문서 저장이 실패하도록 항상 거짓인 제약을 건다. reset()에서 푼다
                jdbc.execute("ALTER TABLE app.document ADD CONSTRAINT test_block_insert CHECK (false) NOT VALID");
            }
            return key;
        }

        @Override
        public Resource load(String key) {
            return new ByteArrayResource(files.get(key));
        }

        @Override
        public void delete(String key) {
            files.remove(key);
            deleted.add(key);
        }
    }

    /** 시작 응답과 상태 응답을 정할 수 있는 ai 서비스 대역. */
    static class FakeAi implements PythonAiClient {

        volatile DocumentPreparationRequest started;
        volatile RuntimeException startError;
        volatile JobStatusResponse status;

        void reset() {
            started = null;
            startError = null;
            status = new JobStatusResponse("ext-1", AiJobStatus.RUNNING, null, null);
        }

        @Override
        public JobStartResponse startDocumentPreparation(DocumentPreparationRequest request) {
            started = request;
            if (startError != null) {
                throw startError;
            }
            return new JobStartResponse("ext-1");
        }

        @Override
        public JobStatusResponse getJobStatus(com.telescope.analysis.job.entity.JobType jobType, String jobId) {
            return status;
        }

        @Override
        public JobStartResponse startAnalysis(AnalysisRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JobStartResponse startPrediction(PredictionRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JobStartResponse startDataSync(com.telescope.analysis.infra.python.DataSyncRequest request) {
            throw new UnsupportedOperationException();
        }
    }
}
