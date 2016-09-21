/*
 * Copyright 2016-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *    http://aws.amazon.com/apache2.0
 *
 * This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and
 * limitations under the License.
 */
package com.amazonaws.services.dynamodbv2.datamodeling;

import static com.amazonaws.services.dynamodbv2.model.KeyType.HASH;
import static com.amazonaws.services.dynamodbv2.model.KeyType.RANGE;

import com.amazonaws.annotation.SdkInternalApi;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapperFieldModel.DynamoDBAttributeType;
import com.amazonaws.services.dynamodbv2.model.KeyType;
import com.amazonaws.util.StringUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Map of DynamoDB annotations.
 */
@SdkInternalApi
final class StandardAnnotationMaps {

    /**
     * Gets all the DynamoDB annotations for a given class.
     */
    static final <T> TableTypedMap<T> of(Class<T> clazz) {
        final TableTypedMap<T> map = new TableTypedMap<T>(clazz);
        map.putAll(clazz);
        return map;
    }

    /**
     * Gets all the DynamoDB annotations; method annotations override field
     * level annotations which override class/type level annotations.
     */
    static final <T> FieldTypedMap<T> of(Method getter, String defaultName) {
        final Class<T> targetType = (Class<T>)getter.getReturnType();
        final String fieldName = StandardBeanProperties.fieldNameOf(getter);

        Field declaredField = null;
        try {
            declaredField = getter.getDeclaringClass().getDeclaredField(fieldName);
        } catch (final SecurityException e) {
            throw new DynamoDBMappingException("no access to field for " + getter, e);
        } catch (final NoSuchFieldException no) {}

        if (defaultName == null) {
            defaultName = fieldName;
        }

        final FieldTypedMap<T> map = new FieldTypedMap<T>(targetType, defaultName);
        map.putAll(targetType);
        map.putAll(declaredField);
        map.putAll(getter);
        return map;
    }

    /**
     * Map of annotation type to annotation instance.
     */
    private static abstract class AbstractAnnotationMap {
        private final Annotations map = new Annotations();

        final void putAll(AnnotatedElement annotated) {
            if (annotated != null) {
                this.map.putAll(new Annotations().putAll(annotated.getAnnotations()));
            }
        }

        final <A extends Annotation> A actualOf(final Class<A> annotationType) {
            return StandardAnnotationMaps.actualOf(annotationType, this.map.get(annotationType));
        }

        final <A extends Annotation> Annotation customOf(final Class<A> annotationType) {
            return this.map.get(annotationType);
        }
    }

    /**
     * Common type-conversions properties.
     */
    static abstract class TypedMap<T> extends AbstractAnnotationMap {
        private final Class<T> targetType;

        private TypedMap(final Class<T> targetType) {
            this.targetType = targetType;
        }

        public <S> DynamoDBTypeConverter<S,T> typeConverter() {
            final Annotation annotation = customOf(DynamoDBTypeConverted.class);
            if (annotation != null) {
                return DynamoDBTypeConverted.Converters.<S,T>of(targetType, annotation);
            }
            return null;
        }

        public DynamoDBAttributeType attributeType() {
            final DynamoDBTyped annotation = actualOf(DynamoDBTyped.class);
            if (annotation != null) {
                return annotation.value();
            }
            return null;
        }

        public Class<T> targetType() {
            return this.targetType;
        }
    }

    /**
     * {@link DynamoDBMapperTableModel} annotations.
     */
    static final class TableTypedMap<T> extends TypedMap<T> implements DynamoDBMapperTableModel.Properties<T> {
        private TableTypedMap(final Class<T> targetType) {
            super(targetType);
        }

        @Override
        public DynamoDBAttributeType attributeType() {
            DynamoDBAttributeType attributeType = super.attributeType();
            if (attributeType == null && actualOf(DynamoDBTable.class) != null) {
                attributeType = DynamoDBAttributeType.M;
            }
            return attributeType;
        }

        @Override
        public String tableName() {
            final DynamoDBTable annotation = actualOf(DynamoDBTable.class);
            if (annotation != null && !annotation.tableName().isEmpty()) {
                return annotation.tableName();
            }
            return null;
        }
    }

    /**
     * {@link DynamoDBMapperFieldModel} annotations.
     */
    static final class FieldTypedMap<T> extends TypedMap<T> implements DynamoDBMapperFieldModel.Properties<T> {
        private final String defaultName;

        private FieldTypedMap(Class<T> targetType, String defaultName) {
            super(targetType);
            this.defaultName = defaultName;
        }

        public boolean ignored() {
            return actualOf(DynamoDBIgnore.class) != null;
        }

        public boolean flattened() {
            return actualOf(DynamoDBFlattened.class) != null;
        }

        public Map<String,String> attributes() {
            final Map<String,String> attributes = new LinkedHashMap<String,String>();
            for (final DynamoDBAttribute a : actualOf(DynamoDBFlattened.class).attributes()) {
                if (a.mappedBy().isEmpty() || a.attributeName().isEmpty()) {
                    throw new DynamoDBMappingException("@DynamoDBFlattened must specify mappedBy and attributeName");
                } else if (attributes.put(a.mappedBy(), a.attributeName()) != null) {
                    throw new DynamoDBMappingException("@DynamoDBFlattened must not duplicate mappedBy=" + a.mappedBy());
                }
            }
            if (attributes.isEmpty()) {
                throw new DynamoDBMappingException("@DynamoDBFlattened must specify one or more attributes");
            }
            return attributes;
        }

        @Override
        public DynamoDBAttributeType attributeType() {
            final DynamoDBScalarAttribute annotation = actualOf(DynamoDBScalarAttribute.class);
            if (annotation != null) {
                if (Set.class.isAssignableFrom(targetType())) {
                    return DynamoDBAttributeType.valueOf(annotation.type().name() + "S");
                } else {
                    return DynamoDBAttributeType.valueOf(annotation.type().name());
                }
            }
            return super.attributeType();
        }

        @Override
        public String attributeName() {
            final DynamoDBHashKey hashKey = actualOf(DynamoDBHashKey.class);
            if (hashKey != null && !hashKey.attributeName().isEmpty()) {
                return hashKey.attributeName();
            }
            final DynamoDBIndexHashKey indexHashKey = actualOf(DynamoDBIndexHashKey.class);
            if (indexHashKey != null && !indexHashKey.attributeName().isEmpty()) {
                return indexHashKey.attributeName();
            }
            final DynamoDBRangeKey rangeKey = actualOf(DynamoDBRangeKey.class);
            if (rangeKey != null && !rangeKey.attributeName().isEmpty()) {
                return rangeKey.attributeName();
            }
            final DynamoDBIndexRangeKey indexRangeKey = actualOf(DynamoDBIndexRangeKey.class);
            if (indexRangeKey != null && !indexRangeKey.attributeName().isEmpty()) {
                return indexRangeKey.attributeName();
            }
            final DynamoDBAttribute attribute = actualOf(DynamoDBAttribute.class);
            if (attribute != null && !attribute.attributeName().isEmpty()) {
                return attribute.attributeName();
            }
            final DynamoDBVersionAttribute versionAttribute = actualOf(DynamoDBVersionAttribute.class);
            if (versionAttribute != null && !versionAttribute.attributeName().isEmpty()) {
                return versionAttribute.attributeName();
            }
            final DynamoDBScalarAttribute scalarAttribute = actualOf(DynamoDBScalarAttribute.class);
            if (scalarAttribute != null && !scalarAttribute.attributeName().isEmpty()) {
                return scalarAttribute.attributeName();
            }
            final DynamoDBNamed annotation = actualOf(DynamoDBNamed.class);
            if (annotation != null && !annotation.value().isEmpty()) {
                return annotation.value();
            }
            return this.defaultName;
        }

        @Override
        public KeyType keyType() {
            final DynamoDBKeyed annotation = actualOf(DynamoDBKeyed.class);
            if (annotation != null) {
                return annotation.value();
            }
            return null;
        }

        @Override
        public boolean versioned() {
            return actualOf(DynamoDBVersioned.class) != null;
        }

        @Override
        public Map<KeyType,List<String>> globalSecondaryIndexNames() {
            final Map<KeyType,List<String>> gsis = new EnumMap<KeyType,List<String>>(KeyType.class);
            final DynamoDBIndexHashKey indexHashKey = actualOf(DynamoDBIndexHashKey.class);
            if (indexHashKey != null) {
                if (!indexHashKey.globalSecondaryIndexName().isEmpty()) {
                    if (indexHashKey.globalSecondaryIndexNames().length > 0) {
                        throw new DynamoDBMappingException("@DynamoDBIndexHashKey must not specify both HASH GSI name/names");
                    }
                    gsis.put(HASH, Collections.singletonList(indexHashKey.globalSecondaryIndexName()));
                } else if (indexHashKey.globalSecondaryIndexNames().length > 0) {
                    gsis.put(HASH, Collections.unmodifiableList(Arrays.asList(indexHashKey.globalSecondaryIndexNames())));
                } else {
                    throw new DynamoDBMappingException("@DynamoDBIndexHashKey must specify one of HASH GSI name/names");
                }
            }
            final DynamoDBIndexRangeKey indexRangeKey = actualOf(DynamoDBIndexRangeKey.class);
            if (indexRangeKey != null) {
                if (!indexRangeKey.globalSecondaryIndexName().isEmpty()) {
                    if (indexRangeKey.globalSecondaryIndexNames().length > 0) {
                        throw new DynamoDBMappingException("@DynamoDBIndexRangeKey must not specify both RANGE GSI name/names");
                    }
                    gsis.put(RANGE, Collections.singletonList(indexRangeKey.globalSecondaryIndexName()));
                } else if (indexRangeKey.globalSecondaryIndexNames().length > 0) {
                    gsis.put(RANGE, Collections.unmodifiableList(Arrays.asList(indexRangeKey.globalSecondaryIndexNames())));
                } else if (localSecondaryIndexNames().isEmpty()) {
                    throw new DynamoDBMappingException("@DynamoDBIndexRangeKey must specify RANGE GSI and/or LSI name/names");
                }
            }
            if (!gsis.isEmpty()) {
                return Collections.unmodifiableMap(gsis);
            }
            return Collections.<KeyType,List<String>>emptyMap();
        }

        @Override
        public List<String> localSecondaryIndexNames() {
            final DynamoDBIndexRangeKey annotation = actualOf(DynamoDBIndexRangeKey.class);
            if (annotation != null) {
                if (!annotation.localSecondaryIndexName().isEmpty()) {
                    if (annotation.localSecondaryIndexNames().length > 0) {
                        throw new DynamoDBMappingException("@DynamoDBIndexRangeKey must not specify both LSI name/names");
                    }
                    return Collections.singletonList(annotation.localSecondaryIndexName());
                } else if (annotation.localSecondaryIndexNames().length > 0) {
                    return Collections.unmodifiableList(Arrays.asList(annotation.localSecondaryIndexNames()));
                }
            }
            return Collections.<String>emptyList();
        }

        @Override
        public DynamoDBAutoGenerator<T> autoGenerator() {
            final Annotation annotation = customOf(DynamoDBAutoGenerated.class);
            if (annotation != null) {
                return DynamoDBAutoGenerated.Generators.<T>of(targetType(), annotation);
            }
            return null;
        }
    }

    /**
     * A map of annotation type to annotation. It will map any first level
     * custom annotations to any DynamoDB annotation types that are present.
     * It will support up to two levels of compounded DynamoDB annotations.
     */
    private static final class Annotations extends LinkedHashMap<Class<? extends Annotation>,Annotation> {
        private static final long serialVersionUID = -1L;

        public boolean putIfAnnotated(Class<? extends Annotation> annotationType, Annotation annotation) {
            if (!annotationType.isAnnotationPresent(DynamoDB.class)) {
                return false;
            } else if ((annotation = put(annotationType, annotation)) == null) {
                return true;
            }
            throw new DynamoDBMappingException(
                "conflicting annotations " + annotation + " and " + get(annotationType) +
                "; allowed only one of @" + annotationType.getSimpleName()
            );
        }

        public Annotations putAll(Annotation ... annotations) {
            for (final Annotation a1 : annotations) {
                putIfAnnotated(a1.annotationType(), a1);
                for (final Annotation a2 : a1.annotationType().getAnnotations()) {
                    if (putIfAnnotated(a2.annotationType(), a1)) {
                        for (final Annotation a3 : a2.annotationType().getAnnotations()) {
                            putIfAnnotated(a3.annotationType(), a2);
                        }
                    }
                }
            }
            return this;
        }
    }

    /**
     * Returns the annotation if of the specified type or attempts to resolve
     * the specified type from the compounded annotations.
     */
    @SuppressWarnings("unchecked")
    static final <A extends Annotation> A actualOf(Class<A> annotationType, Annotation annotation) {
        if (annotation == null || annotation.annotationType() == annotationType) {
            return (A)annotation;
        } else if (annotation.annotationType().isAnnotationPresent(annotationType)) {
            return annotation.annotationType().getAnnotation(annotationType);
        }
        throw new DynamoDBMappingException(
            "could not resolve annotation by type" +
            "; @" + annotationType.getSimpleName() + " not present on " + annotation
        );
    }

}