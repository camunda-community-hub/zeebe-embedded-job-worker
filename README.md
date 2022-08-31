[![Community Extension](https://img.shields.io/badge/Community%20Extension-An%20open%20source%20community%20maintained%20project-FF4700)](https://github.com/camunda-community-hub/community)
![Compatible with: Camunda Platform 8](https://img.shields.io/badge/Compatible%20with-Camunda%20Platform%208-0072Ce)
[![](https://img.shields.io/badge/Lifecycle-Proof%20of%20Concept-blueviolet)](https://github.com/Camunda-Community-Hub/community/blob/main/extension-lifecycle.md#proof-of-concept-)

# Zeebe Embedded Job Worker

Prototype to hook in code as [Exporter](https://docs.camunda.io/docs/self-managed/concepts/exporters/) that can then directly execute Jobs whenever they are created, directly within the JVM and process of the Zeebe broker.

This was used in a proof of concept (POC) to match super low latencies/cycle times for processes - as this skips the Job Acquisition via the Zeebe Gatway, which currently saves a relevant amount of time. 

However, this bears **the risk**, that whatever you do in your JobHandler code can severely impact the stability of the broker.

We are currently also discussing how to address the root problem in low-latency scenarios in a more stable way.
