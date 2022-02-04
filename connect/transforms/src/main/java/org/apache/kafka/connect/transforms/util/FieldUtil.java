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
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;

public class FieldUtil {

    public static Schema schemaFrom(Struct value, String field) {
        Schema schema = null;
        for (String f : field.split("\\.")) {
            if (schema == null) {
                schema = value.schema();
            }
            final Field fieldFromValue = schema.field(f);
            if (fieldFromValue == null) {
                throw new DataException("Field does not exist: " + field);
            }
            schema = fieldFromValue.schema();
        }
        return schema;
    }

    @SuppressWarnings("unchecked")
    public static Object valueFrom(Map<String, Object> value, String field) {
        Object v = null;
        if (field.contains(".")) {
            Object object = null;
            final String[] split = field.split("\\.");
            for (int i = 0; i < split.length; i++) {
                if (object == null) {
                    object = value.get(split[i]);
                } else {
                    final Map<String, Object> map = (Map<String, Object>) object;
                    object = map.get(split[i]);
                    if (i == split.length - 1) {
                        v = object;
                    }
                }
            }
        } else {
            v = value.get(field);
        }
        return v;
    }

    public static Object valueFrom(Struct value, String field) {
        Object v = null;
        if (field.contains(".")) {
            Struct struct = null;
            final String[] split = field.split("\\.");
            for (int i = 0; i < split.length; i++) {
                if (struct == null) {
                    struct = value.getStruct(split[i]);
                } else {
                    if (i == split.length - 1) {
                        v = struct.get(split[i]);
                    } else {
                        struct = struct.getStruct(split[i]);
                    }
                }
            }
        } else {
            v = value.get(field);
        }
        return v;
    }

    public static Field check(Schema schema, String fieldName) {
        Field field = null;
        if (fieldName.contains(".")) {
            final String[] split = fieldName.split("\\.");
            for (String s : split) {
                field = schema.field(s);
                schema = field.schema();
            }
        } else {
            field = schema.field(fieldName);
        }

        if (field == null) {
            throw new IllegalArgumentException("Unknown field: " + fieldName);
        }

        return field;
    }
}
