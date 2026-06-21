package com.soma.backend.domain.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BaseEntity 단위 테스트")
class BaseEntityTest {

    @MappedSuperclass
    static class TestEntity extends BaseEntity {
        private String name;

        TestEntity(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    @Test
    @DisplayName("BaseEntity는 추상 클래스여야 한다")
    void baseEntity_isAbstractClass() {
        assertTrue(Modifier.isAbstract(BaseEntity.class.getModifiers()),
            "BaseEntity must be declared abstract");
    }

    @Test
    @DisplayName("BaseEntity에 @MappedSuperclass 어노테이션이 있어야 한다")
    void baseEntity_hasMappedSuperclassAnnotation() {
        assertNotNull(BaseEntity.class.getAnnotation(MappedSuperclass.class),
            "BaseEntity must be annotated with @MappedSuperclass");
    }

    @Test
    @DisplayName("BaseEntity에 @EntityListeners(AuditingEntityListener.class) 어노테이션이 있어야 한다")
    void baseEntity_hasEntityListenersAnnotation() {
        EntityListeners annotation = BaseEntity.class.getAnnotation(EntityListeners.class);
        assertNotNull(annotation, "BaseEntity must be annotated with @EntityListeners");
        assertEquals(1, annotation.value().length,
            "EntityListeners must have exactly one listener");
        assertEquals(AuditingEntityListener.class, annotation.value()[0],
            "EntityListeners must include AuditingEntityListener");
    }

    @Test
    @DisplayName("createdAt 필드에 @CreatedDate 어노테이션이 있어야 한다")
    void createdAtField_hasCreatedDateAnnotation() throws NoSuchFieldException {
        Field createdAtField = BaseEntity.class.getDeclaredField("createdAt");
        assertNotNull(createdAtField.getAnnotation(CreatedDate.class),
            "createdAt field must be annotated with @CreatedDate");
    }

    @Test
    @DisplayName("createdAt 필드에 @Column(nullable=false, updatable=false) 설정이 있어야 한다")
    void createdAtField_hasCorrectColumnAnnotation() throws NoSuchFieldException {
        Field createdAtField = BaseEntity.class.getDeclaredField("createdAt");
        Column column = createdAtField.getAnnotation(Column.class);
        assertNotNull(column, "createdAt must have @Column annotation");
        assertEquals("created_at", column.name(),
            "createdAt column name must be 'created_at'");
        assertFalse(column.nullable(), "createdAt column must not be nullable");
        assertFalse(column.updatable(), "createdAt column must not be updatable");
    }

    @Test
    @DisplayName("updatedAt 필드에 @LastModifiedDate 어노테이션이 있어야 한다")
    void updatedAtField_hasLastModifiedDateAnnotation() throws NoSuchFieldException {
        Field updatedAtField = BaseEntity.class.getDeclaredField("updatedAt");
        assertNotNull(updatedAtField.getAnnotation(LastModifiedDate.class),
            "updatedAt field must be annotated with @LastModifiedDate");
    }

    @Test
    @DisplayName("updatedAt 필드에 @Column(name='updated_at') 설정이 있어야 한다")
    void updatedAtField_hasCorrectColumnAnnotation() throws NoSuchFieldException {
        Field updatedAtField = BaseEntity.class.getDeclaredField("updatedAt");
        Column column = updatedAtField.getAnnotation(Column.class);
        assertNotNull(column, "updatedAt must have @Column annotation");
        assertEquals("updated_at", column.name(),
            "updatedAt column name must be 'updated_at'");
    }

    @Test
    @DisplayName("createdAt 필드 타입은 LocalDateTime이어야 한다")
    void createdAtField_isLocalDateTimeType() throws NoSuchFieldException {
        Field createdAtField = BaseEntity.class.getDeclaredField("createdAt");
        assertEquals(LocalDateTime.class, createdAtField.getType(),
            "createdAt must be of type LocalDateTime");
    }

    @Test
    @DisplayName("updatedAt 필드 타입은 LocalDateTime이어야 한다")
    void updatedAtField_isLocalDateTimeType() throws NoSuchFieldException {
        Field updatedAtField = BaseEntity.class.getDeclaredField("updatedAt");
        assertEquals(LocalDateTime.class, updatedAtField.getType(),
            "updatedAt must be of type LocalDateTime");
    }

    @Test
    @DisplayName("createdAt 필드는 private이어야 한다")
    void createdAtField_isPrivate() throws NoSuchFieldException {
        Field createdAtField = BaseEntity.class.getDeclaredField("createdAt");
        assertTrue(Modifier.isPrivate(createdAtField.getModifiers()),
            "createdAt must be private");
    }

    @Test
    @DisplayName("updatedAt 필드는 private이어야 한다")
    void updatedAtField_isPrivate() throws NoSuchFieldException {
        Field updatedAtField = BaseEntity.class.getDeclaredField("updatedAt");
        assertTrue(Modifier.isPrivate(updatedAtField.getModifiers()),
            "updatedAt must be private");
    }

    @Test
    @DisplayName("Lombok @Getter로 생성된 getCreatedAt() 메서드가 존재해야 한다")
    void baseEntity_hasGetCreatedAtMethod() throws NoSuchMethodException {
        assertNotNull(BaseEntity.class.getMethod("getCreatedAt"),
            "BaseEntity must have getCreatedAt() method generated by @Getter");
    }

    @Test
    @DisplayName("Lombok @Getter로 생성된 getUpdatedAt() 메서드가 존재해야 한다")
    void baseEntity_hasGetUpdatedAtMethod() throws NoSuchMethodException {
        assertNotNull(BaseEntity.class.getMethod("getUpdatedAt"),
            "BaseEntity must have getUpdatedAt() method generated by @Getter");
    }

    @Test
    @DisplayName("getCreatedAt() 반환 타입은 LocalDateTime이어야 한다")
    void getCreatedAt_returnsLocalDateTime() throws NoSuchMethodException {
        assertEquals(LocalDateTime.class,
            BaseEntity.class.getMethod("getCreatedAt").getReturnType(),
            "getCreatedAt() must return LocalDateTime");
    }

    @Test
    @DisplayName("getUpdatedAt() 반환 타입은 LocalDateTime이어야 한다")
    void getUpdatedAt_returnsLocalDateTime() throws NoSuchMethodException {
        assertEquals(LocalDateTime.class,
            BaseEntity.class.getMethod("getUpdatedAt").getReturnType(),
            "getUpdatedAt() must return LocalDateTime");
    }

    @Test
    @DisplayName("감사 필드 설정 전에는 getCreatedAt()이 null을 반환해야 한다")
    void getCreatedAt_returnsNullBeforeAuditingIsApplied() {
        TestEntity entity = new TestEntity("test");
        assertNull(entity.getCreatedAt(),
            "createdAt must be null before auditing context sets it");
    }

    @Test
    @DisplayName("감사 필드 설정 전에는 getUpdatedAt()이 null을 반환해야 한다")
    void getUpdatedAt_returnsNullBeforeAuditingIsApplied() {
        TestEntity entity = new TestEntity("test");
        assertNull(entity.getUpdatedAt(),
            "updatedAt must be null before auditing context sets it");
    }

    @Test
    @DisplayName("createdAt 필드에 updatable=false — 컬럼 정의에서 업데이트 불가임을 보장한다")
    void createdAtColumn_isNotUpdatable() throws NoSuchFieldException {
        Field createdAtField = BaseEntity.class.getDeclaredField("createdAt");
        Column column = createdAtField.getAnnotation(Column.class);
        assertFalse(column.updatable(),
            "created_at column must have updatable=false to prevent modification after insert");
    }

    @Test
    @DisplayName("BaseEntity는 정확히 두 개의 감사 필드를 가져야 한다")
    void baseEntity_hasExactlyTwoAuditingFields() {
        Field[] declaredFields = BaseEntity.class.getDeclaredFields();
        long auditingFieldCount = java.util.Arrays.stream(declaredFields)
            .filter(f -> f.isAnnotationPresent(CreatedDate.class)
                || f.isAnnotationPresent(LastModifiedDate.class))
            .count();
        assertEquals(2L, auditingFieldCount,
            "BaseEntity must have exactly two auditing fields (createdAt and updatedAt)");
    }
}
