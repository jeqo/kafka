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

import org.apache.kafka.common.Configurable;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;

import java.io.Closeable;

/**
 * Single message transformation for Kafka Connect record types.
 * <br/>
 * Connectors can be configured with transformations to make lightweight message-at-a-time modifications.
 */
public interface Transformation<R extends ConnectRecord<R>> extends Configurable, Closeable {

    String FIELD_SYNTAX_VERSION_CONFIG = "field.syntax.version";
    String FIELD_SYNTAX_VERSION_DOC = "Defines the version of the syntax to access fields. "
        + "If set to `V1`, then the field paths are limited to access the elements at the root level of the struct or map."
        + "If set to `V2`, the syntax will support accessing nested elements. o access nested elements, "
        + "dotted notation is used. If dots are already included in the field name, then backtick pairs "
        + "can be used to wrap field names containing dots. "
        + "e.g. to access elements from a struct/map named \"foo.bar\", "
        + "the following format can be used to access its elements: \"`foo.bar`.baz\".";

    String FIELD_SYNTAX_VERSION_DEFAULT_VALUE = "V1";

    /**
     * Apply transformation to the {@code record} and return another record object (which may be {@code record} itself) or {@code null},
     * corresponding to a map or filter operation respectively.
     * <br/>
     * A transformation must not mutate objects reachable from the given {@code record}
     * (including, but not limited to, {@link org.apache.kafka.connect.header.Headers Headers},
     * {@link org.apache.kafka.connect.data.Struct Structs}, {@code Lists}, and {@code Maps}).
     * If such objects need to be changed, a new ConnectRecord should be created and returned.
     * <br/>
     * The implementation must be thread-safe.
     */
    R apply(R record);

    /** Configuration specification for this transformation. **/
    ConfigDef config();

    /** Signal that this transformation instance will no longer will be used. **/
    @Override
    void close();

}
