---
title: "State Backends"
weight: 6
type: docs
aliases:
  - /zh/dev/stream/state/state_backends.html
---
<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# State Backends

Flink 提供了多种 state backends，它用于指定状态的存储方式和位置。

状态可以位于 Java 的堆或堆外内存。取决于你的 state backend，Flink 也可以自己管理应用程序的状态。
为了让应用程序可以维护非常大的状态，Flink 可以自己管理内存（如果有必要可以溢写到磁盘）。
默认情况下，所有 Flink Job 会使用 [*Flink 配置文件*]({{< ref "docs/deployment/config#flink-配置文件" >}}) 中指定的 state backend。

但是，配置文件中指定的默认 state backend 会被 Job 中指定的 state backend 覆盖，如下所示。

关于可用的 state backend 更多详细信息，包括其优点、限制和配置参数等，请参阅[部署和运维]({{< ref "docs/ops/state/state_backends" >}})的相应部分。

{{< tabs "03941da4-5c40-4bb8-97ce-dd14c08bb9a9" >}}
{{< tab "Java" >}}
```java
Configuration config = new Configuration();
config.set(StateBackendOptions.STATE_BACKEND, "rocksdb");
env.configure(config);
```
{{< /tab >}}
{{< tab "Python" >}}
```python
config = Configuration()
config.set_string('state.backend.type', 'rocksdb')
env = StreamExecutionEnvironment.get_execution_environment(config)
```
{{< /tab >}}
{{< /tabs >}}

{{< top >}}
