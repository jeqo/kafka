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
package org.apache.kafka.connect.transforms.util;

import java.util.Map;
import java.util.function.BiFunction;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;

public enum FieldSyntaxVersion {
    v1("v1", Map::get, Struct::get, Schema::field),
    v2("v2",
        (map, s) -> new FieldPath(s).valueAt(map),
        (struct, s) -> FieldPath.from(s).valueAt(struct),
        (schema, s) -> FieldPath.from(s).fieldAt(schema));

    public final String name;
    private final BiFunction<Map<String, Object>, String, Object> valueAtMap;
    private final BiFunction<Struct, String, Object> valueAtStruct;
    private final BiFunction<Schema, String, Field> fieldAtSchema;

    FieldSyntaxVersion(final String name,
        BiFunction<Map<String, Object>, String, Object> valueAtMap,
        BiFunction<Struct, String, Object> valueAtStruct,
        BiFunction<Schema, String, Field> fieldAtStruct) {
        this.name = name;
        this.valueAtMap = valueAtMap;
        this.valueAtStruct = valueAtStruct;
        this.fieldAtSchema = fieldAtStruct;
    }

    public Object valueAtMap(Map<String, Object> map, String fieldName) {
        return valueAtMap.apply(map, fieldName);
    }

    public Object valueAtStruct(Struct struct, String fieldName) {
        return valueAtStruct.apply(struct, fieldName);
    }

    public Field fieldAtSchema(Schema schema, String fieldName) {
        return fieldAtSchema.apply(schema, fieldName);
    }
}
