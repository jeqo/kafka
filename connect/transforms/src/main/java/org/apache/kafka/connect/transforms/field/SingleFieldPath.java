/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.connect.transforms.field;

import org.apache.kafka.common.cache.Cache;
import org.apache.kafka.common.cache.LRUCache;
import org.apache.kafka.common.cache.SynchronizedCache;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Schema.Type;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.transforms.util.SchemaUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A FieldPath is composed by one or many field names, known as steps,
 * to access values within a data object (either {@code Struct} or {@code Map<String, Object>}).a
 * <p>
 * If the SMT requires accessing multiple fields on the same data object,
 * use {@code FieldPaths} instead.
 * <p>
 * The field path semantics are defined by the {@link FieldSyntaxVersion syntax version}.
 * <p>
 * Paths are calculated once and cached for further access.
 * <p>
 * Invariants:
 * <ul>
 *     <li>A field path can contain one or more steps</li>
 * </ul>
 *
 * See KIP-821.
 *
 * @see FieldSyntaxVersion
 * @see MultiFieldPaths
 */
public class SingleFieldPath implements FieldPath {

    private static final char BACKTICK = '`';
    private static final char DOT = '.';
    private static final char BACKSLASH = '\\';

    private static final Cache<String, SingleFieldPath> PATHS_CACHE = new SynchronizedCache<>(new LRUCache<>(16));

    private final String[] path;

    static SingleFieldPath ofV1(String field) {
        return of(field, FieldSyntaxVersion.V1);
    }

    static SingleFieldPath ofV2(String field) {
        return of(field, FieldSyntaxVersion.V2);
    }

    /**
     * If version is V2, then paths are cached for further access.
     *
     * @param field   field path expression
     * @param version field syntax version
     */
    public static SingleFieldPath of(String field, FieldSyntaxVersion version) {
        if ((field == null || field.isEmpty()) // empty path
                || version.equals(FieldSyntaxVersion.V1)) { // or V1
            return new SingleFieldPath(field, version);
        } else { // use cache when V2
            final SingleFieldPath found = PATHS_CACHE.get(field);
            if (found != null) {
                return found;
            } else {
                final SingleFieldPath fieldPath = new SingleFieldPath(field, version);
                PATHS_CACHE.put(field, fieldPath);
                return fieldPath;
            }
        }
    }

    SingleFieldPath(String pathText, FieldSyntaxVersion version) {
        if (pathText == null || pathText.isEmpty()) { // empty path
            this.path = new String[] {};
        } else {
            switch (version) {
                case V1: // backward compatibility
                    this.path = new String[] {pathText};
                    break;
                case V2:
                    path = buildFieldPathV2(pathText);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown syntax version: " + version);
            }
        }
    }

    private String[] buildFieldPathV2(String pathText) {
        // if no dots or wrapping backticks are used, then return path with single step
        if (!pathText.contains(String.valueOf(DOT_CHAR))) {
            return new String[] {pathText};
        } else {
            // prepare for tracking path steps
            final List<String> steps = new ArrayList<>();
            // avoid creating new string on changes
            final StringBuilder s = new StringBuilder(pathText);

            while (s.length() > 0) { // until path is traversed
                // start processing backtick pair, if any
                if (s.charAt(0) == BACKTICK_CHAR) {
                    s.deleteCharAt(0);

                    // find backtick closing pair
                    int idx = 0;
                    while (idx >= 0) {
                        idx = s.indexOf(String.valueOf(BACKTICK_CHAR), idx);
                        if (idx == -1) { // if not found, fail
                            throw new IllegalArgumentException("Incomplete backtick pair at [...]`" + s);
                        }
                        // check that it is not escaped or wrapped in another backticks pair
                        if (idx < s.length() - 1 // not wrapping the whole field path
                                && (s.charAt(idx + 1) != DOT_CHAR // not wrapping
                                || s.charAt(idx - 1) == BACKSLASH_CHAR)) { // ... or escaped
                            idx++; // move index forward and keep searching
                        } else { // it's the closing pair
                            steps.add(escapeBackticks(s.substring(0, idx)));
                            s.delete(0, idx + 2); // rm backtick and dot
                            break;
                        }
                    }
                } else { // process dots in path
                    final int atDot = s.indexOf(String.valueOf(DOT_CHAR));
                    if (atDot > 0) { // get path step and move forward
                        steps.add(escapeBackticks(s.substring(0, atDot)));
                        s.delete(0, atDot + 1);
                    } else { // add all
                        steps.add(escapeBackticks(s.toString()));
                        s.delete(0, s.length());
                    }
                }
            }

            return steps.toArray(new String[0]);
        }
    }

    /**
     * Return field name with escaped backticks, if any.
     *
     * @param field potentially containing backticks
     * @throws IllegalArgumentException when there are incomplete backtick pairs
     */
    private String escapeBackticks(String field) {
        final StringBuilder s = new StringBuilder(field);
        int idx = 0;
        while (idx >= 0) {
            idx = s.indexOf(String.valueOf(BACKTICK_CHAR), idx + 1);
            if (idx >= 1 && s.length() > 2) {
                if (s.charAt(idx - 1) == DOT_CHAR
                        || (idx < s.length() - 1 && s.charAt(idx + 1) == DOT_CHAR
                        && s.charAt(idx - 1) != BACKSLASH_CHAR)) {
                    throw new IllegalArgumentException("Incomplete backtick pair at [...]" + field);
                }
                if (s.charAt(idx - 1) == BACKSLASH_CHAR) { // escape backtick
                    if ((idx == 1 && s.charAt(0) == BACKSLASH_CHAR) // at the beginning: \`foo[...]
                            || idx == s.length() - 1) { // at the end: [...]baz\`
                        s.deleteCharAt(idx - 1);
                    } else if ((idx > 2 && s.charAt(idx - 2) == DOT_CHAR) // after a dot: [...].\`bar[...]
                            || (idx < s.length() - 1 && s.charAt(idx + 1) == DOT_CHAR)) { // before a dot: [...]bar\`.[...]
                        s.deleteCharAt(idx - 1);
                    }
                }
            }
        }
        return s.toString();
    }

    /**
     * Access a {@code Field} at the current path within a schema {@code Schema}
     * If field is not found, then {@code null} is returned.
     */
    public Field fieldFrom(Schema schema) {
        if (path.length == 1) {
            return schema.field(path[0]);
        } else {
            Schema current = schema;
            for (int i = 0; i < path.length; i++) {
                if (current == null) {
                    return null;
                }
                if (i == path.length - 1) { // get value
                    return current.field(path[i]);
                } else { // iterate
                    current = current.field(path[i]).schema();
                }
            }
        }
        return null;
    }

    /**
     * Access a value at the current path within a schema-based {@code Struct}
     * If object is not found, then {@code null} is returned.
     */
    public Object valueFrom(Struct struct) {
        if (path.length == 1) {
            return struct.get(path[0]);
        } else {
            Struct current = struct;
            for (int i = 0; i < path.length; i++) {
                if (current == null) {
                    return null;
                }
                if (i == path.length - 1) { // get value
                    return current.get(path[i]);
                } else { // iterate
                    current = current.getStruct(path[i]);
                }
            }
        }
        return null;
    }

    /**
     * Access a value at the current path within a schemaless {@code Map<String, Object>}.
     * If object is not found, then {@code null} is returned.
     */
    @SuppressWarnings("unchecked")
    public Object valueFrom(Map<String, Object> map) {
        if (path.length == 1) {
            return map.get(path[0]);
        } else {
            Map<String, Object> current = map;
            for (int i = 0; i < path.length; i++) {
                if (current == null) {
                    return null;
                }
                if (i == path.length - 1) {
                    return current.get(path[i]);
                } else {
                    current = (Map<String, Object>) current.get(path[i]);
                }
            }
        }
        return null;
    }

    @Override
    public Map<String, Object> updateValueFrom(
            Map<String, Object> value,
            MapValueUpdater whenFound
    ) {
        return updateValue(value, 0, whenFound,
                (originalParent, updatedParent, fieldPath, fieldName) -> {
                    // filter out
                },
                (originalParent, updatedParent, fieldPath, fieldName) ->
                        updatedParent.put(fieldName, originalParent.get(fieldName)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> updateValue(
            Map<String, Object> originalValue,
            int step,
            MapValueUpdater update,
            MapValueUpdater notFound,
            MapValueUpdater others
    ) {
        if (originalValue == null) return null;
        Map<String, Object> updatedParent = new HashMap<>(originalValue.size());
        boolean found = false;
        for (Map.Entry<String, Object> entry : originalValue.entrySet()) {
            String fieldName = entry.getKey();
            Object fieldValue = entry.getValue();
            if (path[step].equals(fieldName)) {
                found = true;
                if (step < path.length - 1) {
                    if (fieldValue instanceof Map) {
                        Map<String, Object> updatedField = updateValue(
                                (Map<String, Object>) fieldValue,
                                step + 1,
                                update,
                                notFound,
                                others);
                        updatedParent.put(fieldName, updatedField);
                    } else {
                        // add back to not found and apply others, as only leaf values are updated
                        found = false;
                        others.apply(originalValue, updatedParent, null, fieldName);
                    }
                } else {
                    update.apply(originalValue, updatedParent, this, fieldName);
                }
            } else {
                others.apply(originalValue, updatedParent, null, fieldName);
            }
        }

        if (!found) {
            notFound.apply(originalValue, updatedParent, this, stepAt(step));
        }

        return updatedParent;
    }

    @Override
    public Struct updateValueFrom(
            Schema originalSchema,
            Struct originalValue,
            Schema updatedSchema,
            StructValueUpdater whenFound
    ) {
        return updateValue(originalSchema, originalValue, updatedSchema, 0, whenFound,
                (originalParent, originalField, updatedParent, updatedField, fieldPath) -> {
                    // filter out
                },
                (originalParent, originalField, updatedParent, nullUpdatedField, nullFieldPath) ->
                        updatedParent.put(originalField.name(), originalParent.get(originalField)));
    }

    private Struct updateValue(
            Schema originalSchema,
            Struct originalValue,
            Schema updateSchema,
            int step,
            StructValueUpdater update,
            StructValueUpdater notFound,
            StructValueUpdater others
    ) {
        Struct updated = new Struct(updateSchema);
        boolean found = false;
        for (Field field : originalSchema.fields()) {
            if (step < path.length) {
                if (path[step].equals(field.name())) {
                    found = true;
                    if (step == path.length - 1) {
                        update.apply(
                                originalValue,
                                field,
                                updated,
                                updateSchema.field(field.name()),
                                this
                        );
                    } else {
                        if (field.schema().type() == Type.STRUCT) {
                            Struct fieldValue = updateValue(
                                    field.schema(),
                                    originalValue.getStruct(field.name()),
                                    updateSchema.field(field.name()).schema(),
                                    step + 1,
                                    update,
                                    notFound,
                                    others
                            );
                            updated.put(field.name(), fieldValue);
                        } else {
                            // add back to not found and apply others, as only leaf values are updated
                            found = false;
                            others.apply(originalValue, field, updated, null, this);
                        }
                    }
                } else {
                    others.apply(originalValue, field, updated, null, this);
                }
            }
        }
        if (!found) {
            notFound.apply(
                    originalValue,
                    null,
                    updated,
                    updateSchema.field(stepAt(step)),
                    this);
        }
        return updated;
    }

    @Override
    public Schema updateSchemaFrom(Schema originalSchema, StructSchemaUpdater whenFound) {
        SchemaBuilder updated = SchemaUtil.copySchemaBasics(originalSchema, SchemaBuilder.struct());
        return updateSchema(originalSchema, updated, 0, whenFound,
                (schemaBuilder, field, fieldPath) -> { /* ignore */ },
                (schemaBuilder, field, fieldPath) -> schemaBuilder.field(field.name(), field.schema()));
    }

    @Override
    public Schema updateSchemaFrom(
            Schema originalSchema,
            SchemaBuilder baselineSchemaBuilder,
            StructSchemaUpdater whenFound
    ) {
        return updateSchema(originalSchema, baselineSchemaBuilder, 0, whenFound,
                (schemaBuilder, field, fieldPath) -> { /* ignore */ },
                (schemaBuilder, field, fieldPath) -> schemaBuilder.field(field.name(), field.schema()));
    }

    // Recursive implementation to update schema at different steps.
    // Consider that resulting schemas are usually cached.
    private Schema updateSchema(
            Schema operatingSchema,
            SchemaBuilder builder,
            int step,
            StructSchemaUpdater matching,
            StructSchemaUpdater notFound,
            StructSchemaUpdater others
    ) {
        if (operatingSchema.isOptional()) {
            builder.optional();
        }
        if (operatingSchema.defaultValue() != null) {
            builder.defaultValue(operatingSchema.defaultValue());
        }
        boolean matched = false;
        for (Field field : operatingSchema.fields()) {
            if (step < path.length) {
                if (path[step].equals(field.name())) {
                    matched = true;
                    if (step == path.length - 1) {
                        matching.apply(builder, field, this);
                    } else {
                        Schema fieldSchema = updateSchema(
                                field.schema(),
                                SchemaBuilder.struct(),
                                step + 1,
                                matching,
                                notFound,
                                others);
                        builder.field(field.name(), fieldSchema);
                    }
                } else {
                    others.apply(builder, field, null);
                }
            } else {
                others.apply(builder, field, null);
            }
        }
        if (!matched) {
            notFound.apply(builder, null, this);
        }
        return builder.build();
    }

    public String last() {
        return path[path.length - 1];
    }

    public boolean isEmpty() {
        return path.length == 0;
    }

    public String stepAt(int i) {
        return i < path.length ? path[i] : null;
    }

    // For testing
    String[] path() {
        return Arrays.copyOf(path, path.length);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SingleFieldPath fieldPath = (SingleFieldPath) o;
        return Arrays.equals(path, fieldPath.path);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(path);
    }

    @Override
    public String toString() {
        return "FieldPath(path = " + Arrays.toString(path) + ")";
    }
}
