# Report Engine: Preprocessor

The preprocessor takes a directory of report definitions and outputs various overviews, documentation, precompiled reports,... for human and machine use. It is designed to be used during a CI/CD pipeline for reports.

## How does it work?

The proprocessor contains various tasks that read data from a source directory, process it and store data in an output directory. Here is an overview:

```mermaid
flowchart LR
    A@{ shape: circle, label: "Start" }
    Z@{ shape: dbl-circ, label: "End" }

    subgraph Input [Source Directory <br/>Folder per Report]
      F@{ shape: document, label: "metadata.yaml" }
      G@{ shape: document, label: "report.jrxml" }
      F-. adds context to .->G
    end 

    Default ~~~ Input

    read-metadata-.uses.->F

    subgraph Output [Output Directory]
      direction TB
      B@{ shape: bow-rect, label: "SQLite<br/>metadata.db" }
      C@{ shape: document, label: "Artefacts" }
    end 

    clean -. empties .-> Output
    A-.->clean

    subgraph Default
      clean==>read-metadata
    end

    read-metadata==>Tasks
    read-metadata-.generates.->B

    Tasks-.use / add to .->B
    Tasks-.add.->C
    Tasks-.read from.->Input

    subgraph Tasks [Follow-Up Tasks]
      direction LR
      validate-jasper-reports
      compile-jasper-reports
      copy-source
      generate-md-details-public
      generate-md-overview
    end
    Tasks-->Z
``` 

When calling the preprocessor on the CLI, you can specify 0..n tasks to be executed as arguments. 
When executing without any arguments, the default tasks (clean & read-metadata) are executed. 
If tasks are specified as arguments only those are executed. Before running any of the follow-up tasks, make sure read-metadata was executed.

The `--sourceDir` and `--outputBaseDir` along with task specific configuration options can be passed as options. Use `--help` to see available options.

## Input Structure

### Directory Structure

````
+ inputDir/
  + my_report_01/
    - metadata.yaml
    - report.jrxml (for Jasper Reports) 
````

### Metadata File

````yaml
id: member_table
title: List of Members (Table)
description: This report lists all active members with address and contact details. # optional
outputFormats: [xls, xlsx, csv]

complex: false # optional, default: false, Add warning to users that report requires extra attention due to its complexity.

# restrict report usage: (leave away for unrestricted access)
onlyWithRole: ["Administrator"]
onlyForType: ["Region","Local"]

# optional:
sql: |-
  SELECT * FROM members WHERE id = :group_id AND active = 1

parameter:
    - name: group_id
      label: Group
      description: ID of the group to create the report for
      comment: Is not displayed in the mask when the report is executed, but is automatically filled with the data of the current context.
      type: string
    - name: sample_minimal_parameter
      type: string

# optional:
additionalParameterDescription: |-
  Markdown formatted description to add more context about the parameters.

# optional:
versionHistory: 
    - version: 1.0
    - version: 1.1
      createdOn: 2021-10-15
      description: |-
        * Umstellung auf Style-Template
        * Feldnamen an GUI anpassen
        
      
````

# Usage

In the root directory of your reports repository, run:

````
docker run --rm -v $(pwd):/project reports-engine-preprocessor --help
````

Replace `--help` with your arguments.


## Build

From repository root:

````
mvn package -pl preprocessor
docker build ./preprocessor --tag reports-engine-preprocessor
````

# Tasks before committing

Check each command:
- Metadata
- Options
- functionality
Exception handling
