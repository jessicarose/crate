/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.planner.node.dql;

import com.google.common.collect.ImmutableList;
import io.crate.analyze.WhereClause;
import io.crate.metadata.Routing;
import io.crate.planner.node.ExecutionNode;
import io.crate.planner.node.ExecutionNodeVisitor;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class CountNode implements ExecutionNode {

    public static final ExecutionNodeFactory<CountNode> FACTORY = new ExecutionNodeFactory<CountNode>() {
        @Override
        public CountNode create() {
            return new CountNode();
        }
    };
    private int executionNodeId;
    private Routing routing;
    private WhereClause whereClause;
    private UUID jobId;

    CountNode() {}

    public CountNode(UUID jobId, int executionNodeId, Routing routing, WhereClause whereClause) {
        this.jobId = jobId;
        this.executionNodeId = executionNodeId;
        this.routing = routing;
        this.whereClause = whereClause;
    }

    @Override
    public Type type() {
        return Type.COUNT;
    }

    @Override
    public String name() {
        return "count";
    }

    @Override
    public UUID jobId() {
        return jobId;
    }

    public Routing routing() {
        return routing;
    }

    public WhereClause whereClause() {
        return whereClause;
    }

    @Override
    public int executionNodeId() {
        return executionNodeId;
    }

    @Override
    public Set<String> executionNodes() {
        return routing.nodes();
    }

    @Override
    public List<String> downstreamNodes() {
        return ImmutableList.of(ExecutionNode.DIRECT_RETURN_DOWNSTREAM_NODE);
    }

    @Override
    public int downstreamExecutionNodeId() {
        return ExecutionNode.NO_EXECUTION_NODE;
    }

    @Override
    public <C, R> R accept(ExecutionNodeVisitor<C, R> visitor, C context) {
        return visitor.visitCountNode(this, context);
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        executionNodeId = in.readVInt();
        routing = new Routing();
        routing.readFrom(in);
        whereClause = new WhereClause(in);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeVInt(executionNodeId);
        routing.writeTo(out);
        whereClause.writeTo(out);
    }
}
