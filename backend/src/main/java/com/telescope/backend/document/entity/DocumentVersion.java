package com.telescope.backend.document.entity;

import com.telescope.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 문서의 버전. 실제 파일(저장소 키)과 발행 정보를 가진다.
 *
 * <p>불변이다. 모든 값이 만들 때 정해지고 이후 바뀌지 않는다(루트 {@code AGENTS.md} 4번).
 * 자료 준비 상태는 여기 두지 않고 {@code jobId}의 {@code Job.status}에서 읽는다. 파싱 결과는
 * {@link DocumentPreparationResult}에 따로 쌓는다. 스키마는 {@code V4}, {@code V11}.
 *
 * <p>{@code jobId}, {@code uploadedBy}, {@code canvasId}는 다른 도메인(job, user, canvas)의 ID라 연관관계 대신 숫자로 둔다.
 */
@Getter
@Entity
@Table(name = "document_version")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentVersion extends BaseTimeEntity {

    /** 사용자 업로드 문서의 출처 값. */
    public static final String UPLOAD_SOURCE = "사내 업로드";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(nullable = false)
    private Long jobId;

    /** 올린 사용자. 수집된 공용 문서는 비어 있다. */
    private Long uploadedBy;

    /** 업로드 문서가 속한 캔버스. 공용 문서는 비어 있다. */
    private Long canvasId;

    @Column(nullable = false)
    private int versionNo;

    @Column(nullable = false)
    private LocalDate publishedDate;

    @Column(nullable = false)
    private String source;

    @Column(nullable = false, length = 500)
    private String fileStorageKey;

    private DocumentVersion(Document document, Long jobId, Long uploadedBy, Long canvasId, int versionNo,
                            LocalDate publishedDate, String source, String fileStorageKey) {
        this.document = document;
        this.jobId = jobId;
        this.uploadedBy = uploadedBy;
        this.canvasId = canvasId;
        this.versionNo = versionNo;
        this.publishedDate = publishedDate;
        this.source = source;
        this.fileStorageKey = fileStorageKey;
    }

    /** 캔버스에서 올린 문서의 첫 버전을 만든다. 출처는 {@link #UPLOAD_SOURCE}. */
    public static DocumentVersion upload(Document document, Long jobId, Long uploadedBy, Long canvasId,
                                         LocalDate publishedDate, String fileStorageKey) {
        return new DocumentVersion(document, jobId, uploadedBy, canvasId, 1, publishedDate, UPLOAD_SOURCE, fileStorageKey);
    }

    /** 사용자가 올린 문서인지. */
    public boolean isUploaded() {
        return uploadedBy != null;
    }
}
