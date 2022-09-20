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
import java.util.function.BiConsumer;

/**
 * Represents a path to a field within a structure within a Connect key/value (e.g. Struct or
 * Map<String, Object>).
 * <ul>
 * <li>It follows a dotted notation to represent nested values.</li>
 * <li>If field names contain dots, can be escaped by wrapping field names with backticks.</li>
 * <li>If field names contain backticks at wrapping positions (beginning or end of path, before or after dots), then backticks need to be
 * escaped by backslash.</li>
 * </ul>
 * Paths are calculated once and cached for further access.
 */
public class FieldPath {

    private static final String BACKTICK = "`";
    private static final String DOT = ".";
    public static final char BACKTICK_CHAR = '`';
    public static final char DOT_CHAR = '.';
    public static final char BACKSLASH_CHAR = '\\';

    private static final Map<String, FieldPath> PATHS_CACHE = new HashMap<>();

    private final String[] path;

    static FieldPath ofV1(String field) {
        return of(field, FieldSyntaxVersion.V1);
    }

    static FieldPath ofV2(String field) {
        return of(field, FieldSyntaxVersion.V2);
    }

    /**
     * If version is V2, then paths are cached for further access.
     *
     * @param field   field path expression
     * @param version field syntax version
     */
    public static FieldPath of(String field, FieldSyntaxVersion version) {
        if (field == null || field.isEmpty() || version.equals(FieldSyntaxVersion.V1)) {
            return new FieldPath(field, version);
        } else {
            if (PATHS_CACHE.containsKey(field)) {
                return PATHS_CACHE.get(field);
            } else {
                final FieldPath fieldPath = new FieldPath(field, version);
                PATHS_CACHE.put(field, fieldPath);
                return fieldPath;
            }
        }
    }

    FieldPath(String path, FieldSyntaxVersion version) {
        if (path == null || path.isEmpty()) { // empty path
            this.path = new String[] {};
        } else {
            switch (version) {
                case V1: // backward compatibility
                    this.path = new String[] {path};
                    break;
                case V2:
                    // if no dots or wrapping backticks are used, then return path with single step
                    if (!path.contains(DOT)) {
                        this.path = new String[] {path};
                    } else {
                        // prepare for tracking path steps
                        final List<String> steps = new ArrayList<>();
                        // avoid creating new string on changes
                        final StringBuilder s = new StringBuilder(path);

                        while (s.length() > 0) { // until path is traverse
                            // process backtick pair if any
                            if (s.charAt(0) == BACKTICK_CHAR) {
                                s.deleteCharAt(0);

                                // find backtick closing pair
                                int idx = 0;
                                while (idx >= 0) {
                                    idx = s.indexOf(BACKTICK, idx);
                                    if (idx == -1) {
                                        throw new IllegalArgumentException("Incomplete backtick pair at [...]`" + s);
                                    }
                                    if (idx < s.length() - 1 // non-global backtick
                                            && (s.charAt(idx + 1) != DOT_CHAR
                                            || s.charAt(idx - 1) == BACKSLASH_CHAR)) { // not wrapped or escaped
                                        idx++; // move index forward and keep searching
                                    } else { // it's end pair
                                        steps.add(escapeBackticks(s.substring(0, idx)));
                                        s.delete(0, idx + 2); // rm backtick and dot
                                        break;
                                    }
                                }
                            } else { // process path dots
                                final int atDot = s.indexOf(DOT);
                                if (atDot > 0) { // get step and move forward
                                    steps.add(escapeBackticks(s.substring(0, atDot)));
                                    s.delete(0, atDot + 1);
                                } else { // add all
                                    steps.add(escapeBackticks(s.toString()));
                                    s.delete(0, s.length());
                                }
                            }
                        }

                        this.path = steps.toArray(new String[0]);
                    }
                    break;
                default:
                    throw new IllegalArgumentException("Unknown syntax version: " + version);
            }
        }
    }

    /**
     * Return field name with escaped backticks, if any.
     * @param field potentially containing backticks
     * @throws IllegalArgumentException when there are incomplete backtick pairs
     */
    private String escapeBackticks(String field) {
        final StringBuilder s = new StringBuilder(field);
        int idx = 0;
        while (idx >= 0) {
            idx = s.indexOf(BACKTICK, idx + 1);
            if (idx >= 1 && s.length() > 2) {
                if (s.charAt(idx - 1) == DOT_CHAR
                        || (idx < s.length() - 1 && s.charAt(idx + 1) == DOT_CHAR
                        && s.charAt(idx - 1) != BACKSLASH_CHAR)) {
                    throw new IllegalArgumentException("Incomplete backtick pair at [...]" + field);
                }
                if (s.charAt(idx - 1) == BACKSLASH_CHAR) { // escape backtick
                    if (s.charAt(idx + 1) == DOT_CHAR // before a dot
                            || s.charAt(idx - 2) == DOT_CHAR // after a dot
                            || (idx == 1 && s.charAt(0) == BACKSLASH_CHAR) // at the beginning
                            || idx == s.length() - 1) { // at the end
                        s.deleteCharAt(idx - 1);
                    }
                }
            }
        }
        return s.toString();
    }

    /**
     * Access field at the current path within a schema {@code Schema}
     */
    public Field fieldAt(Schema schema) {
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
     * Access value at the current path within a schema-based {@code Struct}
     */
    public Object valueAt(Struct struct) {
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
     * Access value at the current path within a schemaless {@code Map<String, Object>}
     */
    @SuppressWarnings("unchecked")
    public Object valueAt(Map<String, Object> map) {
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

    public Schema updateSchemaAt(Schema schema, BiConsumer<SchemaBuilder, Field> change) {
        SchemaBuilder updated = SchemaUtil.copySchemaBasics(schema, SchemaBuilder.struct());
        return updateSchema(schema, updated, 0, change);
    }

    public Schema updateSchemaAt(
            Schema schema,
            SchemaBuilder updated,
            BiConsumer<SchemaBuilder, Field> change
    ) {
        return updateSchema(schema, updated, 0, change);
    }

    private Schema updateSchema(Schema operatingSchema, SchemaBuilder builder, int step,
            BiConsumer<SchemaBuilder, Field> change) {
        if (operatingSchema.isOptional()) {
            builder.optional();
        }
        for (Field field : operatingSchema.fields()) {
            if (step < path.length) {
                if (!path[step].equals(field.name())) {
                    builder.field(field.name(), field.schema());
                } else {
                    if (step == path.length - 1) {
                        change.accept(builder, field);
                    } else {
                        Schema fieldSchema = updateSchema(
                                field.schema(),
                                SchemaBuilder.struct(),
                                step + 1,
                                change);
                        builder.field(field.name(), fieldSchema);
                    }
                }
            } else {
                builder.field(field.name(), field.schema());
            }
        }
        return builder.build();
    }

    public Map<String, Object> updateValueAt(Map<String, Object> value, MapValueUpdater change) {
        return updateValue(value, 0, change);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> updateValue(
            Map<String, Object> value,
            int step,
            MapValueUpdater change
    ) {
        Map<String, Object> updated = new HashMap<>(value);
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            if (step < path.length) {
                if (path[step].equals(entry.getKey())) {
                    if (step == path.length - 1) {
                        change.apply(updated, path[step], entry.getValue());
                    } else {
                        if (entry.getValue() instanceof Map) {
                            updated.put(
                                    entry.getKey(),
                                    updateValue((Map<String, Object>) entry.getValue(), step + 1,
                                            change));
                        }
                    }
                }
            }
        }
        return updated;
    }

    public Struct updateValueAt(
            Schema schema,
            Struct value,
            Schema updatedSchema,
            StructValueUpdater change
    ) {
        return updateValue(schema, value, updatedSchema, 0, change);
    }

    private Struct updateValue(
            Schema schema,
            Struct value,
            Schema updateSchema,
            int step,
            StructValueUpdater change
    ) {
        Struct updated = new Struct(updateSchema);
        for (Field field : schema.fields()) {
            if (step < path.length) {
                if (path[step].equals(field.name())) {
                    if (step == path.length - 1) {
                        change.apply(
                                field,
                                updateSchema.field(field.name()),
                                updated,
                                value.get(field.name())
                        );
                    } else {
                        if (field.schema().type() == Type.STRUCT) {
                            Struct fieldValue = updateValue(
                                    field.schema(),
                                    value.getStruct(field.name()),
                                    updateSchema.field(field.name()).schema(),
                                    step + 1,
                                    change
                            );
                            updated.put(field, fieldValue);
                        }
                    }
                } else {
                    updated.put(field, value.get(field));
                }
            } else {
                updated.put(field, value.get(field));
            }
        }
        return updated;
    }

    // For testing
    String[] path() {
        return Arrays.copyOf(path, path.length);
    }

    @Override
    public String toString() {
        return "path=" + Arrays.toString(path);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        FieldPath fieldPath = (FieldPath) o;
        return Arrays.equals(path, fieldPath.path);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(path);
    }

    public String last() {
        return path[path.length - 1];
    }

    public boolean isEmpty() {
        return path.length == 0;
    }

    public String at(int i) {
        return i < path.length ? path[i] : null;
    }

}
