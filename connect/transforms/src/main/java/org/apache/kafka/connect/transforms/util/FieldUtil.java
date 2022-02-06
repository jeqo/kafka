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

import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;

import java.util.Map;
import java.util.function.Function;

public class FieldUtil {

    public static Schema schemaFrom(Schema schema, String path) {
        if (path.contains(".")) {
            final String fieldName = path.substring(0, path.indexOf("."));
            final String tail = path.substring(path.indexOf(".") + 1);
            final Field field = schema.field(fieldName);
            if (field == null)
                throw new DataException("Field does not exist: " + path);
            return schemaFrom(field.schema(), tail);
        } else {
            final Field field = schema.field(path);
            if (field == null)
                throw new DataException("Field does not exist: " + path);
            return field.schema();
        }
    }

    @SuppressWarnings("unchecked")
    public static Object valueFrom(Map<String, Object> value, String path) {
        if (path.contains(".")) {
            final String fieldName = path.substring(0, path.indexOf("."));
            final String tail = path.substring(path.indexOf(".") + 1);
            return valueFrom((Map<String, Object>) value.get(fieldName), tail);
        } else {
            return value.get(path);
        }
    }

    @SuppressWarnings("unchecked")
    public static void update(Map<String, Object> value, String path, Function<Object, Object> update) {
        if (path.contains(".")) {
            final String fieldName = path.substring(0, path.indexOf("."));
            final String tail = path.substring(path.indexOf(".") + 1);
            update((Map<String, Object>) value.get(fieldName), tail, update);
        } else {
            value.computeIfPresent(path, (s, o) -> update.apply(o));
        }
    }

    public static Object valueFrom(Struct value, String path) {
        if (path.contains(".")) {
            final String fieldName = path.substring(0, path.indexOf("."));
            final String tail = path.substring(path.indexOf(".") + 1);
            return valueFrom(value.getStruct(fieldName), tail);
        } else {
            return value.get(path);
        }
    }

    public static void update(Struct value, String path, Function<Object, Object> update) {
        if (path.contains(".")) {
            final String fieldName = path.substring(0, path.indexOf("."));
            final String tail = path.substring(path.indexOf(".") + 1);
            update(value.getStruct(fieldName), tail, update);
        } else {
            value.put(path, update.apply(value.get(path)));
        }
    }

    public static Field check(Schema schema, String path) {
        if (path.contains(".")) {
            final String fieldName = path.substring(0, path.indexOf("."));
            final String tail = path.substring(path.indexOf(".") + 1);
            Field field = schema.field(fieldName);
            if (field == null) {
                throw new IllegalArgumentException("Unknown field: " + fieldName);
            }
            return check(field.schema(), tail);
        } else {
            Field field = schema.field(path);
            if (field == null) {
                throw new IllegalArgumentException("Unknown field: " + path);
            }
            return field;
        }
    }

}
