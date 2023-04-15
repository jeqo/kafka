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

    private final String[] path;

    public SingleFieldPath(String pathText, FieldSyntaxVersion version) {
        if (pathText == null || pathText.isEmpty()) { // empty path
            this.path = new String[] {};
        } else {
            switch (version) {
                case V1: // backward compatibility
                    this.path = new String[] {pathText};
                    break;
                case V2:
                    // if no dots or wrapping backticks are used, then return path with single step
                    if (!pathText.contains(String.valueOf(DOT))) {
                        path = new String[] {pathText};
                    } else {
                        path = buildFieldPathV2(pathText);
                    }
                    break;
                default:
                    throw new IllegalArgumentException("Unknown syntax version: " + version);
            }
        }
    }

    private static String[] buildFieldPathV2(String pathText) {
        // prepare for tracking path steps
        final List<String> steps = new ArrayList<>();
        int idx = 0;
        while (idx < pathText.length()) {
            // start processing backtick pair, if any
            if (pathText.charAt(idx) == BACKTICK) {
                idx++;
                final int start = idx; // this is where the "real" (i.e., not-wrapped-by-backticks) field name starts
                // find backtick closing pair
                while (true) {
                    idx = pathText.indexOf(String.valueOf(BACKTICK), idx);
                    if (idx == -1) { // if not found, fail
                        throw new IllegalArgumentException("Incomplete backtick pair in field path: " + pathText);
                    }
                    boolean endOfPath = idx >= pathText.length() - 1;
                    boolean notWrappingField = !endOfPath && pathText.charAt(idx + 1) != DOT;
                    boolean escaped = pathText.charAt(idx - 1) == BACKSLASH;
                    // check that it is not escaped or wrapped in another backticks pair
                    if (!endOfPath && (notWrappingField || escaped)) {
                        idx++; // move index forward and keep searching
                    } else { // it's the closing pair
                        String field = pathText.substring(start, idx);
                        steps.add(processEscapedBackticks(field));
                        idx += 2; // increment by two (once for the backslash, and one for a potential dot following it)
                        break;
                    }
                }
            } else { // process dots in path
                final int start = idx; // this is where the field name starts
                idx = pathText.indexOf(String.valueOf(DOT), idx);
                if (idx == -1) {
                    // we've reached the end of the path
                    String field = pathText.substring(start);
                    steps.add(field);
                    break;
                } else {
                    String field = pathText.substring(start, idx);
                    steps.add(field);
                    idx++;
                }
            }
        }

        return steps.toArray(new String[0]);
    }

    /**
     * Return field name with escaped backticks, if any.
     *
     * @param field potentially containing backticks
     * @throws IllegalArgumentException when there are incomplete backtick pairs
     */
    private static String processEscapedBackticks(String field) {
        final StringBuilder s = new StringBuilder(field);
        int idx = 0;
        while (idx >= 0) {
            idx = s.indexOf(String.valueOf(BACKTICK), idx + 1);
            if (idx >= 1 && s.length() > 2) {
                if (s.charAt(idx - 1) == DOT
                        || (idx < s.length() - 1 && s.charAt(idx + 1) == DOT
                        && s.charAt(idx - 1) != BACKSLASH)) {
                    throw new IllegalArgumentException("Incomplete backtick pair at [...]" + field);
                }
                if (s.charAt(idx - 1) == BACKSLASH) { // escape backtick
                    if ((idx == 1 && s.charAt(0) == BACKSLASH) // at the beginning: \`foo[...]
                            || idx == s.length() - 1) { // at the end: [...]baz\`
                        s.deleteCharAt(idx - 1);
                    } else if ((idx > 2 && s.charAt(idx - 2) == DOT) // after a dot: [...].\`bar[...]
                            || (idx < s.length() - 1 && s.charAt(idx + 1) == DOT)) { // before a dot: [...]bar\`.[...]
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
