package com.telescope.backend.document.service;

import com.telescope.backend.canvas.entity.Canvas;
import com.telescope.backend.canvas.service.CanvasAccessService;
import com.telescope.backend.document.dto.DocumentResponse;
import com.telescope.backend.document.dto.DocumentUploadCommand;
import com.telescope.backend.document.dto.DocumentUploadResponse;
import com.telescope.backend.document.entity.Document;
import com.telescope.backend.document.entity.DocumentVersion;
import com.telescope.backend.document.repository.DocumentPreparationResultRepository;
import com.telescope.backend.document.repository.DocumentRepository;
import com.telescope.backend.document.repository.DocumentVersionRepository;
import com.telescope.backend.global.exception.BusinessException;
import com.telescope.backend.global.exception.ErrorCode;
import com.telescope.backend.infra.python.AiCallFailedException;
import com.telescope.backend.infra.python.DocumentPreparationRequest;
import com.telescope.backend.infra.python.PythonAiClient;
import com.telescope.backend.infra.storage.FileStorage;
import com.telescope.backend.job.entity.Job;
import com.telescope.backend.job.entity.JobType;
import com.telescope.backend.job.service.JobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 사용자 문서 업로드와 문서 조회. 설계와 결정 이유는 {@code docs/backend/document.md}.
 *
 * <p>업로드 순서({@code job.md} 3.3 "다른 도메인이 작업을 시작하는 순서"를 따른다):
 * <ol>
 *   <li>입력·캔버스 편집 권한 확인</li>
 *   <li>원본을 {@link FileStorage}에 저장 (트랜잭션 밖)</li>
 *   <li>한 트랜잭션: {@code Job}(QUEUED) + {@link Document} + {@link DocumentVersion}. 실패하면 올린 파일을 지운다</li>
 *   <li>ai 자료 준비 시작 (트랜잭션 밖). 성공하면 {@code markStarted}, 실패하면 {@code markFailed}(자동 재시도 없음)</li>
 * </ol>
 * 파싱 결과는 작업이 끝날 때 {@link DocumentPreparationResultHandler}가 저장한다(#63).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(DocumentUploadProperties.class)
public class DocumentService {

    /** 업로드 문서를 화면에 보일 때의 종류 값. */
    static final String UPLOAD_KIND = "internal";
    /** ai 시작이 예상하지 못한 오류로 실패했을 때 작업에 남기는 사유. 사용자에게 보인다. */
    static final String START_FAILED_MESSAGE = "ai 서비스에 작업을 시작하지 못했습니다.";
    private static final int COMPANY_MAX = 100;
    private static final int TITLE_MAX = 255;

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final DocumentPreparationResultRepository resultRepository;
    private final CanvasAccessService canvasAccess;
    private final FileStorage fileStorage;
    private final PythonAiClient aiClient;
    private final JobService jobService;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final DocumentUploadProperties properties;

    /**
     * 캔버스에 파일 하나를 올리고 자료 준비 작업을 시작한다.
     *
     * <p>ai 시작이 실패해도 문서와 작업은 남고 작업이 {@code FAILED}가 된다. 응답은 그대로 {@code 202}이며
     * 화면은 폴링으로 실패를 본다.
     *
     * @param userId  올린 사용자
     * @param command 캔버스·회사·발행일·파일명·크기
     * @param content 파일 내용. 이 메서드가 다 읽는다
     * @throws BusinessException 입력이 틀리면 {@code C001}, 캔버스가 없으면 {@code C002}, 볼 권한이 없으면 {@code K001},
     *                           편집 권한이 없으면 {@code K002}
     */
    public DocumentUploadResponse upload(Long userId, DocumentUploadCommand command, InputStream content) {
        String title = validate(command);
        Canvas canvas = canvasAccess.loadActive(command.canvasId());
        canvasAccess.requireEdit(canvas, userId);

        String key = fileStorage.store(command.originalFilename(), content);
        LocalDate publishedDate = command.publishedDate() != null ? command.publishedDate() : LocalDate.now(clock);
        Saved saved = saveOrDeleteFile(key, () -> {
            Job job = jobService.create(JobType.DOCUMENT_PREPARATION);
            Document document = documentRepository.save(Document.upload(command.company().strip(), title));
            DocumentVersion version = versionRepository.save(DocumentVersion.upload(
                    document, job.getId(), userId, canvas.getId(), publishedDate, key));
            return new Saved(job.getId(), document.getId(), version.getId());
        });

        startPreparation(saved, key);
        return new DocumentUploadResponse(saved.jobId(), List.of(saved.documentId()));
    }

    /**
     * 문서 하나를 카드 데이터로 돌려준다. 업로드 문서는 그 캔버스를 볼 수 있는 사람(소유자·편집·보기)만 본다.
     * 자료 준비가 끝나기 전이면 요약·수치·태그가 비어 있다.
     *
     * @throws BusinessException 문서나 캔버스가 없으면 {@code C002}, 권한이 없으면 {@code K001}
     */
    @Transactional(readOnly = true)
    public DocumentResponse get(Long userId, Long documentId) {
        Document document = documentRepository.findById(documentId).orElseThrow(() -> notFound(documentId));
        DocumentVersion version = versionRepository.findFirstByDocumentIdOrderByVersionNoDesc(documentId)
                .orElseThrow(() -> notFound(documentId));
        if (version.getCanvasId() != null) {
            canvasAccess.requireView(canvasAccess.loadActive(version.getCanvasId()), userId);
        }
        Map<String, Object> result = resultRepository.findByDocumentVersionId(version.getId())
                .map(r -> DocumentJson.readMap(r.getResult()))
                .orElse(Map.of());
        return new DocumentResponse(
                document.getId(),
                version.isUploaded() ? UPLOAD_KIND : document.getDocumentType().toLowerCase(),
                document.getCompany(),
                document.getTitle(),
                version.getSource(),
                version.getPublishedDate(),
                result.get("summary") instanceof String s ? s : null,
                listOf(result.get("figures")),
                listOf(result.get("tags")),
                "",
                version.isUploaded());
    }

    /** 입력을 확인하고 제목(파일명에서 확장자를 뺀 것)을 돌려준다. */
    private String validate(DocumentUploadCommand command) {
        if (command.canvasId() == null) {
            throw invalid("canvasId가 필요합니다.");
        }
        if (!StringUtils.hasText(command.company()) || command.company().strip().length() > COMPANY_MAX) {
            throw invalid("company는 1~" + COMPANY_MAX + "자여야 합니다.");
        }
        if (command.size() <= 0) {
            throw invalid("빈 파일은 올릴 수 없습니다.");
        }
        String filename = command.originalFilename();
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        String extension = dot >= 0 ? filename.substring(dot + 1) : null;
        if (!properties.allows(extension)) {
            throw invalid("지원하지 않는 형식입니다. 올릴 수 있는 형식: " + String.join(", ", properties.allowedExtensions()));
        }
        String title = filename.substring(0, dot).strip();
        if (title.isEmpty()) {
            title = filename;
        }
        return title.length() > TITLE_MAX ? title.substring(0, TITLE_MAX) : title;
    }

    /** 문서·버전·작업을 한 트랜잭션으로 저장한다. 실패하면 이미 올린 파일을 지워 저장소에 고아 파일을 남기지 않는다. */
    private Saved saveOrDeleteFile(String key, Supplier<Saved> save) {
        try {
            return Objects.requireNonNull(tx.execute(status -> save.get()));
        } catch (RuntimeException e) {
            try {
                fileStorage.delete(key);
            } catch (RuntimeException deleteFailure) {
                log.error("업로드 저장 실패 뒤 파일을 지우지 못했다: key={}", key, deleteFailure);
            }
            throw e;
        }
    }

    /** ai에 자료 준비를 요청하고 결과에 따라 작업을 시작 또는 실패로 기록한다. */
    private void startPreparation(Saved saved, String key) {
        try {
            String externalJobId = aiClient.startDocumentPreparation(new DocumentPreparationRequest(
                    saved.documentId().toString(), saved.versionId().toString(), key)).jobId();
            jobService.markStarted(saved.jobId(), externalJobId);
        } catch (AiCallFailedException e) {
            jobService.markFailed(saved.jobId(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("자료 준비 시작 실패: jobId={}, reason={}", saved.jobId(), e.getClass().getSimpleName());
            jobService.markFailed(saved.jobId(), START_FAILED_MESSAGE);
        }
    }

    /** ai 결과의 목록 값. 목록이 아니면 빈 목록. */
    @SuppressWarnings("unchecked")
    private static List<Object> listOf(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_INPUT, message);
    }

    private static BusinessException notFound(Long documentId) {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "ID " + documentId + "번 문서를 찾을 수 없습니다.");
    }

    /** 한 트랜잭션에서 저장한 ID들. */
    private record Saved(Long jobId, Long documentId, Long versionId) {
    }
}
