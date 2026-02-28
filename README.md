# Archi Auto-Layout Plugin

[![Java CI with Maven](https://github.com/rolfmadsen/archimatetool-elk-auto-layout-plugin/actions/workflows/build.yml/badge.svg)](https://github.com/rolfmadsen/archimatetool-elk-auto-layout-plugin/actions/workflows/build.yml)
[![Latest Release](https://img.shields.io/github/v/release/rolfmadsen/archimatetool-elk-auto-layout-plugin?label=Latest%20stable%20release&color=blue&include_prereleases)](https://github.com/rolfmadsen/archimatetool-elk-auto-layout-plugin/releases/latest/download/archi-auto-layout-plugin.archiplugin)
[![Latest Build](https://img.shields.io/badge/Latest_Build-bleeding_edge-orange)](https://github.com/rolfmadsen/archimatetool-elk-auto-layout-plugin/actions/workflows/build.yml)

> [!WARNING]
> **Disclaimer: Vibe-Coded Software**
> This plugin is not created by professional Java developers. It has been "vibe-coded" with the help of AI agents. While we strive for correctness, this software is provided "as is" without any warranties.
>
> **CRITICAL**: Use this plugin at your own risk. The authors are not responsible for any model corruption.

A plugin for [Archi](https://www.archimatetool.com) that automatically arranges ArchiMate diagrams into a clean, standards-aligned layout with a single click.

Unlike generic graph layout algorithms, this plugin understands the ArchiMate metamodel and organizes your diagrams into the characteristic **architecture matrix** — the same structure used in the ArchiMate specification itself.

## What It Does

Open any ArchiMate view, run the plugin, and your diagram is instantly reorganized into a structured grid layout:

### Architecture Matrix Alignment

Every element is placed according to its ArchiMate classification:

| | Passive Structure (Left) | Behavior (Center) | Active Structure (Right) |
|---|---|---|---|
| **Motivation** | Meaning, Value | Goal, Principle, Requirement… | Stakeholder, Driver… |
| **Strategy** | Resource | Capability, Value Stream… | Resource |
| **Business** | Business Object, Contract… | Business Process, Function… | Business Actor, Role… |
| **Application** | Data Object | Application Function, Process… | Application Component… |
| **Technology** | Artifact | Technology Function, Process… | Node, Device, System Software… |
| **Implementation** | Deliverable, Gap | Work Package | — |

- **Vertical axis** follows the ArchiMate layer hierarchy: Motivation at the top, down through Strategy, Business, Application, Technology, and Implementation & Migration at the bottom.
- **Horizontal axis** maps the three ArchiMate aspects: Active Structure on the right, Behavior in the center, and Passive Structure on the left.
- **Internal / External sub-rows**: Each layer is further split so that externally visible elements (Services, Interfaces, Contracts) appear above their internal counterparts (Processes, Functions, Components).

### Intelligent Element Arrangement

Beyond simple grid placement, the plugin applies several smart layout passes:

- **Cross-aspect alignment** — Elements connected by relationships across the three aspect columns (e.g., Application Component → Application Function → Data Object) are aligned at the same vertical height, producing clean horizontal connection lines.
- **Diagonal staircase** — When multiple sibling elements of the same type share a common parent or child (e.g., three Application Components under one parent), they are arranged in a top-left to bottom-right diagonal staircase pattern, preventing overlap and making relationships easy to trace.
- **Horizontal stretch** — When siblings form a diagonal staircase, the element they all connect to — their common parent or child — is automatically stretched in width to span the full staircase, visually anchoring the group.
- **Vertical stretch** — The same logic applied vertically: when an element connects to multiple elements at different heights across aspect columns, it is stretched in height to span them.
- **Centering** — A single element is centered over its connected elements within the same aspect column (e.g., an Application Interface positioned directly above the Application Component it belongs to, rather than drifting to the column center).
- **Container wrapping** — Grouping elements (such as Application Components containing child elements) automatically grow to enclose their children with appropriate padding.
- **Grid snapping** — All positions and dimensions align to the diagram grid from your Archi preferences, producing a clean, pixel-perfect result.

### Respects Your Preferences

The plugin reads your settings from **Edit > Preferences > Diagram**:

- **Element width and height** from the Appearance defaults
- **Grid size** for pixel-perfect alignment
- **Margin width** for diagram padding

## 📥 Download & Installation

**1. Download the Plugin:**
You have two options depending on your needs.

*   **Stable Version (Recommended):** [**Download Official Release**](https://github.com/rolfmadsen/archimatetool-elk-auto-layout-plugin/releases/latest/download/archi-auto-layout-plugin.archiplugin)
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
# Build all modules
# The .archiplugin package is created in the root target/package directory during CI
# To create it locally, you can follow these steps:
mvn clean install -DskipTests
mkdir -p target/package
cp com.archimatetool.autolayout/target/com.archimatetool.autolayout-*.jar target/package/
touch target/package/archi-plugin
cd target/package && zip -r ../../archi-auto-layout-plugin.archiplugin *
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