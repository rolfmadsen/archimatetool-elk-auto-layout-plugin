import os
import glob
import re
import xml.etree.ElementTree as ET

def get_workspace_projects(roots):
    """Returns a map of bundle ID (string) to absolute project path."""
    projects = {}
    
    # Sort roots so 'archi' comes LAST, effectively overwriting plugin duplicates in the map
    # with the correct 'archi' project reference if both exist.
    sorted_roots = sorted(roots, key=lambda r: 1 if "archi" in r.lower() and "plugin" not in r.lower() else 0)
    
    for root in sorted_roots:
        expanded_root = os.path.expanduser(root)
        if not os.path.exists(expanded_root):
            continue
        for name in os.listdir(expanded_root):
            path = os.path.join(expanded_root, name)
            if os.path.isdir(path):
                # Try to get symbolic name from MANIFEST.MF
                manifest_path = os.path.join(path, "META-INF", "MANIFEST.MF")
                if os.path.exists(manifest_path):
                    with open(manifest_path, 'r') as f:
                        content = f.read()
                        match = re.search(r'Bundle-SymbolicName:\s*([^;,\s\n]+)', content)
                        if match:
                            bundle_id = match.group(1).strip()
                            projects[bundle_id] = name # We use directory name for classpath path
                elif os.path.exists(os.path.join(path, ".project")):
                    # Fallback to directory name if manifest not found but it is a project
                    projects[name] = name
    return projects

def get_required_bundles(manifest_path):
    """Extracts list of bundle IDs from Require-Bundle in MANIFEST.MF."""
    if not os.path.exists(manifest_path):
        return []
    
    with open(manifest_path, 'r') as f:
        lines = f.readlines()
    
    content = ""
    in_require = False
    for line in lines:
        if line.startswith("Require-Bundle:"):
            in_require = True
            content += line[15:].strip()
        elif in_require:
            if ":" in line and not line.startswith(" "): # New header
                break
            content += line.strip()
    
    # Bundle names are separated by commas, optional version/resolution info
    bundles = []
    for part in content.split(","):
        bundle_id = part.split(";")[0].strip()
        if bundle_id:
            bundles.append(bundle_id)
    return bundles

def update_classpath_references(classpath_path, required_bundles, workspace_projects):
    """Adds kind="src" entries for required bundles that are in the workspace."""
    try:
        tree = ET.parse(classpath_path)
        root = tree.getroot()
        changed = False
        
        # Get existing src paths
        existing_srcs = [entry.get("path") for entry in root.findall("classpathentry") if entry.get("kind") == "src"]
        
        for bundle in required_bundles:
            if bundle in workspace_projects:
                project_path = "/" + workspace_projects[bundle]
                if project_path not in existing_srcs:
                    print(f"  Adding project ref: {project_path} to {os.path.basename(os.path.dirname(classpath_path))}")
                    ET.SubElement(root, "classpathentry", {
                        "combineaccessrules": "false",
                        "kind": "src",
                        "path": project_path
                    })
                    changed = True
        
        if changed:
            ET.indent(tree, space="    ")
            tree.write(classpath_path, encoding="UTF-8", xml_declaration=True)
            return True
        return False
    except Exception as e:
        print(f"Error processing {classpath_path}: {e}")
        return False

def main():
    workspace_roots = [
        "/home/rolfmadsen/Github/archi",
        "/home/rolfmadsen/Github/archimatetool-duplicate-merge-plugin",
        "/home/rolfmadsen/Github/archimatetool-elk-auto-layout-plugin"
    ]
    
    print("Mapping workspace bundles...")
    workspace_projects = get_workspace_projects(workspace_roots)
    print(f"Mapped {len(workspace_projects)} bundles in workspace.")
    
    # Process each project in the roots
    success_count = 0
    for root in workspace_roots:
        expanded_root = os.path.expanduser(root)
        if not os.path.exists(expanded_root):
            continue
        for name in os.listdir(expanded_root):
            project_path = os.path.join(expanded_root, name)
            if not os.path.isdir(project_path):
                continue
            
            manifest_path = os.path.join(project_path, "META-INF", "MANIFEST.MF")
            classpath_path = os.path.join(project_path, ".classpath")
            
            if os.path.exists(manifest_path) and os.path.exists(classpath_path):
                required = get_required_bundles(manifest_path)
                if update_classpath_references(classpath_path, required, workspace_projects):
                    success_count += 1
            
    print(f"\nFinished! Updated {success_count} .classpath files with project references.")

if __name__ == "__main__":
    main()
