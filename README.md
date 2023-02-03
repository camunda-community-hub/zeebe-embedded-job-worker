[![Community Extension](https://img.shields.io/badge/Community%20Extension-An%20open%20source%20community%20maintained%20project-FF4700)](https://github.com/camunda-community-hub/community)
![Compatible with: Camunda Platform 8](https://img.shields.io/badge/Compatible%20with-Camunda%20Platform%208-0072Ce)
[![](https://img.shields.io/badge/Lifecycle-Proof%20of%20Concept-blueviolet)](https://github.com/Camunda-Community-Hub/community/blob/main/extension-lifecycle.md#proof-of-concept-)

# Zeebe Embedded Job Worker

Prototype to hook in code as [Exporter](https://docs.camunda.io/docs/self-managed/concepts/exporters/) that can then directly execute Jobs whenever they are created, directly within the JVM and process of the Zeebe broker.

This was used in a proof of concept (POC) to match super low latencies/cycle times for processes - as this skips the Job Acquisition via the Zeebe Gatway, which currently saves a relevant amount of time. 

However, this bears **the risk**, that whatever you do in your JobHandler code can severely impact the stability of the broker.

We are currently also discussing how to address the root problem in low-latency scenarios in a more stable way.

# Installation in k8s
See [helm-chart-values.yaml](helm-chart-values.yaml)

# Getting Process Variables through an Input Mapping

A input mapping can be used to get process variables

It may feel like extra effort to create local variables but it's actually a controlled and supported way to get access the current process state at the time when the task is created. If the engine would provide any other facility for exporters to access the variables at the time when a job got created, it would have store a snapshot of that state separately so that the stream processors can continue their work. For to allow exporter failover to another broker, that state would have to be fully replicated. So it would have to be included in the event stream. Now instead of storing a full snapshot of all process variables, using an explicit input mapping allows to store exactly the data that is needed for the job. Furthermore, it fits nicely to the connectors framework, which also employs variable mappings and forces the engine to optimize for high numbers of local variable scopes.

# Releasing new versions
1. Create new release on GitHub. Do not follow GitHub's advice to prefix the version number with a `v` for both release name and tag name. The `v` will be added automatically where needed.
