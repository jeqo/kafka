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
package org.apache.kafka.connect.transforms;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Schema.Type;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.transforms.util.SchemaUtil;

/**
 * Represents a path to a field within a structure within a Connect key/value (e.g. Struct or
 * Map<String, Object>).
 * <ul>
 * <li>It follows a dotted notation to represent nested values.</li>
 * <li>If field names contain dots, can be escaped by wrapping field names with backticks.</li>
 * <li>If field names contain dots at wrapping positions (beginning or end of path, before or after dots), then backticks need to be
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

    /**
     * If version is V2, then paths are cached for further access.
     *
     * @param pathText field path expression
     * @param version  field syntax version
     */
    public static FieldPath from(String pathText, FieldSyntaxVersion version) {
        if (pathText == null || pathText.isEmpty() || version.equals(FieldSyntaxVersion.V1)) {
            return new FieldPath(pathText, version);
        } else {
            if (PATHS_CACHE.containsKey(pathText)) {
                return PATHS_CACHE.get(pathText);
            } else {
                final FieldPath fieldPath = new FieldPath(pathText, version);
                PATHS_CACHE.put(pathText, fieldPath);
                return fieldPath;
            }
        }
    }

    FieldPath(String path, FieldSyntaxVersion version) {
        if (path == null || path.isEmpty()) { // empty path
            this.path = new String[] {};
        } else {
            switch (version) {
                case V1:
                    this.path = new String[] {path};
                    break;
                case V2:
                    if (!path.contains(DOT)
                        && !(path.startsWith(BACKTICK) && path.endsWith(
                        BACKTICK))) { // does not need path steps
                        this.path = new String[] {path};
                    } else {
                        // track fields in path steps
                        List<String> steps = new ArrayList<>();
                        // reuse string bits, will shrink as path is built
                        StringBuilder s = new StringBuilder(path);

                        while (s.length() > 0) {
                            if (s.charAt(0) == BACKTICK_CHAR) { // has opening backtick pair
                                s.deleteCharAt(0);

                                // find backtick pair
                                int idx = 0;
                                while (idx >= 0) {
                                    idx = s.indexOf(BACKTICK, idx);
                                    if (idx == -1) {
                                        throw new IllegalArgumentException(
                                            "Incomplete backtick pair at [...]`" + s);
                                    }
                                    if (idx != s.length() - 1) { // non-global backtick
                                        if (s.charAt(idx + 1) != DOT_CHAR
                                            || s.charAt(idx - 1)
                                            == BACKSLASH_CHAR) { // not wrapped or escaped
                                            idx++; // move index forward and keep searching
                                        } else { // it's end pair
                                            steps.add(
                                                checkIncompleteBacktickPair(s.substring(0, idx)));
                                            s.delete(0, idx + 2); // rm backtick and dot
                                            break;
                                        }
                                    } else { // global backtick
                                        steps.add(checkIncompleteBacktickPair(s.substring(0, idx)));
                                        s.delete(0, s.length());
                                        break;
                                    }
                                }
                            } else { // by dots
                                final int atDot = s.indexOf(DOT);
                                if (atDot > 0) { // get step and move forward
                                    steps.add(checkIncompleteBacktickPair(s.substring(0, atDot)));
                                    s.delete(0, atDot + 1);
                                } else { // add all
                                    steps.add(checkIncompleteBacktickPair(s.toString()));
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

    private String checkIncompleteBacktickPair(String field) {
        StringBuilder s = new StringBuilder(field);
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

    @SuppressWarnings("unchecked")
    public void deleteFieldAt(Map<String, Object> map) {
        if (path.length == 1) {
            map.remove(path[0]);
        } else {
            Map<String, Object> current = map;
            for (int i = 0; i < path.length; i++) {
                if (i == path.length - 1) {
                    current.remove(path[i]);
                } else {
                    current = (Map<String, Object>) current.get(path[i]);
                }
            }
        }
    }

    public Schema updateSchemaAt(Schema schema, BiConsumer<SchemaBuilder, Field> change) {
        final SchemaBuilder builder = SchemaUtil.copySchemaBasics(schema, SchemaBuilder.struct());
        return updateSchema(schema, builder, 0, change);
    }

    private Schema updateSchema(Schema operatingSchema, SchemaBuilder builder, int step, BiConsumer<SchemaBuilder, Field> change) {
        if (operatingSchema.isOptional()) builder.optional();
//        if (schema.defaultValue() != null) {
//            Struct updatedDefaultValue = applyValueWithSchema((Struct) schema.defaultValue(), builder);
//            builder.defaultValue(updatedDefaultValue);
//        }
        for (Field field : operatingSchema.fields()) {
            if (step < path.length) {
                if (!path[step].equals(field.name())) {
                    builder.field(field.name(), field.schema());
                } else {
                    if (step == path.length - 1) {
                        change.accept(builder, field);
                    } else {
                        builder.field(field.name(), updateSchema(field.schema(), SchemaBuilder.struct(), step + 1, change));
                    }
                }
            } else {
                builder.field(field.name(), field.schema());
            }
        }
        return builder.build();
    }

    public Map<String, Object> updateValueAt(Map<String, Object> value, TriConsumer<Map<String, Object>, String, Object> change) {
        return updateValue(value, 0, change);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> updateValue(Map<String, Object> value, int step, TriConsumer<Map<String, Object>, String, Object> change) {
        Map<String, Object> updated = new HashMap<>(value);
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            if (step < path.length) {
                if (path[step].equals(entry.getKey())) {
                    if (step == path.length - 1) {
                        change.accept(updated, path[step], entry.getValue());
                    } else {
                        if (entry.getValue() instanceof Map) {
                            updated.put(
                                entry.getKey(),
                                updateValue((Map<String, Object>) entry.getValue(), step + 1, change));
                        }
                    }
                }
            }
        }
        return updated;
    }

    public Struct updateValueAt(Struct value, Schema schema, TriConsumer<Struct, Field, Object> change) {
        return updateValue(value, schema, 0, change);
    }

    private Struct updateValue(Struct value, Schema schema, int step, TriConsumer<Struct, Field, Object> change) {
        Struct updated = new Struct(schema);
        for (Field field : schema.fields()) {
            if (step < path.length) {
                if (path[step].equals(field.name())) {
                    if (step == path.length - 1) {
                        change.accept(updated, field, value.get(field.name()));
                    } else {
                        if (field.schema().type() == Type.STRUCT) {
                            updated.put(field, updateValue(value.getStruct(field.name()), field.schema(), step + 1, change));
                        }
                    }
                } else {
                    updated.put(field, value.get(field));
                }
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

    @FunctionalInterface
    interface TriConsumer<T, U, V> {
        void accept(T t, U u, V v);
    }
}
