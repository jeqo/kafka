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

import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigException;

import java.util.Arrays;

public enum FieldSyntaxVersion {
    /**
     * No support for nested fields.
     */
    V1("V1"),
    /**
     * Support for nested fields using dotted notation with backtick pairs to wrap field names that
     * include dots.
     */
    V2("V2");

    public static final String FIELD_SYNTAX_VERSION_CONFIG = "field.syntax.version";
    public static final String FIELD_SYNTAX_VERSION_DOC =
            "Defines the version of the syntax to access fields. "
                    + "If set to `V1`, then the field paths are limited to access the elements at the root level of the struct or map."
                    + "If set to `V2`, the syntax will support accessing nested elements. To access nested elements, "
                    + "dotted notation is used. If dots are already included in the field name, then backtick pairs "
                    + "can be used to wrap field names containing dots. "
                    + "e.g. to access elements from a field in a struct/map named \"foo.bar\", "
                    + "the following format can be used to access its elements: \"`foo.bar`.baz\".";

    public static final String FIELD_SYNTAX_VERSION_DEFAULT_VALUE = V1.name();
    public static final ConfigDef.Validator FIELD_SYNTAX_VERSION_VALIDATOR = new Validator();


    public final String name;

    FieldSyntaxVersion(final String name) {
        this.name = name;
    }

    public static FieldSyntaxVersion fromConfig(AbstractConfig config) {
        final String name = config.getString(FIELD_SYNTAX_VERSION_CONFIG);
        for (FieldSyntaxVersion version : values()) {
            if (version.name().equalsIgnoreCase(name)) {
                return version;
            }
        }
        throw new ConfigException("Invalid field syntax version");
    }

    static class Validator implements ConfigDef.Validator {

        @Override
        public void ensureValid(String name, Object value) {
            if (value == null) {
                throw new ConfigException(name, null,
                        "Empty field syntax version. Allowed values: " + Arrays.toString(values()));
            }
            FieldSyntaxVersion current = null;
            for (FieldSyntaxVersion version : values()) {
                if (version.name().equalsIgnoreCase(value.toString())) {
                    current = version;
                    break;
                }
            }
            if (current == null) {
                throw new ConfigException(name, value,
                        "Invalid field syntax version. Allowed values: " + Arrays.toString(values()));
            }
        }

        @Override
        public String toString() {
            return "field syntax version validator";
        }
    }
}
