package com.telescope.research.document.entity;

import com.telescope.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 문서 버전의 자료 준비(파싱) 결과. ai가 돌려준 JSON을 그대로 둔다(형식이 확정 전, #109).
 *
 * <p>문서 버전마다 하나이고 고치지 않는다. 스키마는 {@code V11__add_document_upload.sql}.
 */
@Getter
@Entity
@Table(name = "document_preparation_result")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentPreparationResult extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long documentVersionId;

    /** ai 결과 JSON 문자열. 변환은 서비스의 JSON 도우미가 한다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String result;

    private DocumentPreparationResult(Long documentVersionId, String result) {
        this.documentVersionId = documentVersionId;
        this.result = result;
    }

    /** 문서 버전의 결과를 만든다. */
    public static DocumentPreparationResult of(Long documentVersionId, String resultJson) {
        return new DocumentPreparationResult(documentVersionId, resultJson);
    }
}
