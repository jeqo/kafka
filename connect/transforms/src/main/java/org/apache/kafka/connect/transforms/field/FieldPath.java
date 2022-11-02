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

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;

/**
 * Operations to update data values and schemas based on field paths.
 * <p>
 * See KIP-821.
 *
 * @see SingleFieldPath
 * @see FieldPathGroup
 */
public interface FieldPath {
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
     * Prepares a new schema based on an original one, and applies an update function
     * when the current path(s) is found.
     * <p>
     * If path is not found, {@code whenNotFound} function is used. e.g. to create field if not found.
     * <p>
     * Other fields are copied from original schema.
     * <p>
     * A copy of the {@code Schema} is used as a base for the updated schema.
     *
     * @param originalSchema baseline schema
     * @param whenFound function to apply when current path(s) is/are found
     * @param whenNotFound function to apply when current path(s) is/are not found
     * @return an updated schema. Resulting schemas are usually cached for further access
     */
    Schema updateSchemaFrom(
            Schema originalSchema,
            StructSchemaUpdater whenFound,
            StructSchemaUpdater whenNotFound
    );

    /**
     * Prepares a new schema based on an original one, and applies an update function
     * when the current path(s) is found.
     * <p>
     * If path is not found, {@code whenNotFound} function is used. e.g. to create field if not found.
     * <p>
     * Other fields use {@code toOtherFields} function to apply when not related to the current path(s).
     * <p>
     * A copy of the {@code Schema} is used as a base for the updated schema.
     *
     * @param originalSchema baseline schema
     * @param whenFound function to apply when current path(s) is/are found
     * @param whenNotFound function to apply when current path(s) is/are not found
     * @param toOthers function to apply to fields not related to current path(s)
     * @return an updated schema. Resulting schemas are usually cached for further access
     */
    Schema updateSchemaFrom(
            Schema originalSchema,
            StructSchemaUpdater whenFound,
            StructSchemaUpdater whenNotFound,
            StructSchemaUpdater toOthers
    );

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
     * Access {@code Struct} fields and apply functions to update field values.
     * <p>
     * If path is not found, {@code whenNotFound} function is used.
     * <p>
     * Other fields keep values from original struct.
     *
     * @param originalSchema original struct schema
     * @param originalValue  schema-based data value
     * @param updatedSchema updated struct schema
     * @param whenFound function to apply when a path found
     * @param whenNotFound function to apply when current path(s) is/are not found
     * @return a new struct with the updates fields
     */
    Struct updateValueFrom(
            Schema originalSchema,
            Struct originalValue,
            Schema updatedSchema,
            StructValueUpdater whenFound,
            StructValueUpdater whenNotFound
    );

    /**
     * Access {@code Struct} fields and apply functions to update field values.
     * <p>
     * If path is not found, {@code whenNotFound} function is used.
     * <p>
     * Other fields use {@code toOtherFields} function to apply when not related to the current path(s).
     *
     * @param originalSchema original struct schema
     * @param originalValue  schema-based data value
     * @param updatedSchema updated struct schema
     * @param whenFound function to apply when a path found
     * @param whenNotFound function to apply when current path(s) is/are not found
     * @param toOthers function to apply to fields not related to current path(s)
     * @return a new struct with the updates fields
     */
    Struct updateValueFrom(
            Schema originalSchema,
            Struct originalValue,
            Schema updatedSchema,
            StructValueUpdater whenFound,
            StructValueUpdater whenNotFound,
            StructValueUpdater toOthers
    );

//    Map<String, Object> updateValueFrom(
//            Map<String, Object> originalValue,
//            MapValueUpdater matching,
//            MapValueUpdater others
//    );
}
