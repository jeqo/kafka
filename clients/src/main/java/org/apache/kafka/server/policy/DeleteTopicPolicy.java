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
package org.apache.kafka.server.policy;

import org.apache.kafka.common.Configurable;
import org.apache.kafka.common.errors.PolicyViolationException;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * <p>An interface for enforcing a policy on create topics requests.
 *
 * <p>Common use cases are requiring that the replication factor, <code>min.insync.replicas</code> and/or retention settings for a
 * topic are within an allowable range.
 *
 * <p>If <code>create.topic.policy.class.name</code> is defined, Kafka will create an instance of the specified class
 * using the default constructor and will then pass the broker configs to its <code>configure()</code> method. During
 * broker shutdown, the <code>close()</code> method will be invoked so that resources can be released (if necessary).
 */
public interface DeleteTopicPolicy extends Configurable, AutoCloseable {

    /**
     * Class containing the create request parameters.
     */
    class RequestMetadata {
        private final String topic;

        /**
         * Create an instance of this class with the provided parameters.
         *
         * This constructor is public to make testing of <code>CreateTopicPolicy</code> implementations easier.
         *
         * @param topic the name of the topic to created.
         */
        public RequestMetadata(String topic) {
            this.topic = topic;
        }

        /**
         * Return the name of the topic to create.
         */
        public String topic() {
            return topic;
        }

        @Override
        public int hashCode() {
            return Objects.hash(topic);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            RequestMetadata other = (RequestMetadata) o;
            return topic.equals(other.topic);
        }

        @Override
        public String toString() {
            return "DeleteTopicPolicy.RequestMetadata(topic=" + topic + ")";
        }
    }

    /**
     * Validate the request parameters and throw a <code>PolicyViolationException</code> with a suitable error
     * message if the create topics request parameters for the provided topic do not satisfy this policy.
     *
     * Clients will receive the POLICY_VIOLATION error code along with the exception's message. Note that validation
     * failure only affects the relevant topic, other topics in the request will still be processed.
     *
     * @param requestMetadata the create topics request parameters for the provided topic.
     * @throws PolicyViolationException if the request parameters do not satisfy this policy.
     */
    void validate(RequestMetadata requestMetadata) throws PolicyViolationException;
}
