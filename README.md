# Archi ELK Auto-Layout Plugin

[![Java CI with Maven](https://github.com/rolfmadsen/archimatetool-elk-auto-layout-plugin/actions/workflows/build.yml/badge.svg)](https://github.com/rolfmadsen/archimatetool-elk-auto-layout-plugin/actions/workflows/build.yml)
[![Latest Release](https://img.shields.io/github/v/release/rolfmadsen/archimatetool-elk-auto-layout-plugin?label=Latest%20stable%20release&color=blue&include_prereleases)](https://github.com/rolfmadsen/archimatetool-elk-auto-layout-plugin/releases/latest/download/elk-auto-layout-plugin.archiplugin)
[![Latest Build](https://img.shields.io/badge/Latest_Build-bleeding_edge-orange)](https://github.com/rolfmadsen/archimatetool-elk-auto-layout-plugin/actions/workflows/build.yml)

> [!WARNING]
> **Disclaimer: Vibe-Coded Software**
> This plugin is not created by professional Java developers. It has been "vibe-coded" with the help of AI agents. While we strive for correctness, this software is provided "as is" without any warranties.
>
> **CRITICAL**: Use this plugin at your own risk. The authors are not responsible for any model corruption.

A plugin for [Archi](https://www.archimatetool.com) that implements a specialized auto-layout algorithm for ArchiMate diagrams using the Eclipse Layout Kernel (ELK). 

Unlike standard left-to-right graphs, this plugin perfectly understands the ArchiMate metamodel and strictly forces a hierarchical architecture matrix:
- **Vertical Strategy**: Motivation at the top, Strategy, Business, Application, Technology, and Implementation at the bottom.
- **Horizontal Strategy**: Active Structure mapped to the Right, Behavior mapped to the Center, and Passive Structure mapped to the Left.

## 📥 Download & Installation

**1. Download the Plugin:**
You have two options depending on your needs.

*   **Stable Version (Recommended):** [**Download Official Release**](https://github.com/rolfmadsen/archimatetool-elk-auto-layout-plugin/releases/latest/download/elk-auto-layout-plugin.archiplugin)
*   **Bleeding Edge (Latest Commit):** To get the absolute newest (but potentially unstable) features, click the **Latest Build** badge at the top of this page, select the most recent green checkmark, and download the `.archiplugin` from the **Artifacts** section at the bottom.

**2. Install in Archi:**
* Open Archi.
* Go to **Help -> Manage Plug-ins...**
* Click **Install...** and select the **.archiplugin** file you just downloaded.
* Restart Archi as prompted.

> [!TIP]
> You can also find the latest development build by clicking the **Actions** tab on GitHub, selecting the latest run, and scrolling to the bottom to find the **Artifacts** section!

## Development

### Prerequisites

- **Java**: JDK 17 or 21.
- **Maven**: Version 3.9.0+.

### Building

To build the plugin and create the `.archiplugin` package:

```bash
# Build the plugin using the simplified command
mvn install -pl com.archimatetool.elk -am -DskipTests -T 1C

# The .archiplugin package is created in the root target/package directory during CI
# To create it locally, you can follow these steps:
mkdir -p target/package
cp com.archimatetool.elk/target/com.archimatetool.elk-*.jar target/package/
touch target/package/archi-plugin
cd target/package && zip -r ../../elk-auto-layout-plugin.archiplugin *
```

### How to Release

This project uses GitHub Actions for automated releases. To publish a new version:

1.  Update the version in `pom.xml`.
2.  Commit and push the change.
3.  Create and push a git tag:
    ```bash
    git tag -a v1.0.1 -m "Release version 1.0.1"
    git push origin v1.0.1
    ```
4.  The GitHub Action will automatically build the `.archiplugin` file and attach it to a new GitHub Release.


## License
This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Credits
This plugin is developed for [ArchiMateTool (Archi)](https://www.archimatetool.com), 
Copyright (c) 2013-2026 Phillip Beauvoir, Jean-Baptiste Sarrodie, The Open Group. 
Archi is also released under the MIT License.

## Eclipse Layout Kernel (ELK) Demonstrator

´´´ md
algorithm: elk.layered
elk.direction: LEFT

node Mode1 {
    node Business {
        elk.direction: LEFT
        node ActiveStructure {
            elk.direction: UP
            node Actor
            node Role
            edge Actor -> Role
        }
        node Behaviour {
            elk.direction: UP
            node Function
            node Process
            node Service
            edge Function -> Process
            edge Process -> Service
        }
        node PassiveStructure {
            node Data
        }
        edge ActiveStructure -> Behaviour
        edge Behaviour -> PassiveStructure        
    }
    node Application {
        elk.direction: LEFT
        node ActiveStructure {
            elk.direction: UP
            node Application
            node Interface
            edge Application -> Interface
        }
        node Behaviour {
            elk.direction: UP
            node Function
            node Process
            node Service
            edge Function -> Process
            edge Process -> Service
        }
        node PassiveStructure {
            node Data
        }
        edge ActiveStructure -> Behaviour
        edge Behaviour -> PassiveStructure
    }
}
´´´

Source: https://rtsys.informatik.uni-kiel.de/elklive/examples.html