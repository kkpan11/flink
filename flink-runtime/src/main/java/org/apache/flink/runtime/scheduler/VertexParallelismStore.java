/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.scheduler;

import org.apache.flink.runtime.jobgraph.JobVertexID;

import java.util.Map;

/**
 * Contains the max parallelism per vertex, along with metadata about how these maxes were
 * calculated.
 */
public interface VertexParallelismStore {
    /**
     * Returns a given vertex's parallelism information.
     *
     * @param vertexId vertex for which the parallelism information should be returned
     * @return a parallelism information for the given vertex
     * @throws IllegalStateException if there is no parallelism information for the given vertex
     */
    VertexParallelismInformation getParallelismInfo(JobVertexID vertexId);

    /**
     * Gets a map of all vertex parallelism information.
     *
     * @return A map containing JobVertexID and corresponding VertexParallelismInformation.
     */
    Map<JobVertexID, VertexParallelismInformation> getAllParallelismInfo();
}
