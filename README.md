# Reports Engine

This project contains various code for a reporting engine on top of a database accessible through a web frontend. At the moment it supports Jasper reports only.

## Modules


```mermaid
sequenceDiagram
    actor D as Report Designer
    participant Repo as Report Repository

    box Developed in this repository
        participant Preprocessor
        participant Executor
        participant AzR as Azure Report Executor
        participant Frontend
    end

    actor U as Enduser

    D-->>Repo: develops reports in
    Repo->>Preprocessor: uses in CI

    Preprocessor->>Repo: results stored as release artefacts

    U-->>Frontend: Selects report
    activate Frontend
    Frontend->>Repo: queries metadata, preview images
    U-->>Frontend: Provides variables
    Frontend->>AzR: queue report
    activate AzR
    AzR->>Executor: render
    activate Executor
    Executor->>Repo: fetch precompiled report
    Executor->>AzR: output file
    deactivate Executor
    deactivate AzR
    Frontend->>U: provide download link
    deactivate Frontend



``` 

### Executor
Java code to execute a report. Wrapper around Jasper Reports.

### Azure Report Executor
Wrapper around the Executor module to execute reports through Azure functions.

### Preprocessor
Java-based CLI application that takes report definitions and compiles them, creates documentation and more for humans and machine usage. It is designed to be part of a CI pipeline for report definitions.

See the documentation [here](preprocessor/README.md) for further information.

### Frontend

To Be Developed