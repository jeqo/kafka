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
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Operations to update data values and schemas based on field paths.
 * <p>
 * See KIP-821.
 *
 * @see SingleFieldPath
 * @see MultiFieldPaths
 */
public interface FieldPaths {

    Field fieldFrom(Schema schema);

    Map<FieldPaths, Field> fieldsFrom(Schema schema);

    Map<FieldPaths, Map.Entry<String, Object>> fieldAndValuesFrom(Map<String, Object> map);
    Map.Entry<String, Object> fieldAndValueFrom(Map<String, Object> map);

    Map<FieldPaths, Map.Entry<Field, Object>> fieldAndValuesFrom(Struct struct);
    Map.Entry<Field, Object> fieldAndValueFrom(Struct struct);
    Map<FieldPaths, Map.Entry<Field, Object>> fieldAndValuesFrom(Schema schema, Struct struct);
    Map.Entry<Field, Object> fieldAndValueFrom(Schema schema, Struct struct);

    /**
     * Prepares a new schema based on an original one, and applies an update function
     * when the current path(s) is found.
     * <p>
     * If path is not found, no function is applied, and the path is ignored.
     * <p>
     * Other fields are copied from original schema.
     * @param originalSchema baseline schema
     * @param baselineSchemaBuilder baseline schema build, if changes to the baseline
     *                              are required before copying original
     * @param whenFound function to apply when current path(s) is/are found.
     * @return an updated schema. Resulting schemas are usually cached for further access.
     */
    Schema updateSchemaFrom(
            Schema originalSchema,
            SchemaBuilder baselineSchemaBuilder,
            StructSchemaUpdater whenFound
    );

    /**
     * Prepares a new schema based on an original one, and applies an update function
     * when the current path(s) is found.
     * <p>
     * If path is not found, no function is applied, and the path is ignored.
     * <p>
     * Other fields are copied from original schema.
     * <p>
     * A copy of the {@code Schema} is used as a base for the updated schema.
     *
     * @param originalSchema baseline schema
     * @param whenFound function to apply when current path(s) is/are found
     * @return an updated schema. Resulting schemas are usually cached for further access
     */
    Schema updateSchemaFrom(Schema originalSchema, StructSchemaUpdater whenFound);

    /**
     * Access {@code Struct} fields and apply functions to update field values.
     * <p>
     * If path is not found, no function is applied, and the path is ignored.
     * <p>
     * Other fields keep values from original struct.
     *
     * @param originalSchema original struct schema
     * @param originalValue  schema-based data value
     * @param updatedSchema updated struct schema
     * @param whenFound function to apply when current path(s) is/are found
     * @return updated data value
     */
    Struct updateValueFrom(
            Schema originalSchema,
            Struct originalValue,
            Schema updatedSchema,
            StructValueUpdater whenFound
    );

    /**
     * Access {@code Map} fields and apply functions to update field values.
     * <p>
     * If path is not found, no function is applied, and the path is ignored.
     * <p>
     * Other fields keep values from original struct.
     *
     * @param originalValue  schema-based data value
     * @param whenFound function to apply when current path(s) is/are found
     * @return updated data value
     */
    Map<String, Object> updateValueFrom(
            Map<String, Object> originalValue,
            MapValueUpdater whenFound
    );

    static Builder newBuilder(FieldSyntaxVersion syntaxVersion) {
        return new Builder(syntaxVersion);
    }

    class Builder {
        final FieldSyntaxVersion version;
        final Set<SingleFieldPath> paths = new HashSet<>();

        public Builder(FieldSyntaxVersion version) {
            this.version = version;
        }

        public Builder add(String path) {
            if (!path.isEmpty()) this.paths.add(new SingleFieldPath(path, version));
            return this;
        }

        public FieldPaths build() {
            if (paths.isEmpty()) return null;
            if (paths.size() == 1) return paths.iterator().next();
            else return new MultiFieldPaths(paths);
        }

        public Builder addAll(List<String> fields) {
            fields.forEach(this::add);
            return this;
        }
    }
}
